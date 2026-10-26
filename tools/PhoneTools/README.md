# RemoteTool 手机工具

## 高效操作与恢复（工具 Schema v3）

新增 phone_execute 和 phone_get_operation，总目录为 30 个工具；以连接设备实际公布的工具为准。观察默认读取应用窗口的精简节点，可切换 text/raw、服务端过滤、字段投影及快照差量。屏幕与截图使用统一物理坐标，非法矩形不可点击，目标歧义或被完全遮挡时返回明确错误。

普通动作可携带 selector、guard、wait_for、return_state，一次完成定位、操作、条件等待及回读。phone_execute 最多串行执行 20 步、预算最多 30 秒，先校验全部参数，任一步失败或条件超时即停止；不支持任意脚本和递归批次。回执按操作者隔离，并保留已执行步骤；查询或核验后决定下一步，不把部分批次换新 ID 重发。

MCP 传输会话可通过服务端签发的私密恢复凭据恢复同一操作者，旧传输被拒绝；正在执行的调用阻止接管，删除会话撤销凭据。标准客户端照常使用；项目自带 CLI 会自动初始化，并可用 --agent 指定隔离的稳定聊天标识，实现跨进程复用。用户手动断开不会被恢复机制绕过。

参数、数据契约、恢复限制及端到端示例统一见 [Agent 使用指南](AGENT_GUIDE.md)。

手机端、服务端及配套脚本统一按工具 Schema v3 使用，不保留旧服务执行分支。Hermes 的高层操作脚本和说明维护在 [hermes_skill](hermes_skill/SKILL.md)，可安装到当前 Profile 的 skills/automation/android-phone-ui-automation；不需要修改 Hermes 核心或重启网关。优先使用当前聊天已配置的工具，仅在需要 Python 编排时使用这些脚本。

安装时复制 hermes_skill 内的 SKILL.md、scripts 和 references 到目标技能目录，保留 Profile 现有的 MCP 端点、令牌与绑定配置。设置 HERMES_HOME 指向当前 Profile，以及独立且稳定的 PHONE_MCP_AGENT（或实际 HERMES_SESSION_ID）。Python 回归执行 python -m unittest discover -s tools/PhoneTools/tests -v；可用 REMOTETOOL_HERMES_SKILL 指向已安装目录验证，测试只访问本机模拟 HTTP 服务，不会操控手机或发送消息。

Android App 内置独立 HTTP / MCP 服务，不需要 RemoteTool Web、电脑登录或云账户。WebSocket 是可单独启用的另一条接入通道，两条路径共用手机执行引擎。

## 手机端

无障碍提供独立开关：用户开启但服务未运行时，使用已授权的 Shizuku 或 Root 恢复本 App 的无障碍服务；关闭时只停用本 App，保留其他服务列表。无特权可用时显示系统设置入口和恢复结果。通知读取及特殊权限显示实际授权状态；进入页面、从系统设置返回、App 内开关操作完成时刷新。“转到应用设置界面”带应用设置图标，是系统设置跳转入口。

1. 覆盖安装 APK，打开“我的设备 → 手机工具”，或已连接设备的“更多 → 手机工具”。0.3.80 起为独立页面，顶部启停服务，卡片区分接入方式、连接配置、操作权限和系统授权；授权项目按组折叠。
2. 开启需要的无障碍、Shizuku 或 Root 后端。Shizuku 需先在其管理器启动并授权 RemoteTool；“请求 Root 授权”会调用 `su -c id` 并检查 UID 0。
3. 系统权限按组申请；通知读取、所有文件访问、悬浮窗、修改设置等打开对应系统授权页。Manifest 声明相关能力所需权限，实际能力仍取决于系统授予状态。
4. “独立 MCP 服务”开启后点击“启动”，默认端口 18791。使用手机可达的 LAN/VPN 地址；可编辑地址并复制连接配置。仅本机/USB 调试可使用 `adb forward tcp:18791 tcp:18791`。
5. 高级操作由一个开关控制，默认关闭。通知栏或手机工具页可停止控制。可选“自动恢复”只在 App 被唤醒且此前未手动停止时恢复，不主动唤醒 App，与开机自启分别设置。

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

