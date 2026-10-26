"""Phone MCP client — 支持两种接入方式，一次 initialize 全程复用。

  直连:  http://<phone-ip>:18791/mcp                    ← 手机 App 自带的 MCP server，只认 token
  转发:  http://<relay>:8888/api/phone/v1/bound/<id>/mcp  ← RemoteTool Web 转发，绑定到一条绑定记录

配置解析顺序: 参数 > 环境变量 > $HERMES_HOME/state/phone-mcp.json > plugin bindings 文件

CLI:
  python phone_mcp.py caps | tools | probe | sessions
  python phone_mcp.py call <tool> ['{"json":..}']
  python phone_mcp.py end            # end_ui + disconnect + DELETE session

库用法:
  from phone_mcp import Phone
  p = Phone()                        # 自动找配置
  p.call("phone_observe", {"limit": 400})
"""
import hashlib
import argparse
import json
import os
import sys
import time
import uuid
from pathlib import Path
import urllib.error
import urllib.request
import urllib.parse
import http.client

PROTO = "2025-11-25"


class PhoneError(RuntimeError):
    def __init__(self, code, message="", status=None, executed=None, hint=None, receipt=None):
        super().__init__(f"{code}: {message}" if message else str(code))
        self.code, self.status, self.executed, self.hint = code, status, executed, hint
        self.receipt = receipt or {}
        self.operation_id = self.receipt.get("operation_id")


def hermes_home() -> Path:
    env = os.environ.get("HERMES_HOME")
    if env:
        return Path(env)
    return Path.home() / "AppData" / "Local" / "hermes" if os.name == "nt" else Path.home() / ".hermes"


def _relay_url(base, binding_id):
    base = base.rstrip("/")
    if base.endswith("/mcp"):
        return base
    if "/bound/" in base:
        return base + "/mcp"
    return f"{base}/api/phone/v1/bound/{binding_id}/mcp"


def load_config(url=None, token=None, binding=None, relay=None):
    """解析新版 RemoteTool HTTP MCP 配置。"""
    token = token or os.environ.get("PHONE_MCP_TOKEN")
    url = url or os.environ.get("PHONE_MCP_URL")

    if relay and not url:
        url = _relay_url(relay, binding or "")

    if not url:
        cfg_path = hermes_home() / "state" / "phone-mcp.json"
        if cfg_path.exists():
            cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
            url = cfg.get("url")
            token = token or cfg.get("token")

    if not url:
        url, token = _from_bindings(binding, token)

    if not url:
        raise PhoneError("no_config", "没有找到手机 MCP 配置",
                         hint="传 --url/--token，或写 $HERMES_HOME/state/phone-mcp.json {\"url\":..,\"token\":..}")
    if not token:
        raise PhoneError("no_token", "缺少 token")
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise PhoneError("invalid_config", "MCP URL 必须为不包含内嵌凭据或片段的 HTTP(S) 地址")
    if parsed.path in ("", "/"): url = url.rstrip("/") + "/mcp"
    if not urllib.parse.urlsplit(url).path.rstrip("/").endswith("/mcp"):
        raise PhoneError("invalid_config", "请提供以 /mcp 结尾的 HTTP MCP 地址")
    return url, token, "mcp", "config"


def _from_bindings(binding, token):
    """从 Hermes plugin 的 remotetool-phone-bindings.json 取一条绑定（转发模式）。"""
    path = hermes_home() / "remotetool-phone-bindings.json"
    if not path.exists():
        return None, token
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None, token
    items = list(data.items())
    if not items:
        return None, token
    binding = binding or os.environ.get("HERMES_SESSION_ID")
    if not binding:
        return None, token
    picked = [(k, v) for k, v in items if binding == k]
    if len(picked) != 1:
        raise PhoneError("binding_not_found", "未找到当前会话的精确绑定；请显式提供 MCP URL 和令牌")
    key, row = picked[0]
    url = row.get("url", "")
    if url and not url.rstrip("/").endswith("/mcp"):
        url = url.rstrip("/") + "/mcp"
    return url, (token or row.get("token"))


