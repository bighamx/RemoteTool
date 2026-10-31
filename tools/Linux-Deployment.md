# Linux 部署（Ubuntu 虚拟机 / 任意 systemd 主机）

`tools/Deploy-Linux.ps1` 一条命令完成「发布 → 配置 → 传输 → 切换 → systemd → 健康检查」，可重复执行。

```powershell
pwsh -File tools/Deploy-Linux.ps1                       # 默认 ssh 别名 ubuntu-vm、端口 8890
pwsh -File tools/Deploy-Linux.ps1 -SkipPull             # 跳过 git pull
pwsh -File tools/Deploy-Linux.ps1 -Port 9000 -HostName user@10.0.0.5
pwsh -File tools/Deploy-Linux.ps1 -SourceConfig D:\GIT\RemoteTool\WebApplication\bin\Debug\net8.0\appsettings.json
```

参数：`HostName`、`RemoteUser`、`Port`、`RemotePath`、`DataPath`、`ServiceName`、`Address`、`SourceConfig`、
`CodexExecutable`、`CodexHome`、`SkipPull`、`SkipPublish`。

## 主机前置条件

| 依赖 | 说明 |
|---|---|
| ASP.NET Core 8 运行时 | `sudo apt-get install -y aspnetcore-runtime-8.0`。**主程序虽是自包含发布，但 Codex 桥接进程是用 `dotnet WebApplication.dll` 拉起的**，没有 runtime 会 `Win32Exception (2)`。 |
| Codex CLI | 已登录（`~/.codex/auth.json`），版本需支持 `codex app-server --stdio`（0.1xx 均可）。路径由 `-CodexExecutable` 指定。 |
| sudo | 无密码 sudo 用于写 systemd unit（也可改成用户级 systemd）。 |

## 脚本做了什么

1. `dotnet publish -c Release -r linux-x64 --self-contained true` → `.tmp-linux-publish/`（已 gitignore）。
2. 用 Windows 的 `appsettings.json` 做底（保留 `Auth` 账号/JWT），重写 `Codex`/`Hermes` 为 Linux 路径，
   去掉 Windows 专属节（`RemoteControl`/`QbSettings`/`EmbySettings`/`CloudflareSettings`）。
3. `tar` 打包（`--force-local`，否则 Windows GNU tar 会把 `D:\` 当远程主机）。
4. `scp` 到 `/tmp`，远端停服务 → 旧目录改名为 `<RemotePath>.prev` → 解压 → 写 unit → 重启。
5. `REMOTETOOL_DATA_DIR=<DataPath>`：避免默认写 `/usr/share/RemoteTool` 需要 root。
6. 健康检查：`http://<Address>:<Port>/Account/Login` == 200（`Address` 缺省从远端 `hostname -I` 取第一项）。

## 排障

```bash
sudo journalctl -u remotetool -f          # 日志
sudo systemctl status remotetool          # 状态
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8890/Account/Login
```

回滚：`rm -rf <RemotePath> && mv <RemotePath>.prev <RemotePath> && sudo systemctl restart <ServiceName>`。

**端口冲突**：Ubuntu 虚拟机上 80/8888 常被 openresty 占用，脚本默认 8890；换端口只改 `-Port`。

## Linux 上不可用的功能

截屏/鼠标键盘注入、传感器、磁盘 SMART、IIS 相关运维、Hermes 页签（需本机装 Hermes 网关）——
这些是 Windows API 实现，Linux 上会报错或隐藏。文件管理、Codex 会话、手机 MCP 是跨平台可用的。
