"""Exercise the real installed Hermes registry in an isolated home, without inference."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("source"); args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="remotetool-phone-hermes-") as directory:
        home = Path(directory)
        os.environ["HERMES_HOME"] = directory
        os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
        source = Path(__file__).resolve().parents[1] / "hermes_plugin"
        destination = home / "plugins" / "remotetool_phone"
        shutil.copytree(source, destination)
        (home / "config.yaml").write_text("plugins:\n  enabled: [remotetool_phone]\n", encoding="utf-8")
        sys.path.insert(0, args.source)
        from hermes_cli.plugins import discover_plugins
        discover_plugins(force=True)
        from tools.registry import registry
        entry = registry.get_entry("phone_observe")
        assert entry is not None, "Phone plugin was not loaded by the real discovery path"
        denied = registry.dispatch("phone_observe", {}, session_id="not-bound")
        assert isinstance(denied, str) and "not enabled" in denied, denied
        from hermes_cli.tools_config import _get_platform_tools
        assert "remotetool_phone" in _get_platform_tools({"plugins": {"enabled": ["remotetool_phone"]}}, "api_server")
        print("Real Hermes discovery/registry accepted phone schemas; unbound session denied; toolset available. No inference started.")


if __name__ == "__main__":
    main()
