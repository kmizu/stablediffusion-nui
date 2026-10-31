"""Bounded legacy Fastload trailer handling. Pickle payloads are not executed."""
import gzip
import io

START = b'###START_OF_CONTROLNET_FASTLOAD###'
END = b'###END_OF_CONTROLNET_FASTLOAD###'
MAX_COMPRESSED = 64 * 1024 * 1024
MAX_EXPANDED = 128 * 1024 * 1024
CONTROL_MODES = ('Balanced', 'My prompt is more important', 'ControlNet is more important')
RESOLUTION_MODULES = ('none', 'invert', 'canny')
# Source defaults that select the ordinary single-image path.
NOOP_DEFAULTS = {'input_mode': 'simple', 'output_dir': '', 'loopback': False, 'animatediff_batch': False,
                 'union_control_type': 'Unknown', 'batch_modifiers': [], 'batch_image_files': [],
                 'hr_option': 'Both', 'use_preview_as_input': False, 'guess_mode': False, 'mask': None}


class _Global:
    def __init__(self, module, name):
        self.module, self.name = module, name


class _Unit(dict):
    def __init__(self, modern=False):
        super().__init__()
        self.modern = modern


class _Array(dict):
    pass


class _DType:
    def __init__(self, code):
        import re
        if not isinstance(code, str) or not re.fullmatch(r'[<>=|]?(?:[iu][1248]|f[248]|b1)', code):
            raise ValueError('unsupported array dtype')
        if code[0] not in '<>=|':
            code = ('|' if code[-1] == '1' else '=') + code
        if code[0] == '|' and code[-1] != '1':
            raise ValueError('invalid array byte order')
        self.code = code


def _array(shape, dtype, data, order):
    if not isinstance(dtype, _DType) or not isinstance(shape, tuple) or not 1 <= len(shape) <= 3:
        raise ValueError('unsupported array shape or dtype')
    if not all(type(x) is int and 0 < x <= 16384 for x in shape) or order not in ('C', 'F'):
        raise ValueError('invalid array shape or order')
    if not isinstance(data, bytes):
        raise ValueError('invalid array buffer')
    count = 1
    for n in shape:
        count *= n
        if count > MAX_EXPANDED: raise ValueError('array size limit')
    count *= int(dtype.code[-1])
    if count > MAX_EXPANDED: raise ValueError('array size limit')
    if len(data) != count: raise ValueError('array buffer size mismatch')
    return _Array(dtype=dtype.code, shape=shape, data=data, order=order)


def extract_payload(data, *, max_expanded=MAX_EXPANDED):
    if not isinstance(data, bytes) or not isinstance(max_expanded, int) or max_expanded <= 0:
        raise ValueError('invalid fastload data or limit')
    if data.count(START) != 1 or data.count(END) != 1:
        raise ValueError('missing or ambiguous fastload markers')
    start = data.index(START)
    end = data.index(END)
    if end < start + len(START) or end + len(END) != len(data):
        raise ValueError('invalid fastload trailer')
    compressed = data[start + len(START):end]
    if len(compressed) > MAX_COMPRESSED:
        raise ValueError('compressed fastload data exceeds limit')
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(compressed), mode='rb') as stream:
            payload = stream.read(max_expanded + 1)
    except (OSError, EOFError) as error:
        raise ValueError('invalid gzip data') from error
    if len(payload) > max_expanded:
        raise ValueError('expanded fastload data exceeds limit')
    return data[:start], payload


def append_payload(image, payload):
    if not isinstance(image, bytes) or not isinstance(payload, bytes):
        raise ValueError('image and payload must be bytes')
    if START in image or END in image:
        raise ValueError('image already contains fastload markers')
    if len(payload) > MAX_EXPANDED:
        raise ValueError('expanded fastload data exceeds limit')
    compressed = gzip.compress(payload, mtime=0)
    if len(compressed) > MAX_COMPRESSED:
        raise ValueError('compressed fastload data exceeds limit')
    return image + START + compressed + END


