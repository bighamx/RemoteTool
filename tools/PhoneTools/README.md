# RemoteTool 手机工具

Android App 内置独立 HTTP / MCP 服务，不需要 RemoteTool Web、电脑登录或云账户。WebSocket 是可单独启用的另一条接入通道，两条路径共用手机执行引擎。

## 手机端

1. 覆盖安装 APK，打开“我的设备 → 手机工具”，或已连接设备的“更多 → 手机工具”。0.3.80 起为独立页面，顶部启停服务，卡片区分接入方式、连接配置、操作权限和系统授权；授权项目按组折叠。
2. 开启需要的无障碍、Shizuku 或 Root 后端。Shizuku 需先在其管理器启动并授权 RemoteTool；“请求 Root 授权”会调用 `su -c id` 并检查 UID 0。
3. 系统权限按组申请；通知读取、所有文件访问、悬浮窗、修改设置等打开对应系统授权页。Manifest 声明相关能力所需权限，实际能力仍取决于系统授予状态。
4. “独立 MCP 服务”开启后点击“启动”，默认端口 18791。使用手机可达的 LAN/VPN 地址；可编辑地址并复制连接配置。仅本机/USB 调试可使用 `adb forward tcp:18791 tcp:18791`。
5. 高级操作由一个开关控制，默认关闭。通知栏或手机工具页可停止控制。服务按用户主动启动运行，不在后台静默自动恢复。

HTTP 接入使用 `Authorization: Bearer <手机工具令牌>`，令牌可在 App 重置。直接访问不需要 Web 的登录令牌。手机没有公网入站地址时，可使用 VPN、端口转发或可选的 RemoteTool 通道。

## 通用 Agent / Hermes 直接 MCP

支持 Streamable HTTP 的客户端可使用 App 复制的配置，常见形式如下，具体配置入口按客户端要求：

```json
{
  "mcpServers": {
    "remotetool-phone": {
      "url": "http://192.168.1.100:18791/mcp",
      "headers": { "Authorization": "Bearer <手机工具令牌>" }
    }
  }
}
```

只支持 stdio 的客户端使用本目录 `remotetool_phone.py`，Python 3.10+，不需要安装额外包：

```json
{
  "mcpServers": {
    "remotetool-phone": {
      "command": "python",
      "args": ["E:/tools/PhoneTools/remotetool_phone.py", "mcp"],
      "env": {
        "REMOTETOOL_PHONE_URL": "http://192.168.1.100:18791",
        "REMOTETOOL_PHONE_TOKEN": "<手机工具令牌>"
      }
    }
  }
}
```

替换地址、令牌和脚本绝对路径。独立 Hermes 通过其 MCP 设置配置同一 HTTP/stdio 服务即可，无须安装下面的 RemoteTool 会话适配器。

明确支持 MCP 协议版本 2025-11-25、2025-06-18、2025-03-26。实现 stateless POST/JSON Streamable HTTP；GET 返回 405，不提供旧版 `/sse`。有 Origin 的浏览器请求拒绝，面向原生 Agent 客户端。没有声称实现 2026 规范的全部新语义。

## CLI

在环境变量设置 `REMOTETOOL_PHONE_URL` 和 `REMOTETOOL_PHONE_TOKEN` 后：

```text
python remotetool_phone.py tools
python remotetool_phone.py observe --screenshot
python remotetool_phone.py call phone_get_capabilities
python remotetool_phone.py call phone_shell --args "<包含 command 和可选 backend 的 JSON>"
python remotetool_phone.py upload application.apk
python remotetool_phone.py download application.apk ./application.apk
```

`call` 可传 `--request-id`，同 ID/参数用于去重，不能将同一个 ID 用于新的操作。结果中包含请求 ID；网络失败不会自动重发。上传和下载按 512 KiB 分块，适合较大 APK。

## 能力范围

28 个工具入口（schema version 2）：

每个工具均可选传入 `explanation`（字符串，最多 240 字符），用用户的语言说明本次操作和目的，例如 `phone_tap({"x":120,"y":300,"explanation":"打开网络设置，检查当前连接"})`。手机会在当前操作下方显示说明，同时更新后台状态通知，无需另发消息；不传时沿用原来的操作提示。说明随调用结束短暂保留后清理，`phone_disconnect` 立即撤下所有本 Agent 提示。待处理的用户协助卡片优先显示，不会被操作说明覆盖。说明仅用于展示，不传给底层命令、输入框或其他执行后端；同一 request_id 只改变说明不会重复执行操作。请求用户回应仍使用 `phone_notify_user(kind=help)`，说明文本不含密码、令牌或内部推理。旧版客户端工具定义没有此参数时不要传入。

