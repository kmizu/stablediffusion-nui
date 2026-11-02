"""Clip I/O: export CLIP conditioning to files, and feed conditioning files to the sampler instead of a prompt.

Written from the documented behaviour of Filexor/Clip_IO (README); no upstream code was read or copied.

Hook (params.hooks["clip_io"]):
  input_enabled        bool    use the Positive/Negative prompt boxes as conditioning file lists
  positive_mode        "off" | "simple" | "directive"
  negative_mode        "off" | "simple" | "directive"
  output_enabled       bool    write the CLIP conditioning of the prompt(s) to files while generating
  output_name          str     file name of the positive prompt (".csv" or ".pt", default format below)
  negative_output_name str     file name of the negative prompt (optional)
  format               "csv" | "pt"       used when a name has no extension
  transpose, no_special_tokens, ignore_emphasis, bypass_normalization, token_strings, overwrite   bool

Files live in the conditioning folder: $SDFX_CLIP_IO_DIR, else ~/.cache/stablediffusion-nui/clip_io/conditioning.
Layout: one row per token and one column per embedding dimension (`transpose` swaps them). `token_strings` adds the
token text as the first column (the first row when transposed). A `.pt` file holds a tensor (tokens x dimension);
SDXL files are a dict {"cond", "pooled", "tokens"}.

Input prompts ("simple" mode): space separated file names (quote names that contain spaces with " or '); a name
without an extension is also tried with ".csv" and ".pt"; text between triple quotes is encoded by CLIP; all
pieces are concatenated along the token axis. "directive" mode adds ?name(...) / ?name_ORDER(...) directives:
  ?prompt(\"\"\"text\"\"\", clip_skip=2, no_padding=False)   CLIP-encoded piece (arguments may be lists)
  ?eval(expression)                                         component-wise arithmetic on the conditioning
Directives run after the pieces are joined, by ascending order and then left to right. `eval` is evaluated by a
restricted arithmetic interpreter (not Python eval): variables i (conditioning before the directive), o (same),
t (token index), d (dimension index), constants pi, e, tau, inf, nan, and the math functions listed in EVAL_FUNCTIONS.
"""
from __future__ import annotations

import ast
import csv
import math
import os
import re
from pathlib import Path
from typing import Any, Optional

from . import prompt
from .protocol import EngineError, INVALID_PARAMS

DIR_ENV = "SDFX_CLIP_IO_DIR"
MODES = ("off", "simple", "directive")
FORMATS = ("csv", "pt")
BOOLS = ("input_enabled", "output_enabled", "transpose", "no_special_tokens", "ignore_emphasis",
         "bypass_normalization", "token_strings", "overwrite")
DEFAULTS = {"input_enabled": False, "positive_mode": "simple", "negative_mode": "off",
            "output_enabled": False, "output_name": "", "negative_output_name": "", "format": "csv",
            "transpose": False, "no_special_tokens": False, "ignore_emphasis": False,
            "bypass_normalization": False, "token_strings": False, "overwrite": False}
MAX_CHUNKS = 64                 # 4,928 tokens
MAX_FILE_BYTES = 256 * 1024 * 1024
MAX_EXPRESSION = 2000
MAX_NODES = 400


def _bad(message: str) -> EngineError:
    return EngineError(INVALID_PARAMS, f"Clip I/O: {message}")


# ------------------------------------------------------------------------------------------------ configuration
def normalize(cfg: dict) -> dict:
    out = dict(DEFAULTS)
    out.update({k: v for k, v in cfg.items() if k in DEFAULTS})
    from .hooks import coerce_bool
    for key in BOOLS:
        out[key] = coerce_bool(out[key], DEFAULTS[key])
    for key in ("positive_mode", "negative_mode"):
        value = str(out[key]).strip().lower()
        if value not in MODES:
            raise _bad(f"{key} must be one of {', '.join(MODES)}")
        out[key] = value
    value = str(out["format"]).strip().lower()
    if value not in FORMATS:
        raise _bad("format must be csv or pt")
    out["format"] = value
    for key in ("output_name", "negative_output_name"):
        out[key] = str(out[key] or "").strip()
    if out["output_enabled"] and not (out["output_name"] or out["negative_output_name"]):
        raise _bad("an output name is required to export conditioning")
    return out