def _cache_path():
    d = hermes_home() / "state" / "phone-mcp"
    d.mkdir(parents=True, exist_ok=True)
    if os.name != "nt": d.chmod(0o700)
    return d / "sessions.json"


def _write_cache(data):
    path = _cache_path()
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with os.fdopen(os.open(temp, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w", encoding="utf-8") as stream:
            json.dump(data, stream)
        temp.replace(path)
    finally:
        if temp.exists(): temp.unlink()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "Redirect refused", headers, fp)


def _with_lock(fn, timeout=20):
    """跨进程串行化 initialize，避免并发创建重复身份。"""
    lock = _cache_path().with_suffix(".lock")
    fd = os.open(str(lock), os.O_CREAT | os.O_RDWR, 0o600)
    with os.fdopen(fd, "r+b") as stream:
        if stream.seek(0, 2) == 0: stream.write(b"0"); stream.flush()
        deadline = time.monotonic() + timeout
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
                if time.monotonic() > deadline: raise PhoneError("connection_busy", "其他进程仍在初始化；不抢占锁")
                time.sleep(.05)
        try: return fn()
        finally:
            stream.seek(0)
            if os.name == "nt": msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


class Phone:
    def __init__(self, url=None, token=None, binding=None, relay=None, timeout=60, quiet=False,
                 explanation=None, agent=None):
        self.url, self.token, self.mode, _ = load_config(url, token, binding, relay)
        self.timeout = timeout
        self.quiet = quiet
        self.explanation = explanation
        self.transport = {}
        self._opener = urllib.request.build_opener(NoRedirect())
        self.agent = agent or os.environ.get("PHONE_MCP_AGENT") or os.environ.get("HERMES_SESSION_ID")
        if not self.agent:
            raise PhoneError("agent_identity_required", "请设置 PHONE_MCP_AGENT 或 --agent 为当前聊天的稳定标识，不能与其他 Agent 共用")
        self.key = hashlib.sha256((self.url + "\n" + self.token + "\n" + self.agent).encode()).hexdigest()[:24]
        self.sid = self._cached_sid()

    # ---------- session ----------
    def _read_cache(self):
        try:
            return json.loads(_cache_path().read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {}
        except Exception as error:
            raise PhoneError("cache_corrupt", "会话缓存无法解析；不会静默新建身份，请先核对原连接", hint=type(error).__name__) from error

    def _cached_sid(self):
        row = self._read_cache().get(self.key)
        return row.get("sid") if row else None

    def _save_sid(self, sid, extra=None, locked=False):
        def go():
            data = self._read_cache()
            data[self.key] = {"sid": sid, "url": self.url, "ts": int(time.time()), **(extra or {})}
            _write_cache(data)
            return sid
        go() if locked else _with_lock(go)
        self.sid = sid

    def _http(self, payload, sid=None):
        started = time.monotonic()
        headers = {"Content-Type": "application/json; charset=utf-8",
                   "Accept": "application/json, text/event-stream",
                   "Authorization": "Bearer " + self.token}
        if sid:
            headers["Mcp-Session-Id"] = sid
            headers["MCP-Protocol-Version"] = self._read_cache().get(self.key, {}).get("protocol") or PROTO
        req = urllib.request.Request(self.url, data=json.dumps(payload).encode(), headers=headers)
        last = None
        # Transport failures are not retried. Only explicit session_not_found is recovered by call().
        for attempt in range(1):
            try:
                with self._opener.open(req, timeout=self.timeout) as resp:
                    data = resp.read(12 * 1024 * 1024 + 1)
                    if len(data) > 12 * 1024 * 1024:
                        raise PhoneError("response_too_large", "MCP 响应超过 12 MiB")
                    self.transport = {"http_ms": round((time.monotonic() - started) * 1000, 3),
                                      "response_bytes": len(data),
                                      "serialize_ms": resp.headers.get("X-RemoteTool-Serialize-Ms")}
                    return resp.status, dict(resp.headers), data.decode("utf-8", "replace")
            except urllib.error.HTTPError as exc:
                body = exc.read().decode("utf-8", "replace")
                exc.close()
                raise PhoneError(_err_code(body, exc.code), body[:300], status=exc.code) from None
            except (urllib.error.URLError, OSError, http.client.HTTPException) as exc:
                last = exc
        raise PhoneError("unreachable", f"{type(last).__name__}: {last}",
                         hint="核对 MCP 地址可达性和手机服务状态；跨网可使用转发通道")

    def initialize(self, force=False):
        if self.sid and not force:
            return self.sid
        old_sid = self.sid
        def connect_locked():
            cached = self._read_cache().get(self.key, {})
            if cached.get("sid") and (not force or cached["sid"] != old_sid):
                self.sid = cached["sid"]
                return self.sid
            if force and not cached.get("resume_token"):
                raise PhoneError("session_recovery_failed", "缺少原连接恢复凭据；不会自动创建另一身份")
            params = {"protocolVersion": PROTO, "capabilities": {}, "clientInfo": {"name": "hermes-agent", "version": "1.2"}}
            if cached.get("resume_token"):
                params["_meta"] = {"remotetool/resume_token": cached["resume_token"]}
            _, headers, body = self._http({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": params})
            msg = _parse(body)
            if "error" in msg:
                error = msg["error"]
                raise PhoneError(str((error.get("data") or {}).get("code") or error.get("code")), error.get("message", ""))
            sid = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
            if not sid: raise PhoneError("no_session_id", "未取得 MCP 会话")
            result = msg.get("result") or {}
            resume = result.get("_meta", {}).get("remotetool", {}).get("resume_token")
            if not resume:
                raise PhoneError("service_upgrade_required", "服务未返回新版身份恢复凭据；请更新 RemoteTool")
            self._save_sid(sid, {"protocol": result.get("protocolVersion"), "resume_token": resume}, locked=True)
            self._http({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
            return sid
        return _with_lock(connect_locked)

    # ---------- tools ----------
    def call(self, tool, args=None, retry=True, request_id=None):
        args = dict(args or {})
        request_id = request_id or "hermes-" + uuid.uuid4().hex
        # 每个 phone_* 都支持 explanation：让手机端和用户看到这次操作的目的
        if self.explanation and tool.startswith("phone_") and "explanation" not in args:
            args["explanation"] = self.explanation[:240]
        sid = self.initialize()
        payload = {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                   "params": {"name": tool, "arguments": args,
                              "_meta": {"remotetool/request_id": request_id}}}
        try:
            status, _, body = self._http(payload, sid)
        except PhoneError as exc:
            if exc.status == 404 and exc.code == "session_not_found" and retry:
                self.initialize(force=True)
                return self.call(tool, args, retry=False, request_id=request_id)
            if exc.code == "unreachable":
                raise PhoneError("delivery_unknown", "请求可能已经执行；请查回执，不自动重发", receipt={"operation_id": request_id}) from exc
            raise
        if status == 202:
            raise PhoneError("delivery_unknown", "工具调用没有结果回执；先核对状态",
                             receipt={"operation_id": request_id})
        try:
            msg = _parse(body)
        except PhoneError as error:
            raise PhoneError("delivery_unknown", "工具调用返回无效回执；不自动重发",
                             receipt={"operation_id": request_id}) from error
        if "error" in msg:
            err = msg["error"]
            raise PhoneError(str((err.get("data") or {}).get("code") or err.get("code")),
                             err.get("message", ""), receipt={"operation_id": request_id, **(err.get("data") or {})})
        res = msg.get("result", {})
        if res.get("structuredContent") is not None:
            sc = res["structuredContent"]
            # 服务端把部分失败也放在 structuredContent 里（如 ui_ownership_required），统一转成异常
            if sc.get("connection_closed_by_user"):
                raise PhoneError("connection_closed_by_user", sc.get("connection_message", "用户已断开"), receipt=sc)
            if res.get("isError"):
                raise PhoneError(str(sc.get("code", "operation_failed")), str(sc.get("message", "执行未成功；请核对回执和当前状态")),
                                 hint=str(sc.get("hint") or ""), receipt=sc)
            return sc
        text = "\n".join(c.get("text", "") for c in res.get("content", []) if c.get("type") == "text")
        if res.get("isError"):
            raise PhoneError("operation_failed", text, receipt={"operation_id": request_id})
        return text

    def dispose(self, end_ui=True, disconnect=True):
        """任务收尾：释放 UI 所有权 → 断开身份 → 删掉服务端 session。"""
        out = {}
        if not self.sid:
            return {"already_disconnected": True}
        blocked = False
        try:
            if end_ui:
                out["end_ui"] = self.call("phone_end_ui", {})
        except PhoneError as exc:
            out["end_ui"] = exc.code
            blocked = exc.code in ("connection_closed_by_user", "session_recovery_failed")
        if blocked: return {**out, "requires_user_authorization": True}
        try:
            if disconnect:
                out["disconnect"] = self.call("phone_disconnect", {})
        except PhoneError as exc:
            out["disconnect"] = exc.code
            blocked = exc.code in ("connection_closed_by_user", "session_recovery_failed")
        if blocked: return {**out, "requires_user_authorization": True}
        if disconnect and isinstance(out.get("disconnect"), str):
            return {**out, "cleanup_pending": True}
        if self.sid:
            try:
                req = urllib.request.Request(self.url, method="DELETE", headers={
                    "Authorization": "Bearer " + self.token, "Mcp-Session-Id": self.sid,
                    "MCP-Protocol-Version": PROTO})
                with self._opener.open(req, timeout=15) as resp:
                    out["delete"] = resp.status
            except Exception as exc:
                out["delete"] = str(exc)[:80]
                return {**out, "cleanup_pending": True}
        def remove_cache():
            data = self._read_cache()
            if data.get(self.key, {}).get("sid") == self.sid:
                data.pop(self.key, None); _write_cache(data)
        _with_lock(remove_cache)
        self.sid = None
        return out


def _err_code(body, status):
    try:
        obj = json.loads(body)
        error = obj.get("error") or {}
        return str(obj.get("code") or (error.get("data") or {}).get("code") or error.get("code") or status)
    except Exception:
        return status


def _parse(body):
    body = (body or "").strip()
    try:
        result = json.loads(body)
    except (ValueError, TypeError) as error:
        raise PhoneError("invalid_response", "MCP 响应不是有效 JSON；不自动重发操作") from error
    if not isinstance(result, dict):
        raise PhoneError("invalid_response", "MCP 响应必须是 JSON 对象")
    return result


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cmd", choices=("caps", "tools", "probe", "sessions", "call", "end"))
    parser.add_argument("tool", nargs="?")
    parser.add_argument("arguments", nargs="?", default="{}")
    for name in ("url", "token", "binding", "relay", "agent", "request-id", "args-file"):
        parser.add_argument("--" + name)
    options = parser.parse_args(argv[1:])
    cmd = options.cmd
    kw = {k: getattr(options, k) for k in ("url", "token", "binding", "relay", "agent")}
    if cmd == "probe":
        for label, conf in (("configured MCP", kw),):
            try:
                p = Phone(**conf, timeout=12)
                cap = p.call("phone_get_capabilities")
                print(label, "OK", cap.get("name"), p.url, "mode=" + p.mode)
            except PhoneError as exc:
                print(label, "FAIL", exc.code, exc)
        return 0
    p = Phone(**kw)
    if cmd == "caps":
        print(json.dumps(p.call("phone_get_capabilities"), ensure_ascii=False, indent=1))
    elif cmd == "sessions":
        row = p._read_cache().get(p.key, {})
        print(json.dumps({p.key: {k: v for k, v in row.items() if k != "resume_token"}}, ensure_ascii=False, indent=1))
    elif cmd == "end":
        print(json.dumps(p.dispose(), ensure_ascii=False))
    elif cmd == "call":
        if not options.tool: parser.error("call 需要工具名")
        args = json.loads(Path(options.args_file).read_text(encoding="utf-8") if options.args_file else options.arguments)
        out = p.call(options.tool, args, request_id=options.request_id)
        print(json.dumps(out, ensure_ascii=False, indent=1) if not isinstance(out, str) else out)
    elif cmd == "tools":
        sid = p.initialize()
        _, _, body = p._http({"jsonrpc": "2.0", "id": "list", "method": "tools/list"}, sid)
        print(json.dumps(_parse(body), ensure_ascii=False))
    else:
        print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
