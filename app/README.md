# RemoteTool Android

## 独立手机操控 App

`:mobile` 使用 `full`、`phone` 两个构建版本：主 App 包名 `com.chuckiehelper.mobile`，独立“手机操控”包名 `com.chuckiehelper.phonecontrol`，两者可同时安装。`src/main` 维护同一套设备、云账户登录与配置上传/下载、手机操控、TCP/UDP 端口转发、设置、日志、自启动页面及服务；主 App 的聊天、桌面远控、文件管理、终端和 Docker 实现在 `src/full`，不会进入独立版的源码编译。`src/phone` 仅提供精简版入口和图标，专属媒体、Markdown 和图片依赖限定为 `fullImplementation`。两版共用包内 namespace、公共 Manifest、依赖与签名配置，保留各自的包名、版本号和数据。

在 `app` 目录运行 `./build-app.ps1 -Edition full` 或 `./build-app.ps1 -Edition phone`（默认 Release；`-Edition both` 构建两版）。对应 Gradle 任务为 `:mobile:assembleFullRelease`、`:mobile:assemblePhoneRelease`，产物目录分别为 `mobile/build/outputs/apk/full/release/` 和 `mobile/build/outputs/apk/phone/release/`。版本专属导航在各自的 `AppEditionFactory.kt`，公共页面在 `NativeApp.kt`；修改公共功能只需维护一份实现。

与主 App 一样添加设备登录，或通过云账户下载配置，再进入手机操控设置启用转发通道并启动服务；也可仅使用直连。默认直连端口为 `18792`，与主 App 的 `18791` 分开。独立 App 的系统授权、Root/Shizuku 授权、Keystore 和连接身份均独立；关闭主 App 不会主动停止独立操控服务。两版属于同一云应用，使用相同 PKCE 公共客户端；登录随机 state 增加版本前缀，将浏览器回调转发给同签名的正确 App，再由目标 App 校验完整 state、时效及自己的 PKCE verifier，避免同时安装时串号。

手机操控提供顶部 Agent 连接／UI 操作提示，以及请求协助、用户回执工具。操作结束最后调用 `phone_disconnect`，立即撤下当前 Agent 的提示及待处理消息，服务继续待命；`phone_end_ui` 只结束 UI 操作声明。HyperOS 2 焦点通知与 HyperOS 3+ 超级岛除系统开关外，还需小米平台授权及应用鉴权通过，未通过时保留普通通知和悬浮窗。完整工具及权限说明见 [PhoneTools](../tools/PhoneTools/README.md)。

手机操作工具：App 自带独立 HTTP/MCP 服务，支持无障碍、Shizuku UserService、Root 及原生 Android 能力。无需连接电脑即可在“我的设备 → 手机工具”开启；RemoteTool WebSocket 是可选接入层。提供分组系统授权、主动 Root 授权、高级操作开关，以及新建 Codex/Hermes 会话的手机能力选择。使用方法和工具目录见 [PhoneTools](../tools/PhoneTools/README.md)。

0.3.82 修复手机工具重新进入时的状态恢复：直连、按服务器保存的 Web 转发选择、连接配置页和手填地址会保留；运行状态取自本进程的实际服务，不再把磁盘里的旧开关显示为“正在启动”。后台服务仍在时继续运行，进程结束后显示已停止，按已保存配置手动启动；启动时沿用当前连接的登录凭据，不额外持久化 Cookie。

0.3.84 将手机工具设置（接入方式、按服务器保存的 Web 转发开关、地址、端口、配置页及高级操作/Root/Shizuku 偏好）和按设备保存的 TCP/UDP 转发规则加入云账户的上传/下载配置，兼容不包含这些字段的旧快照。实际系统授权、手机/隧道身份、MCP 及配对令牌、Web 绑定凭据、运行状态和日志不上传；恢复不自动启动服务，相关服务运行时需先停止再应用。导入前校验全部规则并备份，保存失败时回滚也覆盖新增配置存储。

“更多 → 端口转发”支持 WS 承载的双向 TCP/UDP 规则，可访问手机或电脑各自网络中的目标；默认只监听本机，按需允许其他设备访问。开启后与手机工具、会话保持共用一条后台通知，支持分别停止。0.3.81 同时修正新建会话的高级操作对齐和网络断开时子协程异常逃逸问题。

安卓项目位于本目录，应用包名为 `com.chuckiehelper.mobile`。Android 8.0（API 26）及以上。0.2.0 起管理页面使用 Kotlin / Jetpack Compose / Material 3，已移除 WebView 页面和 HTML 资源；原生远程模块继续复用。