def conditioning_dir() -> Path:
    configured = os.environ.get(DIR_ENV)
    return Path(configured) if configured else Path.home() / ".cache" / "stablediffusion-nui" / "clip_io" / "conditioning"


def _inside(base: Path, name: str) -> Path:
    """`name` as a path under `base`; absolute paths and `..` are refused."""
    if not name or "\x00" in name:
        raise _bad("empty file name")
    relative = Path(name.replace("\\", "/"))
    if relative.is_absolute() or relative.drive or ".." in relative.parts:
        raise _bad(f"file name '{name}' must be a relative path inside the conditioning folder")
    path = (base / relative).resolve()
    if base.resolve() not in path.parents:
        raise _bad(f"file name '{name}' is outside the conditioning folder")
    return path


def resolve_file(name: str, base: Optional[Path] = None) -> Path:
    base = base or conditioning_dir()
    for candidate in (name, name + ".csv", name + ".pt"):
        path = _inside(base, candidate)
        if path.is_file():
            return path
    raise _bad(f"conditioning file '{name}' not found in {base} (tried '{name}', '{name}.csv', '{name}.pt')")


def list_files(base: Optional[Path] = None) -> list[str]:
    base = base or conditioning_dir()
    if not base.is_dir():
        return []
    return sorted(str(p.relative_to(base)).replace("\\", "/") for p in base.rglob("*")
                  if p.is_file() and p.suffix.lower() in (".csv", ".pt"))


# ------------------------------------------------------------------------------------------------ input syntax
class Piece:
    """One item of an input prompt: kind is "file", "prompt" or "directive"."""

    def __init__(self, kind: str, text: str, order: int = 0, name: str = "", position: int = 0):
        self.kind, self.text, self.order, self.name, self.position = kind, text, order, name, position

    def __repr__(self):
        return f"Piece({self.kind!r}, {self.text!r}, order={self.order}, name={self.name!r})"


_DIRECTIVE = re.compile(r"\?([A-Za-z][A-Za-z0-9]*?)(?:_(-?\d+))?\(")


def skip_string(text: str, i: int) -> int:
    """`text[i]` opens a quoted string (single, double or triple quotes); returns the index after it."""
    quote = text[i]
    triple = text.startswith(quote * 3, i)
    delimiter = quote * 3 if triple else quote
    j = i + len(delimiter)
    while j < len(text):
        if text[j] == "\\" and not triple and j + 1 < len(text):
            j += 2
            continue
        if text.startswith(delimiter, j):
            return j + len(delimiter)
        j += 1
    raise _bad("unterminated quotation in the input prompt")


def _matching_paren(text: str, start: int) -> int:
    """`text[start]` is "("; returns the index of the matching ")" (quotes are skipped)."""
    depth = 0
    i = start
    while i < len(text):
        c = text[i]
        if c in "\"'":
            i = skip_string(text, i)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise _bad("unbalanced parenthesis in a directive")


def parse_input(text: str, mode: str) -> list[Piece]:
    if mode not in ("simple", "directive"):
        raise _bad(f"cannot parse an input prompt in mode '{mode}'")
    pieces: list[Piece] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c.isspace() or c == ",":
            i += 1
        elif c in "\"'" and text.startswith(c * 3, i):
            end = skip_string(text, i)
            pieces.append(Piece("prompt", text[i + 3:end - 3], position=len(pieces)))
            i = end
        elif c in "\"'":
            end = skip_string(text, i)
            pieces.append(Piece("file", text[i + 1:end - 1], position=len(pieces)))
            i = end
        elif c == "?" and mode == "directive" and (m := _DIRECTIVE.match(text, i)):
            close = _matching_paren(text, m.end() - 1)
            pieces.append(Piece("directive", text[m.end():close], int(m.group(2) or 0), m.group(1).lower(), len(pieces)))
            i = close + 1
        else:
            j = i
            while j < n and not text[j].isspace():
                j += 1
            pieces.append(Piece("file", text[i:j], position=len(pieces)))
            i = j
    return pieces


