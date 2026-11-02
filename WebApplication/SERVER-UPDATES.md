# 服务端更新

第一版支持 Windows IIS，Web 和主 App 使用同一套更新接口。无需配置 RSA 公私钥或 GitHub 签名 Secret。默认只提醒，用户可启用空闲时自动更新。

## 两种模式

**正式部署**：GitHub Actions 编译服务端，发布 `server-v1.2.0` 这样的标签。服务端定期检查固定仓库 `bighamx/RemoteTool` 的正式服务端 Release，验证 HTTPS 下载地址、包大小和 SHA256。独立更新器等待空闲，切换版本目录并重启 IIS；验证失败恢复旧版本。配置、身份、数据库、附件不从发行包覆盖。

**开发部署**：支持 IIS 直接指向 `E:\GIT\RemoteTool\WebApplication\bin\Debug\net8.0`。点击“重新编译当前源码”后，等待空闲，备份运行文件，停止 Web 和空闲的 codex-bridge，执行 Debug 编译，再启动 Web 和 bridge。通过程序集 buildId 核对实际加载版本；失败恢复原运行程序。IIS 路径不变，配置和数据库保留，不要求工作树干净，不执行 git pull/reset/checkout。

开发模式如果选择安装 GitHub 发行版本，要求开发者已自行把源码同步到清单对应的提交且工作树干净，否则明确拒绝。自动下载仍只下载，不会偷偷执行编译。源码重编译默认是手动操作；不会监视文件改动就自动停止服务。

## 构建与发布

```powershell
dotnet run --project tests/ServerUpdates/ServerUpdates.csproj
dotnet publish WebApplication/WebApplication.csproj -c Release -r win-x64 --self-contained true -o artifacts/server
dotnet publish WebApplication/Tools/ServerUpdater/ServerUpdater.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o artifacts/updater
```

创建 `server-v*` 标签后，`.github/workflows/server-release.yml` 测试、编译并上传服务端 ZIP、更新器 ZIP、安装脚本和 `server-update.json`。清单包含提交 SHA、递增发行序号、协议范围、包大小和 SHA256。Android 标签与服务端标签互不混用。常规分支/PR 不自动发布。

## 一次性安装

需要管理员 PowerShell 7，现有 RemoteTool 使用独立的 LocalSystem IIS 应用池。先部署/编译包含本功能的版本，使当前 Web 支持更新接口和 bridge v40 的安全停止接口。旧 bridge 缺少该接口时会拒绝关闭，不能按 PID 强杀。

开发机执行：

```powershell
./WebApplication/Tools/Install-ServerUpdater.ps1 -UpdaterExecutable C:\staging\updater\ServerUpdater.exe
```

安装器从 IIS 的 bin 路径向上查找 `WebApplication.csproj`，自动识别开发模式，记录固定项目/输出目录并注册独立任务。IIS 仍指向原 bin，源码不移动。开发更新任务使用开发者的交互式提升令牌，因此 SDK、NuGet 和用户环境与开发者一致，执行时需要该用户已登录。

正式部署使用 `-Mode Release -InitialRelease C:\staging\server`。安装器准备版本目录、共享配置和独立更新任务；在空闲时执行 `Activate-ServerUpdater.ps1` 切换 IIS，失败恢复原路径。`Hangfire:Database` 会固定为既有数据库的绝对路径；不能确定数据库位置时拒绝迁移，不复制运行中的 SQLite 文件。

更新器和配置位于 `C:\Program Files\RemoteTool` 与 `ProgramData\RemoteTool\updates`，权限限管理员/SYSTEM。内部控制凭据由安装器自动生成，用户不需要管理或输入。更新器本身独立于网站，升级它需管理员重新安装。

## 日常使用

- Web 顶栏与 App 当前设备的“服务端更新”入口可查看版本、检查更新、选择策略、更新并重启。
- 开发模式额外显示“重新编译当前源码”，没有新 Release 时也能使用。
- 检查约每 30 分钟执行，使用 ETag；失败显示错误及检查时间，不冒充“已是最新”。检查最近 100 个 Release，筛选 `server-v*`。
- 自动更新按 UTC 时段执行，默认 18–22 时，对应香港时间次日 02–06 时。长驻终端、隧道等需用户关闭，等待超时只延后，不强停。
- 重启断线后 Web/App 自动重连，只有目标版本/程序集和设备身份恢复才报告成功。旧服务端不会显示 App 更新入口。

任务日志、清单快照、备份、状态位于 updates/jobs；每个任务的版本固定，后续检查不会替换其清单。更新器中断后，由每五分钟执行的计划任务恢复原版本。有待确认任务、回滚未确认、状态未知时不会开始第二次更新。

只停止目标 Web 应用池及它自己的空闲 Codex bridge/app-server，不停止 Hermes gateway、Codex 桌面端或其他开发者程序。首次正式启用前，在测试 IIS 站点验证一次成功更新和一次失败回滚。现有测试使用真实更新器流程及受控 IIS/编译接口，不能替代真实 IIS/真机验证。
