"""Checks for the shipped skill; no phone or Hermes gateway is contacted.

REMOTETOOL_HERMES_SKILL points at android-phone-ui-automation.
"""
import concurrent.futures
import contextlib
import io
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

    def test_standalone_cli_cleans_up_after_caps_error_without_masking_primary_error(self):
        from unittest.mock import MagicMock
        ui = MagicMock()
        ui.caps.side_effect = mcp.PhoneError("cap_failed")
        ui.p.dispose.side_effect = mcp.PhoneError("cleanup_failed")
        with patch.object(ui_module, "UI", return_value=ui), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(mcp.PhoneError) as error:
                ui_module.main(["caps", "--close-on-exit"])
        self.assertEqual("cap_failed", error.exception.code)
        ui.p.dispose.assert_called_once()

    def test_multi_command_cli_does_not_close_shared_identity_by_default(self):
        from unittest.mock import MagicMock
        ui = MagicMock()
        ui.caps.return_value = {"schema_version": 3}
        with patch.object(ui_module, "UI", return_value=ui), contextlib.redirect_stdout(io.StringIO()):
            ui_module.main(["caps"])
        ui.p.dispose.assert_not_called()

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

    def test_explicit_ui_name_and_readonly_dump_nodes(self):
        self.ui.begin(client_name="Hermes 商品查询")
        self.assertEqual("Hermes 商品查询", self.phone.calls[-1][1]["client_name"])
        self.ui._remember({**state(nodes=[node()]), "node_actions": False})
        with self.assertRaises(mcp.PhoneError) as error:
            self.ui.tap(node=self.ui.nodes[0])
        self.assertEqual("read_only_backend", error.exception.code)

    def test_observation_method_and_projection_are_independent(self):
        self.ui.observe(backend="uiautomator", include_unimportant=True, privilege_backend="root", mode="text")
        args = self.phone.calls[-1][1]
        self.assertEqual("uiautomator", args["backend"])
        self.assertEqual("text", args["mode"])
        self.assertTrue(args["include_unimportant"])

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

    def _prepare_send(self):
        self.ui._remember(state(nodes=[node(editable=True, text="", resource_id="app:id/input")]))
        self.phone.replies["phone_observe"] = state(nodes=[
            node(editable=True, text="", resource_id="app:id/input"),
            node(node_id="title", text="群标题", resource_id="app:id/title")])
        self.phone.replies["phone_input_text"] = {"state": state("typed", [
            node(editable=True, text="正文", resource_id="app:id/input"),
            node(node_id="send", text="发送", resource_id="app:id/send_btn")])}
        self.phone.replies["phone_tap"] = {"state": state("submitted", [
            node(editable=True, text="", resource_id="app:id/input"),
            node(node_id="message", text="正文")]), "operation_id": "send-id"}

    def test_send_guards_input_and_submit_and_does_not_claim_delivery(self):
        self._prepare_send()
        result = self.ui.send("正文", "群标题", "com.example.app")
        writes = [c for c in self.phone.calls if c[0] in ("phone_input_text", "phone_tap")]
        self.assertEqual(2, len(writes))
        for _, args, _ in writes:
            self.assertEqual({"package_name": "com.example.app", "selector": {
                "text": "群标题", "resource_id": "app:id/title"}}, args["guard"])
        self.assertTrue(result["verified"])
        self.assertFalse(result["delivery_confirmed"])

    def test_inaccessible_message_does_not_repeat_observe_or_send(self):
        self._prepare_send()
        self.phone.replies["phone_tap"]["state"]["nodes"] = [node(editable=True, text="")]
        result = self.ui.send("正文", "群标题", "com.example.app")
        self.assertTrue(result["submitted"])
        self.assertFalse(result["verified"])
        self.assertEqual("message_not_accessible", result["verification_reason"])
        self.assertEqual(1, sum(c[0] == "phone_observe" for c in self.phone.calls))
        self.assertEqual(1, sum(c[0] == "phone_tap" for c in self.phone.calls))

    def test_truncated_observation_gets_one_bounded_readback(self):
        self._prepare_send()
        initial = self.phone.replies["phone_observe"]
        final = self.phone.replies["phone_tap"]["state"]
        observations = iter([initial, final])
        original_call = self.phone.call
        def call(tool, args=None, **kw):
            if tool == "phone_observe":
                self.phone.calls.append((tool, args or {}, kw))
                return next(observations)
            return original_call(tool, args, **kw)
        self.phone.call = call
        self.phone.replies["phone_tap"] = {"state": {
            **state("partial", [node(editable=True, text="")]), "truncated": True}, "operation_id": "send-id"}
        result = self.ui.send("正文", "群标题", "com.example.app")
        self.assertTrue(result["verified"])
        reads = [c for c in self.phone.calls if c[0] == "phone_observe"]
        self.assertEqual(600, reads[-1][1]["limit"])
        self.assertEqual(2, len(reads))
        self.assertEqual(1, sum(c[0] == "phone_tap" for c in self.phone.calls))

    def test_short_chat_name_requires_unique_explicit_title_control(self):
        self.phone.replies["phone_observe"] = state(nodes=[
            node(text="后花园 ·IT交流中心 [C1]", resource_id="app:id/title")])
        # Stop after guard construction; no actual input or send is performed.
        with patch.object(self.ui, "type", side_effect=mcp.PhoneError("test_stop")) as typed:
            with self.assertRaises(mcp.PhoneError):
                self.ui.send("正文", "后花园", "com.example.app", "app:id/title")
        self.assertEqual({"text": "后花园 ·IT交流中心 [C1]", "resource_id": "app:id/title"},
                         typed.call_args.kwargs["guard"]["selector"])

    def test_partial_name_alone_cannot_match_message_content(self):
        self.phone.replies["phone_observe"] = state(nodes=[node(text="后花园 ·IT交流中心")])
        with self.assertRaises(mcp.PhoneError):
            self.ui.send("正文", "后花园", "com.example.app")
        self.assertNotIn("phone_input_text", [c[0] for c in self.phone.calls])

    def test_run_error_contains_prior_receipts_and_failed_index(self):
        self.phone.replies["phone_tap"] = mcp.PhoneError("stale_snapshot", receipt={"operation_id": "failed"})
        with self.assertRaises(mcp.PhoneError) as error:
            self.ui.run([{"key": "back"}, {"tap": "搜索"}, {"key": "home"}])
        local = error.exception.receipt["local_run"]
        self.assertEqual(1, local["completed_steps"])
        self.assertEqual(1, local["failed_index"])
        self.assertEqual(1, len(local["steps"]))
        self.assertEqual(1, sum(c[0] == "phone_key" for c in self.phone.calls))

    def test_operation_query_does_not_acquire_ui(self):
        self.ui.get_operation("previous")
        self.assertEqual([("phone_get_operation", {"operation_id": "previous"}, {})], self.phone.calls)


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
            def do_DELETE(self):
                owner.calls.append({"method": "DELETE"})
                self.send_response(204)
                self.end_headers()
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

    def test_agent_config_and_runtime_precedence(self):
        config = Path(self.temp.name) / "state" / "phone-mcp.json"
        config.parent.mkdir(parents=True)
        config.write_text(json.dumps({"url": self.url, "token": "test", "agent": "explicit-config-chat"}), encoding="utf-8")
        self.assertEqual("explicit-config-chat", mcp.Phone().agent)
        with patch.dict(os.environ, {"HERMES_SESSION_ID": "runtime-chat"}):
            self.assertEqual("runtime-chat", mcp.Phone().agent)
            self.assertEqual("argument-chat", mcp.Phone(agent="argument-chat").agent)
        with patch.dict(os.environ, {"PHONE_MCP_AGENT": "environment-chat"}):
            self.assertEqual("environment-chat", mcp.Phone().agent)

    def test_delete_uses_negotiated_version(self):
        client = self.client()
        client.initialize()
        client._save_sid(client.sid, {"protocol": "2025-06-18", "resume_token": "secret"})
        from unittest.mock import MagicMock
        response = MagicMock()
        response.__enter__.return_value.status = 204
        with patch.object(client._opener, "open", return_value=response) as request:
            client.dispose(end_ui=False, disconnect=False)
        self.assertEqual("2025-06-18", request.call_args.args[0].get_header("Mcp-protocol-version"))

    def test_cached_cleanup_uses_only_its_resume_token_without_reinitializing(self):
        client = self.client()
        client.initialize()
        client._save_sid("expired", {"protocol": "2025-06-18", "resume_token": "secret", "self_cleanup": "resume-token"})
        from unittest.mock import MagicMock
        response = MagicMock()
        response.__enter__.return_value.status = 204
        before = len(self.calls)
        with patch.object(client._opener, "open", return_value=response) as request:
            result = client.dispose()
        sent = request.call_args.args[0]
        self.assertEqual("secret", sent.get_header("X-remotetool-resume-token"))
        self.assertIsNone(sent.get_header("Mcp-session-id"))
        self.assertEqual("2025-06-18", sent.get_header("Mcp-protocol-version"))
        self.assertEqual(before, len(self.calls))
        self.assertEqual("cached_logical_owner", result["scope"])
        self.assertNotIn(client.key, client._read_cache())

    def test_cleanup_busy_keeps_resume_credentials(self):
        client = self.client()
        client.initialize()
        client._save_sid(client.sid, {"protocol": "2025-11-25", "resume_token": "secret", "self_cleanup": "resume-token"})
        import urllib.error,io
        error = urllib.error.HTTPError(self.url, 409, "busy", {}, io.BytesIO(b'{"code":"device_busy"}'))
        with patch.object(client._opener, "open", side_effect=error):
            with self.assertRaises(mcp.PhoneError) as result:
                client.cleanup_cached()
        self.assertEqual("device_busy", result.exception.code)
        self.assertEqual("secret", client._read_cache()[client.key]["resume_token"])

    def test_explicit_reset_never_deletes_other_agent_cache_or_creates_identity(self):
        client = self.client()
        client.initialize()
        other = self.client("other-chat")
        other.initialize()
        with self.assertRaises(mcp.PhoneError):
            client.forget_cached()
        self.assertIn(client.key, client._read_cache())
        before = len(self.calls)
        result = client.forget_cached(True)
        self.assertEqual(before, len(self.calls))
        self.assertNotIn(client.key, other._read_cache())
        self.assertIn(other.key, other._read_cache())
        self.assertFalse(result["new_identity_created"])

    def test_cross_process_style_cache_reuse_and_chat_isolation(self):
        self.client().call("phone_get_capabilities")
        self.client().call("phone_get_capabilities")
        self.client("other-chat").call("phone_get_capabilities")
        self.assertEqual(2, sum(c["method"] == "initialize" for c in self.calls))

    def test_client_display_name_is_configurable_separately_from_identity(self):
        client = mcp.Phone(url=self.url, token="test", agent="chat", client_name="Codex phone diagnostics")
        client.initialize()
        initialize = next(c for c in self.calls if c["method"] == "initialize")
        self.assertEqual("Codex phone diagnostics", initialize["params"]["clientInfo"]["name"])
        self.assertEqual("chat", client.agent)

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
        self.assertFalse(any(c["method"] == "DELETE" for c in self.calls), "resume already fences the old transport; do not silently delete it")

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