- 设备/观察：`phone_list_devices`、`phone_get_capabilities`、`phone_observe`、`phone_find_nodes`、`phone_wait`、`phone_screenshot`。
- 界面：`phone_tap`、`phone_swipe`、`phone_input_text`、`phone_key`、`phone_ui_action`、`phone_gesture`。
- 应用/文件：`phone_launch_app`、`phone_list_apps`、`phone_transfer_file`、`phone_manage_app`、`phone_files`。
- 系统：`phone_shell`、`phone_system_setting`、`phone_android`、`phone_intent`、`phone_content`、`phone_notifications`。
- 提示与协助：`phone_begin_ui`、`phone_end_ui`、`phone_disconnect`、`phone_notify_user`、`phone_get_user_reply`。

## Agent 状态与用户协助

执行一组 UI 操作前调用 `phone_begin_ui`（可带 `message`、`duration_seconds`），结束后调用 `phone_end_ui`。所有操作与核验完成后，最后调用 `phone_disconnect`，立即撤下本 Agent 的连接／操作提示并取消其待处理协助消息；其他 Agent、手机操控服务和授权凭据保持有效。之后的新工具调用或 `initialize` 会重新连接，`ping`、工具发现和回执查询 REST 接口不会续期。UI 观察、截图、点击、输入、按键及页面操作会自动显示当前操作提示。先开启系统悬浮窗权限，即可在屏幕顶部看到小提示；未开启时仍更新后台通知。

`phone_notify_user` 接收 `message`（最多 1000 字）、`kind`（`info` 或 `help`）、`timeout_seconds`，返回 `notice_id`。帮助请求显示“已完成／暂不处理”，Agent 用 `phone_get_user_reply(notice_id)` 读取 `pending/done/dismissed/expired/cancelled/not_found`；不能将等待、超时或断线当作已完成。回执按 Agent 绑定隔离，仅存在本次操控进程，重新启动进程后需核对，不会自动重发消息。

HTTP MCP 为无状态接入，连接提示使用最近活动的 120 秒租约；UI 批次声明最长 600 秒，Agent 应在到期前续期。撤销绑定或 WebSocket 断开会移除对应连接提示。协助请求默认等待 600 秒，普通提示默认显示 15 秒。

HyperOS 使用小米官方 `miui.focus.param` 客户端接口、系统 `notification_focus_protocol` 版本查询与 `canShowFocus` 开关查询：OS2 对应焦点通知，协议 3 及以上对应超级岛。`canShowFocus=true` 仅表示系统开关开启，不证明应用鉴权或上岛成功；系统日志 `FocusPlugin: onAuthFailed` 表示焦点通知鉴权失败。两个包必须分别在小米平台开通服务、配置 App ID 与签名指纹，并完成场景审核，或使用官方设备白名单联调。是否上岛仍取决于平台授权、用户通知设置及系统实际渲染；未通过时保留普通通知和悬浮窗。参考[官方接入流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)及[开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)。

小米平台分配 App ID 后，主 App 构建传入 `-PmobileXiaomiAppId=实际ID`，独立端传入 `-PphonecontrolXiaomiAppId=实际ID`，分别写入 `com.xiaomi.xms.APP_ID`。使用平台登记的测试证书时，对应设置 `-PmobileXiaomiDebug=true` 或 `-PphonecontrolXiaomiDebug=true`；默认 false，必须与平台登记的证书类型一致，而非仅依据 Gradle 构建类型。未提供 ID 时保持空值，不填入其他应用的身份。

`phone_android` 提供设备、电量、授权状态、剪贴板、音量、响铃、亮度、手电筒、振动、缓存定位、传感器目录、网络、蓝牙、短信提交和唤醒。`phone_content` 通过系统 ContentResolver 查询/修改联系人、日历、短信、通话记录、媒体等已授权内容提供器。`phone_intent` 可调用应用组件、系统设置、拨号/电话、相机、录音、录像、闹钟、分享等系统入口；这些 UI 流程可以继续用无障碍操作。

`phone_shell` 是通用特权出口，可调用设备实际支持的 `input`、`am`、`pm`、`cmd`、`settings`、`dumpsys`、文件及系统命令，不局限于预置 action；明确区分 Shizuku 和 Root 后端。无障碍截图需要 Android 11+，另有 Shizuku/Root 截图入口。UI 节点读取与 Unicode 节点输入使用无障碍，节点带 30 秒有效快照。

能力受系统授权、目标 App、SELinux 和运行身份限制；不会把声明权限当作已授权。SMS 工具仅报告已提交，无法凭返回值证明送达；点击/Intent 也需重新观察确认业务结果。

## 可选 RemoteTool Web 通道和会话集成

配套 Web 服务部署后，手机工具页勾选“连接当前 RemoteTool 通道”并启动，手机主动建立 `/ws/phone-agent`。可关闭独立监听而仅使用此通道，也可两者并行。

