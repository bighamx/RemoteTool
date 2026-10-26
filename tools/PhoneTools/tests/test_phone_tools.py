import contextlib
import concurrent.futures
import importlib.util
import io
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


cli = load("phone_cli", ROOT / "remotetool_phone.py")
plugin = load("phone_plugin", ROOT / "hermes_plugin" / "__init__.py")


class PhoneClientTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.next_error = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                owner.calls.append((self.path, dict(self.headers), body))
                if self.headers.get("Authorization") != "Bearer test-token":
                    self.send_error(401); return
                if "id" not in body and self.path != "/bound":
                    self.send_response(202); self.send_header("Content-Length", "0"); self.end_headers(); return
                if body.get("method") == "tools/call" and owner.next_error is not None:
                    status, code = owner.next_error; owner.next_error = None
                    data = json.dumps({"code": code}).encode()
                    self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data); return
                if self.path == "/redirect":
                    self.send_response(302); self.send_header("Location", "/mcp"); self.end_headers(); return
                if self.path == "/bound":
                    response = {"structuredContent": {"ok": True}, "content": [{"type": "image", "mimeType": "image/jpeg", "data": "YWJj"}]}
                else:
                    response = {"jsonrpc": "2.0", "id": body["id"], "result": {"isError": False, "content": [], "structuredContent": {"ok": True}}}
                    if body.get("method") == "initialize":
                        response["result"] = {"protocolVersion": "2025-06-18"}
                        response["result"]["_meta"] = {"remotetool": {"resume_token": "test-resume"}}
                data = json.dumps(response).encode()
                self.send_response(200)
                if body.get("method") == "initialize":
                    self.send_header("Mcp-Session-Id", "client-session")
                self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()

    def test_http_call_carries_explicit_idempotency_key_and_auth(self):
        result = cli.PhoneClient(self.url, "test-token").call("phone_observe", {"explanation": "读取界面以定位设置入口"}, "stable-call")
        calls = [item for item in self.calls if item[2].get("method") == "tools/call"]
        self.assertEqual("stable-call", result["request_id"])
        self.assertEqual("stable-call", calls[0][2]["params"]["_meta"]["remotetool/request_id"])
        self.assertEqual("/mcp", calls[0][0])
        self.assertEqual("读取界面以定位设置入口", calls[0][2]["params"]["arguments"]["explanation"])
        self.assertEqual("application/json; charset=utf-8", calls[0][1]["Content-Type"])

    def test_short_lived_clients_reuse_isolated_protected_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            cli.PhoneClient(self.url, "test-token", agent="chat-a", cache_directory=directory).call("phone_get_capabilities", {})
            cli.PhoneClient(self.url, "test-token", agent="chat-a", cache_directory=directory).call("phone_get_capabilities", {})
            self.assertEqual(1, sum(body.get("method") == "initialize" for _, _, body in self.calls))
            cli.PhoneClient(self.url, "test-token", agent="chat-b", cache_directory=directory).call("phone_get_capabilities", {})
            self.assertEqual(2, sum(body.get("method") == "initialize" for _, _, body in self.calls))
            for path in Path(directory).glob("*.json"):
                self.assertNotIn("test-token", path.read_text())
                self.assertEqual("test-resume", json.loads(path.read_text())["resume_token"])

    def test_concurrent_initializers_share_one_logical_session(self):
        with tempfile.TemporaryDirectory() as directory:
            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
                tasks = [workers.submit(lambda: cli.PhoneClient(self.url, "test-token", agent="chat-a", cache_directory=directory).call("phone_get_capabilities", {})) for _ in range(4)]
                for task in tasks: self.assertTrue(task.result(timeout=10)["result"]["structuredContent"]["ok"])
            self.assertEqual(1, sum(body.get("method") == "initialize" for _, _, body in self.calls))

    def test_only_explicit_missing_session_recovers_and_preserves_operation_id(self):
        client = cli.PhoneClient(self.url, "test-token")
        client.call("phone_get_capabilities", {})
        self.next_error = (404, "session_not_found")
        result = client.call("phone_tap", {"x": 1, "y": 1}, request_id="same-operation")
        self.assertTrue(result["result"]["structuredContent"]["ok"])
        operations = [body for _, _, body in self.calls if body.get("params", {}).get("_meta", {}).get("remotetool/request_id") == "same-operation"]
        self.assertEqual(2, len(operations))
        self.assertEqual(operations[0], operations[1])
        initialized = [body for _, _, body in self.calls if body.get("method") == "initialize"]
        self.assertEqual(2, len(initialized))
        self.assertEqual("test-resume", initialized[-1]["params"]["_meta"]["remotetool/resume_token"])

    def test_unrelated_404_and_network_failure_do_not_retry_or_reinitialize(self):
        client = cli.PhoneClient(self.url, "test-token")
        client.call("phone_get_capabilities", {})
        self.next_error = (404, "not_found")
        with self.assertRaises(Exception): client.call("phone_tap", {"x": 1, "y": 1})
        self.assertEqual(1, sum(body.get("method") == "initialize" for _, _, body in self.calls))
        with patch.object(client, "mcp", side_effect=cli.urllib.error.URLError("lost")) as failed:
            with self.assertRaises(Exception): client.call("phone_tap", {"x": 1, "y": 1})
            self.assertEqual(1, failed.call_count)

    def test_plugin_missing_binding_is_structured_and_does_not_attempt_network(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(sys.modules, {"hermes_constants": types.SimpleNamespace(get_hermes_home=lambda: directory)}):
            result = json.loads(plugin._handler("phone_observe")({}, session_id="unbound"))
            self.assertEqual("phone_binding_missing", result["code"])
            self.assertEqual("not_started", result["execution"])
            self.assertEqual([], self.calls)

    def test_auth_failure_is_not_retried(self):
        with self.assertRaises(Exception):
            cli.PhoneClient(self.url, "wrong").call("phone_observe", {})
        self.assertEqual(1, len(self.calls))

    def test_initialized_client_preserves_session_and_negotiated_version(self):
        client = cli.PhoneClient(self.url, "test-token")
        client.mcp({"jsonrpc": "2.0", "id": 0, "method": "initialize", "params": {"clientInfo": {"name": "Codex"}}})
        # Multiple UI batches, notices and non-UI work must retain the same identity.
        for name in ["phone_get_capabilities", "phone_begin_ui", "phone_observe", "phone_end_ui",
                     "phone_transfer_file", "phone_begin_ui", "phone_observe", "phone_end_ui",
                     "phone_notify_user", "phone_get_user_reply", "phone_disconnect"]:
            client.call(name, {})
        self.assertEqual(1, sum(body.get("method") == "initialize" for _, _, body in self.calls))
        for _, request_headers, _ in self.calls[1:]:
            headers = {key.lower(): value for key, value in request_headers.items()}
            self.assertEqual("client-session", headers["mcp-session-id"])
            self.assertEqual("2025-06-18", headers["mcp-protocol-version"])

    def test_redirect_never_leaks_token_to_another_endpoint(self):
        with self.assertRaises(Exception):
            cli.PhoneClient(self.url, "test-token").request("/redirect", {"id": 1})
        self.assertEqual(1, len(self.calls))

    def test_stdio_preserves_ids_and_does_not_emit_logs(self):
        output = io.StringIO()
        source = io.StringIO(json.dumps({"jsonrpc": "2.0", "id": "client-7", "method": "tools/list"}) + "\n")
        cli.serve_stdio(cli.PhoneClient(self.url, "test-token"), source, output)
        self.assertEqual("client-7", json.loads(output.getvalue())["id"])

    def test_hermes_runtime_session_is_required_and_image_is_multimodal(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "remotetool-phone-bindings.json").write_text(json.dumps({"session-a": {"url": self.url + "/bound", "token": "test-token"}}))
            fake = types.SimpleNamespace(get_hermes_home=lambda: directory)
            with patch.dict(sys.modules, {"hermes_constants": fake}):
                denied = plugin._handler("phone_observe")({"session_id": "session-a"}, session_id="session-b")
                self.assertIn("not enabled", denied)
                self.assertEqual(0, len(self.calls))
                result = plugin._handler("phone_observe")({"explanation": "检查当前页面"}, session_id="session-a")
                self.assertTrue(result["_multimodal"])
                self.assertEqual("data:image/jpeg;base64,YWJj", result["content"][1]["image_url"]["url"])
                self.assertEqual("session-a", self.calls[0][1]["X-Phone-Session"])
                self.assertEqual("检查当前页面", self.calls[0][2]["arguments"]["explanation"])

    def test_catalog_registers_native_hermes_schemas(self):
        registrations = []
        plugin.register(types.SimpleNamespace(register_tool=lambda **kwargs: registrations.append(kwargs)))
        self.assertGreaterEqual(len(registrations), 20)
        self.assertTrue(all(item["schema"]["parameters"]["type"] == "object" for item in registrations))
        self.assertEqual(len(registrations), len({item["name"] for item in registrations}))
        for item in registrations:
            schema = item["schema"]["parameters"]
            self.assertEqual("string", schema["properties"]["explanation"]["type"])
            self.assertNotIn("explanation", schema["required"])


if __name__ == "__main__":
    unittest.main()
