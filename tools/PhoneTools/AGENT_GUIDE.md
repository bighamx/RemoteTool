# Android 手机操控 Agent 使用指南

## v3 高效操作接口

### Agent 名称与采集方式

MCP initialize 的 clientInfo.name 由 Agent 客户端声明，作为手机上的连接名称；恢复原身份时可更新显示名称，不改变 logical agent 或权限。通过注入工具操作时，在 phone_begin_ui 中声明 client_name（1–40 个可显示字符）和 message。该名称持续到本 owner 断开，不会被后续请求的通道默认名覆盖；名称只用于展示，不是鉴权凭据。

    {"client_name":"Codex 商品查询","message":"查询商品价格并核对型号"}

phone_observe 和 phone_find_nodes 将采集与输出分开：

| 参数 | 选择与用途 |
| --- | --- |
| backend=auto | 默认优先快速 Accessibility；未运行时使用已授权的 UiAutomation。 |
| backend=accessibility | 明确使用本应用无障碍服务；不可用时失败，不静默换后端。适合常规读取和后续节点动作。 |
| backend=uiautomator | 显式运行特权 XML dump，通常需数秒。适合同页对照或文字缺失诊断；节点只读，不能用于 node_id 动作。 |
| include_unimportant=true | 默认包含 Android 标记为“不重要”的节点，可能承载 QQ 正文；本版无障碍服务已请求对应标志。UiAutomation 对应未压缩 dump。 |
| include_unimportant=false | Accessibility 过滤这些行但继续遍历子节点；UiAutomation 使用压缩 dump。输出更少，但可能丢正文。 |
| privilege_backend | UiAutomation 执行权限通道：auto/shizuku/root；明确指定的后端不可用则失败。 |
| mode | compact 精简节点；text 为同一采集结果追加文本；raw 输出诊断行。它不切换采集后端或自动补出缺失信息。 |

例如优先 phone_observe({"backend":"accessibility","include_unimportant":true,"mode":"text"})；需要诊断时显式改用 {"backend":"uiautomator","privilege_backend":"root","include_unimportant":true,"mode":"text"}。不要每次动作后都执行慢速 dump。

返回 backend、include_unimportant、node_actions、truncated 等信息；UiAutomation 还返回实际 privilege_backend、occlusion_checked=false。它不支持差量，changed_since 会得到完整结果及 reset=true；include_system=true 仅用于 Accessibility。动作后的 return_state 仍走默认快速观察路径，需要其他采集方式时单独调用。

本项目统一使用 schema_version=3 的手机端、服务端和配套脚本，不保留旧服务执行分支。首次读取能力确认版本，版本不符时明确提示更新，不回退到旧接口。标准 MCP 协议仍协商 2025-11-25/06-18/03-26；协议版本协商与手机工具 Schema 是不同维度，扩展使用普通工具和 _meta。

- phone_observe 默认 mode=compact，优先目标应用窗口，只输出有语义且可见的节点。mode=text 同时返回可读文本，mode=raw 用于诊断，include_system=true 显式包含系统及悬浮窗口。package_name 选择窗口，filter 做服务端过滤，fields 投影字段。truncated=true 表示达到输出或遍历上限，不能把“未返回”当作不存在。
- 屏幕、截图和节点统一使用物理屏幕坐标。截图缩放后通过 image_to_screen.scale_x/scale_y 转换。raw_bounds 保留原始几何，bounds 是有效屏幕交集；bounds_valid=false 不可点击。occluded、tap_point 表示遮挡情况及未遮挡位置。不要把非法矩形强行钳制成点击点。
- phone_tap、phone_input_text 支持 selector，由手机重新定位。选择器支持精确 text/description/resource_id/class/package_name、text_contains（文字或描述）、text_regex（文字或描述）、clickable/editable/visible。动作目标必须唯一，unique=false 不受支持；找不到、多个匹配、非法或遮挡目标均应重新观察，不自动猜坐标。
- 点击、输入、滑动、按键、启动应用及节点动作支持 guard、wait_for、return_state。guard 是执行前条件，支持 package_name、selector；wait_for 是执行后条件，支持文字、正则、前台包名、选择器出现/消失及 stable_ms。return_state 为 none/compact/text/raw。

