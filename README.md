# RemoteTool

<div align="center">

**基于 .NET 8 的 Web 后台管理与远程控制服务**

[![.NET](https://img.shields.io/badge/.NET-8.0-512BD4?style=flat-square&logo=dotnet)](https://dotnet.microsoft.com/)
[![License](https://img.shields.io/badge/License-SFL%20v1.0-blue?style=flat-square)](https://github.com/bighamx/MIT-NoHuawei)
[![Platform](https://img.shields.io/badge/Platform-Windows-0078D6?style=flat-square&logo=windows)](https://www.microsoft.com/windows)
[![Platform](https://img.shields.io/badge/Platform-Linux-FCC624?style=flat-square&logo=linux)](https://www.linux.org/)

</div>

---

## 🖥️ 系统支持
本项目支持 Windows 和 Linux 操作系统。

配套 **RemoteTool Android App** 支持 Android 8.0 及以上，采用 Kotlin / Jetpack Compose 原生界面，通过本项目的服务端 API 管理电脑。

## ✨ 功能特性
- 🖥️ **系统控制** - 实时监控 CPU、内存、磁盘、显卡等硬件信息，支持锁定、睡眠、休眠、关机等操作
- 📊 **进程管理** - 查看和管理系统进程，支持按类型筛选和终止进程 
- 🎮 **远程控制** - 实时远程桌面控制，支持 H.264 编码，可调节分辨率和画质 <sup>🪟 Windows</sup>
- 💻 **命令终端** - WebSocket 远程终端，支持 PowerShell/CMD <sup>🪟 Windows</sup>，支持Shell <sup>🐧 Linux</sup>
- 🐳 **容器管理** - Docker 容器的启动、停止、删除和日志查看 
- 📝 **Compose 编辑** - Docker Compose 文件的在线编辑、验证和一键部署 
- 📁 **文件管理** - 远程文件浏览、上传、下载和删除 
- ⏰ **任务调度** - Hangfire 的定时任务管理，自行添加任何任务 (内置qBittorrent管理、DDNS 等任务模板)
- 🔐 **安全认证** - JWT 认证保护所有敏感操作 

---

## 📱 手机 App

- **多设备管理**：一个设备可保存多个 LAN、IPv6、VPN 或域名地址，校验设备身份，支持测速、手动切换通道和恢复上次连接。
- **系统监控**：查看 CPU、内存、网络历史曲线及硬件温度、风扇转速；支持折叠卡片、电源操作和进程管理。传感器数据取决于电脑硬件和服务端支持。
- **远程桌面**：H.264 硬件解码、横屏全屏、画质档位、直接触控与触控板、双指滚轮、缩放和平移，以及手机输入法；进入页面后手动连接。远程桌面主要支持 Windows。
- **Codex / Hermes**：浏览、搜索、新建及继续会话，流式回复、Markdown、工具进度、运行计时、图片和文件附件、问题回答、斜杠命令，以及可编辑的消息队列。可选择模型和支持的思考程度；Codex 还支持项目目录、快速模式、个人／团队工作空间切换和用量展示。需要在被管理的电脑配置相应 Agent。
- **更多管理工具**：文件浏览与传输、图片／视频预览、Docker / Compose、命令终端、Hangfire 任务及执行日志，并提供应用更新检查。

从 [GitHub Releases](https://github.com/bighamx/RemoteTool/releases) 下载 APK；安装后添加电脑服务的完整根地址并登录。更新请覆盖安装，以保留设备配置。构建及服务端接入说明见 [app/README.md](app/README.md)。

### 手机界面预览

以下为用户提供的实际界面截图。

<details open>
<summary><b>系统监控与远程桌面</b></summary>

<p>
  <img src="screenshots/android/system.jpg" width="260" alt="手机系统页：电源操作与 CPU、内存历史曲线">
  <img src="screenshots/android/remote.jpg" width="260" alt="手机远程桌面连接页：全屏、触控板与手机输入法">
</p>

</details>

<details open>
<summary><b>Codex 会话列表与对话</b></summary>

<p>
  <img src="screenshots/android/codex-sessions.jpg" width="260" alt="Codex 会话列表与账户用量">
  <img src="screenshots/android/codex-chat.jpg" width="260" alt="Codex 对话：Markdown 消息、上下文用量与附件入口">
</p>

</details>

<details open>
<summary><b>Hermes 会话与更多工具</b></summary>

<p>
  <img src="screenshots/android/hermes-sessions.jpg" width="260" alt="Hermes 会话列表与运行中标记">
  <img src="screenshots/android/more.jpg" width="260" alt="更多页面：文件、Docker、终端、Hangfire、进程与应用更新">
</p>

</details>

---

## 📸 界面预览
<details>
<summary><b>点击展开功能截图</b></summary>

### 系统控制

实时监控系统硬件信息，一键执行电源操作。

![系统控制界面](screenshots/QQ20260209-151205.png)

### 进程管理

查看所有运行中的进程，支持筛选和终止操作。

![进程管理界面](screenshots/QQ20260209-151239.png)

### 远程控制

实时远程桌面，支持 H.264 视频流，低延迟高画质。

![远程控制界面](screenshots/QQ20260209-151344.png)

### 命令终端

WebSocket 远程终端，支持 PowerShell 和 CMD。

![命令终端界面](screenshots/QQ20260209-151405.png)

### 容器管理

管理 Docker 容器的完整生命周期。

![容器管理界面](screenshots/QQ20260209-151413.png)

### Docker Compose

在线编辑和部署 Docker Compose 项目。

![Compose 编辑器](screenshots/QQ20260209-151449.png)

### 文件管理

远程浏览和管理服务器文件。

![文件管理界面](screenshots/QQ20260209-151459.png)

</details>

---

## 🚀 快速开始


### 环境要求
- 🪟 Windows 10/11 或 Windows Server
- 🐧 任何可运行 .NET 8 的 Linux 操作系统
- [.NET 8 SDK/Runtime](https://dotnet.microsoft.com/download/dotnet/8.0)
- （可选，仅Windows）IIS + ASP.NET Core Hosting Bundle<sup>🪟 Windows</sup>

### 安装步骤
1. **克隆仓库**

```bash
git clone https://github.com/bighamx/RemoteTool.git
cd RemoteTool
```

2. **配置应用**

```bash
# 复制示例配置文件
copy WebApplication\appsettings.example.json WebApplication\appsettings.json
```

编辑 `appsettings.json`，配置必要的参数：

```json
{
  "Auth": {
    "Username": "你的用户名",
    "Password": "你的密码",
    "JwtSecret": "至少32位的随机密钥"
  }
}
```

3. **运行应用**

```bash
cd WebApplication
dotnet run
```

4. **访问服务**

- 主页：`http://localhost:5104/`
- Hangfire 面板：`http://localhost:5104/hangfire`

---

## ⚙️ 配置说明
### 基础配置
| 配置项 | 说明 | 示例 |
|--------|------|------|
| `Auth:Username` | 登录用户名 | `admin` |
| `Auth:Password` | 登录密码（必填） | `YourSecurePassword` |
| `Auth:JwtSecret` | JWT 签名密钥（必填，≥32位） | `YourSecretKey...` |

### 可选配置
<details>
<summary><b>qBittorrent 设置</b></summary>

```json
{
  "QbSettings": {
    "DefaultDockerUrl": "http://user:pass@host:port",
    "DefaultHomeUrl": "http://user:pass@host:port"
  }
}
```

</details>

<details>
<summary><b>Cloudflare DDNS 设置</b></summary>

```json
{
  "CloudflareSettings": {
    "ApiToken": "YOUR_API_TOKEN",
    "ZoneId": "YOUR_ZONE_ID",
    "RecordName": "your.domain.com",
    "Proxied": false,
    "Ttl": 1800
  }
}
```

</details>

<details>
<summary><b>远程控制提权代理</b></summary>

用于在 IIS/Session 0 环境下执行需要桌面交互的操作：

```json
{
  "RemoteControl": {
    "ElevatedAgent": {
      "UserName": "管理员用户名",
      "Password": "管理员密码",
      "Domain": "可选，域名"
    }
  }
}
```

或使用环境变量：`REMOTECONTROL_ELEVATEDAGENT_PASSWORD`

</details>

---

## 🖥️ 部署方式
### 方式一：自宿主运行（推荐用于开发/测试）<sup>🪟 Windows/🐧 Linux</sup>
```bash
# 开发模式
dotnet run --project WebApplication/WebApplication.csproj

# 发布后运行
dotnet publish -c Release -o publish
cd publish
ChuckieHelper.WebApi.exe
```

> `ChuckieHelper.WebApi` 是当前服务端项目和程序集的兼容名称，因此项目文件、发布命令及可执行文件仍使用该名称。

### 方式二：IIS 部署（推荐用于生产环境）<sup>🪟 仅Windows</sup>
#### 步骤 1：安装必要组件
1. **启用 IIS**
   - 打开「控制面板」→「程序」→「启用或关闭 Windows 功能」
   - 勾选以下项目：
     - ✅ **Internet Information Services**
     - ✅ **Web 管理工具** → **IIS 管理控制台**
     - ✅ **万维网服务**（保留 Windows 默认启用的角色服务即可）
     - ✅ **万维网服务** → **应用程序开发功能** → **WebSocket 协议**（终端和远程输入功能需要）

   > 本项目是 **.NET 8 / ASP.NET Core** 应用，不依赖传统的 **ASP.NET 4.8**、**.NET Extensibility 4.8** 或 ASP.NET 4.x 托管模块，无需为本项目启用这些组件。也不建议勾选“常见 HTTP 功能”下的全部项目，例如生产环境通常不应启用“目录浏览”。

2. **安装 .NET 8 Hosting Bundle**
   - 下载 [.NET 8 Hosting Bundle](https://dotnet.microsoft.com/download/dotnet/8.0)
   - Hosting Bundle 会安装 .NET 运行时和 IIS 所需的 ASP.NET Core Module（ANCM）
   - 请先启用 IIS，再安装 Hosting Bundle；如果安装顺序相反，请在启用 IIS 后重新运行安装程序并选择“修复”
   - 安装完成后**重启 IIS**（或重启电脑）

#### 步骤 2：发布应用
```bash
dotnet publish WebApplication/WebApplication.csproj -c Release -o C:\inetpub\RemoteTool
```

> 📁 发布后，将你的 `appsettings.json` 复制到 `C:\inetpub\RemoteTool` 目录

#### 步骤 3：创建应用程序池
1. 打开「IIS 管理器」（运行 `inetmgr`）
2. 右键「应用程序池」→「添加应用程序池」
   - **名称**：`RemoteToolPool`
   - **.NET CLR 版本**：`无托管代码`
   - **托管管道模式**：`集成`
3. 点击「确定」创建

#### 步骤 4：配置 LocalSystem 身份（重要！）
> ⚠️ **仅在需要远程控制功能时配置**。LocalSystem 具有最高权限，请谨慎使用。

1. 在「应用程序池」中找到 `RemoteToolPool`
2. 右键 →「高级设置」
3. 找到「进程模型」→「标识」，点击右侧的 `...` 按钮
4. 选择「内置帐户」→ 下拉选择 **LocalSystem**
5. 点击「确定」保存



#### 步骤 5：创建网站
1. 在 IIS 管理器中，右键「网站」→「添加网站」
2. 配置如下：
   - **站点名称**：`RemoteTool`
   - **应用程序池**：选择 `RemoteToolPool`
   - **物理路径**：`C:\inetpub\RemoteTool`
   - **绑定**：
     - 类型：`http`（或 `https`）
     - IP 地址：`全部未分配`
     - 端口：`5104`（或你希望的端口）
3. 点击「确定」创建

#### 步骤 6：验证部署
1. 启动网站（右键网站 →「管理网站」→「启动」）
2. 浏览器访问：`http://localhost:5104/`
3. 使用 `appsettings.json` 中配置的账号密码登录

#### 常见问题排查
| 问题 | 解决方案 |
|------|----------|
| 502.5 错误 | 检查 Hosting Bundle 是否正确安装，尝试重启 IIS |
| 500 错误 | 检查 `appsettings.json` 是否存在且格式正确 |
| 权限不足 | 确认应用程序池使用 LocalSystem 身份运行 |
| 端口被占用 | 更换端口或停止占用端口的服务 |

> ⚠️ **安全提示**：`appsettings.json` 包含敏感信息，请勿提交到公开仓库！

---

## 🔧 桌面代理 <sup>🪟 仅Windows</sup>
在 IIS/Session 0 环境下，远程控制功能需要桌面代理的支持才能和用户桌面进行交互。

桌面代理进程会被自动创建，创建桌面代理进程时，优先使用**复制已有管理员令牌**的方式，若无则依赖**计划任务**：

1. **复制已有管理员令牌**：在当前交互会话中查找已存在的高完整性进程（如用户曾以管理员运行过任务管理器等），复制其令牌并用于启动代理，无需配置密码，即时生效。
2. **计划任务**：若未找到高完整性令牌，且已配置 `RemoteControl:ElevatedAgent`（管理员用户名与密码），应用启动时会自动创建“登录时以最高权限运行”的计划任务，用户**注销并重新登录**后，桌面代理将以管理员身份自动启动。
3. 代理通过命名管道与 WebApi 通信，执行键鼠输入等桌面操作。


手动运行桌面代理模式：
```bash
dotnet WebApplication.dll --desktop-agent
```

> ⚠️ 桌面代理仅支持 Windows，Linux 无需此功能。

---

## ❓ 常见问题
<details>
<summary><b>Q: 访问出现 500/502 错误？</b></summary>

- 检查 `appsettings.json` 是否存在且格式正确
- 确认已安装 .NET 8 Hosting Bundle
- 查看 Windows 事件查看器中的错误日志

</details>

<details>
<summary><b>Q: Hangfire 面板无法访问？</b></summary>

- 确认已使用正确的账号密码登录
- 检查浏览器是否阻止了 Cookie

</details>

<details>
<summary><b>Q: 远程控制功能无法使用？</b></summary>

- 确认应用以管理员权限运行
- 在 IIS 部署时，检查应用池是否配置为 `LocalSystem`
- 验证 `RemoteControl:ElevatedAgent` 配置是否正确

</details>

---

## 📝 开源协议
本项目采用 [Selective Freedom License (SFL) v1.0](https://github.com/bighamx/MIT-NoHuawei) 授权，详见 [LICENSE](LICENSE)。

---

## 🙏 致谢
- [ASP.NET Core](https://github.com/dotnet/aspnetcore)
- [Hangfire](https://github.com/HangfireIO/Hangfire)
