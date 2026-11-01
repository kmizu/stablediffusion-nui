"""Semantic Guidance, re-implemented from v0xie/sd-webui-semantic-guidance bdbd03c (GPL-3.0) behaviour.

Upstream edits the text conditioning, not the noise prediction of the SEGA paper. From the warmup step on,
every model evaluation adds, for each concept, `edit_guidance_scale * strength * (concept - uncond)` to the
positive conditioning wherever `|strength * (concept - uncond)|` exceeds the concept's mean + z * std, with
z the standard normal quantile at `1 - tail_percentage_threshold`. Concepts are the comma-separated SEGA
prompt (positive strength) and negative prompt (negative strength); each concept's first A1111 attention
segment gives its text and strength. Upstream's momentum update multiplies by a velocity that starts at
zero, so momentum never changes the result; this keeps that behaviour. SDXL also edits the pooled
embedding, thresholded over the pooled embedding plus the size embedding as upstream's `vector`.
"""
from __future__ import annotations

import math
from statistics import NormalDist

from .protocol import EngineError, INVALID_PARAMS, UNSUPPORTED

DEFAULTS = {"prompt": "", "negative_prompt": "", "warmup": 10, "edit_guidance_scale": 1.0,
            "tail_percentage_threshold": 0.05, "momentum_scale": 0.3, "momentum_beta": 0.6}


def normalize(config: dict) -> dict:
    c = dict(DEFAULTS)
    c.update({k: v for k, v in (config or {}).items() if k in DEFAULTS and v is not None})
    try:
        out = {"prompt": str(c["prompt"]), "negative_prompt": str(c["negative_prompt"]),
               "warmup": int(float(c["warmup"])), "edit_guidance_scale": float(c["edit_guidance_scale"]),
               "tail_percentage_threshold": float(c["tail_percentage_threshold"]),
               "momentum_scale": float(c["momentum_scale"]), "momentum_beta": float(c["momentum_beta"])}
    except (TypeError, ValueError) as e:
        raise EngineError(INVALID_PARAMS, f"Semantic Guidance: invalid setting ({e})")
    if any(not math.isfinite(v) for k, v in out.items() if isinstance(v, float)):
        raise EngineError(INVALID_PARAMS, "Semantic Guidance: settings must be finite numbers")
    if out["warmup"] < 0 or not 0 <= out["tail_percentage_threshold"] <= 1:
        raise EngineError(INVALID_PARAMS, "Semantic Guidance: warmup must be >= 0 and the tail threshold 0 to 1")
    return out


def parse_concepts(prompt: str, negative: str) -> list[tuple[str, float]]:
    """[(text, strength)]; like upstream, only an empty string yields no concepts."""
    from .prompt import parse_prompt_attention
    concepts = []
    for text, sign in ((prompt, 1.0), (negative, -1.0)):
        if len(text) == 0:
            continue
        for part in (x.strip() for x in text.split(",")):
            segment = parse_prompt_attention(part)[0]
            concepts.append((segment[0], sign * float(segment[1])))
    return concepts


def z_score(tail: float) -> float:
    if tail <= 0:
        return math.inf
    if tail >= 1:
        return -math.inf
    return NormalDist().inv_cdf(1.0 - tail)


def edit_direction(concept, uncond, strength: float, scale: float, z: float):
    """One concept's thresholded edit for `uncond`-shaped conditioning (float32)."""
    import torch
    concept = concept.expand_as(uncond).float()
    edit = strength * (concept - uncond.float())
    threshold = concept.mean() + z * concept.std() if math.isfinite(z) else z
    return torch.where(edit.abs() > threshold, edit * scale, torch.zeros_like(edit))


