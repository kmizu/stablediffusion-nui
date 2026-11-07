"""Official SDK transport check using a PNG previously produced by a real model."""
import argparse
import asyncio
import base64
import json
import os
import shutil
from pathlib import Path

from check_history_mcp_stdio import connect


async def main(args):
    out = Path(args.output).resolve()
    home = out / "home"
    images = home / "outputs"
    images.mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    (home / "config").mkdir(exist_ok=True)
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": args.python, "output_dir": str(images)}), encoding="utf-8")
    fixture = images / "fixture.png"
    shutil.copyfile(args.fixture, fixture)
    env = dict(os.environ, PYTHONPATH=str(Path(args.source).resolve() / "python"))
    async with connect(args, home, env, out) as client:
        async def inspect(image):
            result = (await client.call_tool("inspect_generation_metadata", {"image": image})).model_dump(mode="json")
            assert not result.get("isError", result.get("is_error", False)), result
            return result.get("structuredContent", result.get("structured_content"))
        by_path = await inspect(str(fixture))
        by_base64 = await inspect(base64.b64encode(fixture.read_bytes()).decode("ascii"))
        assert by_path == by_base64
        candidate = by_path["scripts"]["corridor_crawler"]
        assert candidate["placement"] == "Preserve aspect" and candidate["steps"] == 2
        assert candidate["prefab_image"] == ""
        assert "init_image" not in by_path["generation_args"]
        assert any("initial image" in warning for warning in by_path["warnings"])
    print("CORRIDOR_REPLAY_" + args.transport.upper() + "_OK", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", choices=["stdio", "http"], required=True)
    for name in ("java", "jar", "output", "python", "source", "fixture"):
        parser.add_argument("--" + name, required=True)
    asyncio.run(main(parser.parse_args()))