一次点击、条件等待和回读的参数示例（替换为真实界面的选择器）：

    {
      "selector": {"text": "搜索", "unique": true},
      "guard": {"package_name": "com.jingdong.app.mall"},
      "wait_for": {"text_regex": "小米18\\s*Pro", "timeout_ms": 6000},
      "return_state": "compact",
      "explanation": "提交搜索并读取结果"
    }

phone_wait 可单独使用相同条件；selector 配合 absent=true 等待消失，stable_ms 范围 0–2000，timeout_ms 范围 1–10000。只判断目标窗口的快照签名，本应用面板刷新不影响它。稳定不等于业务成功，优先指定业务后置条件。返回 matched、新快照、wait_ms，选择器等待另带 matched_nodes。matched=false 表示超时，不代表前一个动作未执行。等待已返回新状态，不要再 sleep 后无条件重复 observe。

动作附带 wait_for 默认 require_change=true，先采集 state_before，再执行动作；执行后窗口或内容签名有变化且条件成立才命中。预期界面不变时显式设 false。独立 phone_wait 默认 false；显式 true 以等待开始时的观察为基线。条件应选择目标页面独有元素，宽泛的 WLAN|网络 等正则即使在变化后匹配也不能证明跳转完成。

Accessibility revision 是同一 revision_epoch 内跨窗口递增的观察序号，不代表内容变化；窗口局部事件数另见 window_revision。核对动作前后使用 state_before 和 state 的 window_id/signature，观察先后使用 revision；不同 epoch、后端或查询范围不可直接比较。

changed_since 接受本操作者的旧 snapshot_id。窗口和查询范围一致时返回 delta=true、变化的 nodes 和 removed_nodes；旧快照失效、窗口或范围变化时返回完整状态及 reset=true。后续动作使用新 snapshot_id，节点引用仍是 30 秒有效的临时引用。

## 短流程与回执

先取得 UI 占用，再调用 phone_execute：

    {
      "steps": [
        {"tool": "phone_input_text", "arguments": {"selector": {"editable": true}, "text": "小米18 Pro"}},
        {"tool": "phone_tap", "arguments": {"selector": {"text": "搜索"}, "wait_for": {"text": "小米18 Pro", "timeout_ms": 6000}}}
      ],
      "max_ms": 15000,
      "return_state": "compact"
    }

开始前校验所有步骤；手机端串行执行、逐步重新定位，首个动作失败、等待超时或用户断开即停止。最多 20 步、执行预算最多 30000ms；已开始的底层动作可能在超时后才完成，因此超时不表示未执行。支持界面动作、观察、等待及能力查询，不允许嵌套批次、Shell、文件或生命周期工具。只执行显式步骤，不额外获取/释放 UI 或发送消息。

返回 completed_steps、每步 operation_id/isError/execution/code/metrics，以及可选最终 state。部分执行后不能换 ID 重跑整个批次。步骤操作 ID 由父请求及序号确定；重复同一父请求返回原回执，不重做步骤。phone_get_operation 使用 operation_id 查询原始 state/result，限当前逻辑操作者；旧数据库无 owner 的记录不通过 MCP 暴露。

execution 区分 not_started、receipt_available、unknown，不等于业务成功。accepted 表示底层接受动作，业务结果应看后置条件。失败回包含 code/retryable/hint 和 operation_id。metrics.device_ms 是手机端执行耗时，queue_ms 是转发队列等待；重放保留原指标，不代表本次网络耗时。

## 身份恢复与现成客户端

恢复凭据未知、过期或已撤销时返回 resume_unknown，区别于用户明确断开和正在执行的 device_busy；不自动创建另一身份。当 initialize 公布 _meta.remotetool.self_cleanup="resume-token" 时，绑定/App Bearer 配合 X-RemoteTool-Resume-Token 可对原 /mcp URL 发 DELETE，仅清理这个逻辑 owner；不能带另一个会话头，也不能不带 owner 凭据清空整个绑定。执行中的 owner 返回 409 device_busy，保留回执与缓存，等待后再处理。