def read_legacy_pickle(data, *, max_ops=1_000_000, max_items=100_000, max_depth=64):
    """Interpret a bounded primitive pickle without importing or invoking globals.

    This subset models specific upstream unit, enum and primitive numeric array records;
    other globals and data types are rejected rather than executed or dropped.
    """
    import pickletools
    if not isinstance(data, bytes) or len(data) > MAX_EXPANDED:
        raise ValueError('invalid pickle data or size limit')
    mark = object()
    stack, memo = [], {}
    def pop_mark():
        try:
            pos = len(stack) - 1 - stack[::-1].index(mark)
        except ValueError as error:
            raise ValueError('invalid pickle mark') from error
        values = stack[pos + 1:]
        del stack[pos:]
        return values
    def check_size():
        if len(stack) > max_items or len(memo) > max_items:
            raise ValueError('pickle size limit')
    try:
        for count, (op, arg, offset) in enumerate(pickletools.genops(data), 1):
            if count > max_ops:
                raise ValueError('pickle operation limit')
            name = op.name
            if name in ('PROTO', 'FRAME'): pass
            elif name == 'MARK': stack.append(mark)
            elif name == 'NONE': stack.append(None)
            elif name == 'NEWTRUE': stack.append(True)
            elif name == 'NEWFALSE': stack.append(False)
            elif name in ('BININT', 'BININT1', 'BININT2', 'LONG', 'LONG1', 'LONG4', 'INT', 'FLOAT', 'BINFLOAT', 'BINUNICODE', 'SHORT_BINUNICODE', 'BINUNICODE8', 'UNICODE', 'BINBYTES', 'SHORT_BINBYTES', 'BINBYTES8', 'BYTEARRAY8'):
                if isinstance(arg, int) and arg.bit_length() > 64: raise ValueError('pickle integer limit')
                stack.append(bytes(arg) if isinstance(arg, bytearray) else arg)
            elif name == 'EMPTY_LIST': stack.append([])
            elif name == 'EMPTY_DICT': stack.append({})
            elif name == 'EMPTY_TUPLE': stack.append(())
            elif name == 'EMPTY_SET': stack.append(set())
            elif name == 'ADDITEMS':
                values = pop_mark()
                if not isinstance(stack[-1], set) or any(type(v) is not str for v in values):
                    raise ValueError('unsupported pickle set')
                stack[-1].update(values)
                if len(stack[-1]) > max_items: raise ValueError('pickle item limit')
            elif name in ('GLOBAL', 'STACK_GLOBAL'):
                if name == 'GLOBAL':
                    module, global_name = arg.split(' ', 1)
                else:
                    global_name = stack.pop(); module = stack.pop()
                allowed = {
                    ('internal_controlnet.external_code', 'ControlNetUnit'),
                    ('internal_controlnet.external_code', 'ResizeMode'),
                    ('internal_controlnet.external_code', 'ControlMode'),
                    ('internal_controlnet.args', 'ControlNetUnit'),
                    ('scripts.enums', 'InputMode'), ('scripts.enums', 'ResizeMode'),
                    ('scripts.enums', 'ControlMode'), ('scripts.enums', 'HiResFixOption'),
                    ('scripts.enums', 'PuLIDMode'), ('scripts.enums', 'ControlNetUnionControlType'),
                    ('__builtin__', 'set'), ('builtins', 'set'),
                    ('numpy', 'ndarray'), ('numpy', 'dtype'),
                    ('numpy.core.multiarray', '_reconstruct'), ('numpy._core.multiarray', '_reconstruct'),
                    ('numpy.core.numeric', '_frombuffer'), ('numpy._core.numeric', '_frombuffer'),
                    ('_codecs', 'encode'),
                }
                if not isinstance(module, str) or not isinstance(global_name, str) or (module, global_name) not in allowed:
                    raise ValueError('unsupported pickle global')
                stack.append(_Global(module, global_name))
            elif name == 'NEWOBJ':
                args = stack.pop(); glob = stack.pop()
                if not isinstance(glob, _Global) or (glob.module, glob.name) not in (('internal_controlnet.external_code', 'ControlNetUnit'), ('internal_controlnet.args', 'ControlNetUnit')) or args != ():
                    raise ValueError('unsupported pickle object')
                stack.append(_Unit(modern=glob.module == 'internal_controlnet.args'))
            elif name == 'REDUCE':
                args = stack.pop(); glob = stack.pop()
                if not isinstance(glob, _Global) or not isinstance(args, tuple):
                    raise ValueError('unsupported pickle reduction')
                key = (glob.module, glob.name)
                if key == ('numpy', 'dtype') and len(args) == 3 and args[1:] == (False, True):
                    stack.append(_DType(args[0]))
                elif key in (('numpy.core.multiarray', '_reconstruct'), ('numpy._core.multiarray', '_reconstruct')):
                    if len(args) != 3 or not isinstance(args[0], _Global) or (args[0].module, args[0].name) != ('numpy', 'ndarray') or args[1:] != ((0,), b'b'):
                        raise ValueError('unsupported array reconstruction')
                    stack.append(_Array())
                elif key in (('numpy.core.numeric', '_frombuffer'), ('numpy._core.numeric', '_frombuffer')) and len(args) == 4:
                    stack.append(_array(args[2], args[1], args[0], args[3]))
                elif key == ('_codecs', 'encode') and len(args) == 2 and isinstance(args[0], str) and args[1] == 'latin1':
                    stack.append(args[0].encode('latin1'))
                elif key in (('__builtin__', 'set'), ('builtins', 'set')) and len(args) == 1 and isinstance(args[0], list) and all(type(v) is str for v in args[0]):
                    stack.append(set(args[0]))
                elif key == ('internal_controlnet.external_code', 'ResizeMode') and len(args) == 1 and args[0] in ('Just Resize', 'Crop and Resize', 'Resize and Fill'):
                    stack.append(args[0])
                elif key == ('internal_controlnet.external_code', 'ControlMode') and len(args) == 1 and args[0] in ('Balanced', 'My prompt is more important', 'ControlNet is more important'):
                    stack.append(args[0])
                elif glob.module == 'scripts.enums' and len(args) == 1 and args[0] in {
                    'InputMode': ('simple', 'batch', 'merge'),
                    'ResizeMode': ('Just Resize', 'Crop and Resize', 'Resize and Fill'),
                    'ControlMode': ('Balanced', 'My prompt is more important', 'ControlNet is more important'),
                    'HiResFixOption': ('Both', 'Low res only', 'High res only'),
                    'PuLIDMode': ('Fidelity', 'Extremely style'),
                    'ControlNetUnionControlType': ('OpenPose', 'Depth', 'Soft Edge', 'Hard Edge', 'Normal Map', 'Segmentation', 'Tile', 'Inpaint', 'Unknown'),
                }.get(glob.name, ()):
                    stack.append(args[0])
                else:
                    raise ValueError('unsupported pickle reduction')
            elif name == 'BUILD':
                state = stack.pop(); target = stack[-1]
                if isinstance(target, _Unit):
                    if not isinstance(state, dict) or any(not isinstance(k, str) for k in state): raise ValueError('invalid unit state')
                    if target.modern:
                        if set(state) != {'__dict__', '__fields_set__', '__private_attribute_values__'} or not isinstance(state['__fields_set__'], set) or state['__private_attribute_values__'] != {}:
                            raise ValueError('unsupported Pydantic unit state')
                        state = state['__dict__']
                        if not isinstance(state, dict) or any(not isinstance(k, str) for k in state): raise ValueError('invalid unit fields')
                    target.update(state)
                elif isinstance(target, _DType):
                    if not isinstance(state, tuple) or len(state) != 8 or state[1] not in ('<', '>', '|', '=') or state[2:] != (None, None, None, -1, -1, 0):
                        raise ValueError('invalid dtype state')
                    target.code = _DType(state[1] + target.code[1:]).code
                elif isinstance(target, _Array):
                    if not isinstance(state, tuple) or len(state) != 5 or state[0] != 1 or type(state[3]) is not bool:
                        raise ValueError('invalid array state')
                    target.update(_array(state[1], state[2], state[4], 'F' if state[3] else 'C'))
                else: raise ValueError('unsupported pickle state')
            elif name == 'MEMOIZE': memo[len(memo)] = stack[-1]
            elif name in ('BINPUT', 'LONG_BINPUT', 'PUT'):
                index = int(arg)
                if index < 0 or index >= max_items: raise ValueError('pickle memo limit')
                memo[index] = stack[-1]
            elif name in ('BINGET', 'LONG_BINGET', 'GET'): stack.append(memo[int(arg)])
            elif name == 'APPEND':
                stack[-2].append(stack.pop())
                if len(stack[-1]) > max_items: raise ValueError('pickle item limit')
            elif name == 'APPENDS':
                values = pop_mark(); stack[-1].extend(values)
                if len(stack[-1]) > max_items: raise ValueError('pickle item limit')
            elif name == 'SETITEM':
                value = stack.pop(); key = stack.pop(); stack[-1][key] = value
                if len(stack[-1]) > max_items: raise ValueError('pickle item limit')
            elif name == 'SETITEMS':
                values = pop_mark()
                if len(values) % 2: raise ValueError('invalid pickle dict')
                target = stack[-1]
                for i in range(0, len(values), 2): target[values[i]] = values[i + 1]
                if len(target) > max_items: raise ValueError('pickle item limit')
            elif name == 'LIST': stack.append(pop_mark())
            elif name == 'TUPLE': stack.append(tuple(pop_mark()))
            elif name in ('TUPLE1', 'TUPLE2', 'TUPLE3'):
                n = int(name[-1]); values = stack[-n:]; del stack[-n:]; stack.append(tuple(values))
            elif name == 'DICT':
                values = pop_mark()
                if len(values) % 2: raise ValueError('invalid pickle dict')
                stack.append(dict(zip(values[::2], values[1::2])))
            elif name == 'STOP':
                if offset != len(data) - 1 or len(stack) != 1: raise ValueError('invalid pickle termination')
                root = stack[0]
                pending = [(root, 0, frozenset())]
                visited = 0
                while pending:
                    value, depth, ancestors = pending.pop(); visited += 1
                    if visited > max_items or depth > max_depth: raise ValueError('pickle structure limit')
                    if isinstance(value, (_Global, _DType, set)):
                        raise ValueError('unresolved pickle value')
                    if isinstance(value, _Array):
                        if set(value) != {'dtype', 'shape', 'data', 'order'}:
                            raise ValueError('unfinished array value')
                        _array(value['shape'], _DType(value['dtype']), value['data'], value['order'])
                    if isinstance(value, (dict, list)):
                        if id(value) in ancestors: raise ValueError('cyclic pickle value')
                        ancestors = ancestors | {id(value)}
                        children = list(value.items()) if isinstance(value, dict) else value
                        if len(pending) + len(children) > max_items:
                            raise ValueError('pickle structure limit')
                        pending.extend((item, depth + 1, ancestors) for item in children)
                    elif isinstance(value, tuple):
                        if len(pending) + len(value) > max_items: raise ValueError('pickle structure limit')
                        pending.extend((item, depth + 1, ancestors) for item in value)
                return root
            else: raise ValueError(f'unsupported pickle opcode: {name}')
            check_size()
    except (AttributeError, IndexError, KeyError, TypeError, OverflowError) as error:
        raise ValueError('malformed pickle') from error
    raise ValueError('incomplete pickle')


