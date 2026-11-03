# 服务端更新

支持 Windows IIS 和 Linux systemd，Web 和主 App 使用同一套更新接口。无需配置 RSA 公私钥或 GitHub 签名 Secret。默认只提醒，用户可启用空闲时自动更新。

## 两种模式

**正式部署**：GitHub Actions 编译服务端，发布 `server-v1.2.0` 这样的标签。服务端定期检查固定仓库 `bighamx/RemoteTool` 的正式服务端 Release，验证 HTTPS 下载地址、包大小和 SHA256。独立更新器等待空闲，切换版本目录并重启 IIS；验证失败恢复旧版本。配置、身份、数据库、附件不从发行包覆盖。

Linux 使用与自身架构匹配的 `linux-x64-systemd` / `linux-arm64-systemd` 包，保留 systemd 的既有工作目录，在停服后备份并替换程序文件，再启动原服务。失败恢复原程序；配置、数据库、附件不覆盖。

**开发部署**：支持 IIS 直接指向 `E:\GIT\RemoteTool\WebApplication\bin\Debug\net8.0`。选择“编译当前源码”后，等待空闲，备份运行文件，先请求 codex-bridge 安全关闭，成功后才停止 Web 并执行 Debug 编译，再启动 Web 和 bridge。普通操作遇到 bridge 拒绝关闭时只延后，IIS 保持运行；勾选强制中断时见下方说明。通过程序集 buildId 核对实际加载版本；失败恢复原运行程序。IIS 路径不变，配置和数据库保留；仅编译当前源码时不要求工作树干净，也不操作 Git。

源码模式提供“拉取并更新”和“编译当前源码”。前者在任务空闲后检查当前分支与工作区，拉取 origin 对应分支并执行快进合并，再编译并重启；有未提交/未跟踪文件、分离 HEAD、分支已切换或发生分歧时拒绝，不暂存、重置、强推或覆盖本地改动。网络及 Git 检查完成前不会停止网站和 bridge。后者仅编译当前磁盘源码，允许本地改动。拉取后若编译失败，恢复原运行程序，保留已拉取源码供修复，不回退 Git。

源码模式检查使用 `git ls-remote` 读取当前分支的远端版本，不 fetch/pull、不修改工作区。仅当本地源码干净、运行版本与本地 HEAD/远端版本一致时显示“已是最新版本”；分别提示本地改动、源码尚未编译、与远端不同、未检查及检查失败。正式部署继续核对发行版本序号。旧客户端若调用安装 GitHub 发行版本的接口，仍要求本地 HEAD 等于发行提交且工作树干净，否则在停站前拒绝。

## 构建与发布

```powershell
dotnet run --project tests/ServerUpdates/ServerUpdates.csproj
dotnet run --project tests/UpdaterBootstrap/UpdaterBootstrap.csproj
dotnet run --project tests/UpdaterReadiness/UpdaterReadiness.csproj
dotnet publish WebApplication/WebApplication.csproj -c Release -r win-x64 --self-contained false -p:UseAppHost=false -o artifacts/server
```

创建 `server-v*` 标签后，`.github/workflows/server-release.yml` 测试、编译并上传服务端 ZIP、备用安装脚本和 `server-update.json`。更新逻辑编入同一个 `WebApplication.dll`，无独立更新器项目、EXE 或 ZIP。服务端和更新进程共用已安装的 .NET 8 / ASP.NET Core 运行时。清单包含提交 SHA、递增发行序号、协议范围、包大小和 SHA256。Android 标签与服务端标签互不混用。常规分支/PR 不自动发布。

日常 `Web CI`（`dotnet-build.yml`）在 master/main 的网站、依赖、测试或相关工作流变动及对应 PR 时运行，也支持手动检查；只编译解决方案并保留 7 天的 `published-app` 构建产物，不创建 Release，不再监听旧 `v*` 标签。相同分支或 PR 的新检查会取消旧检查。

Web CI 与 Server release 共同调用 `server-update-validation.yml`，并行执行 Windows/Linux 更新契约、Web 更新界面及 DLL 更新器入口验证；Linux 还执行真实 systemd 更新与回滚。正式发布必须等两端验证成功，再生成并检查实际发行包。发布工作流文件名保持不变，以延续现有发行序号；同一服务端标签的发布串行执行，不自动取消正在进行的发布。验证任务只读，只有正式上传任务拥有 Release 写权限。

## 首次启动自动准备

