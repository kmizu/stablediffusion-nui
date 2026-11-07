"""Check a user-supplied aesthetic embedding through official MCP and PNG replay.

The fixture is not distributed here. Supply a trusted, licensed file explicitly;
it is copied into this run's allowed output directory and hash-verified.
"""
import argparse
import asyncio
import hashlib
import json
import os
import shutil
from pathlib import Path

from mcp import Client, StdioServerParameters
from PIL import Image


def rgb(path):
    with Image.open(path) as image:
        return image.size, image.convert('RGB').tobytes()


async def run(args, receipt):
    home = (args.output / 'home').resolve()
    (home / 'config').mkdir(parents=True, exist_ok=True)
    (home / 'python').mkdir(parents=True, exist_ok=True)
    images = (args.output / 'images').resolve()
    images.mkdir(parents=True, exist_ok=True)
    fixture_copy = images / args.fixture.name
    shutil.copyfile(args.fixture, fixture_copy)
    assert hashlib.sha256(fixture_copy.read_bytes()).digest() == hashlib.sha256(args.fixture.read_bytes()).digest()
    settings = {'python_exe': str(args.python.resolve()), 'output_dir': str(images),
                'model_dirs': {'checkpoints': [str(args.models.resolve())]}, 'last_model': args.checkpoint,
                'vram_mode': args.vram_mode, 'dtype': 'fp16', 'preview_every': 0}
    (home / 'config' / 'settings.json').write_text(json.dumps(settings), encoding='utf-8')
    if args.launcher:
        command = [str(args.launcher.resolve()), '--mcp', '--home', str(home), '--drain-seconds', '5']
    else:
        command = [str(args.java), '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-cp', str(args.jar.resolve()),
                   'sdfx.mcp.McpMain', '--home', str(home), '--drain-seconds', '5']
    env = dict(os.environ, PYTHONPATH=str((args.source / 'python').resolve()))
    receipt['jar_sha256'] = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    receipt['command'] = command
    receipt['calls'] = []
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=3600) as client:
        async def call(tool, values, error=False):
            response = await client.call_tool(tool, values, read_timeout_seconds=3600)
            data = response.model_dump(mode='json')
            is_error = data.get('isError', data.get('is_error', False))
            body = data.get('structuredContent', data.get('structured_content')) or {}
            receipt['calls'].append({'tool': tool, 'arguments': values, 'error': is_error, 'result': body})
            assert bool(is_error) == error, data
            return body
        await call('set_model', {'name': args.checkpoint})
        params = {'prompt': 'a blue ceramic teapot on a wooden table, soft daylight', 'negative_prompt': 'blurry',
                  'width': args.side, 'height': args.side, 'steps': args.steps, 'seed': 173,
                  'sampler_name': 'Euler', 'return_images': False, 'model_keyword': False}
        first = lambda result: result['images'][0]['path']
        reference = first(await call('txt2img', params))
        made = {"path": str(fixture_copy.resolve())}
        options = {'aesthetic_gradients': {'enabled': True, 'embedding': made['path'], 'weight': 0.3, 'steps': 5}}
        styled = first(await call('txt2img', dict(params, always_on=options)))
        assert rgb(styled) != rgb(reference), 'aesthetic hook did not affect the image'
        metadata = await call('inspect_generation_metadata', {'image': styled})
        recorded = metadata['always_on']['aesthetic_gradients']
        actual_digest = hashlib.sha256(Path(made['path']).read_bytes()).hexdigest()
        assert recorded['embedding_sha256'] == actual_digest, recorded
        assert Path(recorded['embedding']).resolve() == Path(made['path']).resolve(), recorded
        replay = dict(metadata['generation_args'], always_on=metadata['always_on'], return_images=False)
        replay.setdefault('model_keyword', False)
        reproduced = first(await call('txt2img', replay))
        assert rgb(styled) == rgb(reproduced), 'PNG replay differs'
        receipt.update(success=True, reference=reference, embedding=made['path'], embedding_sha256=actual_digest,
                       styled=styled, replay=reproduced)


def main():
    parser = argparse.ArgumentParser()
    for key in ('output', 'source', 'models', 'jar', 'python'):
        parser.add_argument('--' + key, type=Path, required=True)
    launch = parser.add_mutually_exclusive_group(required=True)
    launch.add_argument('--java', type=Path)
    launch.add_argument('--launcher', type=Path)
    parser.add_argument('--checkpoint', required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--side', type=int, default=512)
    parser.add_argument('--steps', type=int, default=12)
    parser.add_argument('--vram-mode', default='model_offload')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    receipt = {'args': {key: str(value) for key, value in vars(args).items()}}
    try:
        asyncio.run(run(args, receipt))
    finally:
        (args.output / 'receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