def write_legacy_pickle(units, *, max_items=100_000, max_depth=64):
    """Serialize validated data-only units in the upstream dict/NumPy format.

    Input objects are rebuilt from primitive values before pickle.dumps, so no
    caller-defined reduce methods or classes can be invoked by this writer.
    """
    import math
    import pickle
    import numpy as np
    remaining = [max_items]
    bytes_remaining = [MAX_EXPANDED]
    def convert(value, depth, ancestors):
        remaining[0] -= 1
        if remaining[0] < 0 or depth > max_depth: raise ValueError('unit structure limit')
        if value is None or type(value) in (bool, str, bytes): return value
        if type(value) is int:
            if value.bit_length() > 64: raise ValueError('integer limit')
            return value
        if type(value) is float:
            if not math.isfinite(value): raise ValueError('non-finite number')
            return value
        if type(value) not in (list, tuple, dict, _Array, _Unit): raise ValueError('unsupported unit value')
        if id(value) in ancestors: raise ValueError('cyclic unit value')
        ancestors = ancestors | {id(value)}
        if isinstance(value, dict) and set(value) == {'dtype', 'shape', 'data', 'order'}:
            arr = _array(value['shape'], _DType(value['dtype']), value['data'], value['order'])
            bytes_remaining[0] -= len(arr['data'])
            if bytes_remaining[0] < 0: raise ValueError('unit array data limit')
            return np.frombuffer(arr['data'], dtype=np.dtype(arr['dtype'])).reshape(arr['shape'], order=arr['order']).copy(order='K')
        if isinstance(value, dict):
            if any(type(k) is not str for k in value): raise ValueError('unit keys must be strings')
            return {k: convert(v, depth + 1, ancestors) for k, v in value.items()}
        items = [convert(v, depth + 1, ancestors) for v in value]
        return tuple(items) if type(value) is tuple else items
    if type(units) is not list: raise ValueError('units must be a list')
    safe = convert(units, 0, frozenset())
    # Protocol 3 uses textual GLOBAL opcodes and is readable by legacy NumPy 1.
    # NumPy 2 advertises `_core`; normalize only the GLOBAL opcode, never raw
    # buffers, so identical byte sequences inside an image stay untouched.
    import pickletools
    payload = pickle.dumps(safe, protocol=3)
    ops = list(pickletools.genops(payload))
    chunks = []
    for index, (op, arg, start) in enumerate(ops):
        end = ops[index + 1][2] if index + 1 < len(ops) else len(payload)
        if op.name == 'GLOBAL' and arg == 'numpy._core.multiarray _reconstruct':
            chunks.append(b'cnumpy.core.multiarray\n_reconstruct\n')
        else:
            chunks.append(payload[start:end])
    payload = b''.join(chunks)
    if len(payload) > MAX_EXPANDED: raise ValueError('expanded fastload data exceeds limit')
    return payload


def _check_units(units):
    if not isinstance(units, list) or any(not isinstance(unit, dict) for unit in units):
        raise ValueError('fastload payload must contain a list of units')
    pending = list(units)
    while pending:
        value = pending.pop()
        if isinstance(value, dict):
            if any(type(key) is not str for key in value):
                raise ValueError('fastload unit keys must be strings')
            pending.extend(value.values())
        elif isinstance(value, (list, tuple)):
            pending.extend(value)


def decode_fastload(data):
    image, payload = extract_payload(data)
    units = read_legacy_pickle(payload)
    _check_units(units)
    return image, units


def encode_fastload(image, units):
    payload = write_legacy_pickle(units)
    _check_units(read_legacy_pickle(payload))
    return append_payload(image, payload)


def encode_wire(value, *, max_items=100_000, max_depth=64):
    """Convert a decoded value to lossless JSON data without exposing pickle."""
    import base64
    import math
    remaining, byte_budget = [max_items], [MAX_EXPANDED]
    def walk(item, depth, ancestors):
        remaining[0] -= 1
        if remaining[0] < 0 or depth > max_depth: raise ValueError('wire structure limit')
        if item is None: return {'type':'null'}
        if type(item) is bool: return {'type':'bool', 'value':item}
        if type(item) is int:
            if item.bit_length() > 64: raise ValueError('wire integer limit')
            return {'type':'int', 'value':str(item)}
        if type(item) is float:
            if not math.isfinite(item): raise ValueError('non-finite wire number')
            return {'type':'float', 'value':item}
        if type(item) is str: return {'type':'string', 'value':item}
        if type(item) is bytes:
            byte_budget[0] -= len(item)
            if byte_budget[0] < 0: raise ValueError('wire bytes limit')
            return {'type':'bytes', 'base64':base64.b64encode(item).decode('ascii')}
        if type(item) not in (list, tuple, dict, _Unit, _Array): raise ValueError('unsupported wire value')
        if id(item) in ancestors: raise ValueError('cyclic wire value')
        ancestors = ancestors | {id(item)}
        if isinstance(item, dict) and set(item) == {'dtype','shape','data','order'}:
            array = _array(item['shape'], _DType(item['dtype']), item['data'], item['order'])
            byte_budget[0] -= len(array['data'])
            if byte_budget[0] < 0: raise ValueError('wire bytes limit')
            return {'type':'array','dtype':array['dtype'],'shape':list(array['shape']),
                    'data':base64.b64encode(array['data']).decode('ascii'),'order':array['order']}
        if isinstance(item, dict):
            if any(type(key) is not str for key in item): raise ValueError('wire keys must be strings')
            return {'type':'dict','items':[[key, walk(val, depth+1, ancestors)] for key,val in item.items()]}
        return {'type':'tuple' if type(item) is tuple else 'list', 'items':[walk(v,depth+1,ancestors) for v in item]}
    return walk(value,0,frozenset())


