"""RemoteTool schema 3 UI helpers. Actions return settled state; uncertain writes are never replayed."""
import argparse
import json
import re
import sys
import time
from phone_mcp import Phone, PhoneError


class UI:
    def __init__(self, phone=None, **kw):
        self.p = phone or Phone(**kw)
        self.cap = None
        self.nodes = []
        self.snap = None
        self.state = {}
        self._ui = False

    def caps(self):
        if self.cap is None:
            self.cap = self.p.call("phone_get_capabilities")
        if int(self.cap.get("schema_version", 0)) < 3:
            raise PhoneError("service_upgrade_required", "请更新 RemoteTool 手机端和服务端至 schema 3")
        return self.cap

    def _remember(self, state):
        self.state = state
        self.snap = state.get("snapshot_id")
        self.nodes = [{**n, "_snapshot_id": self.snap, "_node_actions": state.get("node_actions", True)} for n in state.get("nodes", [])]
        return state

    def w(self):
        return self.state.get("screen_width") or self.caps()["screen_width"]

    def h(self):
        return self.state.get("screen_height") or self.caps()["screen_height"]

    def begin(self, message=None, client_name=None):
        self.caps()
        args = {"message": message} if message else {}
        label = client_name or getattr(self.p, "client_name", None)
        if label:
            args["client_name"] = label
        result = self.p.call("phone_begin_ui", args)
        self._ui = True
        return result

    def ensure_ui(self):
        if not self._ui:
            self.begin()

    def observe(self, limit=300, **options):
        self.ensure_ui()
        return self._remember(self.p.call("phone_observe", {"limit": limit, "mode": "compact", **options}))

    def _action(self, tool, args, wait_for=None, guard=None):
        self.ensure_ui()
        request = {**args, "wait_for": wait_for or {"stable_ms": 250, "timeout_ms": 6000},
                   "return_state": "compact"}
        if guard:
            request["guard"] = guard
        result = self._call_state(tool, request)
        if "state" in result:
            self._remember(result["state"])
        if result.get("matched") is False:
            raise PhoneError("postcondition_timeout", "动作已提交，但条件未满足；检查当前界面，不重复动作",
                             executed=True, receipt=result)
        return result

    def _call_state(self, tool, args, **options):
        try:
            return self.p.call(tool, args, **options)
        except PhoneError as error:
            if "state" in error.receipt:
                self._remember(error.receipt["state"])
            raise

    def _usable(self, node):
        bounds = node.get("bounds") or []
        return (len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]
                and max(bounds[0], 0) < min(bounds[2], self.w())
                and max(bounds[1], 0) < min(bounds[3], self.h())
                and node.get("visible", True) and not node.get("occluded", False))

    def screen(self, refresh=False, payload_only=True):
        if refresh or not self.snap:
            self.observe()
        lines = []
        for node in self.nodes:
            if not self._usable(node):
                continue
            text = node.get("text") or node.get("description") or ""
            if payload_only and not text:
                continue
            lines.append(f'{node["node_id"]} {node["bounds"]} {text} <{node.get("resource_id", "")}>')
        return "\n".join(lines)

    def find(self, pattern, refresh=False):
        if refresh or not self.snap:
            self.observe()
        regex = re.compile(pattern, re.I)
        return [n for n in self.nodes if self._usable(n) and regex.search(" ".join(
            str(n.get(k) or "") for k in ("text", "description", "resource_id")))]

    @staticmethod
    def _unique(nodes):
        if len(nodes) != 1:
            raise PhoneError("no_match" if not nodes else "ambiguous_target",
                             f"需要唯一目标，实际匹配 {len(nodes)} 个；请缩小 selector 范围")
        return nodes[0]

    def _target(self, node):
        if node.get("_node_actions") is False:
            raise PhoneError("read_only_backend", "UiAutomation 节点只供读取；请用 backend=accessibility 重新定位后再操作")
        if not self._usable(node):
            raise PhoneError("invalid_target", "目标不可见、被遮挡或矩形无效")
        snapshot = node.get("_snapshot_id")
        if not snapshot or snapshot != self.snap:
            raise PhoneError("stale_snapshot", "节点不属于当前快照；先重新观察")
        return {"node_id": node["node_id"], "snapshot_id": snapshot}

    def tap(self, pattern=None, node=None, selector=None, guard=None, wait_for=None):
        if sum(x is not None for x in (pattern, node, selector)) != 1:
            raise ValueError("pattern、node、selector 必须且只能提供一个")
        args = self._target(node) if node is not None else {
            "selector": selector if selector is not None else {"text_regex": pattern}}
        return self._action("phone_tap", args, wait_for, guard)

    def wait(self, pattern=None, timeout=10, **condition):
        self.ensure_ui()
        if timeout <= 0:
            raise ValueError("timeout 必须大于零")
        if pattern is not None:
            condition["text_regex"] = pattern
        deadline = time.monotonic() + timeout
        while True:
            remaining = max(1, int((deadline - time.monotonic()) * 1000))
            result = self.p.call("phone_wait", {**condition, "timeout_ms": min(10000, remaining)})
            self._remember(result)
            if result.get("matched"):
                return result
            if time.monotonic() >= deadline:
                raise PhoneError("wait_timeout", "条件未满足；不重复之前的动作", receipt=result)

    def type(self, text, node=None, selector=None, append=False, guard=None):
        self.ensure_ui()
        if node is not None and selector is not None:
            raise ValueError("node 和 selector 不能同时提供")
        if selector is not None:
            args = {"selector": selector}
        else:
            if node is None:
                if not self.snap:
                    self.observe()
                editable = [n for n in self.nodes if n.get("editable") and self._usable(n)]
                focused = [n for n in editable if n.get("focused")]
                node = self._unique(focused or editable)
            args = self._target(node)
        result = self._action("phone_input_text", {**args, "text": text, "append": append}, guard=guard)
        edited = [n for n in self.nodes if n.get("editable") and self._usable(n)]
        # 只读取唯一输入框，不能用其他输入框中的文本证明本次输入成功。
        if node is not None and node.get("resource_id"):
            edited = [n for n in edited if n.get("resource_id") == node["resource_id"]]
        readback = edited[0].get("text") if len(edited) == 1 else None
        ok = readback is not None and (readback.endswith(text) if append else readback == text)
        if not ok:
            raise PhoneError("input_mismatch", "输入内容未得到唯一输入框回读确认；先检查界面", executed=True, receipt=result)
        return {"input": result, "readback": readback, "ok": True}

    def send(self, text, expected_chat, package_name, chat_resource_id=None):
        """仅用于已授权消息。简称必须同时指定标题控件；唯一定位后锁定完整标题。"""
        if not text or not expected_chat or not package_name:
            raise ValueError("必须提供 text、expected_chat 和 package_name")
        self.observe()
        titles = [n for n in self.nodes if self._usable(n) and not n.get("editable") and (
            (n.get("resource_id") == chat_resource_id and expected_chat in (n.get("text") or ""))
            if chat_resource_id else n.get("text") == expected_chat)]
        title = self._unique(titles)
        title_selector = {"text": title["text"]}
        if title.get("resource_id"):
            title_selector["resource_id"] = title["resource_id"]
        guard = {"package_name": package_name, "selector": title_selector}
        typed = self.type(text, guard=guard)
        if not typed["ok"]:
            raise PhoneError("input_mismatch", "输入内容未得到回读确认；未点击发送", receipt=typed["input"])
        buttons = [n for n in self.nodes if self._usable(n) and (
            n.get("text") in ("发送", "Send") or n.get("description") in ("发送", "Send")
            or (n.get("resource_id") or "").rsplit("/", 1)[-1] in ("send_btn", "send", "send_button"))]
        result = self.tap(node=self._unique(buttons), guard=guard)
        def visible_message():
            return any(not n.get("editable") and text in (
                (n.get("text") or "") + "\n" + (n.get("description") or ""))
                for n in self.nodes if self._usable(n))
        message_visible = visible_message()
        if not message_visible and self.state.get("truncated"):
            try:
                self.observe(limit=600)
                message_visible = visible_message()
            except PhoneError as error:
                return {"submitted": True, "verified": False, "delivery_confirmed": False,
                        "operation_id": result.get("operation_id"), "verification_error": error.code,
                        "next_action": "提交后核验失败；先查回执或截图，不自动重发"}
        editors = [n for n in self.nodes if n.get("editable") and self._usable(n)]
        verified = bool(editors) and all(not n.get("text") for n in editors) and message_visible
        return {"submitted": True, "verified": verified, "delivery_confirmed": False,
                "operation_id": result.get("operation_id"),
                "verification_reason": "message_and_empty_input" if verified else
                    "message_not_accessible" if not message_visible else "input_not_confirmed_empty",
                "next_action": "核对消息状态；不自动重发" if not verified else "界面已回显，送达状态仍以应用为准"}

    def scroll(self, direction="next", times=1):
        if direction not in ("next", "down", "previous", "up"):
            raise ValueError("direction 必须是 next/down/previous/up")
        if not 1 <= int(times) <= 10:
            raise ValueError("times 必须在 1..10 之间")
        if not self.snap:
            self.observe()
        forward = direction in ("next", "down")
        action = 4096 if forward else 8192
        for _ in range(int(times)):
            containers = [n for n in self.nodes if action in n.get("actions", []) and self._usable(n)]
            if containers:
                node = max(containers, key=lambda n: (n["bounds"][2]-n["bounds"][0])*(n["bounds"][3]-n["bounds"][1]))
                self._action("phone_ui_action", {**self._target(node),
                             "action": "scroll_forward" if forward else "scroll_backward"})
            else:
                self._action("phone_swipe", {"x1": self.w()//2, "x2": self.w()//2,
                    "y1": int(self.h()*(.72 if forward else .3)),
                    "y2": int(self.h()*(.3 if forward else .72)), "duration_ms": 420})
        return self.screen()

    def key(self, name="back"):
        return self._action("phone_key", {"key": name})

    def launch(self, package_name):
        return self._action("phone_launch_app", {"package_name": package_name},
                            {"package_name": package_name, "stable_ms": 250, "timeout_ms": 10000})

    def dump_text(self, minlen=1, backend="auto", include_unimportant=True):
        result = self.observe(mode="text", backend=backend, include_unimportant=include_unimportant)
        return [line for line in result.get("text", "").splitlines() if len(line) >= minlen]

    def execute(self, steps, max_ms=15000, request_id=None):
        self.ensure_ui()
        result = self._call_state("phone_execute", {"steps": steps, "max_ms": max_ms,
                             "return_state": "compact"}, request_id=request_id)
        if "state" in result:
            self._remember(result["state"])
        if result.get("code") or result.get("matched") is False:
            raise PhoneError(str(result.get("code", "batch_failed")), "批次已停止；先检查步骤回执", receipt=result)
        return result

    def get_operation(self, operation_id):
        """查询本逻辑身份的原操作回执，不重放动作，也不接管 UI。"""
        return self.p.call("phone_get_operation", {"operation_id": operation_id})

    def run(self, steps):
        """本地编排；任何一步失败立即结束，绝不继续后面的步骤。"""
        log = []
        for index, step in enumerate(steps):
            if not isinstance(step, dict) or len(step) != 1:
                raise ValueError("每一步必须只含一个动作键；动作参数用对象传入")
            verb, args = next(iter(step.items()))
            if verb not in ("tap", "type", "send", "wait", "scroll", "key", "launch", "screen", "find", "dump_text"):
                raise ValueError("不支持的动作: " + verb)
            fn = getattr(self, verb)
            try:
                result = fn(**args) if isinstance(args, dict) else fn() if args is True else fn(args)
            except PhoneError as error:
                error.receipt = {**error.receipt, "local_run": {
                    "completed_steps": len(log), "failed_index": index, "steps": log}}
                raise
            log.append({verb: result})
        return log


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cmd", choices=("caps", "screen", "find", "tap", "wait", "type", "send",
                                       "scroll", "back", "home", "launch", "dump", "run", "execute", "end"))
    parser.add_argument("args", nargs="*")
    for name in ("url", "token", "binding", "relay", "agent", "client-name", "chat", "package", "chat-resource-id"):
        parser.add_argument("--" + name)
    parser.add_argument("--backend", choices=("auto", "accessibility", "uiautomator"), default="auto")
    parser.add_argument("--include-unimportant", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--close-on-exit", action="store_true", help="Standalone task only: close this owner in finally; omit for a multi-command workflow")
    parser.add_argument("--why", default="Hermes 正在操作手机界面以完成用户任务")
    args = parser.parse_args(argv)
    ui = UI(**{k: getattr(args, k) for k in ("url", "token", "binding", "relay", "agent")},
            explanation=args.why, client_name=args.client_name)
    try:
        cmd, rest = args.cmd, args.args
        if cmd in ("run", "execute"):
            with open(rest[0], encoding="utf-8") as stream:
                data = json.load(stream)
            result = ui.run(data) if cmd == "run" else ui.execute(data, int(args.timeout*1000))
        elif cmd == "send":
            result = ui.send(rest[0], args.chat, args.package, args.chat_resource_id)
        elif cmd == "wait":
            result = ui.wait(rest[0], args.timeout)
        elif cmd in ("back", "home"):
            result = ui.key(cmd)
        elif cmd == "end":
            result = ui.p.dispose()
        elif cmd == "dump":
            result = ui.dump_text(backend=args.backend, include_unimportant=args.include_unimportant)
        elif cmd in ("screen", "find"):
            ui.observe(backend=args.backend, include_unimportant=args.include_unimportant)
            result = getattr(ui, cmd)(*rest)
        else:
            result = getattr(ui, cmd)(*rest)
        print(result if isinstance(result, str) else json.dumps(result, ensure_ascii=False, indent=1))
        return 0
    finally:
        if args.close_on_exit and args.cmd != "end":
            primary_error = sys.exc_info()[0] is not None
            try:
                cleanup = ui.p.dispose()
                if cleanup.get("cleanup_pending") or cleanup.get("requires_user_authorization"):
                    raise PhoneError("cleanup_pending", "收尾未确认，保留连接缓存")
            except PhoneError as error:
                print(json.dumps({"cleanup_error": error.code}, ensure_ascii=False), file=sys.stderr)
                if not primary_error:
                    raise


if __name__ == "__main__":
    raise SystemExit(main())