def split_arguments(inner: str) -> list[str]:
    """Top level comma separated arguments of a directive; empty arguments stay empty strings."""
    args, depth, start, i = [], 0, 0, 0
    while i < len(inner):
        c = inner[i]
        if c in "\"'":
            i = skip_string(inner, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            args.append(inner[start:i].strip())
            start = i + 1
        i += 1
    args.append(inner[start:].strip())
    return args if any(args) else []


def parse_prompt_directive(inner: str) -> dict:
    """?prompt(prompt, clip_skip=..., no_padding=...): positional arguments may be omitted (`?prompt('x',,True)`)."""
    names = ("prompt", "clip_skip", "no_padding")
    values: dict[str, Any] = {}
    for index, arg in enumerate(split_arguments(inner)):
        if not arg:
            continue
        key = None
        m = re.match(r"([A-Za-z_]\w*)\s*=(?!=)", arg)
        if m:
            key, arg = m.group(1), arg[m.end():].strip()
            if key not in names:
                raise _bad(f"?prompt has no argument '{key}'")
        elif index >= len(names):
            raise _bad("?prompt takes at most three arguments")
        else:
            key = names[index]
        try:
            values[key] = ast.literal_eval(arg)
        except (ValueError, SyntaxError) as error:
            raise _bad(f"?prompt argument {key}: {arg!r} is not a literal") from error
    if "prompt" not in values:
        raise _bad("?prompt needs a prompt (triple quoted)")
    texts = values["prompt"]
    texts = [texts] if isinstance(texts, str) else list(texts) if isinstance(texts, (list, tuple)) else None
    if not texts or not all(isinstance(t, str) for t in texts):
        raise _bad("?prompt prompt must be a string or a list/tuple of strings")

    def broadcast(key, default, accept):
        raw = values.get(key, default)
        raw = list(raw) if isinstance(raw, (list, tuple)) else [raw]
        if not raw or not all(accept(x) for x in raw):
            raise _bad(f"?prompt {key} is invalid")
        return [raw[min(k, len(raw) - 1)] for k in range(len(texts))]

    skips = broadcast("clip_skip", 1, lambda x: isinstance(x, int) and not isinstance(x, bool) and 1 <= x <= 12)
    flags = broadcast("no_padding", False, lambda x: isinstance(x, (bool, int)))
    return {"items": [(t, s, bool(f)) for t, s, f in zip(texts, skips, flags)]}


# ------------------------------------------------------------------------------------------------ eval directive
EVAL_FUNCTIONS = ("sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "exp", "log", "log2", "log10",
                  "sqrt", "abs", "fabs", "floor", "ceil", "trunc", "erf", "sigmoid", "sign", "atan2", "pow", "fmod",
                  "copysign", "min", "max", "where", "clamp")
_CONSTANTS = {"pi": math.pi, "e": math.e, "tau": math.tau, "inf": math.inf, "nan": math.nan}


def _torch_function(name: str):
    import torch
    table = {"fabs": torch.abs, "sigmoid": torch.sigmoid, "min": torch.minimum, "max": torch.maximum,
             "clamp": torch.clamp, "where": torch.where, "pow": torch.pow, "fmod": torch.fmod, "copysign": torch.copysign,
             "atan2": torch.atan2}
    return table.get(name) or getattr(torch, name)


def _as_tensor(value, like):
    import torch
    if isinstance(value, torch.Tensor):
        return value.to(torch.float32) if value.dtype == torch.bool else value
    return torch.tensor(float(value), dtype=like.dtype, device=like.device)


def evaluate_expression(expression: str, i, o=None):
    """Vectorised, restricted evaluation of `expression` over a (tokens x dimensions) tensor. Returns the new tensor."""
    import torch
    expression = expression.strip()
    if not expression:
        raise _bad("?eval needs an expression")
    if len(expression) > MAX_EXPRESSION:
        raise _bad(f"?eval expression is longer than {MAX_EXPRESSION} characters")
    try:
        tree = ast.parse(expression, mode="eval")
    except SyntaxError as error:
        raise _bad(f"?eval syntax error: {error.msg}") from error
    if sum(1 for _ in ast.walk(tree)) > MAX_NODES:
        raise _bad("?eval expression is too complex")
    i = i.to(torch.float32)
    o = i.clone() if o is None else o.to(torch.float32)
    tokens, dims = i.shape
    t = torch.arange(tokens, dtype=torch.float32, device=i.device).reshape(-1, 1).expand(tokens, dims)
    d = torch.arange(dims, dtype=torch.float32, device=i.device).reshape(1, -1).expand(tokens, dims)
    names = {"i": i, "o": o, "t": t, "d": d}

    def num(x):
        return _as_tensor(x, i)

    def call(name, args):
        if name not in EVAL_FUNCTIONS:
            raise _bad(f"?eval: function '{name}' is not available")
        if name == "where":
            if len(args) != 3:
                raise _bad("?eval: where(condition, a, b) takes three arguments")
            return torch.where(num(args[0]) != 0, num(args[1]), num(args[2]))
        if name == "clamp":
            if len(args) != 3:
                raise _bad("?eval: clamp(x, low, high) takes three arguments")
            return torch.minimum(torch.maximum(num(args[0]), num(args[1])), num(args[2]))
        fn = _torch_function(name)
        args = [num(a) for a in args]
        two = name in ("atan2", "pow", "fmod", "copysign", "min", "max")
        if len(args) != (2 if two else 1):
            raise _bad(f"?eval: {name} takes {2 if two else 1} argument(s)")
        return fn(*args)

    def ev(node):
        if isinstance(node, ast.Expression):
            return ev(node.body)
        if isinstance(node, ast.Constant):
            if isinstance(node.value, bool):
                return float(node.value)
            if isinstance(node.value, (int, float)):
                return float(node.value)
            raise _bad("?eval accepts numbers only")
        if isinstance(node, ast.Name):
            if node.id in names:
                return names[node.id]
            if node.id in _CONSTANTS:
                return _CONSTANTS[node.id]
            raise _bad(f"?eval: unknown name '{node.id}'")
        if isinstance(node, ast.UnaryOp):
            v = num(ev(node.operand))
            if isinstance(node.op, ast.USub):
                return -v
            if isinstance(node.op, ast.UAdd):
                return v
            if isinstance(node.op, ast.Not):
                return (v == 0).to(torch.float32)
            raise _bad("?eval: unsupported unary operator")
        if isinstance(node, ast.BinOp):
            a, b = num(ev(node.left)), num(ev(node.right))
            ops = {ast.Add: torch.add, ast.Sub: torch.sub, ast.Mult: torch.mul, ast.Div: torch.true_divide,
                   ast.FloorDiv: torch.floor_divide, ast.Mod: torch.remainder, ast.Pow: torch.pow}
            fn = ops.get(type(node.op))
            if fn is None:
                raise _bad("?eval: unsupported operator")
            return fn(a, b)
        if isinstance(node, ast.Compare):
            result, left = None, num(ev(node.left))
            cmps = {ast.Lt: torch.lt, ast.LtE: torch.le, ast.Gt: torch.gt, ast.GtE: torch.ge, ast.Eq: torch.eq,
                    ast.NotEq: torch.ne}
            for op, comparator in zip(node.ops, node.comparators):
                right = num(ev(comparator))
                fn = cmps.get(type(op))
                if fn is None:
                    raise _bad("?eval: unsupported comparison")
                step = fn(left, right)
                result = step if result is None else torch.logical_and(result, step)
                left = right
            return result.to(torch.float32)
        if isinstance(node, ast.BoolOp):
            values = [num(ev(v)) != 0 for v in node.values]
            combine = torch.logical_and if isinstance(node.op, ast.And) else torch.logical_or
            result = values[0]
            for v in values[1:]:
                result = combine(result, v)
            return result.to(torch.float32)
        if isinstance(node, ast.IfExp):
            return torch.where(num(ev(node.test)) != 0, num(ev(node.body)), num(ev(node.orelse)))
        if isinstance(node, ast.Call):
            if node.keywords:
                raise _bad("?eval: keyword arguments are not supported")
            func = node.func
            if isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name) and func.value.id in ("torch", "math"):
                name = func.attr
            elif isinstance(func, ast.Name):
                name = func.id
            else:
                raise _bad("?eval: only the listed math functions can be called")
            return call(name, [ev(a) for a in node.args])
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id == "math" \
                and node.attr in _CONSTANTS:
            return _CONSTANTS[node.attr]
        raise _bad(f"?eval: unsupported syntax ({type(node).__name__})")

    try:
        result = num(ev(tree))
    except (ZeroDivisionError, OverflowError, ValueError) as error:
        raise _bad(f"?eval failed: {error}") from error
    result = torch.broadcast_to(result, (tokens, dims)).clone()
    if not bool(torch.isfinite(result).all()):
        raise _bad("?eval produced NaN or infinite values")
    return result


