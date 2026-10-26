#!/usr/bin/env python3
"""Standard-library MCP stdio adapter and CLI for the independent Android HTTP server.

REMOTETOOL_PHONE_URL=http://phone:18791
REMOTETOOL_PHONE_TOKEN=<App tool token>
No RemoteTool Web installation or Python package installation is required.
"""
import argparse
import base64
import concurrent.futures
import json
import os
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


class PhoneClient:
    def __init__(self, url=None, token=None):
        self.url = (url or os.environ.get("REMOTETOOL_PHONE_URL", "")).rstrip("/")
        self.token = token or os.environ.get("REMOTETOOL_PHONE_TOKEN", "")
        parts = urllib.parse.urlsplit(self.url)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
            raise ValueError("Set REMOTETOOL_PHONE_URL to an HTTP(S) phone address")
        if not self.token:
            raise ValueError("Set REMOTETOOL_PHONE_TOKEN from the phone App")
        self.opener = urllib.request.build_opener(NoRedirect())
        self.session_id = None
        self.protocol_version = "2025-11-25"

    def request(self, path, payload=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.url + path, data=data, headers={
            "Authorization": "Bearer " + self.token, "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": self.protocol_version})
        if self.session_id:
            request.add_header("Mcp-Session-Id", self.session_id)
        params = (payload or {}).get("params", {})
        install = params.get("name") == "phone_manage_app" and params.get("arguments", {}).get("action") == "install"
        # Never retry a write: a lost response may follow a successful action.
        try:
            with self.opener.open(request, timeout=130 if install else 45) as response:
                if response.headers.get("Mcp-Session-Id"):
                    self.session_id = response.headers["Mcp-Session-Id"]
                body = response.read(12 * 1024 * 1024 + 1)
                if len(body) > 12 * 1024 * 1024:
                    raise ValueError("Phone response exceeds size limit")
                result = json.loads(body) if body else None
                if (payload or {}).get("method") == "initialize" and result and "result" in result:
                    self.protocol_version = result["result"]["protocolVersion"]
                return result
        except urllib.error.HTTPError as error:
            error.close()
            raise

    def mcp(self, message):
        return self.request("" if self.url.endswith("/mcp") else "/mcp", message)

    def call(self, name, args, request_id=None):
        request_id = request_id or "cli-" + uuid.uuid4().hex
        result = self.mcp({"jsonrpc": "2.0", "id": uuid.uuid4().hex, "method": "tools/call", "params": {
            "name": name, "arguments": args, "_meta": {"remotetool/request_id": request_id}}})
        if "error" in result:
            raise RuntimeError(result["error"]["message"])
        return {"request_id": request_id, "result": result["result"]}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "Redirect refused", headers, fp)


def serve_stdio(client, source=None, target=None):
    source, target = source or sys.stdin, target or sys.stdout
    lock = threading.Lock()

    def handle(message):
        if "id" not in message:
            # HTTP session identity is retained by this long-lived client; no response is needed for notifications.
            return
        try:
            response = client.mcp(message)
        except Exception as exc:
            response = {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32000,
                "message": f"Phone response unavailable ({type(exc).__name__}); observe before retrying"}}
        if response is not None:
            with lock:
                target.write(json.dumps(response, ensure_ascii=False) + "\n")
                target.flush()

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for line in source:
            try:
                message = json.loads(line)
                if not isinstance(message, dict):
                    raise ValueError("JSON-RPC object required")
                pool.submit(handle, message)
            except (ValueError, TypeError):
                with lock:
                    target.write(json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}) + "\n")
                    target.flush()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    sub.add_parser("mcp")
    sub.add_parser("tools")
    observe = sub.add_parser("observe"); observe.add_argument("--screenshot", action="store_true")
    call = sub.add_parser("call"); call.add_argument("tool"); call.add_argument("--args", default="{}"); call.add_argument("--request-id")
    upload = sub.add_parser("upload"); upload.add_argument("path"); upload.add_argument("--name")
    download = sub.add_parser("download"); download.add_argument("name"); download.add_argument("path")
    args = parser.parse_args(argv)
    client = PhoneClient()
    if args.mode == "mcp":
        serve_stdio(client)
        return 0
    if args.mode == "tools":
        result = client.mcp({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    elif args.mode == "observe":
        result = client.call("phone_observe", {"screenshot": args.screenshot})
    elif args.mode == "call":
        result = client.call(args.tool, json.loads(args.args), args.request_id)
    elif args.mode == "upload":
        path = Path(args.path); name = args.name or path.name; offset = 0
        with path.open("rb") as stream:
            while True:
                chunk = stream.read(512 * 1024)
                if not chunk and offset:
                    break
                result = client.call("phone_transfer_file", {"action": "upload", "name": name, "offset": offset,
                    "data_base64": base64.b64encode(chunk).decode("ascii")})
                if result["result"].get("isError"):
                    break
                offset += len(chunk)
                if not chunk:
                    break
    else:
        path = Path(args.path); temp = path.with_name(path.name + ".phone-download-" + uuid.uuid4().hex)
        offset = 0
        try:
            with temp.open("xb") as output:
                while True:
                    result = client.call("phone_transfer_file", {"action": "download", "name": args.name, "offset": offset, "length": 512 * 1024})
                    if result["result"].get("isError"):
                        raise RuntimeError(json.dumps(result["result"]))
                    data = result["result"]["structuredContent"]
                    chunk = base64.b64decode(data["data_base64"], validate=True)
                    output.write(chunk); offset += len(chunk)
                    if offset >= data["total"]:
                        break
                    if not chunk:
                        raise RuntimeError("Empty chunk before end of file")
            os.replace(temp, path)
        finally:
            temp.unlink(missing_ok=True)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result.get("result", {}).get("isError") else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Phone request failed ({type(error).__name__}): {error}. Writes are not retried.", file=sys.stderr)
        raise SystemExit(1)
