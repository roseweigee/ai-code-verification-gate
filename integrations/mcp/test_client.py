"""Calls the MCP server the same way an agent would: over stdio, through the MCP client."""
import asyncio
import json
import sys
from pathlib import Path

from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client

HERE = Path(__file__).resolve().parent


async def main(paths):
    params = StdioServerParameters(command=sys.executable, args=[str(HERE / "verify_server.py")])
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            tools = await session.list_tools()
            print("tools:", [t.name for t in tools.tools])
            for p in paths:
                res = await session.call_tool("verify_generated_project", {"project_path": p})
                data = res.structuredContent or {}
                if "result" in data and isinstance(data["result"], dict):
                    data = data["result"]
                if not data and res.content:  # some SDK versions return the dict as JSON text
                    data = json.loads(res.content[0].text)
                print(f"\n{p}\n  verdict={data.get('verdict')} errors={data.get('errors')} warnings={data.get('warnings')}")
                for f in data.get("findings", [])[:4]:
                    print(f"  {f['severity']:5} {f['rule']} {f['title']}")
                print("  note:", data.get("note", "")[:60], "...")


asyncio.run(main(sys.argv[1:]))
