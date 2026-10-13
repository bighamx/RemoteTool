# macOS 服务端与网页适配

本仓库在新版 Web 工作台上提供 macOS 服务端适配。入口是 `/`；旧 `/mobile/` 会跳转到工作台。

## 本版范围

| 功能 | 状态 |
| --- | --- |
| 工作台登录、概览、系统操作、命令终端 | 已实现；终端为按行协议，无 PTY；手机可点“发送”提交命令 |
| Codex 会话列表与对话 | 复用上游新版工作台；Mac 服务端已适配 Codex CLI |
| 工作台主屏幕图标、独立窗口启动 | 根路径已配置 Web App Manifest、图标和 Service Worker；需要 HTTPS 或 localhost |
| 文件、Docker、Compose、定时任务 | 复用原有工作台页面 |
| macOS Codex CLI 桥接、用户数据目录、zsh | 已适配，需实机验证已安装 Codex 的协议版本 |
| Mac 桌面代理、JPEG 主屏幕、触摸、拖拽、滚轮、按键、Unicode 文字、剪贴板 API | 已写入 Swift 源码，需 Mac 编译和授权验证 |
| 上游新版 Web 远程桌面 | Mac 使用已有 MJPEG 接口；锁屏时提示在电脑上解锁，Windows 继续使用 H.264 |
| Mac H.264、音频、多屏切换、锁屏／登录界面 | 本版未实现或未保证 |
| Mac 温度、风扇、每核 CPU 曲线 | 本版未实现；不伪造传感器数据 |
| Hermes 聊天 | 使用上游工作台现有页面和 API |
| Mac 与 Codex 桌面 App 同时写入同一会话 | 未实现桌面 IPC 协作；会话占用时先退出原写入客户端 |

## 1. 在 Mac 上准备环境

- .NET 8 SDK（Apple Silicon 使用 Arm64，Intel 使用 x64）。
- Codex CLI，先在 Mac 终端中完成安装和登录，验证 `codex --version` 和 `codex app-server --help`。
- Python 3，用于生成配置。
- 如需桌面控制：macOS 12.3+，Xcode Command Line Tools。不同 macOS / Xcode 版本仍需实机测试。
- 如需容器管理：启动 Docker Desktop。默认读取 `~/.docker/run/docker.sock`；可用 `Docker:Host` 配置实际 Socket URI。

## 2. 构建桌面代理（可选）

在项目根目录运行：

```bash
bash tools/RemoteTool.MacAgent/build.sh
open tools/RemoteTool.MacAgent/build/RemoteToolMacAgent.app --args --permissions
```

然后在“系统设置 → 隐私与安全性”中，开启该 App 的“屏幕录制”和“辅助功能”。设置后退出权限提示进程，再启动服务。未开启权限时，H5 概览会展示缺失权限。

桌面采集需要 Mac 处于已登录且已解锁的图形会话。锁屏时 ScreenCaptureKit 可能返回空的显示器列表；H5 会提示先解锁，无法从服务端代为越过锁屏。解锁后刷新 H5 再连接桌面。

默认是本机 ad-hoc 签名。保持代理 App 的路径和签名身份稳定；重新构建或移动 App 后可能需要重新授权。发布给其他人时，应使用自己的 Developer ID 签名并公证。可设置 `MAC_AGENT_SIGN_IDENTITY` 指定签名身份。

## 3. 生成配置并启动

```bash
python3 tools/configure-macos.py
dotnet run --project WebApplication --urls http://127.0.0.1:5104
```

脚本会询问登录账号密码、生成 JWT 密钥，并探测 Codex 和已构建的 Mac 代理。已有 `appsettings.json` 时不会覆盖。

电脑浏览器打开：`http://127.0.0.1:5104/`。

手机局域网访问时使用：

```bash
dotnet run --project WebApplication --urls http://0.0.0.0:5104
```

手机打开 `http://Mac的局域网IP:5104/`。使用同源 Cookie 登录，不需要把 Codex 端口暴露给手机。首次启动时按 macOS 提示允许局域网连接／防火墙访问。

远程访问请通过已有 VPN 或 HTTPS 反向代理。JPEG H5 控制链路不依赖 WebCodecs；原有 Windows 高帧率播放仍有其浏览器与安全上下文要求。

