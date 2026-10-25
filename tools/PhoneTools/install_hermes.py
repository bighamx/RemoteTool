"""Install into the selected Hermes home using its own plugin activation path."""
import shutil
import argparse
import json
import os
import sys
import traceback
from pathlib import Path


def main():
    from hermes_constants import get_hermes_home
    home = Path(get_hermes_home())
    source = Path(__file__).parent / "hermes_plugin"
    destination = home / "plugins" / "remotetool_phone"
    marker = destination / ".remotetool-ready"
    destination.mkdir(parents=True, exist_ok=True)
    for path in source.iterdir():
        if path.is_file():
            target = destination / path.name
            if not target.exists() or target.read_bytes() != path.read_bytes():
                shutil.copy2(path, target)
    from hermes_cli.plugins_cmd import _get_enabled_set, cmd_enable
    if "remotetool_phone" not in _get_enabled_set():
        cmd_enable("remotetool_phone")
    # This integration targets the gateway API Server, not the optional Desktop/serve backend.
    # Verify the actual gateway on every install check, including when files already match.
    from hermes_cli.plugins_activation import reload_running_gateway, find_activation
    state = reload_running_gateway()
    activation = find_activation((state or {}).get("activations"), "remotetool_phone")
    expected = {tool["name"] for tool in json.loads((source / "tools.json").read_text(encoding="utf-8"))}
    registered = set((activation or {}).get("deferred", {}).get("tools", []))
    if not state or not state.get("reloaded") or not expected.issubset(registered):
        raise RuntimeError("Gateway did not confirm phone tool registration")
    from hermes_cli.plugins import discover_plugins
    from hermes_cli.config import load_config_readonly
    from hermes_cli.tools_config import _get_platform_tools
    discover_plugins(force=True)
    if "remotetool_phone" not in _get_platform_tools(load_config_readonly(), "api_server"):
        raise RuntimeError("Phone toolset is disabled in this Profile")
    marker.write_text("1", encoding="ascii")
    print("REMOTETOOL_PHONE_READY")
    return {"ready": True, "tool_count": len(expected)}


def run():
    parser = argparse.ArgumentParser()
    parser.add_argument("--home")
    parser.add_argument("--source")
    parser.add_argument("--result")
    args = parser.parse_args()
    if args.home:
        os.environ["HERMES_HOME"] = args.home
    if args.source:
        sys.path.insert(0, args.source)
    os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
    result = None
    try:
        result = main()
        return 0
    except Exception as error:
        traceback.print_exc()
        # The service logs this bounded diagnostic; no credentials or full trace go to the App.
        result = {"ready": False, "error": type(error).__name__, "message": str(error)[:400]}
        return 1
    finally:
        if args.result and result is not None:
            target = Path(args.result)
            temporary = target.with_suffix(".tmp")
            temporary.write_text(json.dumps(result), encoding="utf-8")
            temporary.replace(target)


if __name__ == "__main__":
    sys.exit(run())