UI CLI 的 --close-on-exit 在 finally 收尾，适合一次独立任务；连续多条 CLI 复用同一会话时不要开启，在整轮结束后调用 end。UiAutomation 句柄用于动作时明确返回 read_only_backend，应改用 accessibility 重观察。phone_execute 的步骤回执还包含该步可获得的 truncated、visited_nodes、backend，未返回这些字段不能推断没有截断。

初始化可返回 _meta.remotetool.resume_token/agent_id/generation。恢复凭据必须保密，不能展示给模型或打印进日志。明确收到 HTTP 404 session_not_found 后，在新初始化的 params._meta["remotetool/resume_token"] 提交凭据；传输 ID 更新，操作者及 UI/快照/消息/回执归属不变，旧传输被拒绝。旧调用仍运行时返回 device_busy，凭据无效或撤销时返回 session_recovery_failed，不自动建立替代身份。

恢复只在同一服务实例内有效；服务重启后需重新建立授权连接，不盲目重放操作。用户手动断开后恢复不能清除禁止状态，必须停止。HTTP DELETE 撤销恢复凭据。传输闲置 5 分钟回收；恢复凭据最多保留 24 小时且有数量上限。显示名称不是恢复凭据。

本项目 remotetool_phone.py 会自动初始化、发送通知并记录协商版本。短命 CLI 应指定独立、稳定的逻辑 Agent/聊天标识，例如：

    python remotetool_phone.py --agent hermes:<chat-id> call phone_get_capabilities
    python remotetool_phone.py --agent hermes:<chat-id> call phone_execute --args-file steps.json --request-id <logical-operation-id>

CLI 必须提供 --agent 或 REMOTETOOL_PHONE_AGENT，避免每个短命进程误建新身份。缓存按完整端点、凭据身份及逻辑 Agent 隔离，首次初始化使用操作系统文件锁，进程退出后自动释放锁；缓存原子替换。只有明确的 session_not_found 会触发恢复，并保留原操作 ID。401、其他 404、超时、连接重置和用户断开均不自动重试。恢复缓存可能含敏感凭据，不要放公共目录、上传云端或打印。

正常 MCP JSON 响应提供 X-RemoteTool-Serialize-Ms、X-RemoteTool-Response-Bytes 两个诊断头，分别表示该端序列化耗时和实际 UTF-8 JSON 字节数。项目 CLI 返回 transport.http_ms/response_bytes/serialize_ms，代表最后一次工具 HTTP 请求，不含首次初始化或模型决策；不要与手机端 device_ms 混为同一个时间指标。

原生 Hermes 返回 phone_binding_missing 表示当前聊天没有原生绑定。选择用户显式配置的 MCP 入口或重新附加本会话；不要搜索凭据、读取其他会话配置或选“最后一条绑定”继续任务。

## 连接原则

一个 Agent 的持续任务保持同一逻辑操作者。平时复用 MCP 传输会话；必要时通过恢复凭据更换传输并保留操作者。HTTP 连接可以短开短关。优先使用已配置的 MCP 或 RemoteTool 注入工具，不为每个动作创建客户端。同名初始化不代表同一身份，只有正确的服务端恢复凭据可以接管自身状态。

如果实现自定义 HTTP 客户端：

1. 对配置中的**原始 MCP URL**发送一次 `initialize`，读取响应头 `Mcp-Session-Id`，保存结果里的 `protocolVersion`。不要把 Web 绑定 ID、聊天会话 ID、设备 ID 或 JSON-RPC `id` 当成 MCP 会话 ID。
2. 后续请求都带 `Authorization`、`Mcp-Session-Id`、`MCP-Protocol-Version`；POST 使用 `Content-Type: application/json; charset=utf-8` 和 `Accept: application/json, text/event-stream`。按标准发送 `notifications/initialized`，再发现和调用工具。
3. 短命进程需复用受保护缓存；缓存键隔离完整端点、凭据身份和逻辑 Agent。凭据更换需隔离旧缓存。对初始化和缓存更新加锁，防止并发启动各建一条会话。独立 Agent 不共享身份；不要仅按端点使用全局共享缓存。无法可靠实现时使用标准长驻 MCP 客户端或 `remotetool_phone.py mcp`。
4. 收到服务端指令和工具描述后按其执行。不应依赖无会话头的匿名兼容入口；初始化后不混用旧 REST/匿名接口。

