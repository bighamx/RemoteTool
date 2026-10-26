"""Checks for the shipped skill; no phone or Hermes gateway is contacted.

REMOTETOOL_HERMES_SKILL points at android-phone-ui-automation.
"""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

SKILL = os.environ.get("REMOTETOOL_HERMES_SKILL") or str(Path(__file__).resolve().parents[1] / "hermes_skill")
if SKILL:
    scripts = Path(SKILL) / "scripts"
    spec = importlib.util.spec_from_file_location("phone_mcp", scripts / "phone_mcp.py")
    mcp = importlib.util.module_from_spec(spec)
    sys.modules["phone_mcp"] = mcp
    spec.loader.exec_module(mcp)
    spec = importlib.util.spec_from_file_location("hermes_phone_ui", scripts / "phone_ui.py")
    ui_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ui_module)


def state(snapshot="s1", nodes=None):
    return {"snapshot_id": snapshot, "nodes": nodes or [], "screen_width": 1080, "screen_height": 2400}


def node(**extra):
    return {"node_id": "n1", "bounds": [20, 100, 400, 180], "text": "搜索",
            "resource_id": "app:id/search", **extra}


class FakePhone:
    def __init__(self):
        self.calls = []
        self.replies = {}

    def call(self, tool, args=None, **kw):
        self.calls.append((tool, args or {}, kw))
        if tool in self.replies:
            result = self.replies[tool]
            if isinstance(result, Exception):
                raise result
            return result
        if tool == "phone_get_capabilities":
            return {"schema_version": 3, "screen_width": 1080, "screen_height": 2217}
        if tool in ("phone_observe", "phone_wait"):
            return {**state(nodes=[node()]), "matched": True, "text": "搜索"}
        return {"accepted": True, "matched": True, "state": state("s2", [node()]), "operation_id": "op"}


