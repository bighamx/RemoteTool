# macOS 服务端与网页适配

本仓库在新版 Web 工作台上提供 macOS 服务端适配。入口是 `/`；旧 `/mobile/` 会跳转到工作台。

## 本版范围

| 功能 | 状态 |
| --- | --- |
| 工作台登录、概览、系统操作、命令终端 | 已实现；Mac 终端使用 PTY，支持交互式程序、终端尺寸同步与手机快捷键 |
| Codex 会话列表与对话 | 复用上游新版工作台；Mac 服务端已适配 Codex CLI，完成的助手回复支持 Markdown |
| 工作台主屏幕图标、独立窗口启动 | 根路径已配置 Web App Manifest、图标和 Service Worker；需要 HTTPS 或 localhost |
| 文件、Docker、Compose、定时任务 | 复用原有工作台页面 |
| macOS Codex CLI 桥接、用户数据目录、zsh | 已适配，需实机验证已安装 Codex 的协议版本 |
| Mac 桌面代理、多显示器切换、触摸、拖拽、滚轮、按键、Unicode 文字、剪贴板 API | 已实现；新安装仍需在 Mac 编译并授权，实测范围见下文 |
| 上游新版 Web 远程桌面 | Mac 使用 ScreenCaptureKit NV12 → VideoToolbox H.264，最高 1920 宽、20 FPS、目标 3 Mbps；支持手机双指缩放／平移，MJPEG 保留为回退；锁屏时提示在电脑上解锁 |
| Android 原生 App 视频格式 | `/api/stream?format=h264` 输出带 AUD 的 Annex B 流；默认请求仍输出网页使用的 MP4。`tests/test_macos_stream_formats.py` 用生成画面验证两种输出的真实硬件编码和解码，不依赖屏幕录制权限 |
| Mac 音频、锁屏远程解锁、网络自适应码率 | 本版未实现 |
| Mac 温度、风扇、每核 CPU 曲线 | 本版未实现；不伪造传感器数据 |
| Hermes 聊天 | 使用上游工作台现有页面和 API |
| Mac 与 Codex 桌面 App 同时写入同一会话 | 未实现桌面 IPC 协作；会话占用时先退出原写入客户端 |

## 1. 在 Mac 上准备环境

- .NET 8 SDK（Apple Silicon 使用 Arm64，Intel 使用 x64）。
- Codex CLI，先在 Mac 终端中完成安装和登录，验证 `codex --version` 和 `codex app-server --help`。
- Python 3，用于生成配置。
- 如需桌面控制：macOS 12.3+，Xcode Command Line Tools。不同 macOS / Xcode 版本仍需实机测试。
- H.264 桌面流需要提供 `h264_videotoolbox` 编码器的 FFmpeg；可通过 `REMOTETOOL_FFMPEG_PATH` 指定绝对路径。
- 如需容器管理：启动 Docker Desktop。默认读取 `~/.docker/run/docker.sock`；可用 `Docker:Host` 配置实际 Socket URI。

## 2. 构建原生代理（终端与桌面功能需要）

构建脚本同时生成桌面代理和终端使用的 `RemoteToolPtyAgent`。仅使用 PTY 终端时无需屏幕录制或辅助功能权限；下面的授权步骤用于远程桌面。

在项目根目录运行：

```bash
bash tools/RemoteTool.MacAgent/build.sh
open tools/RemoteTool.MacAgent/build/RemoteToolMacAgent.app --args --permissions
```

然后在“系统设置 → 隐私与安全性”中，开启该 App 的“屏幕录制”和“辅助功能”。设置后退出权限提示进程，再启动服务。未开启权限时，H5 概览会展示缺失权限。

桌面采集需要 Mac 处于已登录且已解锁的图形会话。锁屏时 ScreenCaptureKit 可能返回空的显示器列表；H5 会提示先解锁，无法从服务端代为越过锁屏。解锁后刷新 H5 再连接桌面。

默认是本机 ad-hoc 签名。保持代理 App 的路径和签名身份稳定；重新构建或移动 App 后可能需要重新授权。发布给其他人时，应使用自己的 Developer ID 签名并公证。可设置 `MAC_AGENT_SIGN_IDENTITY` 指定签名身份。

若由 launchd 等后台服务启动，须从实际运行的工作台检查 `api/input/session-state` 的 `available`；从终端直接运行代理获得权限，不代表后台服务也获得了屏幕录制权限。未授权时工作台会在连接桌面前提示，需在 macOS 隐私设置中授权后重启服务。

## 3. 生成配置并启动

```bash
python3 tools/configure-macos.py
dotnet run --project WebApplication --urls http://127.0.0.1:5104
```

脚本会询问登录账号密码、生成 JWT 密钥，并探测 Codex 和已构建的 Mac 代理。已有 `appsettings.json` 时不会覆盖。若生成配置后才构建代理，需要手动补填 `RemoteControl:MacAgentExecutable`，或单独配置 `RemoteControl:MacPtyExecutable`。

电脑浏览器打开：`http://127.0.0.1:5104/`。

手机局域网访问时使用：

```bash
dotnet run --project WebApplication --urls http://0.0.0.0:5104
```

手机打开 `http://Mac的局域网IP:5104/`。使用同源 Cookie 登录，不需要把 Codex 端口暴露给手机。首次启动时按 macOS 提示允许局域网连接／防火墙访问。

远程访问请通过已有 VPN 或 HTTPS 反向代理。H.264 优先使用 WebCodecs，其次使用 MSE；不支持时回退到 MJPEG。WebCodecs 需要 HTTPS 或 localhost 安全上下文。

