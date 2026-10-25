import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("phone_installer", ROOT / "install_hermes.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
TOOLS = [tool["name"] for tool in json.loads((ROOT / "hermes_plugin" / "tools.json").read_text())]


class InstallerTests(unittest.TestCase):
    def run_install(self, home, answer, enabled=True, platform=True):
        modules = {
            "hermes_constants": types.SimpleNamespace(get_hermes_home=lambda: home),
            "hermes_cli.plugins_cmd": types.SimpleNamespace(_get_enabled_set=lambda: {"remotetool_phone"} if enabled else set(), cmd_enable=lambda name: self.enabled.append(name)),
            "hermes_cli.plugins_activation": types.SimpleNamespace(
                reload_running_gateway=lambda: answer,
                find_activation=lambda rows, name: next((row for row in rows or [] if row.get("name") == name), None)),
            "hermes_cli.plugins": types.SimpleNamespace(discover_plugins=lambda **kwargs: None),
            "hermes_cli.config": types.SimpleNamespace(load_config_readonly=lambda: {}),
            "hermes_cli.tools_config": types.SimpleNamespace(_get_platform_tools=lambda *args: ["remotetool_phone"] if platform else []),
        }
        self.enabled = []
        with patch.dict(sys.modules, modules), contextlib.redirect_stdout(io.StringIO()):
            return installer.main()

    def answer(self, tools=TOOLS):
        return {"reloaded": True, "activations": [{"name": "remotetool_phone", "deferred": {"tools": tools}}]}

    def test_ready_requires_live_registration_of_entire_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_install(Path(directory), self.answer(), enabled=False)
            self.assertEqual({"ready": True, "tool_count": len(TOOLS)}, result)
            self.assertEqual(["remotetool_phone"], self.enabled)

    def test_existing_marker_does_not_hide_missing_gateway(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            plugin = home / "plugins" / "remotetool_phone"
            plugin.mkdir(parents=True)
            (plugin / ".remotetool-ready").write_text("1")
            with self.assertRaisesRegex(RuntimeError, "did not confirm"):
                self.run_install(home, None)

    def test_incomplete_runtime_registration_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "did not confirm"):
                self.run_install(Path(directory), self.answer(TOOLS[:1]))

    def test_profile_toolset_must_be_enabled(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "disabled in this Profile"):
                self.run_install(Path(directory), self.answer(), platform=False)

    def test_failed_child_writes_structured_result_without_ready_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.json"
            with patch.object(sys, "argv", ["install_hermes.py", "--result", str(path)]), \
                 patch.object(installer, "main", side_effect=RuntimeError("registration failed")), \
                 patch.dict(os.environ), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, installer.run())
            result = json.loads(path.read_text())
            self.assertFalse(result["ready"])
            self.assertEqual("RuntimeError", result["error"])
            self.assertFalse(path.with_suffix(".tmp").exists())


if __name__ == "__main__":
    unittest.main()
