"""Official SDK test of the read-only history request draft over stdio or HTTP."""
import argparse
import asyncio
import copy
import json
import os
from pathlib import Path
from check_history_mcp_stdio import connect


async def main(args):
    out = Path(args.output).resolve()
    home = out / "home"
    directory = home / "config" / "generation-history"
    directory.mkdir(parents=True, exist_ok=True)
    (home / "python").mkdir(exist_ok=True)
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": args.python}), encoding="utf-8")
    doc = json.loads(Path(args.library).read_text(encoding="utf-8"))
    history = next(c for c in doc["collections"] if c["id"] == "history-txt2img")
    record = history["presets"][0]
    record["fields"]["generation.seed"] = {"type": "integer", "value": "9007199254740993"}
    record["fields"]["generation.width"] = {"type": "integer", "value": "513"}
    record["fields"]["plugin.untranslated"] = {"type": "text", "value": "not mapped"}
    history["tracked"] = sorted(set(history["tracked"]) | set(record["fields"]))
    inpaint = copy.deepcopy(history)
    inpaint["id"] = "history-inpaint"
    inpaint["presets"][0]["id"] = "9654e524-90cb-4cc0-b000-000000000001"
    inpaint["presets"][0]["source"] = None
    doc["collections"].append(inpaint)
    target = directory / "library-v1.json"
    target.write_text(json.dumps(doc), encoding="utf-8")
    before = target.read_bytes()
    env = dict(os.environ, PYTHONPATH=str(Path(args.source).resolve() / "python"))
    async with connect(args, home, env, out) as client:
        async def call(tool, params):
            result = (await client.call_tool(tool, params)).model_dump(mode="json")
            assert not result.get("isError", result.get("is_error", False)), result
            return result.get("structuredContent", result.get("structured_content"))
        prior = await call("get_status", {})
        draft = await call("generation_history", {"action": "prepare", "mode": "txt2img", "id": record["id"]})
        assert draft["params"]["prompt"] == "actual upstream saved prompt"
        assert "seed" not in draft["params"] and "width" not in draft["params"]
        assert {"generation.seed", "generation.width"} <= set(draft["invalid_fields"])
        assert "plugin.untranslated" in draft["omitted_fields"]
        assert draft["source_may_have_unmapped_settings"] and not draft["replay_guaranteed"]
        paint = await call("generation_history", {"action": "prepare", "mode": "inpaint", "id": inpaint["presets"][0]["id"]})
        assert paint["tool"] == "img2img" and paint["required_inputs"] == ["init_image", "mask"]
        assert {"init_image", "mask"} <= set(paint["missing_fields"])
        after = await call("get_status", {})
        assert prior["queue_length"] == after["queue_length"] == 0 and after["current_job"] is None
    assert target.read_bytes() == before
    (out / "receipt.json").write_text(json.dumps({"draft": draft, "inpaint": paint}, indent=2), encoding="utf-8")
    print(f"HISTORY_DRAFT_MCP_{args.transport.upper()}_OK", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--transport", choices=["stdio", "http"], required=True)
    for name in ("java", "jar", "library", "output", "python", "source"):
        p.add_argument("--" + name, required=True)
    asyncio.run(main(p.parse_args()))
