# Linux 部署（Ubuntu 虚拟机 / 任意 systemd 主机）

`WebApplication/Tools/Deploy-Linux.ps1` 一条命令完成「发布 → 配置 → 传输 → 切换 → systemd → 健康检查」，可重复执行。

```powershell
pwsh -File WebApplication/Tools/Deploy-Linux.ps1                       # 默认 ssh 别名 ubuntu-vm、端口 8890
pwsh -File WebApplication/Tools/Deploy-Linux.ps1 -SkipPull             # 跳过 git pull
pwsh -File WebApplication/Tools/Deploy-Linux.ps1 -Port 9000 -HostName user@10.0.0.5
pwsh -File WebApplication/Tools/Deploy-Linux.ps1 -SourceConfig D:\GIT\RemoteTool\WebApplication\bin\Debug\net8.0\appsettings.json
```

参数：`HostName`、`RemoteUser`、`Port`、`RemotePath`、`DataPath`、`ServiceName`、`Address`、`SourceConfig`、
`CodexExecutable`、`CodexHome`、`SkipPull`、`SkipPublish`。

还可用 `-ProxyUrl http://代理主机:端口` 为 systemd 服务设置 HTTP(S) 代理。
服务不会自动继承 SSH 登录环境的代理。配置保存到数据目录的 `service-proxy.env`（仅当前用户可读），
并排除 loopback 地址，避免桥接本地请求走代理。重新部署会保留此文件。
工作目录按 POSIX 路径处理，不能在 Windows 上用 `Split-Path` 计算 Linux 路径。

## 主机前置条件

| 依赖 | 说明 |
|---|---|
| ASP.NET Core 8 运行时 | `sudo apt-get install -y aspnetcore-runtime-8.0`。网站、Codex 桥接和外部 DLL 更新进程共用此运行时。 |
| Codex CLI | 已登录（`~/.codex/auth.json`），版本需支持 `codex app-server --stdio`（0.1xx 均可）。路径由 `-CodexExecutable` 指定。 |
| sudo | 无密码 sudo 用于写 systemd unit（也可改成用户级 systemd）。 |

## 脚本做了什么

1. `dotnet publish -c Release -r linux-x64 --self-contained false -p:UseAppHost=false` → `.tmp-linux-publish/`（已 gitignore）。
2. 用 Windows 的 `appsettings.json` 做底（保留 `Auth` 账号/JWT），重写 `Codex`/`Hermes` 为 Linux 路径，
   去掉 Windows 专属节（`RemoteControl`/`QbSettings`/`EmbySettings`/`CloudflareSettings`）。
3. `tar` 使用相对归档文件名打包，兼容 Windows BSD tar 与 GNU tar，避免把盘符识别成远程主机。
4. `scp` 到数据目录中随机命名且仅当前用户可访问的部署暂存目录，检查活动任务后停服务 → 旧目录改名为带时间和随机后缀的 `<RemotePath>.prev-*` → 解压 → 写 unit → 重启。
   默认保留远端已有 `appsettings.json`；显式指定 `-SourceConfig` 才覆盖它。不会删除旧的回滚目录。
   旧版本误生成的反斜杠工作目录会保留，并通过符号链接维持旧会话的工作路径，不重写原生会话记录。
   停服后保留应用目录中的 SQLite 数据库及伴随文件，避免部署时丢失 Hangfire 等应用数据。
5. `REMOTETOOL_DATA_DIR=<DataPath>`：避免默认写 `/usr/share/RemoteTool` 需要 root。
6. 健康检查：`http://<Address>:<Port>/Account/Login` == 200（`Address` 缺省从远端 `hostname -I` 取第一项）。

## 自动更新

部署脚本设置 `REMOTETOOL_SERVICE_NAME` 和 `KillMode=process`，保留独立 bridge / updater 的生命周期。Web 直接继承当前服务身份启动外部 DLL 更新进程，没有计划任务或 timer。更新设置、源码重编译、强制中断与 Web/App 提示共用 [SERVER-UPDATES.md](../SERVER-UPDATES.md) 中的接口。

非 root 系统服务需要预先具有对该 unit 执行 `sudo -n systemctl start/stop` 的权限；更新功能不会扩大授权。也可以配置当前账户的用户级 systemd unit，设置 `ServerUpdate:UserService=true`。GitHub 发布包含 Linux x64 和 ARM64 包，运行实例按架构选择。更新保留原服务工作目录和配置，失败还原原程序。

## 排障

```bash
sudo journalctl -u remotetool -f          # 日志
sudo systemctl status remotetool          # 状态
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8890/Account/Login
```

回滚：先停止服务，将当前应用目录改名保留，再将选定的 `<RemotePath>.prev-*` 恢复到原应用路径，最后启动服务。
数据目录、代理环境文件及已有配置应保留；不要用递归删除代替回滚。

**端口冲突**：Ubuntu 虚拟机上 80/8888 常被 openresty 占用，脚本默认 8890；换端口只改 `-Port`。

## Linux 上不可用的功能

截屏/鼠标键盘注入、传感器、磁盘 SMART、IIS 相关运维、Hermes 页签（需本机装 Hermes 网关）——
这些是 Windows API 实现，Linux 上会报错或隐藏。文件管理、Codex 会话、手机 MCP 是跨平台可用的。
