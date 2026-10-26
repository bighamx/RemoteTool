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
import hashlib
import time
import contextlib
from pathlib import Path


class PhoneClient:
    def __init__(self, url=None, token=None, agent=None, cache_directory=None):
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
        self.resume_token = None
        self._gate = threading.RLock()
        self._transport = threading.local()
        self.agent = agent or os.environ.get("REMOTETOOL_PHONE_AGENT")
        self.cache = None
        if self.agent:
            key = hashlib.sha256((self.url + "\n" + hashlib.sha256(self.token.encode()).hexdigest() + "\n" + self.agent).encode()).hexdigest()
            directory = Path(cache_directory or os.environ.get("REMOTETOOL_PHONE_CACHE") or Path(os.environ.get("LOCALAPPDATA", Path.home() / ".cache")) / "RemoteTool" / "phone-clients")
            directory.mkdir(parents=True, exist_ok=True)
            if os.name != "nt": directory.chmod(0o700)
            self.cache = directory / (key + ".json")

    @contextlib.contextmanager
    def _cache_lock(self):
        if self.cache is None:
            yield; return
        lock = self.cache.with_suffix(".lock")
        fd = os.open(lock, os.O_CREAT | os.O_RDWR, 0o600)
        with os.fdopen(fd, "r+b") as stream:
            if stream.seek(0, 2) == 0: stream.write(b"0"); stream.flush()
            started = time.monotonic()
            while True:
                try:
                    stream.seek(0)
                    if os.name == "nt":
                        import msvcrt
                        msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except OSError:
                    if time.monotonic() - started > 45: raise TimeoutError("MCP initialization lock timed out")
                    time.sleep(.05)
            try: yield
            finally:
                stream.seek(0)
                if os.name == "nt": msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)

    def connect(self, replace_session=None):
        with self._gate:
            if self.session_id and replace_session is None: return
            with self._cache_lock():
                saved = {}
                if self.cache and self.cache.exists():
                    saved = json.loads(self.cache.read_text(encoding="utf-8"))
                    self.session_id = saved.get("session_id")
                    self.resume_token = saved.get("resume_token")
                    self.protocol_version = saved.get("protocol_version", self.protocol_version)
                if self.session_id and self.session_id != replace_session: return
                self.session_id = None
                params = {"protocolVersion": self.protocol_version, "capabilities": {}, "clientInfo": {"name": "RemoteTool CLI", "version": "1.2.0"}}
                if self.resume_token: params["_meta"] = {"remotetool/resume_token": self.resume_token}
                result = self.request("" if self.url.endswith("/mcp") else "/mcp", {"jsonrpc": "2.0", "id": "initialize", "method": "initialize", "params": params})
                if "error" in result: raise RuntimeError(result["error"].get("data", {}).get("code", result["error"]["message"]))
                if not self.session_id: raise RuntimeError("Server did not issue an MCP session; use a compatible standard client")
                self.request("" if self.url.endswith("/mcp") else "/mcp", {"jsonrpc": "2.0", "method": "notifications/initialized"})
                if self.cache:
                    temp = self.cache.with_name(self.cache.name + "." + uuid.uuid4().hex + ".tmp")
                    try:
                        with os.fdopen(os.open(temp, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w", encoding="utf-8") as target:
                            json.dump({"session_id": self.session_id, "resume_token": self.resume_token, "protocol_version": self.protocol_version}, target)
                        temp.replace(self.cache)
                    finally:
                        if temp.exists(): temp.unlink()

    def request(self, path, payload=None):
        started = time.monotonic()
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
                self._transport.metrics = {"http_ms": round((time.monotonic() - started) * 1000, 3), "response_bytes": len(body),
                    "serialize_ms": float(response.headers.get("X-RemoteTool-Serialize-Ms", "0"))}
                if (payload or {}).get("method") == "initialize" and result and "result" in result:
                    self.protocol_version = result["result"]["protocolVersion"]
                    self.resume_token = result["result"].get("_meta", {}).get("remotetool", {}).get("resume_token")
                return result
        except urllib.error.HTTPError as error:
            try: error.phone_code = json.loads(error.read()).get("code")
            except Exception: error.phone_code = None
            error.close()
            raise

    def mcp(self, message):
        return self.request("" if self.url.endswith("/mcp") else "/mcp", message)

    def call(self, name, args, request_id=None):
        self.connect()
        request_id = request_id or "cli-" + uuid.uuid4().hex
        message = {"jsonrpc": "2.0", "id": uuid.uuid4().hex, "method": "tools/call", "params": {
            "name": name, "arguments": args, "_meta": {"remotetool/request_id": request_id}}}
        try: result = self.mcp(message)
        except urllib.error.HTTPError as error:
            if error.code != 404 or error.phone_code != "session_not_found": raise
            # An explicit unknown-session rejection happens before execution. Other failures are never retried.
            self.connect(replace_session=self.session_id)
            result = self.mcp(message)
        if "error" in result:
            raise RuntimeError(result["error"]["message"])
        return {"request_id": request_id, "result": result["result"], "transport": getattr(self._transport, "metrics", {})}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "Redirect refused", headers, fp)


def serve_stdio(client, source=None, target=None):
    source, target = source or sys.stdin, target or sys.stdout
    lock = threading.Lock()

    def handle(message):
        try:
            if message.get("method") not in ("initialize", "notifications/initialized"): client.connect()
            response = client.mcp(message)
        except Exception as exc:
            response = {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32000,
                "message": f"Phone response unavailable ({type(exc).__name__}); observe before retrying"}}
        if response is not None and "id" in message:
            with lock:
                target.write(json.dumps(response, ensure_ascii=False) + "\n")
                target.flush()

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for line in source:
            try:
                message = json.loads(line)
                if not isinstance(message, dict):
                    raise ValueError("JSON-RPC object required")
                if message.get("method") in ("initialize", "notifications/initialized"): handle(message)
                else: pool.submit(handle, message)
            except (ValueError, TypeError):
                with lock:
                    target.write(json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}) + "\n")
                    target.flush()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent", help="Stable logical agent/chat identity for isolated cross-process session reuse")
    sub = parser.add_subparsers(dest="mode", required=True)
    sub.add_parser("mcp")
    sub.add_parser("tools")
    observe = sub.add_parser("observe"); observe.add_argument("--screenshot", action="store_true")
    call = sub.add_parser("call"); call.add_argument("tool"); call.add_argument("--args", default="{}"); call.add_argument("--args-file"); call.add_argument("--request-id")
    upload = sub.add_parser("upload"); upload.add_argument("path"); upload.add_argument("--name")
    download = sub.add_parser("download"); download.add_argument("name"); download.add_argument("path")
    args = parser.parse_args(argv)
    client = PhoneClient(agent=args.agent)
    if args.mode != "mcp" and not client.agent:
        parser.error("CLI commands require --agent <stable-task-or-chat-id> or REMOTETOOL_PHONE_AGENT; use a distinct ID for each independent agent")
    if args.mode == "mcp":
        serve_stdio(client)
        return 0
    if args.mode == "tools":
        client.connect()
        result = client.mcp({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    elif args.mode == "observe":
        result = client.call("phone_observe", {"screenshot": args.screenshot})
    elif args.mode == "call":
        result = client.call(args.tool, json.loads(Path(args.args_file).read_text(encoding="utf-8")) if args.args_file else json.loads(args.args), args.request_id)
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
