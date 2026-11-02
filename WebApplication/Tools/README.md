# Web 服务端工具

仅供服务端使用的工具与服务端源码放在一起：

| 路径 | 用途 |
|---|---|
| `HermesManagement/` | Web 调用的 Hermes 管理辅助脚本；发布到运行目录 `hermes/` |
| `ServerUpdater/` | 独立更新/重编译执行器，单独构建，不编进 Web 程序集 |
| `RemoteTool.SensorHost/` | Windows 传感器采集程序，单独构建后部署到 `sensors/` |
| `RemoteTool.MacAgent/` | macOS 桌面、权限和 PTY 辅助程序 |
| `Install-ServerUpdater.ps1` / `Activate-ServerUpdater.ps1` | 一次性安装更新器与正式部署迁移 |
| `Publish-ServerUpdate.ps1` | GitHub 服务端发行打包与 SHA256 清单 |
| `Deploy-Linux.ps1` / `Linux-Deployment.md` | Linux 部署 |
| `configure-macos.py` | macOS 私有配置初始化 |
| `Repair-HermesGatewayTask.ps1` | 修正 Hermes gateway 的交互式计划任务 |
| `Set-IisAlwaysRunning.ps1` | IIS 常驻配置 |

服务端更新说明见 [SERVER-UPDATES.md](../SERVER-UPDATES.md)。文档中的命令从仓库根目录执行。

仓库根目录 `tools/` 继续保留 Android 构建、App 发布，以及不依赖 Web 的手机直连 MCP/CLI 工具。Web 打包仍引用其中的 PhoneTools，但它不是 Web 专属组件。