手机访问 HTTPS 入口后，Android Chrome 在满足安装条件时会显示工作台的“安装应用”按钮，也可在 Chrome 菜单选择安装。iPhone Safari 使用“分享 → 添加到主屏幕”。安装后以独立窗口启动；断网时只显示连接提示，设备控制仍需联网访问 Mac 服务。

## 4. 可选配置

以下是需要手动修改的配置项说明（不是可直接覆盖的完整配置）：

| 配置项 | macOS 示例 |
| --- | --- |
| `Codex:Executable` | `command -v codex` 输出的绝对路径 |
| `Codex:Home` | `/Users/你的用户名/.codex` |
| `Codex:WorkingDirectory` | `/Users/你的用户名/Documents/Codex/Mobile` |
| `RemoteControl:MacAgentExecutable` | 代理 `.app/Contents/MacOS/RemoteToolMacAgent` 的绝对路径 |
| `CloudflareAccess:Team` / `Audience` / `Email` | 可选的旧式单邮箱配置；现在可在工作台“设置 → 身份验证”维护多个允许邮箱，同时配置任意标准 OIDC 提供方。OIDC 回调固定为外部站点的 `/access/callback`。 |
| `Hermes:HomeDirectory` | Hermes 实际的数据目录 |
| `Hermes:SourceDirectory` | Hermes 源码安装目录 |
| `Hermes:PythonExecutable` | Hermes 虚拟环境中 `bin/python` 的绝对路径 |
| `Hermes:KeyFile` | 包含 `API_SERVER_KEY` 的文件路径 |
| `Docker:Host` | `unix:///Users/你的用户名/.docker/run/docker.sock` |

Mac 数据默认保存在 `~/Library/Application Support/RemoteTool`。`REMOTETOOL_DATA_DIR` 可以覆盖服务数据目录；不要指向已有 Codex 配置目录。服务应由已经登录桌面的普通用户启动，以复用该用户的 Codex 登录和桌面权限。Mac 的关机／重启不自动提权；权限不足会返回实际错误。

2026-10-13 已在 Mac 实测：代理的屏幕录制和辅助功能权限均可用，能抓到 JPEG；真实 HTTP 服务的 Mac 锁屏状态和 MJPEG 路由通过独立临时配置的集成测试。新版工作台已在 Chrome 手机尺寸模拟器和公网 Chrome 中验证终端连接及命令回显。未测试锁屏状态下的桌面输入。

## 5. 实机验证顺序

1. 登录 H5，检查平台为 macOS，CPU、内存、磁盘可读取。
2. 连接终端，运行 `pwd`、`whoami` 和 `printf '中文测试\n'`；断开后确认 Shell 退出。
3. 打开 Codex 会话列表，先创建无项目会话并发送一个只读问题。
4. 选择已有项目发送只读任务；确认回复与电脑端历史一致。
5. 执行耗时任务后刷新页面、切换手机网络，确认恢复原任务且没有重复发送。
6. 在允许的测试目录验证审批、停止、错误显示；请求结果不确定时先核对历史。
7. 构建／授权代理，连接桌面，验证点击、拖拽、滚轮、中文文本和 Command 快捷键。失去焦点／断线后检查按键释放。
8. 如需要，再验证文件和 Docker，最后验证电源操作。

## 6. 已完成的自动验证

构建与测试命令：

```bash
dotnet build WebApplication/WebApplication.csproj
dotnet run --project tests/AgentContracts
dotnet run --project tests/BridgeStartup -- WebApplication/bin/Debug/net8.0/WebApplication.dll
python3 tests/test_macos_h5_integration.py
```

集成测试使用临时目录和模拟 Codex，不调用真实模型，也不修改真实 Codex 账户。它验证 HTTP 登录／退出、页面入口、非 Windows 桥接启动、JSON-RPC 初始化、会话／任务去重、SSE 和审批。

Linux 上的编译和模拟测试不能证明 Mac 系统 API、Swift 编译、真实 Agent 版本或浏览器权限已经通过。本版暂未接入 PTY、WebRTC 和原生 H.264 编码。

终端已在 Chrome 390px 手机模拟视口中验证连接、点击“发送”和回车提交，公网 Chrome 验证了终端命令回显。iPhone Safari 和实际 Mac 锁屏状态需要单独验证。