@unittest.skipUnless(SKILL, "Set REMOTETOOL_HERMES_SKILL for installed Hermes helper tests")
class UiHelperTests(unittest.TestCase):
    def setUp(self):
        self.phone = FakePhone()
        self.ui = ui_module.UI(phone=self.phone)

    def test_action_reuses_returned_state_without_sleep_or_observe(self):
        with patch.object(ui_module.time, "sleep", side_effect=AssertionError("fixed sleep")):
            self.ui.tap(selector={"text": "搜索"})
            self.ui.screen()
        self.assertEqual(["phone_get_capabilities", "phone_begin_ui", "phone_tap"],
                         [call[0] for call in self.phone.calls])

    def test_wait_consumes_snapshot_without_extra_observe(self):
        self.ui.wait("搜索")
        self.assertEqual("s1", self.ui.snap)
        self.assertNotIn("phone_observe", [c[0] for c in self.phone.calls])

    def test_new_physical_dimensions_override_capabilities(self):
        self.ui.observe()
        self.assertEqual(2400, self.ui.h())

    def test_old_schema_rejected_repeatedly(self):
        self.phone.replies["phone_get_capabilities"] = {"schema_version": 2}
        for _ in range(2):
            with self.assertRaises(mcp.PhoneError) as error:
                self.ui.caps()
            self.assertEqual("service_upgrade_required", error.exception.code)

    def test_old_nodes_cannot_use_new_snapshot(self):
        self.ui._remember(state(nodes=[node()]))
        old = self.ui.nodes[0]
        self.ui._remember(state("s2", [node()]))
        with self.assertRaises(mcp.PhoneError):
            self.ui.tap(node=old)
        self.assertEqual([], self.phone.calls)

    def test_invalid_and_obscured_nodes_never_click(self):
        for extra in ({"bounds": [1175, 476, 1080, 637]}, {"bounds": [0, 2174, 1080, 2174]},
                      {"occluded": True}, {"bounds": [0, 2500, 50, 2600]}):
            self.ui._remember(state(nodes=[node(**extra)]))
            with self.assertRaises(mcp.PhoneError):
                self.ui.tap(node=self.ui.nodes[0])
        self.assertEqual([], self.phone.calls)

    def test_ambiguous_inputs_never_select_first(self):
        self.ui._remember(state(nodes=[node(editable=True), node(node_id="n2", editable=True)]))
        with self.assertRaises(mcp.PhoneError) as error:
            self.ui.type("正文")
        self.assertEqual("ambiguous_target", error.exception.code)
        self.assertNotIn("phone_input_text", [c[0] for c in self.phone.calls])

    def test_postcondition_failure_stops_run(self):
        self.phone.replies["phone_tap"] = {"matched": False, "state": state("failure"), "operation_id": "first"}
        with self.assertRaises(mcp.PhoneError):
            self.ui.run([{"tap": "搜索"}, {"key": "back"}])
        self.assertEqual("failure", self.ui.snap)
        self.assertNotIn("phone_key", [c[0] for c in self.phone.calls])

    def test_tool_error_preserves_state_and_never_coordinate_retries(self):
        self.phone.replies["phone_tap"] = mcp.PhoneError("stale_snapshot", receipt={"state": state("failure")})
        with self.assertRaises(mcp.PhoneError):
            self.ui.tap("搜索")
        self.assertEqual("failure", self.ui.snap)
        self.assertEqual(1, sum(c[0] == "phone_tap" for c in self.phone.calls))

    def test_text_dump_never_uses_shell(self):
        self.assertEqual(["搜索"], self.ui.dump_text())
        self.assertEqual("text", self.phone.calls[-1][1]["mode"])
        self.assertNotIn("phone_shell", [c[0] for c in self.phone.calls])

    def test_batch_uses_correct_budget_and_keeps_id(self):
        self.ui.execute([{"tool": "phone_key", "arguments": {"key": "back"}}], max_ms=1200, request_id="fixed")
        _, args, kw = self.phone.calls[-1]
        self.assertEqual(1200, args["max_ms"])
        self.assertNotIn("timeout_ms", args)
        self.assertEqual("fixed", kw["request_id"])

    def test_send_requires_explicit_target(self):
        with self.assertRaises(ValueError):
            self.ui.send("正文", "", "com.example.app")
        self.assertEqual([], self.phone.calls)

    def test_send_guards_input_and_submit_and_does_not_claim_delivery(self):
        self.ui._remember(state(nodes=[node(editable=True, text="", resource_id="app:id/input")]))
        self.phone.replies["phone_input_text"] = {"state": state("typed", [
            node(editable=True, text="正文", resource_id="app:id/input"),
            node(node_id="send", text="发送", resource_id="app:id/send_btn")])}
        self.phone.replies["phone_tap"] = {"state": state("submitted", [
            node(editable=True, text="", resource_id="app:id/input"),
            node(node_id="message", text="正文")]), "operation_id": "send-id"}
        result = self.ui.send("正文", "群标题", "com.example.app")
        writes = [c for c in self.phone.calls if c[0] in ("phone_input_text", "phone_tap")]
        self.assertEqual(2, len(writes))
        for _, args, _ in writes:
            self.assertEqual({"package_name": "com.example.app", "selector": {"text": "群标题"}}, args["guard"])
        self.assertTrue(result["verified"])
        self.assertFalse(result["delivery_confirmed"])


