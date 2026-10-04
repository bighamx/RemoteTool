"""Bounded JSON adapter to installed Hermes' official model-settings handlers.
Credentials are input-only; no arbitrary command or configuration-file API is exposed.
"""
import asyncio
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import sys
from datetime import datetime, timezone

def main():
    request = json.load(sys.stdin)
    action = request.get("action")
    if action not in {"providers", "save_provider", "default_model", "model_info"}:
        raise ValueError("Unsupported settings operation")
    with contextlib.redirect_stdout(io.StringIO()):
        from hermes_cli.web_routers.config_env import list_custom_endpoints, upsert_custom_endpoint
        from hermes_cli.web_routers.models import get_model_info, set_model_assignment
        from hermes_cli.web_models import CustomEndpointUpdate, ModelAssignment
        if action in {"save_provider", "default_model"}:
            from hermes_constants import get_hermes_home
            home = get_hermes_home()
            backup = home / "backups" / "chuckie-helper" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
            backup.mkdir(parents=True, exist_ok=False)
            for name in ("config.yaml", ".env"):
                source = home / name
                if source.exists(): shutil.copy2(source, backup / name)
        body = request.get("body") or {}
        if action == "providers": result = list_custom_endpoints()
        elif action == "model_info": result = get_model_info()
        elif action == "save_provider": result = upsert_custom_endpoint(CustomEndpointUpdate.model_validate(body))
        else:
            body["scope"] = "main"
            result = asyncio.run(set_model_assignment(ModelAssignment.model_validate(body)))
    def scrub(value):
        if isinstance(value, dict):
            return {k: scrub(v) for k, v in value.items() if k.lower() not in {"api_key", "api_key_preview", "password", "secret", "access_token", "token"}}
        if isinstance(value, list): return [scrub(v) for v in value]
        return value
    print(json.dumps(scrub(result), ensure_ascii=False))

if __name__ == "__main__":
    try: main()
    except Exception:
        # Never export provider keys, raw config, command arguments or tracebacks.
        print(json.dumps({"success": False, "message": "Hermes 模型设置操作失败，请检查安装版本与配置"}, ensure_ascii=False))
        sys.exit(1)