## 使用

1. 添加设备名称和完整根地址，例如 `http://192.168.1.224:8888`，IPv6 地址需要方括号。
2. App 先读取 `/api/device/identity`。同一个设备可添加多个 LAN、VPN、IPv6 或域名地址，属于其他设备的地址会被拒绝。
3. 启动时恢复上次设备及通道；连接设备时直接使用保存的通道。顶部通道按钮及“更多 → 连接通道”可检测各地址的响应时间或失败原因，再手动切换；检测失败的地址仍显示，设备标识不匹配的地址不能选择。取消检测或切换失败保留原连接。凭据按设备保存，切换同一设备的地址无需重新输入密码。
4. 进入远程页不会自动连接，点击连接按钮后打开原生全屏控制页。
5. 从已连接设备打开“我的设备”会保留原设备、当前页面及文件路径；没有切换设备时，返回会恢复进入前的页面。

旧设备配置直接读取 `MainActivity` 私有 SharedPreferences；登录会话继续使用 Android CookieManager，以兼容旧会话和原生远程模块，不用于渲染网页。身份探测不发送登录 Cookie，设备凭据由 Android Keystore 保护。**更新仅使用覆盖安装，不要卸载或执行 `adb shell pm clear`**，这些操作会删除设备配置。

输入法工具栏的全选、剪切、复制与文本粘贴通过统一输入连接兼容层处理，覆盖原生 Compose 页面及弹窗。剪切在保存选中文本后直接替换选区并结束旧组合输入，避免搜狗等输入法使用模拟剪切按键时未删除内容；密码框保持禁止复制和剪切。

## 功能

### 云账户与配置备份

“我的设备”和“更多”页面提供“云账户”入口，固定连接 `https://api.xbyham.com`。通过手机浏览器打开网站用户名密码登录页，沿用网站 reCAPTCHA 安全验证和授权确认，然后返回 App。原生公共客户端使用 OAuth 授权码、PKCE S256 和随机 state；云账户令牌由独立 Android Keystore 密钥加密保存，与电脑设备登录独立。

不登录仍可使用所有本地设备功能；登录后可手动上传和下载一份云配置快照。同步设备名称、地址、首选连接通道，以及更新偏好、文件排序、远程画质、Codex/Hermes 会话分组设置。电脑密码、设备及云账户令牌、Cookie、会话历史、附件、草稿和消息队列不上传。云端配置由网站提供用户隔离和管理员管理。

上传会替换云端快照，使用版本号防止覆盖其他手机的最新上传；发生冲突后需要刷新并再次确认。下载先显示摘要，经确认后按设备标识合并设备及地址，应用同步设置，保留本机独有设备和登录凭据，不自动连接导入设备。应用前在 App 私有目录保留 `cloud-before-import.json` 旧配置。退出云账户保留本地配置并尝试吊销云端令牌。

网站端使用通用应用数据接口：`/api/app-data/v1/me` 和 `/api/app-data/v1/apps/{appId}/config`。RemoteTool 的 `appId` 为 `remotetool`，OIDC 公共客户端为 `remotetool-android`，授权范围为 `appdata.config`。服务端将配置按“账户 + 应用”隔离，校验客户端与应用绑定关系；新增 App 复用相同的接口和管理页面。网站端需先部署配套接口、数据库安装脚本和客户端注册逻辑，具体见 `wxbackend-netcore` 仓库的接入文档。

### 设备功能

- 多设备、多地址管理、身份校验、连接测速。
- Codex / Hermes 会话列表、续聊、流式回复、Markdown、附件双向收发、工具进度和计时、问题回答、模型与思考程度选择、斜杠命令和可编辑消息队列。Codex 支持项目目录、快速模式、工作空间切换、5 小时／周用量，以及额度重置的查看与确认使用；能力取决于本机 Agent 版本和配置。
- 系统电源操作、进程管理、WebSocket 终端。
- 原生实时性能面板：CPU、RAM、网卡吞吐、硬件温度、风扇/水泵 RPM、逻辑处理器曲线，60 秒 / 5 分钟 / 15 分钟历史与折叠详情。CPU 各核心温度独立展开。
- Docker 容器、镜像拉取和更新检查、Compose 编辑/验证及实时命令日志。
- 文件浏览、上传/下载、复制/移动/重命名/删除、压缩/解压、文本与十六进制编辑。复制/移动/解压使用远程目录选择器；Compose 文件可跳转原生 Compose 管理，项目条目可点击读取配置。
- 图片适屏预览与手势缩放、平移；支持手机解码的视频在线播放及全屏。WMV 等不支持的编码显示兼容提示，仅在点击下载后下载；不做服务端转码。
- 原生 Hangfire：定时任务、统计、队列、服务器、状态分页与详情、历史、重试/删除、手动触发和 Cron 修改，通过授权 API 管理。
- 原生远程桌面：H.264 MediaCodec/TextureView、WebSocket 键鼠、直接触控/触控板、缩放/平移、文字输入、Ctrl/Alt 锁定、自动隐藏工具栏。旧服务或解码失败时回退 MJPEG。

