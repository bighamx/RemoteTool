# 服务端更新

支持 Windows IIS 和 Linux systemd，Web 和主 App 使用同一套更新接口。无需配置 RSA 公私钥或 GitHub 签名 Secret。默认只提醒，用户可启用空闲时自动更新。

## 两种模式

**正式部署**：GitHub Actions 编译服务端，发布 `server-v1.2.0` 这样的标签。服务端定期检查固定仓库 `bighamx/RemoteTool` 的正式服务端 Release，验证 HTTPS 下载地址、包大小和 SHA256。独立更新器等待空闲，切换版本目录并重启 IIS；验证失败恢复旧版本。配置、身份、数据库、附件不从发行包覆盖。

Linux 使用与自身架构匹配的 `linux-x64-systemd` / `linux-arm64-systemd` 包，保留 systemd 的既有工作目录，在停服后备份并替换程序文件，再启动原服务。失败恢复原程序；配置、数据库、附件不覆盖。

**开发部署**：支持 IIS 直接指向 `E:\GIT\RemoteTool\WebApplication\bin\Debug\net8.0`。点击“编译并重启”后，等待空闲，备份运行文件，先请求 codex-bridge 安全关闭，成功后才停止 Web 并执行 Debug 编译，再启动 Web 和 bridge。普通操作遇到 bridge 拒绝关闭时只延后，IIS 保持运行；勾选强制中断时见下方说明。通过程序集 buildId 核对实际加载版本；失败恢复原运行程序。IIS 路径不变，配置和数据库保留，不要求工作树干净，不执行 git pull/reset/checkout。

源码模式界面只显示“编译并重启”，不显示安装发行包和自动安装策略；源码更新由开发者自行拉取。旧客户端若调用安装 GitHub 发行版本的接口，仍要求本地 HEAD 等于发行提交且工作树干净，否则在停站前拒绝。不会下载编译产物覆盖源码模式的 bin，也不会监视文件改动就自动停止服务。

## 构建与发布

```powershell
dotnet run --project tests/ServerUpdates/ServerUpdates.csproj
dotnet run --project tests/UpdaterBootstrap/UpdaterBootstrap.csproj
dotnet run --project tests/UpdaterReadiness/UpdaterReadiness.csproj
dotnet publish WebApplication/WebApplication.csproj -c Release -r win-x64 --self-contained false -p:UseAppHost=false -o artifacts/server
```

创建 `server-v*` 标签后，`.github/workflows/server-release.yml` 测试、编译并上传服务端 ZIP、备用安装脚本和 `server-update.json`。更新逻辑编入同一个 `WebApplication.dll`，无独立更新器项目、EXE 或 ZIP。服务端和更新进程共用已安装的 .NET 8 / ASP.NET Core 运行时。清单包含提交 SHA、递增发行序号、协议范围、包大小和 SHA256。Android 标签与服务端标签互不混用。常规分支/PR 不自动发布。

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

- Web 顶栏与 App 当前设备的“服务端更新”入口可查看版本、检查更新、选择策略、更新并重启。
- 源码模式显示“编译并重启”，没有新 Release 时也能使用；先自行拉取源码，再执行编译即可。
- 检查约每 30 分钟执行，使用 ETag；失败显示错误及检查时间，不冒充“已是最新”。检查最近 100 个 Release，筛选 `server-v*`。
- 自动更新的时间按 UTC 保存，Web/App 显示为查看设备的本地时间；默认对应香港时间 02:00–06:00。长驻终端、隧道等需用户关闭，普通更新等待超时只延后。
- 手动操作可勾选“强制中断任务”，仅对本次生效；跳过等待，结束经过本机认证、PID 和启动时间核对的 RemoteTool Codex bridge 进程树，并断开 Web 连接。可能中断任务且不会自动重发。自动更新始终等待空闲，不继承强制选项，不停止 Hermes gateway 或桌面 Codex。
- 重启断线后 Web/App 自动重连，只有目标版本/程序集和设备身份恢复才报告成功。旧服务端不会显示 App 更新入口。

任务日志、清单快照、备份、状态位于 updates/jobs；每个任务的版本固定，后续检查不会替换其清单。启动后核对 worker 的 PID/启动标识回执（Windows 进程创建时间、Linux `/proc` 启动 tick），并用文件锁避免重复执行。网站恢复运行或点击“立即检查”时，可重新拉起已退出的更新进程，根据事务记录回滚，不重发会话消息。如果网站和更新进程都已停止，需要外部重新启动其中一个；没有计划任务在后台拉起。回滚未确认、状态未知时不会开始第二次更新。

空闲检查只依据任务的已确认终态，不依据登记时间判断任务结束。`acceptance_unknown`、404/5xx、探测失败都不能当作空闲，原生回执不会为更新而被清除。回滚后仍显示最初的失败原因，并在 `updates/jobs/<任务标识>/failure.json` 保留失败阶段和原因。

只停止目标 Web 应用池及它自己的 Codex bridge/app-server；普通操作要求空闲，强制操作须手动勾选。不停止 Hermes gateway、Codex 桌面端或其他开发者程序。首次正式启用前，在测试 IIS 站点验证一次成功更新和一次失败回滚。现有测试使用真实更新器流程及受控 IIS/编译接口，不能替代真实 IIS/真机验证。