def decode_wire(value, *, max_items=100_000, max_depth=64):
    """Validate and reconstruct the tagged JSON value without executing code."""
    import base64
    import binascii
    import math
    remaining, byte_budget = [max_items], [MAX_EXPANDED]
    def data_bytes(encoded):
        if type(encoded) is not str or len(encoded) > ((byte_budget[0] + 2) // 3) * 4:
            raise ValueError('wire bytes limit or invalid base64')
        try: result = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as error: raise ValueError('invalid wire base64') from error
        byte_budget[0] -= len(result)
        if byte_budget[0] < 0: raise ValueError('wire bytes limit')
        return result
    def walk(item,depth):
        remaining[0] -= 1
        if remaining[0] < 0 or depth > max_depth: raise ValueError('wire structure limit')
        if type(item) is not dict or type(item.get('type')) is not str: raise ValueError('invalid wire value')
        kind = item['type']
        if kind == 'null' and set(item) == {'type'}: return None
        if kind in ('bool','int','float','string') and set(item) == {'type','value'}:
            v = item['value']
            if kind == 'bool' and type(v) is bool: return v
            if kind == 'string' and type(v) is str: return v
            if kind == 'float' and type(v) in (int,float) and math.isfinite(v): return float(v)
            if kind == 'int' and type(v) is str and len(v) <= 21:
                try: n = int(v)
                except ValueError: pass
                else:
                    if str(n) == v and n.bit_length() <= 64: return n
            raise ValueError('invalid wire scalar')
        if kind == 'bytes' and set(item) == {'type','base64'}: return data_bytes(item['base64'])
        if kind == 'array' and set(item) == {'type','dtype','shape','data','order'}:
            if type(item['shape']) is not list: raise ValueError('invalid wire shape')
            return _array(tuple(item['shape']), _DType(item['dtype']), data_bytes(item['data']), item['order'])
        if kind in ('list','tuple') and set(item) == {'type','items'} and type(item['items']) is list:
            result=[walk(v,depth+1) for v in item['items']]
            return tuple(result) if kind == 'tuple' else result
        if kind == 'dict' and set(item) == {'type','items'} and type(item['items']) is list:
            out={}
            for pair in item['items']:
                if type(pair) is not list or len(pair)!=2 or type(pair[0]) is not str or pair[0] in out:
                    raise ValueError('invalid or duplicate wire key')
                out[pair[0]]=walk(pair[1],depth+1)
            return out
        raise ValueError('invalid wire value')
    return walk(value,0)


def resolve_units(mode, priority, current, loaded):
    """Choose immutable per-job units using upstream mode and precedence rules."""
    if mode not in ('load_only','save_only','load_save'):
        raise ValueError('invalid fastload mode')
    if priority not in ('current_first','loaded_first'):
        raise ValueError('invalid fastload priority')
    def freeze(units):
        if type(units) is not list or any(not isinstance(unit, dict) for unit in units):
            raise ValueError('fastload units must be a list of objects')
        copied = decode_wire(encode_wire(units))
        if any(type(unit.get('enabled', False)) is not bool for unit in copied):
            raise ValueError('fastload enabled must be boolean')
        return copied
    active = freeze(current)
    if mode == 'save_only': return active, True
    # Upstream skips loading when no file was supplied; Load & Save still saves
    # the existing units for this job.
    if loaded is None: return active, mode == 'load_save'
    if priority == 'current_first' and any(unit.get('enabled', False) for unit in active):
        return active, mode == 'load_save'
    chosen = freeze(loaded)
    return chosen, mode == 'load_save'


def scan_gallery(root, ctx, *, with_info=False):
    """Index readable image metadata without following links or creating files."""
    import os
    import warnings
    from pathlib import Path
    from PIL import Image, UnidentifiedImageError
    root = Path(root).expanduser().resolve()
    if ctx is None or not root.is_dir():
        raise ValueError('gallery directory is unavailable')
    entries, scanned, total, skipped_links, skipped_oversize = [], 0, 0, 0, 0
    def fail(error): raise error
    for directory, subdirs, files in os.walk(root, followlinks=False, onerror=fail):
        ctx.check_interrupt()
        kept = []
        for name in sorted(subdirs):
            if (Path(directory) / name).is_symlink(): skipped_links += 1
            else: kept.append(name)
        subdirs[:] = kept
        for name in sorted(files):
            ctx.check_interrupt()
            scanned += 1
            if scanned > 100_000: raise ValueError('gallery file count limit')
            path = Path(directory) / name
            if path.is_symlink():
                skipped_links += 1
                continue
            if not path.is_file(): continue
            try:
                with warnings.catch_warnings():
                    warnings.simplefilter('error', Image.DecompressionBombWarning)
                    with Image.open(path) as opened:
                        if opened.width * opened.height > 32_000_000:
                            skipped_oversize += 1
                            continue
                        metadata = opened.info.get('parameters', '')
                        if not isinstance(metadata, str): metadata = ''
            except (Image.DecompressionBombError, Image.DecompressionBombWarning):
                skipped_oversize += 1
                continue
            except (UnidentifiedImageError, OSError, ValueError):
                continue
            if len(metadata) > 2_000_000: raise ValueError('gallery metadata limit')
            total += len(metadata)
            if total > 32_000_000: raise ValueError('gallery metadata limit')
            entries.append((str(path), metadata))
    return (entries, {'skipped_links': skipped_links, 'skipped_oversize': skipped_oversize}) if with_info else entries


def metadata_controlnet_pairs(text):
    """Read ControlNet key/value groups from A1111 parameters text."""
    if not isinstance(text, str) or len(text) > 2_000_000:
        raise ValueError('invalid or oversized metadata')
    groups, pos = [], 0
    while True:
        start = text.find('ControlNet', pos)
        if start < 0: break
        quote = text.find('"', start + len('ControlNet'))
        if quote < 0: break
        end = text.find('"', quote + 1)
        if end < 0: break
        pos = end + 1
        if quote == start + len('ControlNet') or end == quote + 1: continue
        group, index, pairs = text[quote + 1:end], 0, []
        next_close = group.find(')')
        if next_close < 0: next_close = len(group)
        while index < len(group):
            colon = index
            while colon < len(group) and group[colon] not in ':,': colon += 1
            if colon == len(group): break
            if group[colon] == ',':
                index = colon + 1
                continue
            key = group[index:colon].lstrip()
            value_start = colon + 1
            while value_start < len(group) and group[value_start].isspace(): value_start += 1
            if value_start < len(group) and group[value_start] == '(':
                if next_close < value_start:
                    next_close = group.find(')', value_start)
                    if next_close < 0: next_close = len(group)
                close = next_close
                value_end = close + 1 if close < len(group) and (close + 1 == len(group) or group[close + 1] == ',') else group.find(',', value_start)
            else:
                value_end = group.find(',', value_start)
            if value_end < 0: value_end = len(group)
            value = group[value_start:value_end]
            if key and value: pairs.append((key, value))
            index = value_end + 1
        groups.append(pairs)
    return groups


def filter_gallery_page(entries, filters, page, page_size=36, check=None):
    """Intersect metadata filters and return a deterministic page of paths."""
    if not isinstance(entries, list) or len(entries) > 100_000 or not isinstance(filters, list):
        raise ValueError('invalid gallery input')
    if type(page) is not int or type(page_size) is not int or not 1 <= page_size <= 1000:
        raise ValueError('invalid gallery page')
    if any(not isinstance(item, (tuple, list)) or len(item) != 2 or
           any(not isinstance(value, str) for value in item) for item in filters):
        raise ValueError('invalid gallery filter')
    wanted = {tuple(item) for item in filters}
    selected, remaining = [], 32_000_000
    for entry in entries:
        if check is not None: check()
        if not isinstance(entry, (tuple, list)) or len(entry) != 2 or not isinstance(entry[0], str):
            raise ValueError('invalid gallery entry')
        if not isinstance(entry[1], str): raise ValueError('invalid gallery metadata')
        remaining -= len(entry[1])
        if remaining < 0: raise ValueError('gallery metadata limit')
        pairs = {pair for group in metadata_controlnet_pairs(entry[1]) for pair in group}
        if wanted <= pairs: selected.append(entry[0])
    selected.sort()
    pages = (len(selected) + page_size - 1) // page_size
    if pages == 0: return [], 0
    if not 1 <= page <= pages: page = 1
    return selected[(page - 1) * page_size:page * page_size], pages


def source_rgb(image):
    """Source ControlNet HWC3: composite alpha over white, expand grayscale."""
    import numpy as np
    from PIL import Image
    if image.mode in ('RGBA', 'LA') or (image.mode == 'P' and 'transparency' in image.info):
        rgba = np.asarray(image.convert('RGBA'), dtype=np.uint8)
        alpha = rgba[:, :, 3:4].astype(np.float32) / 255.0
        pixels = rgba[:, :, :3].astype(np.float32) * alpha + 255.0 * (1.0 - alpha)
        return Image.fromarray(pixels.clip(0, 255).astype(np.uint8), 'RGB')
    return image.convert('RGB')


def noninpaint_mask_input(image, mask, *, ignore_mask=False):
    """Choose upstream's ordinary RGB or mask-scribble input (not inpaint/CLIP)."""
    from PIL import Image
    if not isinstance(image, Image.Image) or not isinstance(mask, Image.Image):
        raise ValueError('image and mask are required')
    if image.size != mask.size:
        raise ValueError('image and mask sizes differ')
    source = source_rgb(image)
    # Source applies HWC3 (alpha over white) to the mask, then reads channel 0.
    channel = mask if mask.mode == 'L' else source_rgb(mask).getchannel('R')
    low, high = channel.getextrema()
    if ignore_mask or high <= 5 or low >= 250:
        return source
    return Image.merge('RGB', (channel, channel, channel))


def map_native_unit(unit):
    """Map representable values and return every unrepresented source field.

    Image/mask data remain in memory. This function neither creates files nor
    implies that the native engine already implements every upstream option.
    """
    import math
    if not isinstance(unit, dict): raise ValueError('unit must be an object')
    original = decode_wire(encode_wire(unit))
    enabled = original.get('enabled', False)
    model = original.get('model') or ''
    module = original.get('module') or 'none'
    if type(enabled) is not bool or type(model) is not str or type(module) is not str:
        raise ValueError('invalid native ControlNet identity')
    def number(name, default, minimum, maximum):
        value = original.get(name, default)
        if type(value) not in (int,float) or not math.isfinite(value) or not minimum <= value <= maximum:
            raise ValueError(f'invalid native ControlNet {name}')
        return float(value)
    weight = number('weight', 1, 0, 2)
    start = number('guidance_start', 0, 0, 1)
    end = number('guidance_end', 1, 0, 1)
    if start > end: raise ValueError('ControlNet start exceeds end')
    resize_value = original.get('resize_mode', 'Crop and Resize')
    resize_names = {'Just Resize':0, 'Crop and Resize':1, 'Resize and Fill':2,
                    'Inner Fit (Scale to Fit)':1, 'Scale to Fit (Inner Fit)':1,
                    'Outer Fit (Shrink to Fit)':2, 'Envelope (Outer Fit)':2}
    if type(resize_value) is int and resize_value in (0,1,2,3):
        resize_mode = 0 if resize_value == 3 else resize_value
    elif type(resize_value) is str and resize_value in resize_names:
        resize_mode = resize_names[resize_value]
    else:
        raise ValueError('unsupported ControlNet resize mode')
    source_resize = original.get('_sdfx_source_compatible_resize', True)
    if type(source_resize) is not bool: raise ValueError('invalid ControlNet resize compatibility')
    mapped = {'enabled':enabled, 'model':model, 'preprocessor':module, 'weight':weight,
              'guidance_start':start, 'guidance_end':end, 'preprocessor_args':{}, 'resize_mode':resize_mode,
              'source_compatible_resize':source_resize, 'control_mode':'Balanced', 'processor_res':-1,
              'pixel_perfect':False}
    used = {'enabled','model','module','weight','guidance_start','guidance_end','image','resize_mode',
            '_sdfx_source_compatible_resize'}
    control_mode = original.get('control_mode', 'Balanced')
    if type(control_mode) is str and control_mode in CONTROL_MODES:
        mapped['control_mode'] = control_mode
        used.add('control_mode')
    if module in RESOLUTION_MODULES:
        # Source canny resizes to the processor resolution; none/invert ignore it and both thresholds.
        pixel_perfect = original.get('pixel_perfect', False)
        res = original.get('processor_res', -1)
        if type(pixel_perfect) is bool and type(res) in (int, float) and math.isfinite(res):
            mapped['pixel_perfect'] = pixel_perfect
            # Source resets an out-of-range resolution to the preprocessor default.
            mapped['processor_res'] = int(res) if 64 <= res <= 2048 and res == int(res) else -1
            used.update(('pixel_perfect', 'processor_res'))
        if module != 'canny':
            used.update(name for name in ('threshold_a', 'threshold_b') if name in original)
    # Values without an effect on the pinned unit's ordinary txt2img/img2img output.
    # low_vram only moves models between devices; save_detected_map only adds a UI preview.
    for name in ('low_vram', 'save_detected_map', 'is_ui', 'pulid_mode', 'inpaint_crop_input_image'):
        if type(original.get(name)) in (bool, str): used.add(name)
    for name, default in NOOP_DEFAULTS.items():
        if name in original and original[name] == default: used.add(name)
    for name in ('advanced_weighting', 'effective_region_mask', 'ipadapter_input', 'batch_images',
                 'batch_mask_dir', 'batch_keyframe_idx', 'generated_image'):
        if name in original and original[name] is None: used.add(name)
    if module == 'canny':
        for source, target in (('threshold_a','low_threshold'),('threshold_b','high_threshold')):
            if source in original:
                value=original[source]
                if type(value) not in (int,float) or not math.isfinite(value): raise ValueError('invalid canny threshold')
                if value != -1:
                    if not 0 <= value <= 255: raise ValueError('invalid canny threshold')
                    mapped['preprocessor_args'][target] = float(value)
                used.add(source)
    raw_image = original.get('image')
    image, mask = raw_image, None
    extras = {}
    has_embedded_mask = False
    if isinstance(raw_image, list) and raw_image and isinstance(raw_image[0], dict) and 'image' in raw_image[0]:
        extras['image.batch'] = raw_image
        has_embedded_mask = True
    if isinstance(raw_image, dict) and not isinstance(raw_image, _Array) and set(raw_image) != {'dtype','shape','data','order'}:
        has_embedded_mask = True
        image, mask = raw_image.get('image'), raw_image.get('mask')
        extras = {f'image.{key}':value for key,value in raw_image.items() if key not in ('image','mask')}
    elif (isinstance(raw_image, (tuple, list)) and len(raw_image)==2
          and not (isinstance(raw_image[0], dict) and 'image' in raw_image[0])):
        has_embedded_mask = True
        image, mask = raw_image
    if not has_embedded_mask and original.get('mask') is not None:
        mask = original['mask']
        used.add('mask')
    unapplied = {key:value for key,value in original.items() if key not in used}
    unapplied.update(extras)
    # Source uses a non-inpaint, non-CLIP mask only to choose RGB or mask scribble input (native_form applies it).
    if mask is not None and ('inpaint' in module or 'clip' in module or 'ip-adapter' in module):
        unapplied['image.mask'] = mask
    return {'params':mapped, 'image':image, 'mask':mask, 'unapplied':unapplied, 'original':original}


def native_to_legacy_unit(params, image=None, mask=None):
    """Construct a detached legacy unit from explicitly representable native fields."""
    import base64
    import io
    import warnings
    from PIL import Image
    def legacy_image(value):
        if value is None: return None
        if isinstance(value, dict) and set(value) == {'dtype', 'shape', 'data', 'order'}:
            return _array(value['shape'], _DType(value['dtype']), value['data'], value['order'])
        if type(value) is not bytes or len(value) > MAX_EXPANDED:
            raise ValueError('invalid native image')
        try:
            with warnings.catch_warnings():
                warnings.simplefilter('error', Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(value)) as opened:
                    if opened.width * opened.height > 32_000_000: raise ValueError('native image exceeds limit')
                    opened.verify()
        except (OSError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
            raise ValueError('invalid native image') from error
        return base64.b64encode(value).decode('ascii')
    if type(params) is not dict:
        raise ValueError('native unit must be an object')
    allowed = {'enabled', 'model', 'preprocessor', 'weight', 'guidance_start', 'guidance_end',
               'preprocessor_args', 'resize_mode', 'source_compatible_resize', 'control_mode',
               'processor_res', 'pixel_perfect'}
    if set(params) - allowed:
        raise ValueError('unsupported native ControlNet fields')
    args = params.get('preprocessor_args', {})
    if type(args) is not dict:
        raise ValueError('invalid preprocessor arguments')
    module = params.get('preprocessor', 'none')
    if set(args) - ({'low_threshold', 'high_threshold'} if module == 'canny' else set()):
        raise ValueError('unsupported preprocessor arguments')
    unit = {'enabled': params.get('enabled', False), 'model': params.get('model', ''),
            'module': module, 'weight': params.get('weight', 1),
            'guidance_start': params.get('guidance_start', 0),
            'guidance_end': params.get('guidance_end', 1)}
    resize_mode = params.get('resize_mode', 1)
    if type(resize_mode) is not int or resize_mode not in (0, 1, 2):
        raise ValueError('invalid native ControlNet resize mode')
    unit['resize_mode'] = ('Just Resize', 'Crop and Resize', 'Resize and Fill')[resize_mode]
    source_resize = params.get('source_compatible_resize', False)
    if type(source_resize) is not bool: raise ValueError('invalid native resize compatibility')
    if not source_resize: unit['_sdfx_source_compatible_resize'] = False
    control_mode = params.get('control_mode', 'Balanced')
    if control_mode not in CONTROL_MODES: raise ValueError('invalid native ControlNet control mode')
    unit['control_mode'] = control_mode
    res, pixel_perfect = params.get('processor_res', -1), params.get('pixel_perfect', False)
    if type(res) is not int or not (res == -1 or 64 <= res <= 2048) or type(pixel_perfect) is not bool:
        raise ValueError('invalid native ControlNet processor resolution')
    if (res != -1 or pixel_perfect) and module not in RESOLUTION_MODULES:
        raise ValueError('processor resolution is not supported for this preprocessor')
    if module in RESOLUTION_MODULES:
        unit['processor_res'], unit['pixel_perfect'] = res, pixel_perfect
    if image is None and mask is not None:
        raise ValueError('native mask requires an image')
    image, mask = legacy_image(image), legacy_image(mask)
    if image is not None:
        unit['image'] = {'image': image, 'mask': mask} if mask is not None else image
    for source, target in (('low_threshold', 'threshold_a'), ('high_threshold', 'threshold_b')):
        if source in args:
            unit[target] = args[source]
    mapped = map_native_unit(unit)
    return mapped['original']


def publish_fastload(target, units, image_prefix, ctx):
    """Publish a new legacy output atomically without replacing existing files.

    A protected session must never create a readable legacy trailer or sidecar.
    Protected publishing is explicitly unavailable until a compatible protected
    format is implemented; no plaintext stage is created on that path.
    """
    import os
    import tempfile
    from pathlib import Path
    if ctx is None: raise ValueError('owned publication context is required')
    if getattr(ctx, 'image_password', None):
        raise ValueError('protected sessions cannot write readable Fastload payloads')
    ctx.check_interrupt()
    target = Path(target)
    if target.suffix.lower() not in ('.cni', '.png', '.jpg', '.jpeg', '.webp'):
        raise ValueError('unsupported Fastload output extension')
    if not target.parent.is_dir(): raise ValueError('output directory does not exist')
    if target.exists() or target.is_symlink(): raise FileExistsError('output already exists')
    if target.suffix.lower() == '.cni' and image_prefix:
        raise ValueError('standalone cni must not contain image data')
    if target.suffix.lower() != '.cni' and not image_prefix:
        raise ValueError('image output requires image bytes')
    payload = encode_fastload(image_prefix, units)
    ctx.check_interrupt()
    fd, name = tempfile.mkstemp(prefix='.fastload-stage-', suffix='.tmp', dir=str(target.parent))
    identity = None
    try:
        with os.fdopen(fd, 'wb') as stage:
            identity = os.fstat(stage.fileno())
            stage.write(payload); stage.flush(); os.fsync(stage.fileno())
        ctx.check_interrupt()
        def commit():
            os.link(name, target)
            return str(target)
        return ctx.publish(commit)
    finally:
        try:
            current=os.stat(name, follow_symlinks=False)
            if identity is not None and (current.st_dev,current.st_ino)==(identity.st_dev,identity.st_ino): os.unlink(name)
        except FileNotFoundError:
            pass


def run_fastload_service(params, ctx):
    """Data-only service boundary shared by worker and future native UI."""
    import base64
    import binascii
    from pathlib import Path
    if not isinstance(params, dict) or ctx is None:
        raise ValueError('invalid Fastload request')
    operation = params.get('operation')
    ctx.check_interrupt()
    def summarize(units):
        summary = []
        for unit in units:
            try:
                mapped = map_native_unit(unit)
                info = {'enabled':mapped['params']['enabled'], 'model':mapped['params']['model'],
                    'preprocessor':mapped['params']['preprocessor'], 'unapplied':sorted(mapped['unapplied'])}
                if not mapped['params']['source_compatible_resize']:
                    info['warning'] = ('This unit uses NUI resize interpolation. Other Fastload apps may use different interpolation.')
                summary.append(info)
            except ValueError:
                summary.append({'error':'Some values cannot be applied to the current ControlNet form'})
        return summary
    if operation == 'version':
        return {'version':1, 'upstream_version':1.1, 'legacy_subset_only':True, 'protected_write':False}
    if operation == 'gallery':
        path = params.get('path')
        if not isinstance(path, str) or not path: raise ValueError('gallery directory is required')
        entries, scan_info = scan_gallery(path, ctx, with_info=True)
        choices, pair_count = {}, 0
        for _, metadata in entries:
            ctx.check_interrupt()
            for group in metadata_controlnet_pairs(metadata):
                for key, value in group:
                    pair_count += 1
                    if pair_count > 100_000: raise ValueError('gallery filter count limit')
                    choices.setdefault(key, set()).add(value)
        requested_page = params.get('page', 1)
        paths, pages = filter_gallery_page(entries, params.get('filters', []), requested_page, check=ctx.check_interrupt)
        ctx.check_interrupt()
        page = 0 if pages == 0 else requested_page if 1 <= requested_page <= pages else 1
        return {'paths': paths, 'page': page, 'pages': pages,
                'choices': {key: sorted(values) for key, values in sorted(choices.items())}, **scan_info}
    if operation == 'gallery_source':
        path = params.get('path')
        if not isinstance(path, str) or not path: raise ValueError('gallery image is required')
        image = Path(path).expanduser().absolute()
        if image.is_symlink() or not image.is_file(): raise ValueError('gallery image is unavailable')
        def read_bounded(source):
            with source.open('rb') as stream:
                data = stream.read(MAX_EXPANDED + MAX_COMPRESSED + 1024)
            if len(data) >= MAX_EXPANDED + MAX_COMPRESSED + 1024: raise ValueError('input file exceeds limit')
            return data
        data = read_bounded(image)
        if START in data or END in data:
            _, units = decode_fastload(data)
            if units:
                ctx.check_interrupt()
                return {'path': str(image), 'kind': 'embedded'}
        sidecar = image.with_suffix('.cni')
        if sidecar.is_symlink(): raise ValueError('gallery sidecar is a symbolic link')
        if sidecar.is_file():
            _, units = decode_fastload(read_bounded(sidecar))
            if units:
                ctx.check_interrupt()
                return {'path': str(sidecar), 'kind': 'sidecar'}
        ctx.check_interrupt()
        return {'path': None, 'kind': 'none'}
    if operation == 'inspect':
        path = params.get('path')
        if not isinstance(path,str) or not path: raise ValueError('input path is required')
        with Path(path).open('rb') as source:
            raw = source.read(MAX_EXPANDED + MAX_COMPRESSED + 1024)
        if len(raw) >= MAX_EXPANDED + MAX_COMPRESSED + 1024: raise ValueError('input file exceeds limit')
        prefix, units = decode_fastload(raw)
        ctx.check_interrupt()
        return {'units':encode_wire(units), 'has_image':bool(prefix), 'summary':summarize(units)}
    if operation == 'map':
        units = decode_wire(params.get('units'))
        _check_units(units)
        return {'mapped':encode_wire([map_native_unit(unit) for unit in units])}
    if operation == 'from_native':
        items = decode_wire(params.get('items'))
        _check_units(items)
        units = []
        for item in items:
            if type(item) is not dict or set(item) - {'params', 'image', 'mask'}:
                raise ValueError('invalid native unit')
            units.append(native_to_legacy_unit(item.get('params'), item.get('image'), item.get('mask')))
        ctx.check_interrupt()
        return {'units': encode_wire(units), 'summary': summarize(units)}
    if operation == 'preview':
        import io
        import warnings
        import numpy as np
        from PIL import Image
        units = decode_wire(params.get('units'))
        _check_units(units)
        index = params.get('index')
        if type(index) is not int or index < 0 or index >= len(units): raise ValueError('invalid preview index')
        mapped = map_native_unit(units[index])
        def render(value):
            if value is None: return None
            if isinstance(value, dict) and set(value) == {'dtype','shape','data','order'}:
                a = _array(value['shape'], _DType(value['dtype']), value['data'], value['order'])
                if a['dtype'] != '|u1' or len(a['shape']) not in (2,3) or (len(a['shape']) == 3 and a['shape'][2] not in (3,4)):
                    raise ValueError('preview supports only uint8 grayscale, RGB or RGBA arrays')
                im = Image.fromarray(np.frombuffer(a['data'],dtype=np.uint8).reshape(a['shape'],order=a['order']))
            else:
                if type(value) is str:
                    encoded = value.split(',',1)[1] if value.startswith('data:image/') and ',' in value else value
                    if len(encoded) > (MAX_EXPANDED + 2)//3*4: raise ValueError('preview input exceeds limit')
                    try: value = base64.b64decode(encoded,validate=True)
                    except (ValueError,binascii.Error) as error: raise ValueError('invalid preview image') from error
                if type(value) is not bytes or len(value) > MAX_EXPANDED: raise ValueError('unsupported preview image')
                try:
                    with warnings.catch_warnings():
                        warnings.simplefilter('error', Image.DecompressionBombWarning)
                        with Image.open(io.BytesIO(value)) as opened:
                            if opened.width * opened.height > 32_000_000: raise ValueError('preview image exceeds limit')
                            im = opened.copy()
                except (OSError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
                    raise ValueError('invalid preview image') from error
            if im.width * im.height > 32_000_000: raise ValueError('preview image exceeds limit')
            output = io.BytesIO(); im.save(output,format='PNG')
            if output.tell() > MAX_EXPANDED: raise ValueError('preview output exceeds limit')
            return base64.b64encode(output.getvalue()).decode('ascii')
        image, mask = render(mapped['image']), render(mapped['mask'])
        ctx.check_interrupt()
        return {'image':image,'mask':mask}
    if operation == 'native_form':
        units = decode_wire(params.get('units'))
        _check_units(units)
        index = params.get('index')
        if type(index) is not int or index < 0 or index >= len(units):
            raise ValueError('invalid native form index')
        mapped = map_native_unit(units[index])
        previews = run_fastload_service({'operation': 'preview', 'units': encode_wire([units[index]]), 'index': 0}, ctx)
        unapplied = sorted(mapped['unapplied'])
        from . import controlnet as cn_mod
        module = mapped['params']['preprocessor']
        if mapped['params']['enabled'] and module not in ('none', '') and module not in cn_mod.available_preprocessors():
            unapplied.append(f'module={module} (unavailable)')
        image, mask = previews['image'], previews['mask']
        consume_mask = mask is not None and 'image.mask' not in mapped['unapplied']
        if image is not None and (consume_mask or mapped['params']['source_compatible_resize']):
            import io
            from PIL import Image
            with Image.open(io.BytesIO(base64.b64decode(image))) as a:
                if consume_mask:
                    with Image.open(io.BytesIO(base64.b64decode(mask))) as b:
                        selected = noninpaint_mask_input(a, b)
                    mask = None
                else:
                    selected = source_rgb(a)
            output = io.BytesIO(); selected.save(output, format='PNG')
            image = base64.b64encode(output.getvalue()).decode('ascii')
        return {'params': mapped['params'], 'image': image, 'mask': mask, 'unapplied': unapplied}
    if operation == 'prepare_generation':
        current = decode_wire(params.get('current'))
        mode, priority = params.get('mode'), params.get('priority')
        frozen, _ = resolve_units(mode, priority, current, None)
        path = params.get('path', '')
        if not isinstance(path, str): raise ValueError('invalid Fastload input path')
        skip = mode == 'save_only' or not path or (
            priority == 'current_first' and any(unit.get('enabled', False) for unit in frozen))
        loaded = None
        if not skip:
            with Path(path).open('rb') as source:
                raw = source.read(MAX_EXPANDED + MAX_COMPRESSED + 1024)
            if len(raw) >= MAX_EXPANDED + MAX_COMPRESSED + 1024: raise ValueError('input file exceeds limit')
            _, loaded = decode_fastload(raw)
        ctx.check_interrupt()
        units, save = resolve_units(mode, priority, current, loaded)
        result = {'units': encode_wire(units), 'save': save,
                  'load_skipped': skip and mode != 'save_only'}
        forms = params.get('native_forms', False)
        if type(forms) is not bool: raise ValueError('invalid native forms flag')
        if forms:
            if len(units) > 3: raise ValueError('native generation supports at most three ControlNet units')
            wire = result['units']
            result['forms'] = [
                {'params': {'enabled': False}, 'image': None, 'mask': None, 'unapplied': []}
                if unit.get('enabled', False) is False else
                run_fastload_service({'operation': 'native_form', 'units': wire, 'index': index}, ctx)
                for index, unit in enumerate(units)
            ]
        return result
    if operation == 'resolve':
        current = decode_wire(params.get('current'))
        mode, priority = params.get('mode'), params.get('priority')
        frozen, _ = resolve_units(mode, priority, current, None)
        skip = mode == 'save_only' or params.get('loaded') is None or (
            priority == 'current_first' and any(unit.get('enabled', False) for unit in frozen))
        loaded = None if skip else decode_wire(params['loaded'])
        units, save = resolve_units(params.get('mode'),params.get('priority'),current,loaded)
        return {'units':encode_wire(units), 'save':save,
                'load_skipped': skip and mode != 'save_only'}
    if operation == 'encode':
        if getattr(ctx, 'image_password', None):
            raise ValueError('protected sessions cannot encode readable Fastload payloads')
        units = decode_wire(params.get('units'))
        payload = encode_fastload(b'', units)
        ctx.check_interrupt()
        encoded = base64.b64encode(payload).decode('ascii')
        # Claim the response under the worker's cancellation lock. A later
        # interrupt is explicitly too late; an earlier one cannot return bytes.
        return ctx.publish(lambda: {'base64': encoded})
    if operation == 'save':
        if getattr(ctx, 'image_password', None):
            raise ValueError('protected sessions cannot write readable Fastload payloads')
        path = params.get('path')
        if not isinstance(path,str) or not path: raise ValueError('output path is required')
        units = decode_wire(params.get('units'))
        prefix = params.get('image_base64', '')
        if type(prefix) is not str or len(prefix) > (MAX_EXPANDED + 2) // 3 * 4: raise ValueError('invalid or oversized image')
        try: image = base64.b64decode(prefix,validate=True)
        except (ValueError,binascii.Error) as error: raise ValueError('invalid image base64') from error
        return {'path':publish_fastload(path,units,image,ctx)}
    raise ValueError('unknown Fastload operation')