其他电脑上的 Agent 无需与手机处于同一局域网：手机能访问 Web 服务，Agent 也能访问该 Web 服务即可。点击“复制 Web MCP 配置”会创建不依赖聊天会话的连接，生成 `/api/phone/v1/bound/{id}/mcp` 地址及独立工具令牌；`id` 是连接标识，关联目标手机和高级操作范围，不是手填的手机 ID。通过“撤销 Web MCP 连接”使其令牌立即失效。修改连接操作范围时会先撤销旧连接，再生成新配置。

管理 API：`POST /api/phone/v1/connections` 接收 `{phone_id, allow_advanced}` 并返回 `{id, phone_id, token, mcp_url}`；`GET /api/phone/v1/connections` 查看已有外部连接；`DELETE /api/phone/v1/bindings/{id}` 撤销。管理接口使用现有 Web 登录，Agent 的 MCP 调用仅携带这条连接的工具令牌。

App 在 Codex/Hermes 新建和分享创建中提供“关闭 / 注入说明 / 启用工具”，选择网关在线手机和高级操作。描述模式仅注入说明；工具模式绑定真实工具。手机没连接该通道时不会伪装为工具已启用。

Codex 使用 app-server 动态工具及图片结果回传，绑定保存在原桥接受保护状态目录，恢复时由 app-server 还原工具元数据。桌面接管需要另外配置 MCP，不保证桌面客户端继承手机 handler。

Hermes 工具模式在当前 Profile 中安装本目录 `hermes_plugin`，使用 Hermes 自身 enable/reload-plugins 接口加载，不改 Hermes 源码、不重启网关；旧版缺少接口时返回插件未就绪。Windows IIS LocalSystem 会沿用现有交互用户启动机制，以桌面用户身份执行安装检查，避免网关管道的用户 SID 校验失败；工具就绪以网关实际确认完整工具注册为准，不依赖旧标记文件或 stdout 文本。插件从运行时取得真实 session ID，再读取该 Profile 的 `remotetool-phone-bindings.json`；未绑定的会话拒绝调用。已加载的 Profile 工具目录中可能仍显示手机工具，调用权限由绑定检查落实。

新建会话的能力提示词会说明可直接操作绑定的 Android 手机、通过服务端转发的连接关系、按本次绑定实际工具生成的能力分组、当前高级工具范围和手机端授权条件，并引导 Agent 先检查能力与观察界面、按工具参数操作、核验结果以及处理离线和不确定回执。Hermes 追加到会话 system_prompt，Codex 使用 developerInstructions；工具参数定义仍单独注册。“注入说明”会明确当前没有启用可调用工具，提示词不包含内部接口地址或令牌。新提示词适用于更新后新建的会话，已有会话保持原提示词。

可选 `PhoneTools:CallbackBaseUrl` 指定 Agent 运行环境能访问的 Web 根地址，例如 `http://127.0.0.1:8888`；未配置时使用创建请求的站点根地址。不要使用手机地址填这个回调配置。服务端 `phone-tools/bindings.json` 只保留令牌哈希，实际调用凭据保存在各 Agent 私有状态中。删除会话会撤销服务端绑定；用户可 `DELETE /api/phone/v1/bindings/{id}` 单独撤销。

IIS 部署需避免 WebDAV handler/module 拦截 DELETE；项目 web.config 已显式移除本站点的 WebDAV。手机工具传输使用 HTTP/MCP 与 WS，不使用 WebDAV。Codex 桥接版本 26 使用 state-v11，并通过任务交接保留旧桥接的执行中任务。

## 开发验证

工具 Schema 权威定义在 Android `PhoneToolCatalog.kt`；Android 测试导出 `app/mobile/build/phone-tools-catalog.json`，更新后复制到本目录 `hermes_plugin/tools.json`，用于 Hermes 适配器。发布时需保持两者一致。

```text
app/gradlew.bat :mobile:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
dotnet run --project tests/PhoneTools/PhoneTools.csproj
python -m unittest discover -s tools/PhoneTools/tests -v
python tools/PhoneTools/tests/check_codex_protocol.py <实际使用的codex.exe>
```

已验证独立 HTTP 服务、去重与冲突、重启未知状态、真实 WS 转发、绑定隔离及撤销、外部连接 API、CLI/stdio、Hermes 图片/会话处理、实际 Codex 进程接受工具定义。使用已安装的 MCP Python 客户端完成了 Web MCP 初始化、发现、调用并经 WS 获得模拟手机执行端结果；真实 Hermes discovery/registry 在隔离 Home 加载了插件并拒绝未绑定会话。真机后端授权、厂商限制、锁屏和后台在线效果需要在在线手机上验证。