首次运行时，Web 将 `WebApplication.dll`、DLL 依赖及 `.deps.json` / `.runtimeconfig.json` 复制到网站外的 `<数据目录>/updates/engine/<构建标识>/`。Windows 数据目录通常为 `C:/ProgramData/RemoteTool`，Linux 使用 `REMOTETOOL_DATA_DIR`。外部副本不复制 appsettings、数据库、日志或凭据。

执行更新时，Web 直接启动 `dotnet <外部目录>/WebApplication.dll --server-updater <installation.json绝对路径>`，继承 Web 已有的身份、权限和环境，不切换桌面用户、不重新提权。Windows 在挂起状态创建子进程并校验其已脱离当前 IIS Job 后才运行；若宿主禁止脱离，停站前明确失败。Linux 使用独立会话并要求服务 `KillMode=process`，使停止网站主进程时保留更新进程。更新模式直接进入更新流程，不启动网站和后台服务。

没有计划任务、定时器或额外服务。已有旧版 `RemoteTool_ServerUpdate` 计划任务时，在没有更新任务运行期间备份任务 XML 后删除，保留安装信息、凭据和策略。网站 DLL 变化后会刷新外部副本。可设置 `ServerUpdate:AutoInstall=false` 关闭首次自动准备。源码编译也使用服务端身份和环境，SDK / NuGet 源需对该身份可用。

Linux 配置 `ServerUpdate:ServiceName` 或 `REMOTETOOL_SERVICE_NAME` 指定网站 unit，默认 `remotetool.service`；用户级 unit 还需 `ServerUpdate:UserService=true`。必须使用 `KillMode=process`，工作目录和 MainPID 必须与当前网站一致。root 或用户级服务可直接控制；普通账户管理系统级服务时需要既有的非交互 systemctl 权限，更新功能不会自行授予 sudo 权限。权限或停止规则不符时在停站前拒绝。

## 手动安装（权限不足时的备用方式）

需要管理员 PowerShell 7，现有 RemoteTool 使用独立的 LocalSystem IIS 应用池。先部署/编译包含本功能的版本，使当前 Web 支持更新接口和 bridge v40 的安全停止接口。旧 bridge 缺少该接口时会拒绝关闭，不能按 PID 强杀。

开发机执行：

```powershell
./WebApplication/Tools/Install-ServerUpdater.ps1
```

安装器从 IIS 的 bin 路径向上查找 `WebApplication.csproj`，自动识别开发模式，记录固定项目/输出目录并准备外部 DLL。IIS 仍指向原 bin，源码不移动。不注册计划任务，不要求桌面用户已登录。

Windows 正式部署使用 `-Mode Release -InitialRelease C:\staging\server`。安装器准备版本目录和共享配置；在空闲时执行 `Activate-ServerUpdater.ps1` 切换 IIS，失败恢复原路径。`Hangfire:Database` 会固定为既有数据库的绝对路径；不能确定数据库位置时拒绝迁移，不复制运行中的 SQLite 文件。

备用安装脚本也复制网站 DLL 和依赖，在网站外运行；可用 `-ApplicationDll` 指定完整构建目录中的 `WebApplication.dll`。配置权限限管理员/SYSTEM。内部控制凭据由安装器自动生成，用户不需要管理或输入。

## 日常使用

