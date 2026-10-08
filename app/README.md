# RemoteTool Android

安卓项目位于本目录，应用包名为 `com.chuckiehelper.mobile`。Android 8.0（API 26）及以上。0.2.0 起管理页面使用 Kotlin / Jetpack Compose / Material 3，已移除 WebView 页面和 HTML 资源；原生远程模块继续复用。

## 使用

1. 添加设备名称和完整根地址，例如 `http://192.168.1.224:8888`，IPv6 地址需要方括号。
2. App 先读取 `/api/device/identity`。同一个设备可添加多个 LAN、VPN、IPv6 或域名地址，属于其他设备的地址会被拒绝。
3. 启动时恢复上次设备及通道；连接设备时直接使用保存的通道。顶部通道按钮及“更多 → 连接通道”可检测各地址的响应时间或失败原因，再手动切换；检测失败的地址仍显示，设备标识不匹配的地址不能选择。取消检测或切换失败保留原连接。凭据按设备保存，切换同一设备的地址无需重新输入密码。
4. 进入远程页不会自动连接，点击连接按钮后打开原生全屏控制页。
5. 从已连接设备打开“我的设备”会保留原设备、当前页面及文件路径；没有切换设备时，返回会恢复进入前的页面。

旧设备配置直接读取 `MainActivity` 私有 SharedPreferences；登录会话继续使用 Android CookieManager，以兼容旧会话和原生远程模块，不用于渲染网页。身份探测不发送登录 Cookie，设备凭据由 Android Keystore 保护。**更新仅使用覆盖安装，不要卸载或执行 `adb shell pm clear`**，这些操作会删除设备配置。

## 功能

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

工具栏的“键盘”直接打开手机输入法，输入法提交的文字通过 Windows Unicode 输入事件发送；“发送文本”适合一次输入整段文字。输入不经过电脑剪贴板，也不会写入诊断日志。触控板使用相对鼠标移动，点击和滚轮保持电脑当前指针位置。

Windows 桌面代理在专用线程上绑定当前输入桌面，并保留服务身份已有的桌面访问权限。IIS 本身使用 LocalSystem 时，代理复用该身份以支持锁屏登录桌面；程序不会修改账户或桌面访问权限。切换桌面导致视频中断或停止出帧时，App 会自动恢复连接。更新 App 使用 `adb install -r`，保留已保存的设备地址。

## 构建与安装

需要 JDK 17+ 和 Android SDK 34。设置 `ANDROID_HOME`，或在不提交的 `local.properties` 中配置 `sdk.dir`。

```powershell
.\gradlew.bat :mobile:assembleDebug
.\gradlew.bat :mobile:testDebugUnitTest
adb connect 192.168.1.219:5555
adb -s 192.168.1.219:5555 install -r .\mobile\build\outputs\apk\debug\mobile-debug.apk
```

App 原生绘制性能面板，Web 使用网页组件，两端共享性能 API。远程原生解码使用 `/api/stream?format=h264`，浏览器保持 MP4 分片流。双指上下滑动产生滚轮，双指张合缩放，放大后单指平移；手势锁定避免误点击。

远程画质支持原画（原始分辨率、20 Mbps、60 fps）、高清（1080p、8 Mbps、60 fps）、均衡（720p、3 Mbps、30 fps）、省流（480p、1 Mbps、24 fps）及低速网络（360p、250 Kbps 目标 / 500 Kbps 上限、10 fps、RGB565 等效量化）。选择会保存，切换时重建视频连接。实时信息每秒统计实际收到的视频字节数及解码输出帧数，不是配置目标值。H.264 仍使用 8 位 YUV420 编码，减少颜色发生在编码前。横屏两侧空白足够宽时工具栏改为可滚动的左右列，窄屏保留自动隐藏的上下工具栏。

工具栏与码率信息同步隐藏；点击画面外空白切换工具栏，点击画面隐藏工具栏并正常执行鼠标点击。远程窗口使用完整横屏区域，不为挖孔预留固定黑边。触控板整帧发送 WebSocket 并启用 TCP_NODELAY；相对位移合并累计且最多一条等待服务端执行确认，避免停手后回放事件队列。旧服务通过已确认的 HTTP 移动接口保持单条在途。

## 服务端

- `tools/ChuckieHelper.SensorHost` 使用 LibreHardwareMonitor 0.9.6，独立部署到 Web 运行目录的 `sensors` 子目录。只读取温度和转速，不调用风扇控制 API。当前 MSI PRO Z790-P II 已有 PawnIO 驱动，服务复用既有 LocalSystem 身份采集。
- 已读到 CPU P/E 核、封装、脚座、MOS、PCH、内存、显卡核心/热点及磁盘温度，CPU/水泵/机箱/显卡风扇 RPM。报警阈值、距 TjMax 余量及分辨率不作为当前温度展示。未标定的传感器保留原始名称，内存 DIMM 编号不猜测为 A2/B2。
- 采集超过 20 秒未更新时隐藏旧读数。诊断快照在 ProgramData/ChuckieHelper/sensors/latest.json，不含账户凭据；采集程序随父服务退出。
- 新增 `/api/native-jobs` 任务管理 API，均要求登录。
- Docker / Compose 标准输出和错误按 UTF-8 解码，App 只读日志清理 ANSI 控制码。
- 原生终端沿用服务端按行输入协议，支持输出选择复制和 ANSI 文本控制，未引入 PTY，不承诺 vim 等全屏交互程序兼容。
- Kotlin 页面在 `mobile/src/main/java/com/chuckiehelper/mobile/nativeui`。响应正文在 IO 线程读取，JSON 在后台解析，页面保留路径和 Compose 草稿。

- 新增 `/api/device/identity`、`/api/system/performance`、`/api/job/overview`。
- 性能在服务端每约 3 秒采样，在内存中保留 15 分钟。服务重启后重新开始积累。
- Windows 温度中的“系统热区”来自 ACPI，不能当成 CPU 核心温度；GPU 温度由原有 `nvidia-smi` 采集。未提供的传感器不会伪造读数。
- FFmpeg 可通过环境变量 `CHUCKIEHELPER_FFMPEG_PATH` 设置完整路径。IIS 不会继承用户 WinGet PATH，建议配置系统公共路径，避免把大型可执行文件放进 Shadow Copy 源目录。
- IIS 的程序集更新须验证文件校验值及运行接口；Razor 与静态资源须同步到实际站点目录。

调试 APK 为开发签名。正式发布时需另行配置自己的稳定签名密钥。