明确支持 MCP 协议版本 2025-11-25、2025-06-18、2025-03-26。实现带客户端身份的 POST/JSON Streamable HTTP；GET 返回 405，不提供旧版 `/sse`。initialize 返回 Mcp-Session-Id，后续请求须携带该头；同一绑定 URL 下的各客户端独立显示名称、协助通知和 UI 占用。DELETE /mcp 仅断开当前客户端。旧版无会话头的调用保留为单个匿名 Agent。有 Origin 的浏览器请求拒绝，面向原生 Agent 客户端。

## Agent 必须遵循的连接使用方式

**同一 Agent 的一个持续任务复用一个 MCP 会话。不要每次调用都 initialize，也不要每一批 UI 操作都重建连接。** 优先使用已配置的 MCP 工具或 RemoteTool 注入的 `phone_*` 工具；它们已有身份，不要另写临时 HTTP 脚本再建一个客户端。可将 [Agent 使用指南](AGENT_GUIDE.md) 提供给使用自定义脚本的 Agent。

- HTTP/TCP 可以短连接；需要持续复用的是 MCP 逻辑会话。第一次 `initialize` 保存**响应头** `Mcp-Session-Id` 和结果里的 `protocolVersion`。后续 `tools/list`、`tools/call`、`ping`、`DELETE` 都传同一个 `Mcp-Session-Id` 及 `MCP-Protocol-Version`。同名 `clientInfo.name` 只是展示名称，不会合并会话。
- 自定义脚本若每次启动新进程，必须跨进程保存会话。缓存按“完整 MCP 端点 + 凭据身份 + 逻辑 Agent”隔离，并对首次初始化和缓存更新加锁，避免并发创建多个会话；不要把令牌拼进文件名或打印出来。不同 Agent 不共享一个会话。无法正确管理缓存时，使用常驻 MCP 客户端或本项目长驻 stdio 模式。
- 顺序执行 `phone_get_capabilities → phone_begin_ui → observe → 操作 → 核验 → phone_end_ui`，后续 UI 批次仍用同一会话。文件及其他非 UI 工作可继续；等待用户手动操作时释放 UI，保留连接和 `notice_id`，恢复后重新获取 UI 并观察。
- UI 占用、节点快照和协助回执都属于会话。在会话 A 开始 UI 后，不能从新建的 B 操作或结束 A。`ui_busy/device_busy` 应等待或说明冲突；不得用新连接绕过。只释放自己持有的 UI。
- 整个任务结束、受阻或放弃时，在清理流程中释放 UI，最后调用 `phone_disconnect`；不要每步断开。自定义 HTTP 客户端可再 `DELETE <原 MCP URL>`，携带自己的会话头，销毁传输会话。`phone_disconnect` 结束手机活动，`DELETE` 结束 MCP 会话；关闭脚本或 TCP 连接不等同于这两者。清理后不要再截图“检查断开”。

错误处理必须区分：只有 HTTP 404 `session_not_found` 才保留原恢复凭据、串行恢复同一操作者；恢复后核对回执和当前状态，再决定后续动作。不要丢弃密钥静默创建另一身份。`connection_closed_by_user` 表示用户主动断开，必须停止并告知用户，得到继续授权后再建新会话。`delivery_unknown`、超时、响应丢失可能发生在操作已执行之后，禁止自动重发写操作或换 `request_id` 绕过去重；先检查真实状态或回执。`request_id` 是逻辑操作的去重标识，与会话 ID、JSON-RPC `id` 不同。

当前兼容边界：无会话头仍走旧匿名身份，不代表推荐用法；不要与已初始化会话混用。服务端空闲回收目前只移除 MCP 会话，手机提示和历史连接按手机自身空闲规则清理，并非即时同步。相同名称不自动顶替旧连接，以支持多个同名真实 Agent。手机端手动断开和 Agent 的 `phone_disconnect` 语义不同，前者禁止旧身份继续使用。历史日志不会因断开自动删除；已结束记录按保留策略清理，仍活跃的旧身份需在连接页单独断开。

