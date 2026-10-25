"""Interoperability check using the installed reference MCP Python client."""
import asyncio
import os
from mcp import ClientSession
from mcp.client.streamable_http import create_mcp_http_client, streamable_http_client


async def main():
    async with create_mcp_http_client(headers={"Authorization": "Bearer " + os.environ["REMOTETOOL_PHONE_TOKEN"]}) as http:
        async with streamable_http_client(os.environ["REMOTETOOL_PHONE_URL"], http_client=http) as streams:
            async with ClientSession(streams[0], streams[1]) as session:
                await session.initialize()
                tools = await session.list_tools()
                assert any(tool.name == "phone_observe" for tool in tools.tools)
                result = await session.call_tool("phone_observe", {})
                assert not getattr(result, "is_error", getattr(result, "isError", False))
                assert result.content
                print("Reference MCP client completed initialize -> tools/list -> tools/call -> WebSocket phone result.")


if __name__ == "__main__":
    asyncio.run(main())