# ------------------------------------------------------------------------------------------------ files
def _is_number(cell: str) -> bool:
    try:
        float(cell)
        return True
    except ValueError:
        return False


def read_csv_matrix(path: Path, dim: Optional[int] = None):
    """Rows of a CSV conditioning file as a (tokens x dim) float tensor (a leading token-string column or header row,
    and the transposed layout, are recognised)."""
    import torch
    with open(path, newline="", encoding="utf-8-sig") as handle:
        rows = [row for row in csv.reader(handle) if row and any(c.strip() for c in row)]
    if not rows:
        raise _bad(f"{path.name} is empty")
    rows = [[c.strip() for c in row] for row in rows]
    hinted = None
    if all(not _is_number(c) for c in rows[0]) and len(rows) > 1:
        rows, hinted = rows[1:], "transposed"
    if all(row and not _is_number(row[0]) for row in rows):
        rows = [row[1:] for row in rows]
        hinted = hinted or "rows"
    width = {len(r) for r in rows}
    if len(width) != 1:
        raise _bad(f"{path.name}: rows have different numbers of columns")
    try:
        matrix = torch.tensor([[float(c) for c in row] for row in rows], dtype=torch.float32)
    except ValueError as error:
        raise _bad(f"{path.name}: a cell is not a number ({error})") from error
    return _orient(matrix, dim, hinted, path.name)