## CLI

在环境变量设置 REMOTETOOL_PHONE_URL、REMOTETOOL_PHONE_TOKEN 和独立的 REMOTETOOL_PHONE_AGENT（稳定任务/聊天标识）后：

```text
python remotetool_phone.py tools
python remotetool_phone.py observe --screenshot
python remotetool_phone.py call phone_get_capabilities
python remotetool_phone.py call phone_shell --args "<包含 command 和可选 backend 的 JSON>"
python remotetool_phone.py upload application.apk
python remotetool_phone.py download application.apk ./application.apk
```

`call` 可传 `--request-id`，同 ID/参数用于去重，不能将同一个 ID 用于新的操作。结果中包含请求 ID；网络失败不会自动重发。上传和下载按 512 KiB 分块，适合较大 APK。

CLI 会自动初始化，并使用按端点、凭据身份和逻辑 Agent 隔离的受保护缓存跨进程复用会话。--agent 可覆盖 REMOTETOOL_PHONE_AGENT；首次初始化有进程锁，只有明确的 session_not_found 才尝试恢复，其他错误不自动重试。Python 集成可持有一个 PhoneClient，call() 自动完成首次连接；常驻 stdio 仍可使用 mcp 子命令。不要让不同 Agent 使用同一逻辑标识，结束任务时清理自己的 UI 并断开，不能仅退出 Python 进程。

## 能力范围

28 个工具入口（schema version 2）：

每个工具均可选传入 `explanation`（字符串，最多 240 字符），用用户的语言说明本次操作和目的，例如 `phone_tap({"x":120,"y":300,"explanation":"打开网络设置，检查当前连接"})`。手机会在当前操作下方显示说明，同时更新后台状态通知，无需另发消息；不传时沿用原来的操作提示。说明随调用结束短暂保留后清理，`phone_disconnect` 立即撤下所有本 Agent 提示。待处理的用户协助卡片优先显示，不会被操作说明覆盖。说明仅用于展示，不传给底层命令、输入框或其他执行后端；同一 request_id 只改变说明不会重复执行操作。请求用户回应仍使用 `phone_notify_user(kind=help)`，说明文本不含密码、令牌或内部推理。旧版客户端工具定义没有此参数时不要传入。

- 设备/观察：`phone_list_devices`、`phone_get_capabilities`、`phone_observe`、`phone_find_nodes`、`phone_wait`、`phone_screenshot`。
- 界面：`phone_tap`、`phone_swipe`、`phone_input_text`、`phone_key`、`phone_ui_action`、`phone_gesture`。
- 应用/文件：`phone_launch_app`、`phone_list_apps`、`phone_transfer_file`、`phone_manage_app`、`phone_files`。
- 系统：`phone_shell`、`phone_system_setting`、`phone_android`、`phone_intent`、`phone_content`、`phone_notifications`。
- 提示与协助：`phone_begin_ui`、`phone_end_ui`、`phone_disconnect`、`phone_notify_user`、`phone_get_user_reply`。

## Agent 状态与用户协助

手机操控页的“连接与日志”提供当前连接、历史连接、连接详情和调用时间线。调用详情显示参数、结果、耗时及错误。记录存放在当前 App 的本地数据库，保留 30 天、最多 2 万条调用；敏感字段会隐藏，图片和文件的 Base64 正文不记录，长内容明确标注截断。清空展示日志不会删除幂等去重记录。记录从安装本功能的版本开始积累，旧版没有保存的详细日志无法补录。

用户在手机端断开某个连接后，该连接释放 UI 占用；尚未开始的调用取消，旧会话收到 `connection_closed_by_user`、`retryable:false` 和重新建连说明，不能自动重发。已经开始的操作以真实执行回执为准，回执会附带用户断开标记。Agent 的主动 `phone_disconnect` 仍仅结束自己的当前活动。