- Web 入口位于左下角服务端版本后的“更新”小按钮，仅在检查成功、版本匹配且服务端确认有可用更新时显示；顶部不再显示入口。App 沿用当前设备的“服务端更新”入口，可查看版本、检查更新、选择策略、更新并重启。
- Web 对话框使用网站现有主题变量和按钮样式，版本信息、检查结果、任务进度、设置与底部操作分别呈现；支持深浅主题、窄屏滚动、键盘关闭和焦点提示。样式位于 `wwwroot/css/server-update.css`，由共享布局加载。
- 源码模式只显示当前状态所需的一个主操作：本地与远端源码不同时为“拉取并更新”；本地源码待生效或有未提交修改时为“编译并重启”；源码和运行版本都已是最新时隐藏更新按钮。检查失败、尚未检查或检查对应旧运行版本时先重新检查，不猜测操作。旧服务端不支持源码检查与拉取时保留“编译并重启”。界面分别显示服务端运行版本和本地源码版本，不再同时提供两个编译／拉取入口。
- 检查约每 30 分钟执行，使用 ETag；失败显示错误及检查时间，不冒充“已是最新”。检查最近 100 个 Release，筛选 `server-v*`。
- 自动更新的时间按 UTC 保存，Web/App 显示为查看设备的本地时间；默认对应香港时间 02:00–06:00。长驻终端、隧道等需用户关闭，普通更新等待超时只延后。
- 手动操作可勾选“强制中断任务”，仅对本次生效；跳过等待，结束经过本机认证、PID 和启动时间核对的 RemoteTool Codex bridge 进程树，并断开 Web 连接。可能中断任务且不会自动重发。自动更新始终等待空闲，不继承强制选项，不停止 Hermes gateway 或桌面 Codex。
- 重启断线后 Web/App 自动重连，只有目标版本/程序集和设备身份恢复才报告成功。旧服务端不会显示 App 更新入口。
- 点击更新后立即显示正在提交，再展示服务端实际阶段（等待空闲及阻塞原因、拉取、下载、编译、重启、验证、回滚、完成或失败）和不定百分比的活动进度条。打开窗口或任务运行时每两秒刷新；接受更新后立即查询，不再等待原来的 60 秒后台周期。重启断线时保留阶段并提示正在重连；延后、失败、回滚完成和需要人工恢复时停止活动进度。旧查询不得覆盖新提交返回的任务，提交异常先查询受理结果而不自动重试操作。
- Web/App 将完成、延后、失败和回滚记录明确标为“本次更新”或“上次更新”：只把本轮窗口内已受理或观察到运行中的同一任务作为本次操作；重新打开窗口后，保留的终态显示为上次记录。下载完成待安装、运行中及需要恢复的异常仍按实际状态展示。

同机另建发行包测试站点时，使用独立 IIS 站点、应用池、发布目录、`REMOTETOOL_DATA_DIR`、`REMOTETOOL_SHARED_CONFIG` 及绝对路径的 Hangfire 数据库；发布目录位于 Git 源码目录外，自动准备后应确认 `mode=release`。只准备测试配置，不复制现有会话、任务、数据库或业务服务凭据。同一物理电脑的设备 ID 按既有规则相同，客户端测试时需确认所连接的端口地址。

任务日志、清单快照、备份、状态位于 updates/jobs；每个任务的版本固定，后续检查不会替换其清单。启动后核对 worker 的 PID/启动标识回执（Windows 进程创建时间、Linux `/proc` 启动 tick），并用文件锁避免重复执行。网站恢复运行或点击“立即检查”时，可重新拉起已退出的更新进程，根据事务记录回滚，不重发会话消息。如果网站和更新进程都已停止，需要外部重新启动其中一个；没有计划任务在后台拉起。回滚未确认、状态未知时不会开始第二次更新。

空闲检查只依据任务的已确认终态，不依据登记时间判断任务结束。`acceptance_unknown`、404/5xx、探测失败都不能当作空闲，原生回执不会为更新而被清除。回滚后仍显示最初的失败原因，并在 `updates/jobs/<任务标识>/failure.json` 保留失败阶段和原因。

用户核实并明确关闭的历史跟踪记录可附加 `maintenance_review`（`kind=historical_tracking`、核对时间和精确匹配的 run/session/turn ID）。原 `acceptance_unknown`、输出和幂等标识保持不变；更新检查及桥接安全关闭仅忽略这条已核对的历史记录，真实运行、排队及未核对记录仍阻塞。Hermes 只有在网关进程存活、全部 Profile 的新鲜状态确认为空闲，且原生接口明确返回 `run_not_found` 时才清除旧的 Web 活跃提示登记；不删除 Hermes 会话或历史。

恢复连接后的 Codex 状态、请求回执查询及 SSE 重连会按原 `turn_id` 补查原生历史，确认完成／失败／中断后同步回执并保留同一会话的新任务。支持未携带 `run_tracking_lost` 错误码的旧记录；桌面快照未覆盖的旧轮次继续读取原生分页历史，找到目标即停。缺失历史、读取失败或原轮次仍在执行时继续保留未知状态，不推断成功或自动重发。

桥接安全关闭在响应完成前解析宿主生命周期服务，回调内只发出停止信号，避免在请求作用域已释放后访问 `RequestServices`，导致接口已受理却无法退出、更新器随后超时。

只停止目标 Web 应用池及它自己的 Codex bridge/app-server；普通操作要求空闲，强制操作须手动勾选。不停止 Hermes gateway、Codex 桌面端或其他开发者程序。首次正式启用前，在测试 IIS 站点验证一次成功更新和一次失败回滚。现有测试使用真实更新器流程及受控 IIS/编译接口，不能替代真实 IIS/真机验证。