手机访问 HTTPS 入口后，Android Chrome 在满足安装条件时会显示工作台的“安装应用”按钮，也可在 Chrome 菜单选择安装。iPhone Safari 使用“分享 → 添加到主屏幕”。安装后以独立窗口启动；断网时只显示连接提示，设备控制仍需联网访问 Mac 服务。

## 4. 可选配置

以下是需要手动修改的配置项说明（不是可直接覆盖的完整配置）：

| 配置项 | macOS 示例 |
| --- | --- |
| `Codex:Executable` | `command -v codex` 输出的绝对路径 |
| `Codex:Home` | `/Users/你的用户名/.codex` |
| `Codex:WorkingDirectory` | `/Users/你的用户名/Documents/Codex/Mobile` |
| `RemoteControl:FFmpegPath` | FFmpeg 的绝对路径；也可使用 `REMOTETOOL_FFMPEG_PATH` 环境变量 |
| `RemoteControl:MacAgentExecutable` | 代理 `.app/Contents/MacOS/RemoteToolMacAgent` 的绝对路径 |
| `RemoteControl:MacPtyExecutable` | 可选；默认读取桌面代理同目录的 `RemoteToolPtyAgent` |
| `CloudflareAccess:Team` / `Audience` / `Email` | 可选的旧式单邮箱配置；现在可在工作台“设置 → 身份验证”维护多个允许邮箱，同时配置任意标准 OIDC 提供方。OIDC 回调固定为外部站点的 `/access/callback`。 |
| `Hermes:HomeDirectory` | Hermes 实际的数据目录 |
| `Hermes:SourceDirectory` | Hermes 源码安装目录 |
| `Hermes:PythonExecutable` | Hermes 虚拟环境中 `bin/python` 的绝对路径 |
| `Hermes:KeyFile` | 包含 `API_SERVER_KEY` 的文件路径 |
| `Docker:Host` | `unix:///Users/你的用户名/.docker/run/docker.sock` |

Mac 数据默认保存在 `~/Library/Application Support/RemoteTool`。`REMOTETOOL_DATA_DIR` 可以覆盖服务数据目录；不要指向已有 Codex 配置目录。服务应由已经登录桌面的普通用户启动，以复用该用户的 Codex 登录和桌面权限。Mac 的关机／重启不自动提权；权限不足会返回实际错误。

已在双显示器 Mac 上实测：两块屏幕均可输出 1920×1080 H.264；390px Chrome 手机模拟视口可切换副屏、缩放／平移，放大后的输入坐标通过检查。静止画面样本约 0.65 Mbps，动态画面流量和操作延迟仍需按实际网络测试。PTY 可执行命令、同步终端尺寸，Ctrl+C 可中断长命令后继续使用 Shell。锁屏状态下的桌面输入仍需单独验证。

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

Linux 上的编译和模拟测试不能证明 Mac 系统 API、Swift 编译、真实 Agent 版本或浏览器权限已经通过。本版暂未接入 WebRTC、音频或网络自适应码率；H.264 使用 FFmpeg 的 VideoToolbox 硬件编码器。

终端已在 Chrome 390px 手机模拟视口中验证连接、发送、回车与 Ctrl+C。iPhone Safari 和实际 Mac 锁屏状态需要单独验证。


## 7. Mac 兼容性修复与验证

- Codex 桌面协作通过 `Codex:Home/ipc/ipc.sock` 连接，并校验对端 UID 和可执行文件；原生写入锁使用非阻塞 `flock` 检测。
- 接管时传入预期轮次；进度连接失败后转为结果待确认，不自动重发；桌面确认同一轮次结束时清除旧计时，同时保留其他客户端的真实运行状态。
- H.264 支持 Android 所需的 Annex B/AUD 与浏览器所需的 fragmented MP4。H.264/MJPEG 采集前区分锁屏（423）、录屏未授权（403）和显示器断开（503）。
- 输入断线使用原生 `release` 释放按键，即使随后锁屏也可清理；锁屏命令使用已授权代理发送 Control+Command+Q，替代新版 macOS 已删除的 CGSession 路径。
- 能力接口正确声明多显示器、H.264 和桌面协作。其他 Mac Codex 客户端未退出时拒绝切换账号，避免桌面与后台身份不一致。
- 图片预览使用系统 `sips` 输出真实 JPEG，按比例缩放且保留原文件；复制/解压识别 `/var`、`/tmp`、`/etc` 的标准系统链接，保留普通链接循环与压缩包越界防护。
- PTY 区分结构化控制帧与普通 Shell 花括号命令；Unix 项目路径保留大小写，根目录分组和子目录匹配正确；Web 禁用未实现的 Mac 休眠操作。

相关检查：

```bash
dotnet run --project tests/RemoteFiles
dotnet run --project tests/AgentContracts
dotnet run --project tests/BridgeStartup -- WebApplication/bin/Release/net8.0/WebApplication.dll
REMOTETOOL_TEST_DOTNET=dotnet python3 tests/test_macos_stream_formats.py
python3 tests/test_macos_pty.py
node --check WebApplication/wwwroot/js/remote.js
```

视频测试使用生成的 NV12 画面验证真实 VideoToolbox 编码和 FFmpeg 解码，锁屏与权限响应使用隔离代理验证。Mac 原生写入锁、对端身份拒绝、进程检测、断线恢复、文件转换及 PTY 均有对应检查。

实机已验证本机/HTTPS 视频数据与输入 WebSocket，以及终端花括号命令、窗口尺寸和中文输出。完整手机交互、Windows 实机及关机/重启/睡眠等操作未覆盖；Mac 锁屏远程解锁、音频和自适应码率尚未实现。
