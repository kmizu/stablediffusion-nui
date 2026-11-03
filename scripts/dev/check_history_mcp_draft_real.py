"""Real-model SDK smoke test: history prepare, then explicit txt2img."""
import argparse, asyncio, hashlib, json, os
from pathlib import Path
from mcp import Client, StdioServerParameters

async def main(a):
    out=Path(a.output).resolve(); home=out/'home'; directory=home/'config'/'generation-history'
    directory.mkdir(parents=True, exist_ok=True); (home/'python').mkdir(exist_ok=True)
    settings={'python_exe':a.python,'output_dir':str(out/'images'),'model_dirs':{'checkpoints':[a.models]},'last_model':a.checkpoint,'vram_mode':'model_offload','dtype':'fp16'}
    (home/'config'/'settings.json').write_text(json.dumps(settings),encoding='utf-8')
    doc=json.loads(Path(a.library).read_text(encoding='utf-8'))
    history=next(c for c in doc['collections'] if c['id']=='history-txt2img'); record=history['presets'][0]
    record['fields']={'generation.prompt':{'type':'text','value':'a blue ceramic teapot on a wooden table, soft daylight'},'generation.negativePrompt':{'type':'text','value':'blurry, distorted'},'generation.steps':{'type':'integer','value':'8'},'generation.width':{'type':'integer','value':str(a.side)},'generation.height':{'type':'integer','value':str(a.side)},'generation.seed':{'type':'integer','value':'173'},'generation.sampler':{'type':'text','value':'Euler'}}
    history['tracked']=sorted(record['fields']); target=directory/'library-v1.json'; target.write_text(json.dumps(doc),encoding='utf-8'); before=target.read_bytes()
    env=dict(os.environ,PYTHONPATH=str(Path(a.source).resolve()/'python'),HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1')
    cmd=[a.java,'-Xlog:disable','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(Path(a.jar).resolve()),'sdfx.mcp.McpMain','--home',str(home),'--drain-seconds','5']
    async with Client(StdioServerParameters(command=cmd[0],args=cmd[1:],env=env,cwd=str(Path(a.source).resolve())),read_timeout_seconds=900) as client:
        async def call(name, args):
            result=(await client.call_tool(name,args,read_timeout_seconds=900)).model_dump(mode='json'); assert not result.get('isError',result.get('is_error',False)),result
            return result.get('structuredContent',result.get('structured_content'))
        draft=await call('generation_history',{'action':'prepare','mode':'txt2img','id':record['id']})
        assert not draft['invalid_fields'] and not draft['missing_fields'] and draft['params']['seed']==173,draft
        status=await call('get_status',{}); assert status['queue_length']==0 and status['current_job'] is None
        await call('set_model',{'name':a.checkpoint})
        params=dict(draft['params'],return_images=False); result=await call('txt2img',params)
        assert result.get('images'),result
        image=Path(result['images'][0]['path']); assert image.is_file()
    assert target.read_bytes()==before
    (out/'receipt.json').write_text(json.dumps({'draft':draft,'image':str(image),'image_sha256':hashlib.sha256(image.read_bytes()).hexdigest(),'checkpoint':a.checkpoint,'jar_sha256':hashlib.sha256(Path(a.jar).read_bytes()).hexdigest()},indent=2),encoding='utf-8')
    print('REAL_HISTORY_DRAFT_OK',image,flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for x in ('java','jar','python','source','models','checkpoint','library','output'): p.add_argument('--'+x,required=True)
    p.add_argument('--side',type=int,default=512)
    asyncio.run(main(p.parse_args()))
