"""Official MCP client: explicit --home must govern CreaPrompt without SDFX_HOME."""
import argparse
import asyncio
import json
import os
from pathlib import Path
from mcp import Client, StdioServerParameters


async def check(args):
    home = args.output.resolve() / "explicit-home"
    (home / "config").mkdir(parents=True, exist_ok=True)
    (home / "python" / "sdfx").mkdir(parents=True, exist_ok=True)
    custom = home / "creaprompt" / "csv"
    custom.mkdir(parents=True, exist_ok=True)
    (custom / "9_1OnlyAtHome.csv").write_text("a unique lantern\n", encoding="utf-8")
    (home / "config" / "settings.json").write_text(json.dumps({"python_exe": str(args.python.resolve())}), encoding="utf-8")
    env = dict(os.environ, PYTHONPATH=str(args.source.resolve() / "python"))
    env.pop("SDFX_HOME", None)
    command = ([str(args.launcher.resolve()), "--mcp"] if args.launcher else
               [str(args.java.resolve()), "-Djava.awt.headless=true", "-cp", str(args.jar.resolve()), "sdfx.mcp.McpMain"])
    command += ["--home", str(home), "--dry-run"]
    async with Client(StdioServerParameters(command=command[0], args=command[1:], env=env,
                                            cwd=str(args.source.resolve())), read_timeout_seconds=120) as client:
        result = await client.call_tool("list_scripts", {})
        assert not result.is_error, result
        assert "cat_OnlyAtHome" in result.model_dump_json(), "explicit home category was not discovered"
    print("explicit MCP home discovered CreaPrompt category without SDFX_HOME")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--java", type=Path, required=True)
    p.add_argument("--python", type=Path, required=True)
    p.add_argument("--launcher", type=Path)
    asyncio.run(check(p.parse_args()))


if __name__ == "__main__":
    main()