“自动恢复”是独立开关：App 被重新唤醒时，若此前服务开启且没有被用户手动停止，则恢复原有接入方式；手动停止会持久取消恢复意图。它不主动唤醒 App，也不替代开机自启。转发恢复复用已保存电脑身份和登录验证；Android 暂不允许后台启动时，会在再次进入 App 时重试。

执行一组 UI 操作前调用 `phone_begin_ui`（可带 `message`、`duration_seconds`），结束后调用 `phone_end_ui`，下一批仍复用同一个连接。整个任务的操作与核验完成后，最后调用 `phone_disconnect`，立即撤下本 Agent 的连接／操作提示并取消其待处理协助消息；其他 Agent、手机操控服务和授权凭据保持有效。Agent 主动结束活动后，新的工具调用可重新激活同一身份，重新 `initialize` 则创建新身份，因此清理后不要再调用工具核验断开；用户在手机端主动断开则会禁止旧身份继续使用。`ping`、工具发现和回执查询 REST 接口不会重新激活已结束的手机活动。UI 观察、截图、点击、输入、按键及页面操作会自动显示当前操作提示。先开启系统悬浮窗权限，即可在屏幕顶部看到小提示；未开启时仍更新后台通知。

`phone_notify_user` 接收 `message`（最多 1000 字）、`kind`（`info` 或 `help`）、`timeout_seconds`，返回 `notice_id`。帮助请求显示“已完成／暂不处理”，Agent 用 `phone_get_user_reply(notice_id)` 读取 `pending/done/dismissed/expired/cancelled/not_found`；不能将等待、超时或断线当作已完成。回执按 Agent 绑定隔离，仅存在本次操控进程，重新启动进程后需核对，不会自动重发消息。

UI 占用由当前连接独享，无需续期；调用 phone_end_ui、断开或停止服务时释放，连续 10 分钟没有 UI 操作也会自动释放。其他连接可并行执行非 UI 任务。duration_seconds 为兼容旧客户端保留，但不再决定占用期限。客户端身份在 30 分钟无请求后失效，需重新 initialize；不会自动重放未知结果。撤销绑定或 WebSocket 断开会移除对应连接提示。协助请求默认等待 600 秒，普通提示默认显示 15 秒。

悬浮窗按连接分组展示各自信息，使用半透明背景；整个区域可拖动并记住位置，点击标题或“收起”立即以约 180ms 动画最小化，点击“展开”恢复。长内容自动缓慢滚动，无需手动滚动。操作状态显示“正在操控手机”。连接调用时间线采用紧凑列表，查看调用详情后返回保留滚动位置，并短暂高亮刚查看的条目 0.5 秒。

通过 phone_shell 读写屏幕或 UI 状态时须传 ui_operation=true，并先获得 UI 占用。APK 经 exchange 分块上传后，安装通过一次进程标准输入流传输，预算 120 秒；普通工具仍为 35 秒、Shell 最长 30 秒。节点快照按客户端隔离，30 秒有效，每客户端最多保留 4 份、总计 32 份。悬浮窗列出各连接，宽度通过 400ms 动画适应内容，收窄延迟 1.2 秒；超过屏幕宽度的文字换行，长说明可滚动查看。

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
app/gradlew.bat :mobile:testFullDebugUnitTest :mobile:testPhoneDebugUnitTest :mobile:lintFullRelease :mobile:lintPhoneRelease :mobile:assembleFullRelease :mobile:assemblePhoneRelease
dotnet run --project tests/PhoneTools/PhoneTools.csproj
python -m unittest discover -s tools/PhoneTools/tests -v
python tools/PhoneTools/tests/check_codex_protocol.py <实际使用的codex.exe>
```

已验证独立 HTTP 服务、去重与冲突、重启未知状态、真实 WS 转发、绑定隔离及撤销、外部连接 API、CLI/stdio、Hermes 图片/会话处理、实际 Codex 进程接受工具定义。使用已安装的 MCP Python 客户端完成了 Web MCP 初始化、发现、调用并经 WS 获得模拟手机执行端结果；真实 Hermes discovery/registry 在隔离 Home 加载了插件并拒绝未绑定会话。真机后端授权、厂商限制、锁屏和后台在线效果需要在在线手机上验证。