class SemanticGuidanceHook:
    """UNet forward pre-hook editing the positive rows of the text conditioning per evaluation."""

    def __init__(self, unet, concepts, config, batch, *, empty=None, negative=None, negative_pooled=None, add_time_proj=None):
        self.unet = unet
        self.empty = empty  # empty-prompt chunk for A1111 pad_cond
        self.concepts = concepts  # [(embeds (1, T, C), pooled (1, P) or None, strength)]
        self.config = config
        self.batch = batch
        self.negative = negative
        self.negative_pooled = negative_pooled
        self.add_time_proj = add_time_proj
        self.z = z_score(config["tail_percentage_threshold"])
        self.step = 0
        self.last_timestep = None
        self.handle = None

    def install(self):
        self.handle = self.unet.register_forward_pre_hook(self.pre, with_kwargs=True)
        return self

    def remove(self):
        if self.handle is not None:
            self.handle.remove()
            self.handle = None

    def _rows(self, n: int):
        """(uncond rows or None, start of positive rows). Positive rows follow the unconditional ones."""
        if n == self.batch:
            return None, 0
        if n in (2 * self.batch, 3 * self.batch):  # CFG, or CFG + PAG perturbed positives
            return slice(0, self.batch), self.batch
        raise EngineError(UNSUPPORTED, f"Semantic Guidance found an unexpected batch of {n} for {self.batch} images")

    def pre(self, module, args, kwargs):
        import torch
        args = list(args)
        timestep = args[1] if len(args) > 1 else kwargs.get("timestep")
        t = float(torch.as_tensor(timestep).flatten()[0])
        if self.last_timestep is not None and t != self.last_timestep:
            self.step += 1
        self.last_timestep = t
        if self.step < self.config["warmup"] or not self.concepts:
            return None
        in_args = len(args) > 2
        hidden = args[2] if in_args else kwargs["encoder_hidden_states"]
        uncond_rows, start = self._rows(hidden.shape[0])
        uncond = hidden[uncond_rows] if uncond_rows is not None else self.negative.to(hidden.device)
        scale = self.config["edit_guidance_scale"]
        edit = torch.zeros_like(uncond, dtype=torch.float32)
        for embeds, _, strength in self.concepts:
            concept = embeds.to(hidden.device)
            if concept.shape[1] < uncond.shape[1]:
                # A1111 pad_cond: append empty-prompt chunks.
                chunks = (uncond.shape[1] - concept.shape[1]) // self.empty.shape[1]
                concept = torch.cat([concept] + [self.empty.to(hidden.device)] * chunks, dim=1)
            if concept.shape[1] != uncond.shape[1]:
                raise EngineError(INVALID_PARAMS, "Semantic Guidance concept is longer than the prompt; shorten it")
            edit += edit_direction(concept, uncond, strength, scale, self.z)
        hidden = hidden.clone()
        groups = (hidden.shape[0] - start) // self.batch
        hidden[start:] += edit.repeat(groups, 1, 1).to(hidden.dtype)
        if in_args:
            args[2] = hidden
        else:
            kwargs["encoder_hidden_states"] = hidden
        added = kwargs.get("added_cond_kwargs")
        if added and "text_embeds" in added and self.concepts[0][1] is not None:
            kwargs["added_cond_kwargs"] = self._edit_pooled(dict(added), uncond_rows, start, groups)
        return tuple(args), kwargs

    def _edit_pooled(self, added, uncond_rows, start, groups):
        import torch
        pooled, time_ids = added["text_embeds"], added["time_ids"]
        uncond = pooled[uncond_rows] if uncond_rows is not None else self.negative_pooled.to(pooled.device)
        ids = time_ids[uncond_rows] if uncond_rows is not None else time_ids[:self.batch]
        size = self.add_time_proj(ids.flatten()).reshape(ids.shape[0], -1).to(torch.float32)
        uncond_vector = torch.cat([uncond.float(), size], dim=1)
        scale = self.config["edit_guidance_scale"]
        edit = torch.zeros_like(uncond_vector)
        for _, concept_pooled, strength in self.concepts:
            # The concept shares the positive size conditioning, so only the pooled part differs.
            concept_vector = torch.cat([concept_pooled.to(pooled.device).float().expand_as(uncond), size], dim=1)
            edit += edit_direction(concept_vector, uncond_vector, strength, scale, self.z)
        pooled = pooled.clone()
        pooled[start:] += edit[:, :pooled.shape[1]].repeat(groups, 1).to(pooled.dtype)
        added["text_embeds"] = pooled
        return added


def install(engine, pipe, gp, kw: dict, config: dict, *, composed: bool = False):
    """Encode the concepts like the main prompt and hook the UNet; returns the cleanup callable."""
    import torch
    from . import prompt as prompt_mod
    c = normalize(config)
    if composed:
        raise EngineError(UNSUPPORTED, "Semantic Guidance cannot combine with Neutral Prompt or Composable LoRA branches")
    if "prompt_embeds" not in kw:
        raise EngineError(UNSUPPORTED, "Semantic Guidance needs prompt embeddings (not available with PhotoMaker)")
    embeds = kw["prompt_embeds"]
    batch = embeds.shape[0] if torch.is_tensor(embeds) else max(1, int(gp.batch_size))
    concepts = []
    with torch.no_grad():
        if engine.sdxl:
            empty, _ = prompt_mod.encode_sdxl(pipe, "", gp.clip_skip)
        else:
            empty = prompt_mod.encode_sd15(pipe, "", gp.clip_skip, engine.kind == "sd2")
        for text, strength in parse_concepts(c["prompt"], c["negative_prompt"]):
            if engine.sdxl:
                emb, pooled = prompt_mod.encode_sdxl(pipe, text, gp.clip_skip)
            else:
                emb, pooled = prompt_mod.encode_sd15(pipe, text, gp.clip_skip, engine.kind == "sd2"), None
            concepts.append((emb.to(engine.dtype), None if pooled is None else pooled.to(engine.dtype), strength))
    hook = SemanticGuidanceHook(pipe.unet, concepts, c, batch, empty=empty.to(engine.dtype),
                                negative=kw.get("negative_prompt_embeds"),
                                negative_pooled=kw.get("negative_pooled_prompt_embeds"),
                                add_time_proj=getattr(pipe.unet, "add_time_proj", None)).install()
    return hook.remove