## 正常操作顺序

```text
initialize 一次 → 记住响应头和版本 → tools/list
  phone_get_capabilities
  phone_begin_ui
    phone_observe / phone_screenshot
    按实际界面操作 → 重新观察核验
  phone_end_ui
  同一连接继续文件任务 / 等待用户协助 / 下一批 UI
  所有核验结束 → 清理自己的 UI → phone_disconnect
  自定义 HTTP 客户端：DELETE 原 MCP URL，携带自己的会话头
```

UI、节点快照、协助通知按逻辑操作者隔离。经恢复凭据接管的新传输继承同一操作者，独立操作者之间不能混用。观察和截图也是 UI 操作；Shell 操作 UI 时设 ui_operation=true。节点使用当前操作者最新 snapshot_id/node_id，30 秒失效，页面变化后重新观察。

`phone_end_ui` 仅结束一批 UI 操作，不断开连接。下一批再次 `begin_ui`，不再 `initialize`。UI 无须续期，5 分钟无 UI 操作自动释放；其他连接可并行处理非 UI 任务。同一批中的观察、操作、核验按顺序执行，不并发点击和读取变化中的页面。

普通操作用可选 `explanation` 简述动作与目的（用户语言、最多 240 字符），不写凭据、敏感输入或内部推理。需要用户解锁、授权或人工处理时使用 `phone_notify_user(kind=help)`，保留 `notice_id`，释放 UI 后用同一连接以合理间隔查 `phone_get_user_reply`；pending、超时、dismissed 不代表完成。得到回复后重新获取 UI 并观察，再继续依赖该步骤的操作。

## 遇到问题

- `ui_busy/device_busy`：等待或说明冲突，不新建身份绕过，不尝试释放其他连接。自己失去旧会话时，无法从新身份替旧身份 `end_ui`；可请用户在连接管理中断开明确属于自己的旧连接，或等待空闲释放。
- HTTP 404 session_not_found：旧传输已失效，保留恢复凭据，按前述恢复流程串行重新初始化；检查回执与当前 UI 后再继续。缺少恢复凭据时明确失败，不自动创建替代身份。不要把其他 404、401、超时当作传输过期，也不要盲目重放原操作。
- `connection_closed_by_user`：用户主动断开，停止并明确告知；不得自动初始化绕过。只有用户明确要求继续后，才新建会话。
- `delivery_unknown`、超时、丢失响应：操作可能已发生，不能自动重发写操作或换 ID 绕过去重。先查回执或实际状态。`request_id` 用于同一逻辑操作的去重，同 ID 不可用于不同操作；它不等于 MCP 会话 ID 或 JSON-RPC 消息 `id`。
- `phone_offline`、权限/后端不可用：说明具体阻碍，必要时请求用户协助。不要把连接成功、命令已提交、点击成功当成最终业务成功。

## 结束与历史记录

任务完成、失败、受阻或放弃均清理自己的 UI，最后调用 `phone_disconnect`；不要在每步或每批之间断开。HTTP 客户端可再 DELETE 原 MCP URL，释放该传输会话并删除对应缓存。只退出脚本不等于断开。清理后不要再截图、通知或重新初始化来“验证已断开”；不要撤销设备级绑定，也不要清理其他 Agent。

Agent 主动 `phone_disconnect` 结束当前手机活动，但不销毁 MCP 传输会话；后续有意继续的新工具调用可重新激活原身份。手机端用户手动断开则会拒绝旧身份继续调用，必须遵循上面的用户授权规则。断开后历史日志保留，不代表仍在连接；“清除历史”不能代替断开活跃连接。服务端会话空闲回收与手机展示清理是独立机制，当前不会即时同步结束。