def _orient(matrix, dim: Optional[int], hint: Optional[str], name: str):
    if matrix.dim() == 3 and matrix.shape[0] == 1:
        matrix = matrix[0]
    if matrix.dim() != 2 or matrix.numel() == 0:
        raise _bad(f"{name}: expected a tokens x dimension matrix")
    rows, cols = matrix.shape
    if dim is None:
        return matrix.T if hint == "transposed" else matrix
    if cols == dim and (rows != dim or hint != "transposed"):
        return matrix
    if rows == dim:
        return matrix.T.contiguous()
    raise _bad(f"{name}: shape {rows}x{cols} does not fit this model's conditioning width {dim}")


def read_conditioning(path: Path, dim: Optional[int] = None) -> tuple:
    """(tokens x dim float32 tensor, pooled tensor or None)."""
    if path.stat().st_size > MAX_FILE_BYTES:
        raise _bad(f"{path.name} is larger than {MAX_FILE_BYTES // (1024 * 1024)} MB")
    if path.suffix.lower() == ".pt":
        import torch
        try:
            data = torch.load(path, map_location="cpu", weights_only=True)
        except Exception as error:  # unpickling errors vary
            raise _bad(f"{path.name} could not be loaded as a tensor file: {str(error).splitlines()[0]}") from error
        pooled = None
        if isinstance(data, dict):
            pooled = data.get("pooled")
            data = next((data[k] for k in ("cond", "conditioning", "c") if k in data), None)
        if not isinstance(data, torch.Tensor):
            raise _bad(f"{path.name} does not contain a tensor")
        matrix = _orient(data.detach().float().cpu(), dim, None, path.name)
        if pooled is not None:
            pooled = pooled.detach().float().cpu().reshape(1, -1)
        return matrix, pooled
    return read_csv_matrix(path, dim), None


