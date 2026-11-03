"""Official SDK regression for failed async and synchronous jobs with partial images."""
import argparse, asyncio, json, os, shutil
from pathlib import Path
from PIL import Image
from check_history_mcp_stdio import connect

async def main(a):
    out=Path(a.output).resolve(); home=out/'home'; outputs=home/'outputs'; folder=outputs/'inputs'; folder.mkdir(parents=True,exist_ok=True); (home/'config').mkdir(exist_ok=True); (home/'python').mkdir(exist_ok=True)
    models=home/'models'/'checkpoints'; models.mkdir(parents=True,exist_ok=True); (models/'fixture.safetensors').write_bytes(b'dry-run fixture')
    (home/'config'/'settings.json').write_text(json.dumps({'python_exe':a.python,'output_dir':str(outputs),'model_dirs':{'checkpoints':[str(models)]},'last_model':'fixture'}),encoding='utf-8')
    Image.new('RGB',(64,64),'blue').save(folder/'a.png')
    with Image.open(a.protected) as image: assert image.info.get('Encrypt'), 'fixture is not protected'
    shutil.copyfile(a.protected,folder/'b.png')
    env=dict(os.environ,PYTHONPATH=str(Path(a.source).resolve()/'python'))
    async with connect(a,home,env,out) as c:
        async def call(name,params):
            r=(await c.call_tool(name,params,read_timeout_seconds=120)).model_dump(mode='json'); return r, r.get('structuredContent',r.get('structured_content')) or {}
        r,sub=await call('img2img_folder_async',{'prompt':'partial SDK','image_folder':str(folder),'width':64,'height':64,'steps':1,'return_images':False}); assert not r.get('is_error'),r
        while True:
            r,failed=await call('get_job',{'job_id':sub['job_id'],'wait_seconds':5,'return_images':False})
            if failed.get('status') not in ('queued','running','waiting_for_engine'): break
        assert r.get('is_error') and failed['status']=='failed' and failed['error'] and len(failed['images'])==1,failed
        path=Path(failed['images'][0]['path']); assert path.is_file() and str(path).startswith(str(outputs))
        r,sync=await call('run_script',{'script_id':'xyz_grid','values':{'x_type':'Size','x_values':'64x64,8192x8192'},'params':{'prompt':'partial sync','width':64,'height':64,'steps':1},'return_images':False})
        assert r.get('is_error') and sync['status']=='failed' and 'job limit exceeded' in sync['error'] and len(sync['images'])==1,(r,sync)
        assert Path(sync['images'][0]['path']).is_file()
        r,after=await call('txt2img',{'prompt':'recovery','width':64,'height':64,'steps':1,'return_images':False}); assert not r.get('is_error') and after['images'],r
    (out/'receipt.json').write_text(json.dumps({'transport':a.transport,'async':failed,'sync':sync,'recovery':after},indent=2),encoding='utf-8')
    print('MCP_PARTIAL_FAILURE_'+a.transport.upper()+'_OK')

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--transport',choices=['stdio','http'],required=True)
    for x in ('java','jar','python','source','output','protected'): p.add_argument('--'+x,required=True)
    asyncio.run(main(p.parse_args()))
