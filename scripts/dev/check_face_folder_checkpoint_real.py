"""Official MCP SDK: folder face checkpoint, interrupted source, recovery. Isolated home only."""
import argparse, asyncio, hashlib, json, os, shutil, time
from pathlib import Path
from mcp import Client, StdioServerParameters
from PIL import Image, ImageChops

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''): h.update(chunk)
    return h.hexdigest()

async def run(a, receipt):
    out=a.output.resolve(); home=out/'home'; images=out/'images'; inputs=images/'inputs'
    (home/'config').mkdir(parents=True,exist_ok=True); (home/'python').mkdir(exist_ok=True); inputs.mkdir(parents=True,exist_ok=True)
    for name,src in [('a.png',a.input),('b.png',a.input_two)]: shutil.copyfile(src,inputs/name)
    originals={p.name:sha(p) for p in inputs.iterdir()}; assert len(set(originals.values()))==2
    settings={'python_exe':str(a.python.resolve()),'output_dir':str(images),'model_dirs':{'checkpoints':[str(a.models.resolve())]},'last_model':a.base,'vram_mode':'model_offload','dtype':'fp16','preview_every':0}
    (home/'config'/'settings.json').write_text(json.dumps(settings),encoding='utf-8')
    env=dict(os.environ,PYTHONPATH=str(a.source.resolve()/'python'),HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1')
    cmd=[str(a.java),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(a.jar.resolve()),'sdfx.mcp.McpMain','--home',str(home),'--drain-seconds','5']
    receipt.update(jar_sha256=sha(a.jar),input_sha256=originals,face_sha256=sha(a.models/(a.face+'.safetensors')),calls=[])
    async with Client(StdioServerParameters(command=cmd[0],args=cmd[1:],env=env,cwd=str(a.source.resolve())),read_timeout_seconds=3600) as client:
        async def call(name,args,allow_error=False):
            r=(await client.call_tool(name,args,read_timeout_seconds=3600)).model_dump(mode='json')
            receipt['calls'].append({'tool':name,'args':args,'result':r})
            assert allow_error or not r.get('isError',r.get('is_error',False)),r
            return r.get('structuredContent',r.get('structured_content')) or {}
        async def wait(job):
            for _ in range(1200):
                v=await call('get_job',{'job_id':job,'wait_seconds':1,'return_images':False},allow_error=True)
                if v.get('status') in {'done','failed','cancelled'}: return v
            raise TimeoutError('job did not finish')
        await call('set_model',{'name':a.base})
        params={'image_folder':str(inputs),'prompt':'2girls, standing side by side, upper body, park, anime illustration','negative_prompt':'lowres, blurry, text','width':a.width,'height':a.height,'steps':12,'cfg_scale':5,'seed':2468,'sampler_name':'Euler','denoising_strength':0.05,'return_images':False,'model_keyword':False}
        face={'batch_face_swap':{'enabled':True,'detector':'face_yolov8n.pt','checkpoint':a.face,'resolution':a.face_resolution,'denoising_strength':0.6,'prompt':'[PROMPT], smile'}}
        async def submit(always=None):
            return (await call('img2img_folder_async',dict(params,**({'always_on':always} if always else {}))))['job_id']
        plain=await wait(await submit()); assert plain['status']=='done' and len(plain['images'])==2,plain
        # Modify only the isolated second input after the first has begun generating (manifest is already frozen).
        before={str(p) for p in (images/'img2img').rglob('*.png')}
        job=await submit(face); injected=False
        for _ in range(1200):
            state=await call('get_job',{'job_id':job,'wait_seconds':0,'return_images':False})
            if state.get('status') in {'failed','cancelled','done'}: break
            if state.get('status')=='running' and 'base step' in state.get('message',''):
                if {str(p) for p in (images/'img2img').rglob('*.png')} != before: raise AssertionError('first output completed before injection')
                (inputs/'b.png').write_bytes(b'changed by isolated failure test')
                injected=True; break
            await asyncio.sleep(0.1)
        assert injected, state
        failed=await wait(job)
        new_files=sorted(set(str(p) for p in (images/'img2img').rglob('*.png'))-before)
        assert failed['status']=='failed' and new_files, (failed,new_files)
        shutil.copyfile(a.input_two,inputs/'b.png'); assert {p.name:sha(p) for p in inputs.iterdir()}==originals
        recovered=await wait(await submit(face)); assert recovered['status']=='done' and len(recovered['images'])==2,recovered
        facehash=receipt['face_sha256'][:10]
        for base,changed in zip(plain['images'],recovered['images']):
            with Image.open(base['path']) as x,Image.open(changed['path']) as y:
                text=y.info.get('parameters',''); assert a.face in text and facehash in text and 'Face checkpoint hash' in text,text
                assert ImageChops.difference(x.convert('RGB'),y.convert('RGB')).getbbox(), 'face change absent'
        after=await wait(await submit()); assert after['status']=='done' and len(after['images'])==2,after
        for x,y in zip(plain['images'],after['images']):
            with Image.open(x['path']) as left,Image.open(y['path']) as right:
                assert ImageChops.difference(left.convert('RGB'),right.convert('RGB')).getbbox() is None,'base model did not recover'
        assert {p.name:sha(p) for p in inputs.iterdir()}==originals
        receipt.update(success=True,plain=plain,failed=failed,partial_files=new_files,recovered=recovered,after=after)

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ('output','models','jar','java','python','source','input','input-two'): p.add_argument('--'+name,type=Path,required=True)
    for name in ('base','face'): p.add_argument('--'+name,required=True)
    p.add_argument('--width',type=int,default=512); p.add_argument('--height',type=int,default=512); p.add_argument('--face-resolution',type=int,default=768)
    a=p.parse_args(); a.output.mkdir(parents=True,exist_ok=True); receipt={'success':False,'args':{k:str(v) for k,v in vars(a).items()}}
    try: asyncio.run(run(a,receipt))
    finally: (a.output/'receipt.json').write_text(json.dumps(receipt,indent=2,ensure_ascii=False),encoding='utf-8')