def _cell(value: str) -> str:
    return "'" + value if _is_number(value) else value


def write_conditioning(path: Path, matrix, tokens: list[str], pooled, options: dict) -> None:
    """Write (tokens x dim) `matrix` as csv or pt (by the suffix of `path`)."""
    import torch
    matrix = matrix.detach().float().cpu()
    if options["transpose"] and path.suffix.lower() != ".pt":
        matrix = matrix.T
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".tmp")
    try:
        if path.suffix.lower() == ".pt":
            payload: Any = matrix if pooled is None else {"cond": matrix, "pooled": pooled.detach().float().cpu(),
                                                         "tokens": tokens}
            torch.save(payload, temp)
        else:
            with open(temp, "w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle, lineterminator="\n")
                values = [[f"{x:.9g}" for x in row] for row in matrix.tolist()]
                if options["token_strings"]:
                    if options["transpose"]:
                        writer.writerow([_cell(t) for t in tokens])
                    else:
                        values = [[_cell(t)] + row for t, row in zip(tokens, values)]
                writer.writerows(values)
        os.replace(temp, path)
    finally:
        if temp.exists():
            temp.unlink()


# ------------------------------------------------------------------------------------------------ CLIP encoding
def _chunks(pipe, tokenizer, text: str, special: bool, ignore_emphasis: bool):
    info = prompt._TokInfo(pipe, tokenizer)
    parsed = prompt.parse_prompt_attention(text)
    raw = prompt.chunk_tokens(parsed, info.tokenize, info.id_start, info.id_end, info.comma)
    out = []
    for ids, mults in raw:
        if special:
            ids = prompt.apply_pad(ids, info.id_end, info.id_pad)
        else:
            ids, mults = ids[1:-1], mults[1:-1]
            n = len(ids)
            while n and ids[n - 1] == info.id_end:
                n -= 1
            ids, mults = ids[:n], mults[:n]
            if not ids:
                continue
        out.append((list(ids), [1.0] * len(ids) if ignore_emphasis else list(mults)))
    if len(out) > MAX_CHUNKS:
        raise _bad(f"the prompt is longer than {MAX_CHUNKS} chunks of 75 tokens")
    return info, out


def _emphasize(z, mults, normalize: bool):
    import torch
    m = torch.tensor(mults, dtype=z.dtype, device=z.device).reshape(1, -1, 1)
    original = z.mean()
    z = z * m
    if normalize:
        new = z.mean()
        if float(new) != 0.0:
            z = z * (original / new)
    return z


def encode_text(engine, text: str, clip_skip: int = 1, *, special: bool = True, ignore_emphasis: bool = False,
                normalize: bool = True, tokens: bool = False):
    """CLIP conditioning of `text` as (matrix tokens x dim float32, pooled or None, token strings or None).

    With the default options this is exactly what generation encodes."""
    import torch
    pipe = engine.pipe
    text = prompt.strip_extra_networks(text)
    sdxl = bool(engine.sdxl)
    standard = special and not ignore_emphasis and normalize
    pooled = None
    strings = None
    with torch.no_grad():
        if sdxl:
            pooled = prompt.encode_sdxl(pipe, text, clip_skip)[1] if not standard else None
        if standard:
            if sdxl:
                z, pooled = prompt.encode_sdxl(pipe, text, clip_skip)
            else:
                z = prompt.encode_sd15(pipe, text, clip_skip, engine.kind == "sd2")
        elif sdxl:
            te1, te2 = pipe.text_encoder, pipe.text_encoder_2
            layer = prompt.sdxl_layer_choice(clip_skip)
            _, chunks1 = (_chunks(pipe, pipe.tokenizer, text, special, ignore_emphasis) if te1 is not None else (None, None))
            _, chunks2 = _chunks(pipe, pipe.tokenizer_2, text, special, ignore_emphasis)
            if not chunks2:
                raise _bad("the prompt has no tokens")
            zs = []
            for k in range(len(chunks2)):
                parts = []
                if chunks1 is not None:
                    ids1, m1 = chunks1[min(k, len(chunks1) - 1)]
                    z1, _ = prompt._encode_chunk(te1, ids1, layer, False, prompt._te_device(pipe, te1))
                    parts.append(_emphasize(z1, m1, normalize))
                ids2, m2 = chunks2[k]
                z2, _ = prompt._encode_chunk(te2, ids2, layer, False, prompt._te_device(pipe, te2),
                                             pipe.tokenizer_2.eos_token_id)
                z2 = _emphasize(z2, m2, normalize)
                zs.append(torch.cat([p.to(z2.device, z2.dtype) for p in parts] + [z2], dim=-1))
            z = torch.cat(zs, dim=1)
        else:
            te = pipe.text_encoder
            device = prompt._te_device(pipe, te)
            openclip = engine.kind == "sd2"
            layer = prompt.sd_layer_choice(clip_skip, openclip, int(getattr(te.config, "num_hidden_layers", 12)))
            _, chunks = _chunks(pipe, pipe.tokenizer, text, special, ignore_emphasis)
            if not chunks:
                raise _bad("the prompt has no tokens")
            zs = []
            for ids, mults in chunks:
                z, _ = prompt._encode_chunk(te, ids, layer, layer is not None, device)
                zs.append(_emphasize(z, mults, normalize))
            z = torch.cat(zs, dim=1)
        if tokens:
            tokenizer = pipe.tokenizer_2 if sdxl else pipe.tokenizer
            _, chunk_list = _chunks(pipe, tokenizer, text, special, ignore_emphasis)
            strings = [s for ids, _ in chunk_list for s in tokenizer.convert_ids_to_tokens(ids)]
            if len(strings) != z.shape[1]:
                strings = None
    if z.shape[1] > MAX_CHUNKS * 77:
        raise _bad(f"the prompt is longer than {MAX_CHUNKS} chunks of 75 tokens")
    return z[0].float().cpu(), (None if pooled is None else pooled.float().cpu()), strings


# ------------------------------------------------------------------------------------------------ Clip Output
def output_path(name: str, cfg: dict, base: Optional[Path] = None) -> Path:
    if not Path(name).suffix.lower() in (".csv", ".pt"):
        name += "." + cfg["format"]
    return _inside(base or conditioning_dir(), name)


def export(engine, cfg: dict, text: str, name: str, gp, base: Optional[Path] = None) -> Optional[Path]:
    """Write the conditioning of `text` to the conditioning folder. Returns the path, or None when the file exists
    and overwrite is off."""
    path = output_path(name, cfg, base)
    if path.exists() and not cfg["overwrite"]:
        return None
    matrix, pooled, strings = encode_text(
        engine, text, gp.clip_skip, special=not cfg["no_special_tokens"], ignore_emphasis=cfg["ignore_emphasis"],
        normalize=not cfg["bypass_normalization"], tokens=cfg["token_strings"])
    if cfg["token_strings"] and strings is None:
        raise _bad("token strings are unavailable for this prompt")
    write_conditioning(path, matrix, strings or [], pooled, cfg)
    return path


# ------------------------------------------------------------------------------------------------ Clip Input
def build_side(engine, text: str, mode: str, gp, dim: int, base: Optional[Path] = None):
    """Conditioning (1 x tokens x dim, float32) and pooled (or None) for an input prompt."""
    import torch
    text = prompt.strip_extra_networks(text)
    pieces = parse_input(text, mode)
    if not pieces:
        raise _bad("the input prompt names no conditioning file")
    parts, pooled = [], None

    def take(matrix, pool):
        nonlocal pooled
        if matrix.shape[1] != dim:
            raise _bad(f"conditioning width {matrix.shape[1]} does not fit this model ({dim})")
        parts.append(matrix)
        if pooled is None and pool is not None:
            pooled = pool

    for piece in pieces:
        if piece.kind == "file":
            take(*read_conditioning(resolve_file(piece.text, base), dim))
        elif piece.kind == "prompt":
            matrix, pool, _ = encode_text(engine, piece.text, gp.clip_skip)
            take(matrix, pool)
        elif piece.name == "prompt":
            for sub, skip, no_padding in parse_prompt_directive(piece.text)["items"]:
                matrix, pool, _ = encode_text(engine, sub, skip, special=not no_padding)
                take(matrix, pool)
        elif piece.name != "eval":
            raise _bad(f"unknown directive '?{piece.name}' (available: prompt, eval)")
    if not parts:
        raise _bad("the input prompt produced no conditioning")
    cond = torch.cat(parts, dim=0)
    if cond.shape[0] > MAX_CHUNKS * 77:
        raise _bad(f"the conditioning has more than {MAX_CHUNKS * 77} tokens")
    evals = sorted((p for p in pieces if p.kind == "directive" and p.name == "eval"), key=lambda p: (p.order, p.position))
    for piece in evals:
        cond = evaluate_expression(piece.text, cond)
    return cond.unsqueeze(0), pooled


def _pad_rows(tensor, length: int, empty):
    """Extend (1 x tokens x dim) `tensor` to `length` tokens with the rows of the empty-prompt conditioning, tiled."""
    import torch
    missing = length - tensor.shape[1]
    if missing <= 0:
        return tensor
    if not bool(tensor.abs().any()):
        fill = torch.zeros((tensor.shape[0], missing, tensor.shape[2]), dtype=tensor.dtype, device=tensor.device)
    else:
        reps = -(-missing // empty.shape[1])
        fill = empty.to(tensor.device, tensor.dtype).repeat(tensor.shape[0], reps, 1)[:, :missing]
    return torch.cat([tensor, fill], dim=1)


def applies(cfg: Optional[dict], pos: str, neg: str, gp) -> tuple[str, str]:
    """The input mode that applies to (pos, neg) -- only the generation's own prompts are replaced, never the prompts
    other features (ADetailer, Hires overrides, ...) encode."""
    if not cfg or not cfg["input_enabled"]:
        return "off", "off"
    return (cfg["positive_mode"] if pos == gp.prompt else "off",
            cfg["negative_mode"] if neg == gp.negative_prompt else "off")


def encode_input(engine, cfg: dict, pos: str, neg: str, gp, bs: int, modes: tuple[str, str], base: Optional[Path] = None):
    """Embeds dict like Engine._encode_plain, with the sides in `modes` taken from conditioning files."""
    import torch
    plain = engine._encode_plain(pos if modes[0] == "off" else "", neg if modes[1] == "off" else "", gp, 1)
    pe, ne = plain["prompt_embeds"], plain["negative_prompt_embeds"]
    pooled_p, pooled_n = plain.get("pooled_prompt_embeds"), plain.get("negative_pooled_prompt_embeds")
    empty = engine._encode_plain("", "", gp, 1)["prompt_embeds"]
    dim = pe.shape[-1]
    sdxl = pooled_p is not None
    empty_pooled = prompt.encode_sdxl(engine.pipe, "", gp.clip_skip)[1] if sdxl else None
    dtype, device = pe.dtype, pe.device

    def side(text, mode):
        cond, pooled = build_side(engine, text, mode, gp, dim, base)
        cond = cond.to(device, dtype)
        if sdxl:
            pooled = (empty_pooled if pooled is None else pooled).reshape(1, -1).to(device, dtype)
        return cond, pooled

    if modes[0] != "off":
        pe, pooled_p = side(pos, modes[0])
    if modes[1] != "off":
        ne, pooled_n = side(neg, modes[1])
    length = max(pe.shape[1], ne.shape[1])
    pe, ne = _pad_rows(pe, length, empty), _pad_rows(ne, length, empty)
    out = {"prompt_embeds": pe.repeat(bs, 1, 1).to(engine.dtype), "negative_prompt_embeds": ne.repeat(bs, 1, 1).to(engine.dtype)}
    if sdxl:
        out["pooled_prompt_embeds"] = pooled_p.repeat(bs, 1).to(engine.dtype)
        out["negative_pooled_prompt_embeds"] = pooled_n.repeat(bs, 1).to(engine.dtype)
    return out