远程桌面和键鼠能力取决于服务端平台，现有服务的这部分主要支持 Windows。

手机界面截图见[项目 README](../README.md#-手机-app)，APK 下载见 [GitHub Releases](https://github.com/bighamx/RemoteTool/releases)。

会话首次加载最近 100 条可显示消息，工具内部记录不占这 100 条；上滑到顶部或点击“加载更早消息”可继续查看。已完成轮次仅保留工具调用次数，正在运行的轮次保留工具明细。在线刷新以服务端原始顺序替换已加载范围，本地快照用于离线查看；运行中的临时旁白和发送状态单独保留。助手在代码块外独立一行输出 `MEDIA:绝对文件路径` 时，服务端会把实际文件加入可下载附件，用户消息和代码示例不会被识别为附件。

Hermes 上游的 500 条限制按原始记录计算，可能大部分都是工具记录。设备服务会继续读取上游分页，直到覆盖所需可显示消息和更早的分页游标，再计算“还有更多”；不会把第一批 500 条误判成完整历史。加载更早消息会保留已加载范围，原始记录按消息 ID 排序去重。

Codex 回复中的 Markdown 文件链接也可作为附件打开或下载，例如 `[说明](E:/project/readme.md)` 和 `[安装包](</path with spaces/app.apk>)`。保留原链接文字，点击链接或附件卡片打开同一预览；支持绝对路径、URL 编码、`file:` 链接以及文件行号后缀，代码块和行内代码不参与识别。已有附件优先复用；未绑定的文件由 App 在点击时通过已登录的文件管理接口核对并读取，不要求升级设备服务。`.md`、`.markdown` 使用现有 Markwon SDK 在 App 内预览，超长内容限制预览长度并保留全文下载。

服务端对 JSON 等文本响应支持 gzip / Brotli，App 自动解压 gzip。超过 1 KB 的 JSON 请求在服务端明确支持后采用 gzip，仅在压缩后更小时启用；兼容未升级的服务端。SSE 实时流和音视频、文件上传维持原有传输方式。

工具调用与文件修改分别计数，点击历史计数或运行中工具入口查看简化详情（名称、简述、状态），每页 50 条。服务端使用有容量和有效期限制的内存缓存；缓存缺失或源记录变化时重新读取，服务重启后按需重建。运行中的详情面板每 2 秒更新，结束后停止轮询；无法确认的结果显示“状态未知”。

工具栏的“键盘”直接打开手机输入法，输入法提交的文字通过 Windows Unicode 输入事件发送；“发送文本”适合一次输入整段文字。输入不经过电脑剪贴板，也不会写入诊断日志。触控板使用相对鼠标移动，点击和滚轮保持电脑当前指针位置。

Windows 桌面代理在专用线程上绑定当前输入桌面，并保留服务身份已有的桌面访问权限。IIS 本身使用 LocalSystem 时，代理复用该身份以支持锁屏登录桌面；程序不会修改账户或桌面访问权限。切换桌面导致视频中断或停止出帧时，App 会自动恢复连接。更新 App 使用 `adb install -r`，保留已保存的设备地址。

## 构建与安装

需要 JDK 17+ 和 Android SDK 34。设置 `ANDROID_HOME`，或在不提交的 `local.properties` 中配置 `sdk.dir`。

发行包使用 Release 构建，启用 R8 代码优化、未使用代码移除和资源裁剪：

```powershell
rtk proxy .\gradlew.bat :mobile:assembleFullRelease :mobile:lintFullRelease --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

主 App 产物位于 `mobile/build/outputs/apk/full/release/`，`tools/Publish-AppUpdate.ps1` 默认根据该目录的 `output-metadata.json` 读取版本化文件名。当前 Release 沿用已安装版本的签名，构建机必须使用同一签名密钥；发布脚本可通过 `-PreviousApkPath` 检查签名一致和版本递增。不要通过卸载 App 来解决签名不一致。

开发调试仍可使用以下命令：

```powershell
.\gradlew.bat :mobile:assembleFullDebug :mobile:assemblePhoneDebug
.\gradlew.bat :mobile:testFullDebugUnitTest :mobile:testPhoneDebugUnitTest
adb connect 192.168.1.219:5555
adb -s 192.168.1.219:5555 install -r .\mobile\build\outputs\apk\debug\mobile-debug.apk
```

App 原生绘制性能面板，Web 使用网页组件，两端共享性能 API。远程原生解码使用 `/api/stream?format=h264`，浏览器保持 MP4 分片流。双指上下滑动产生滚轮，双指张合缩放，放大后单指平移；手势锁定避免误点击。

远程画质支持原画（原始分辨率、20 Mbps、60 fps）、高清（1080p、8 Mbps、60 fps）、均衡（720p、3 Mbps、30 fps）、省流（480p、1 Mbps、24 fps）及低速网络（360p、250 Kbps 目标 / 500 Kbps 上限、10 fps、RGB565 等效量化）。选择会保存，切换时重建视频连接。实时信息每秒统计实际收到的视频字节数及解码输出帧数，不是配置目标值。H.264 仍使用 8 位 YUV420 编码，减少颜色发生在编码前。横屏两侧空白足够宽时工具栏改为可滚动的左右列，窄屏保留自动隐藏的上下工具栏。

工具栏与码率信息同步隐藏；点击画面外空白切换工具栏，点击画面隐藏工具栏并正常执行鼠标点击。远程窗口使用完整横屏区域，不为挖孔预留固定黑边。触控板整帧发送 WebSocket 并启用 TCP_NODELAY；相对位移合并累计且最多一条等待服务端执行确认，避免停手后回放事件队列。旧服务通过已确认的 HTTP 移动接口保持单条在途。

## 服务端

- `tools/RemoteTool.SensorHost` 使用 LibreHardwareMonitor 0.9.6，独立部署到 Web 运行目录的 `sensors` 子目录。只读取温度和转速，不调用风扇控制 API。当前 MSI PRO Z790-P II 已有 PawnIO 驱动，服务复用既有 LocalSystem 身份采集。运行时优先加载 `RemoteTool.SensorHost.dll`，兼容尚未更新的旧名称采集程序。
- 已读到 CPU P/E 核、封装、脚座、MOS、PCH、内存、显卡核心/热点及磁盘温度，CPU/水泵/机箱/显卡风扇 RPM。报警阈值、距 TjMax 余量及分辨率不作为当前温度展示。未标定的传感器保留原始名称，内存 DIMM 编号不猜测为 A2/B2。
- 采集超过 20 秒未更新时隐藏旧读数。诊断快照在 ProgramData/RemoteTool/sensors/latest.json，不含账户凭据；采集程序随父服务退出。
- 新增 `/api/native-jobs` 任务管理 API，均要求登录。
- Docker / Compose 标准输出和错误按 UTF-8 解码，App 只读日志清理 ANSI 控制码。
- 原生终端沿用服务端按行输入协议，支持输出选择复制和 ANSI 文本控制，未引入 PTY，不承诺 vim 等全屏交互程序兼容。
- Kotlin 页面在 `mobile/src/main/java/com/chuckiehelper/mobile/nativeui`。响应正文在 IO 线程读取，JSON 在后台解析，页面保留路径和 Compose 草稿。

- 新增 `/api/device/identity`、`/api/system/performance`、`/api/job/overview`。
- 性能在服务端每约 3 秒采样，在内存中保留 15 分钟。服务重启后重新开始积累。
- Windows 温度中的“系统热区”来自 ACPI，不能当成 CPU 核心温度；GPU 温度由原有 `nvidia-smi` 采集。未提供的传感器不会伪造读数。
- FFmpeg 可通过环境变量 `CHUCKIEHELPER_FFMPEG_PATH` 设置完整路径。IIS 不会继承用户 WinGet PATH，建议配置系统公共路径，避免把大型可执行文件放进 Shadow Copy 源目录。
- IIS 的程序集更新须验证文件校验值及运行接口；Razor 与静态资源须同步到实际站点目录。

当前已发行版本使用既有开发签名，更新须保持一致。签名密钥迁移需单独规划，不能直接更换后覆盖安装。

构建优先使用项目私有的 `signing/RemoteTool-release-signing-debug.keystore`，该目录已被 Git 忽略。也可用 `-PremoteToolSigningStore=完整路径` 或 `REMOTE_TOOL_SIGNING_STORE` 指定相同证书的密钥。2026-10-21 已将原 Downloads 中的密钥移至此目录；发布前仍须与已安装 APK 的证书核对。
