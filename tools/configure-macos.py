#!/usr/bin/env python3
"""Create a private, user-owned configuration without writing credentials to command arguments."""
import getpass
import json
import os
from pathlib import Path
import secrets
import shutil

root = Path(__file__).resolve().parent.parent
target = root / "WebApplication" / "appsettings.json"
if target.exists():
    raise SystemExit("appsettings.json 已存在，未覆盖。请手动修改 Codex / RemoteControl 配置。")
username = input("登录用户名 [admin]: ").strip() or "admin"
password = getpass.getpass("登录密码: ")
if not password:
    raise SystemExit("密码不能为空")
codex = shutil.which("codex")
if not codex:
    codex = input("Codex 可执行程序完整路径（可暂时留空）: ").strip()
helper = root / "tools" / "RemoteTool.MacAgent" / "build" / "RemoteToolMacAgent.app" / "Contents" / "MacOS" / "RemoteToolMacAgent"
config = {
    "Logging": {"LogLevel": {"Default": "Information", "Microsoft.AspNetCore": "Warning"}},
    "AllowedHosts": "*",
    "Auth": {"Username": username, "Password": password, "JwtSecret": secrets.token_urlsafe(48)},
    "Codex": {"Executable": codex or "", "Home": str(Path.home() / ".codex"), "WorkingDirectory": str(Path.home() / "Documents" / "Codex" / "Mobile")},
    "RemoteControl": {"MacAgentExecutable": str(helper) if helper.exists() else ""},
    "Hermes": {"BaseUrl": "http://127.0.0.1:8642/", "HomeDirectory": str(Path.home() / ".hermes")}
}
with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
    json.dump(config, output, ensure_ascii=False, indent=2)
print("配置已创建。运行 dotnet run --project WebApplication --urls http://127.0.0.1:5104")