@unittest.skipUnless(SKILL, "Set REMOTETOOL_HERMES_SKILL for installed Hermes helper tests")
class McpHelperTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"HERMES_HOME": self.temp.name, "HERMES_SESSION_ID": "",
                                          "PHONE_MCP_AGENT": "", "PHONE_MCP_URL": "", "PHONE_MCP_TOKEN": ""})
        self.env.start()
        self.calls = []
        self.next_error = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                message = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                owner.calls.append(message)
                status = 200
                if message["method"] == "initialize":
                    count = sum(c["method"] == "initialize" for c in owner.calls)
                    result = {"protocolVersion": "2025-11-25", "_meta": {"remotetool": {"resume_token": "secret"}}}
                    self.send_response(200)
                    self.send_header("Mcp-Session-Id", "sid" + str(count))
                elif message["method"] == "notifications/initialized":
                    self.send_response(202)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                elif owner.next_error:
                    status, result = owner.next_error
                    owner.next_error = None
                    self.send_response(status)
                else:
                    result = {"structuredContent": {"ok": True}, "isError": False}
                    self.send_response(status)
                body = json.dumps({"jsonrpc": "2.0", "id": message.get("id"), "result": result} if status == 200 else result).encode()
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/mcp"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.env.stop()
        self.temp.cleanup()

    def client(self, agent="chat"):
        return mcp.Phone(url=self.url, token="test", agent=agent)

    def test_identity_required_and_old_rest_rejected(self):
        with self.assertRaises(mcp.PhoneError):
            self.client(agent=None)
        with self.assertRaises(mcp.PhoneError):
            mcp.Phone(url=self.url.removesuffix("/mcp") + "/api/old", token="test", agent="chat")

    def test_cross_process_style_cache_reuse_and_chat_isolation(self):
        self.client().call("phone_get_capabilities")
        self.client().call("phone_get_capabilities")
        self.client("other-chat").call("phone_get_capabilities")
        self.assertEqual(2, sum(c["method"] == "initialize" for c in self.calls))

    def test_concurrent_clients_initialize_once(self):
        clients = [self.client() for _ in range(4)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results = [pool.submit(p.call, "phone_get_capabilities") for p in clients]
            for result in results:
                self.assertTrue(result.result()["ok"])
        self.assertEqual(1, sum(c["method"] == "initialize" for c in self.calls))

    def test_explicit_expiry_recovers_original_identity_and_operation_id(self):
        client = self.client()
        client.initialize()
        self.next_error = (404, {"code": "session_not_found"})
        client.call("phone_tap", {"x": 1, "y": 2}, request_id="original")
        init = [c for c in self.calls if c["method"] == "initialize"]
        self.assertEqual("secret", init[-1]["params"]["_meta"]["remotetool/resume_token"])
        writes = [c for c in self.calls if c["method"] == "tools/call"]
        self.assertEqual(["original", "original"], [c["params"]["_meta"]["remotetool/request_id"] for c in writes])

    def test_unrelated_404_does_not_recover(self):
        client = self.client()
        client.initialize()
        self.next_error = (404, {"code": "binding_not_found"})
        with self.assertRaises(mcp.PhoneError):
            client.call("phone_tap", {})
        self.assertEqual(1, sum(c["method"] == "initialize" for c in self.calls))

    def test_network_uncertainty_is_not_retried(self):
        client = self.client()
        client.initialize()
        with patch.object(client, "_http", side_effect=mcp.PhoneError("unreachable")) as request:
            with self.assertRaises(mcp.PhoneError) as error:
                client.call("phone_tap", {}, request_id="lost")
        self.assertEqual("delivery_unknown", error.exception.code)
        self.assertEqual("lost", error.exception.operation_id)
        self.assertEqual(1, request.call_count)

    def test_rich_error_is_not_success(self):
        client = self.client()
        client.initialize()
        response = {"result": {"isError": True, "structuredContent": {
            "code": "precondition_failed", "operation_id": "bad", "execution": "not_started",
            "message": "guard failed", "retryable": False, "metrics": {"device_ms": 2}}}}
        with patch.object(client, "_http", return_value=(200, {}, json.dumps(response))):
            with self.assertRaises(mcp.PhoneError) as error:
                client.call("phone_tap", {})
        self.assertEqual("precondition_failed", error.exception.code)
        self.assertEqual("bad", error.exception.operation_id)

    def test_binding_is_exact_and_no_implicit_last(self):
        path = Path(self.temp.name) / "remotetool-phone-bindings.json"
        path.write_text(json.dumps({"chat-1": {"url": self.url, "token": "other"}}), encoding="utf-8")
        self.assertEqual((None, None), mcp._from_bindings(None, None))
        with self.assertRaises(mcp.PhoneError):
            mcp._from_bindings("chat", None)
        self.assertEqual((self.url, "other"), mcp._from_bindings("chat-1", None))

    def test_invalid_json_and_corrupt_cache_are_explicit_errors(self):
        with self.assertRaises(mcp.PhoneError):
            mcp._parse("not json")
        mcp._cache_path().write_text("{bad", encoding="utf-8")
        with self.assertRaises(mcp.PhoneError):
            self.client()
