"""Phone adapter: use runtime session identity, never a model-supplied session ID."""
import json
import uuid
import urllib.request
from pathlib import Path


def _handler(name):
    def call(args, session_id=None, **kwargs):
        from hermes_constants import get_hermes_home
        try:
            path = Path(get_hermes_home()) / "remotetool-phone-bindings.json"
            binding = json.loads(path.read_text(encoding="utf-8")).get(session_id or "") if path.exists() else None
            if not binding:
                return json.dumps({"error": "Phone tools are not enabled for this session"})
            payload = {"tool": name, "arguments": args, "request_id": "hermes-" + uuid.uuid4().hex}
            request = urllib.request.Request(binding["url"], data=json.dumps(payload).encode(), headers={
                "Content-Type": "application/json; charset=utf-8", "Authorization": "Bearer " + binding["token"], "X-Phone-Session": session_id})
            with urllib.request.urlopen(request, timeout=130 if name == "phone_manage_app" and args.get("action") == "install" else 45) as response:
                result = json.load(response)
            images = [item for item in result.get("content", []) if item.get("type") == "image"]
            if images:
                content = [{"type": "text", "text": json.dumps(result.get("structuredContent", {}), ensure_ascii=False)}]
                content.extend({"type": "image_url", "image_url": {"url": "data:" + item["mimeType"] + ";base64," + item["data"]}} for item in images)
                return {"_multimodal": True, "content": content, "text_summary": json.dumps(result.get("structuredContent", {}), ensure_ascii=False)}
            return json.dumps(result.get("structuredContent", result), ensure_ascii=False)
        except Exception as exc:
            return json.dumps({"error": f"Phone result unavailable ({type(exc).__name__}); observe before retrying"})
    return call


def register(ctx):
    schemas = json.loads((Path(__file__).parent / "tools.json").read_text(encoding="utf-8"))
    for tool in schemas:
        ctx.register_tool(name=tool["name"], toolset="remotetool_phone", handler=_handler(tool["name"]),
                          schema={"name": tool["name"], "description": tool["description"], "parameters": tool["inputSchema"]})
