"""Generate synthetic Pydantic ControlNetUnit fixtures from pinned upstream AST."""
import ast, hashlib, json, pickle, sys, types, urllib.request
from enum import Enum
from pathlib import Path
from typing import *
from logging import Logger
import numpy as np
import torch
from PIL import Image
from pydantic import BaseModel, Field, validator, root_validator

rev='56cec5b2958edf3b1807b7e7b2b1b5186dbd2f81'
base=f'https://raw.githubusercontent.com/Mikubill/sd-webui-controlnet/{rev}/'
checks={'internal_controlnet/args.py':'867216765a99ada5fc501d4004665f5fee222683ce841e637af71e03953e628e','scripts/enums.py':'ad88ccee8975185e9c57eef66781f6acfc9cf226bb3d34e60fd3fc7ab8525710'}
sources={}
for path, expected in checks.items():
 data=urllib.request.urlopen(base+path,timeout=20).read()
 assert hashlib.sha256(data).hexdigest()==expected
 sources[path]=ast.parse(data)
names={'InputMode','ResizeMode','ControlMode','HiResFixOption','PuLIDMode','ControlNetUnionControlType'}
enums=[n for n in sources['scripts/enums.py'].body if isinstance(n,ast.ClassDef) and n.name in names]
assert {n.name for n in enums}==names
sys.modules['scripts']=types.ModuleType('scripts')
enum_mod=types.ModuleType('scripts.enums'); enum_mod.__dict__['Enum']=Enum; sys.modules['scripts.enums']=enum_mod
exec(compile(ast.fix_missing_locations(ast.Module(body=[ast.ImportFrom(module='__future__', names=[ast.alias(name='annotations')], level=0), *enums],type_ignores=[])),base+'scripts/enums.py','exec'),enum_mod.__dict__)
cls=next(n for n in sources['internal_controlnet/args.py'].body if isinstance(n,ast.ClassDef) and n.name=='ControlNetUnit')
sys.modules['internal_controlnet']=types.ModuleType('internal_controlnet')
mod=types.ModuleType('internal_controlnet.args'); sys.modules[mod.__name__]=mod
mod.__dict__.update(globals()); mod.__dict__['__name__']='internal_controlnet.args'; mod.__dict__['_unimplemented_func']=lambda *a,**kw:None
for name in names: mod.__dict__[name]=getattr(enum_mod,name)
future=ast.ImportFrom(module='__future__',names=[ast.alias(name='annotations')],level=0)
exec(compile(ast.fix_missing_locations(ast.Module(body=[future,cls],type_ignores=[])),base+'internal_controlnet/args.py','exec'),mod.__dict__)
unit=mod.ControlNetUnit.construct(enabled=True,module='canny',model='fixture-model',weight=.75,image={'image':np.arange(36,dtype=np.uint8).reshape(3,4,3),'mask':np.arange(12,dtype=np.uint8).reshape(3,4)},resize_mode=enum_mod.ResizeMode.INNER_FIT,control_mode=enum_mod.ControlMode.BALANCED)
out=Path('python/tests/fixtures/controlnet_fastload')
for protocol in (2,3,4,5):
 data=pickle.dumps([unit],protocol=protocol); (out/f'modern-unit-protocol-{protocol}.pickle').write_bytes(data); print(protocol,len(data),hashlib.sha256(data).hexdigest())
(out/'modern-provenance.json').write_text(json.dumps({'source_revision':rev,'sources':checks,'pydantic':'1.10.21','numpy':np.__version__,'python':sys.version,'synthetic':True},indent=2)+'\n',encoding='utf-8')
