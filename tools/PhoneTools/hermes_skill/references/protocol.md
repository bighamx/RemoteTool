# RemoteTool 手机 MCP 契约

本脚本面向 schema_version=3。能力清单共 30 个工具，实际 tools/list 会按权限过滤；无需为旧服务保留执行分支。标准 MCP 协议版本协商与应用工具 Schema 是两个不同维度。

## 地址与鉴权

直连手机使用 http://<可达手机地址>:18791/mcp 和手机工具令牌。转发通道使用 http://<服务端>/api/phone/v1/bound/<绑定ID>/mcp 和该绑定的令牌；不是 Web 登录 JWT。直连地址需要网络可达，不强制同一网段。

配置文件 $HERMES_HOME/state/phone-mcp.json 内容为 {"url":".../mcp","token":"..."}。bindings 文件只接受当前 HERMES_SESSION_ID 或 --binding 精确键。独立聊天用独立 PHONE_MCP_AGENT；同一聊天的多进程沿用该值。

phone-mcp.json 可额外配置 agent。显式参数、PHONE_MCP_AGENT、HERMES_SESSION_ID 均优先于文件值；缺少所有身份来源仍明确报错，不能用同一默认身份混用聊天。DELETE 携带缓存中的协商协议版本。

Python 的 Phone(client_name="展示名称", agent="稳定聊天身份") 将显示名称与逻辑身份分开。Hermes 默认显示 hermes-agent，其他客户端复用脚本时应明确提供自己的名称。MCP 恢复可更新传输声明的名称；通过 phone_begin_ui(client_name=...) 显式声明的名字在当前 owner 内优先，直到断开。名称不改变 logical agent、UI 占用、快照或回执归属。

## HTTP 与逻辑身份

1. POST initialize，客户端声明 protocolVersion=2025-11-25。
2. 保存响应头 Mcp-Session-Id、result.protocolVersion 和 result._meta.remotetool.resume_token。
3. 发送 notifications/initialized，返回 202；后续请求带会话头和 MCP-Protocol-Version。普通响应为 JSON；GET 为 405，不依赖 SSE。
4. 只有明确 HTTP 404/code=session_not_found 才串行恢复：initialize.params._meta["remotetool/resume_token"]=原密钥。沿用同一逻辑 owner，新传输替代旧传输。不是按 clientInfo.name 合并身份。
5. 其他 404、401、网络超时、device_busy 不触发重建。session_recovery_failed/connection_closed_by_user 必须停止；不静默丢弃密钥另建身份。

传输空闲 5 分钟回收，恢复凭据最长空闲 24 小时，受容量上限影响。恢复表在内存中，服务重启后不能恢复原逻辑身份。首次 initialize 回包丢失时无法恢复尚未收到的密钥，不自动重试。

phone_get_capabilities 的 app 对象报告 package_name、version_name、version_code，用于区分手机实际运行的主应用/独立操控版及安装版本。仅凭服务端分发 schema 或磁盘 APK 文件不能推断手机已升级；旧手机缺少 app 时只记录版本未知。

缓存以端点、令牌、聊天标识隔离，用 OS 跨进程锁串行初始化、原子文件写入。密钥不写到输出、日志或说明文本。缓存损坏时显式失败，不自动换身份。

## UI 与快照

phone_begin_ui 后才能 UI 观察/动作；UI 占用独享，不能抢其他 owner。compact 默认选择应用窗口并过滤不可见/非法节点；raw 和 include_system 供诊断按需使用。文本模式仍是无障碍树文本，不保证识别画面全部内容。

观察上限 600 节点；filter 先筛选再受输出上限限制，fields 可投影。快照 owner 隔离，保留 30 秒；旧节点不得搭配新快照。changed_since 仅用于相同 owner、窗口和查询范围，范围变化会返回完整状态。节点 bounds/tap_point 为物理屏幕像素；截图另含 image_to_screen 缩放信息。

phone_observe/phone_find_nodes 的 limit schema 声明 minimum=1、maximum=600、default=400。原始 HTTP 请求即使绕过客户端 schema 检查，手机仍将越界整数钳制至 1..600；观察结果返回 requested_limit（未指定为 400）和 effective_limit，适用于 Accessibility、UiAutomation 及动作/等待内嵌 state。是否读全还必须检查 truncated；effective_limit=600 不等于返回全量。

诊断 QQ 正文缺失时还需区分“重要性过滤”和输出精简。真机对照显示，uiautomator dump --compressed 与当前 Accessibility 树可能都缺少正文，而未压缩 dump 可以获得。应检查服务是否启用 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS，再比较同一页面的 raw 树、压缩及未压缩 dump；mode=raw 不会自行开启该服务标志。未压缩 dump 是按需诊断路径，不作为每次动作后的默认等待或核验。

新实现已启用该服务标志，include_unimportant 默认 true。Agent 可按次指定 backend=auto/accessibility/uiautomator，mode=compact/text/raw 和 include_unimportant 独立控制；UiAutomation 的 include_unimportant=false 对应压缩 dump。结果明确报告 backend、include_unimportant、node_actions；UiAutomation 的遮挡检测和差量不可用，节点只读。

tap/input 可用 selector；action 可带 guard、wait_for、return_state。selector 要唯一，guard 在执行前检查；matched=false 表示动作后等待超时，必须核对当前状态。phone_wait 支持 text、text_regex、package_name、selector、absent 与 stable_ms；超时 1..10000ms，stable_ms 0..2000ms，直接返回快照。

等待目标应用处于前台的请求为 phone_wait({"package_name":"com.jingdong.app.mall","timeout_ms":8000})。package_name 是请求字段，foreground_package 是观察结果字段；将后者传给 phone_wait 会得到 invalid_arguments/Unknown argument，不能将这种校验失败当作等待超时或网络故障重试。phone_wait 仅等待，不负责启动目标应用。

动作 wait_for 默认 require_change=true：先记录动作前 state_before（revision/revision_epoch/window_id/signature/backend），动作提交后只在窗口或内容签名不同且条件匹配时返回 matched=true。require_change=false 仅用于预期不改变界面的动作；独立 phone_wait 默认 false，显式 true 时以开始等待的观察为基线。revision 单独增加不算内容变化。条件应使用目标页独有控件，不能用源页已有的宽泛正则证明跳转成功；界面任意变化也不等于目标业务成功。超时返回现状，绝不重发动作。

Accessibility 的 revision 是进程内跨窗口递增的观察序号；revision_epoch 标识进程，重启后不可跨 epoch 比较。它表示观察先后，不表示页面内容一定变化。window_revision 是窗口内事件计数，event_revision 用于内部事件唤醒；比较内容用同范围 signature + window_id，比较动作前后用 state_before 与 state。不同后端或 mode/filter 范围的签名不可直接比较。

state_before 还保留 rotation、package、foreground_package；变化判断同时检查窗口、签名、后端、旋转和包名。它只证明观察发生变化，不证明动作导致变化，也不保证业务成功。同页刷新若结果不变，可显式 require_change=false，再用应用加载结束标记或业务回显验证；不应仅以“旧内容仍在”声称刷新完成。异步结果可能在等待超时之后到达；postcondition_timeout 不撤销动作，按同一 operation_id 查询回执，检查现状，再用独立 phone_wait 分段等待目标条件，禁止重放原动作。可在单次上限 10000ms 内调整 timeout_ms。

phone_execute 参数为 steps=[{"tool":"phone_tap","arguments":{...}}]、max_ms、return_state。1..20 步，预算 100..30000ms。不会回滚已执行步骤；已开始的底层动作可能超过总预算后完成。批次只允许公布的 UI/观察工具，不允许 shell、递归批次或连接生命周期工具。

## 回执与错误

tools/call 的 params._meta["remotetool/request_id"] 标识一个逻辑操作，区别于 JSON-RPC id。恢复会话后重用同一操作 ID，不能把部分批次换新 ID 整批重放。

结果 structuredContent 包含 operation_id、execution（not_started/receipt_available/unknown）、retryable 和 metrics。isError 是工具失败标记，不能用字段数量判断错误。部分批次还含步骤回执和 completed_steps；应保存失败响应中的 state。

转发在发送前检查 WebSocket 在线状态，并在发送锁内复查。确认未进入发送返回 phone_offline/channel_closed、execution=not_started、delivery_state=not_sent。进入 SendAsync 后即可能送达；发送中失败为 possibly_sent，发送完成无回执为 sent，二者 execution=unknown，保留 delivery_unknown。send_completed=false 不能证明完全没发出去。断开/连接替换立即唤醒 pending，不再等回执超时；已经发送的调用仍不能伪装为未执行，即使下一次设备列表显示离线。

delivery_state 适用于 PhoneGateway.Invoke 转发工具结果：收到手机回执时，无论工具成功还是手机拒绝执行，统一为 sent；网关在发送前拒绝工具或重复 pending ID 也明确返回 not_sent。sent 只证明请求经转发通道送达，不表示动作执行成功、消息送达或订单成功；仍查看 isError、execution 和业务回执。直接手机 MCP、initialize/ping/tools/list 等网关本地结果不受此字段约定约束，缺失不能据此推断服务版本或传输结果。

重复 pending operation_id 是特殊情况：新尝试为 not_sent，但原逻辑操作可能已经在手机执行，因此 execution=unknown、retryable=false，保留原 operation_id 查回执，不能按 not_sent 换新 ID 重发。delivery_state 描述本次转发尝试，execution 描述操作结果，两者不可混用。

## 内部 WebSocket 帧与隔离测试

当前实现的权威来源是 WebApplication/Services/PhoneControl/PhoneGateway.cs、app/mobile/src/main/java/com/chuckiehelper/mobile/phonecontrol/PhoneRelay.kt，运行样例在 tests/PhoneTools/Program.cs。此处是内部转发协议，不是标准 MCP WebSocket。生产入口 /ws/phone-agent 使用 RemoteTool 登录 Cookie access_token 或 Bearer 验证；phone_token 是安装配对凭据，不是 MCP 绑定 token，不能混用。隔离测试使用临时数据目录、新 PhoneGateway、随机本地端口和合成 phone_id/token，绝不接生产绑定。

最小帧序列（每行一个完整 UTF-8 JSON text message，示例 deadline 必须替换为未来的 Unix 毫秒时间）：

```json
{"type":"hello","phone_id":"phone-mock-isolated","phone_token":"mock-pairing-secret-at-least-32-characters","device":{"name":"Isolated mock"},"capabilities":{"schema_version":3},"tools":[{"name":"phone_get_capabilities","description":"Mock read only","inputSchema":{"type":"object","properties":{},"additionalProperties":false},"annotations":{"readOnlyHint":true,"destructiveHint":false}}]}
{"type":"ready"}
{"type":"invoke","request_id":"mock-op-1","tool":"phone_get_capabilities","arguments":{},"allow_advanced":false,"deadline":0,"agent_id":"mock-owner","agent_name":"Isolated mock agent"}
{"type":"result","request_id":"mock-op-1","result":{"isError":false,"structuredContent":{"schema_version":3,"operation_id":"mock-op-1","execution":"receipt_available"},"content":[{"type":"text","text":"mock receipt"}]}}
```

方向依次为手机→服务端 hello、服务端→手机 ready/invoke、手机→服务端 result；result.result 是 MCP 工具结果对象，request_id 必须原样关联。text 内容应与 structuredContent 一致，示例仅作帧结构演示。截图是同一结果 content 数组的 image 块；当前没有独立二进制附件帧。服务端读取 hello 上限 256 KiB，后续 text message 上限 8 MiB，超限拒绝。

可选手机→服务端能力更新为 {"type":"capabilities","capabilities":{...},"tools":[...]}。服务端→手机 agent_presence 含 agent_id、agent_name、connected、generation；touch_only=true 仅维持已有活动，不能复活已断开的 owner。手机运行时 owner 加 web: 前缀。心跳使用 OkHttp 每 20 秒的 WebSocket 控制 Ping/Pong，当前没有 JSON heartbeat 帧，也没有 accepted/cancel/capabilities_changed 帧；MCP HTTP ping 是另一层，不能替代底层发送证据。

sendAttempted 在拿到发送锁、确认连接仍 Open 且取消检查通过之后、调用 Socket.SendAsync 之前置 true；sendCompleted 只在 await SendAsync 成功返回后置 true，不等待手机回执。其后才等待关联 result。SendAsync 返回证明本地发送完成，不证明业务执行；真实网络中无法仅凭断线时刻可靠制造 possibly_sent。

复现基线：dotnet run --project tests/PhoneTools/PhoneTools.csproj。该测试使用临时目录、随机端口和假手机，不访问共享服务。它覆盖成功/手机拒绝均 sent、发送前取消 not_sent、发送后丢回执 sent+unknown，以及断线立即唤醒 pending。tests/PhoneTools/FaultingPhoneSocket.cs 为确定性替身，真实 PhoneGateway 的三态各重复 3 次，使用单调时钟断言 pending 在本地 <1s 完成；生产网络时延不能直接当本地 <1s 的断言。

若需确定性覆盖 possibly_sent，使用测试专用 WebSocket 替身，让 ReceiveAsync 先投递 hello 后保持等待；SendAsync 对 ready 正常返回，但遇到 invoke 时受控抛 WebSocketException。这样真实 PhoneGateway 的 sendAttempted 已置 true、sendCompleted 仍 false，而不是通过猜测包大小、断线时刻或修改生产通道来凑状态。不要为测试给生产 API 增加故障开关。

替身提供 InvokeReceived 信号，sent 用例先确认 invoke 已进入发送函数再 Abort；该替身成功发送返回已完成 Task，当前单调用路径在 Invoke 返回 pending Task 前已通过 sendCompleted=true。收到 invoke 本身不等于任意异步传输的发送完成证据，仍须断言 relay_metrics.send_completed。真实 loopback WS 与替身统一使用 1000ms pending 预算，并逐次打印 elapsed_ms/budget_ms；此阈值仅为本地回归断言。

phone_get_operation({"operation_id":"原ID"}) 仅查询当前 owner 的记录。delivery_unknown、无有效 JSON 响应或连接中断后，先查询/核对状态，不自动重发。accepted、matched 或 verified 都不直接等于消息送达/订单成功。

### 操作日志诊断与身份边界

查询回执的 owner 必须与原调用完全一致。转发 MCP 的手机侧 owner 为 web:<binding_id>:<logical_id>，直接 HTTP MCP 为 direct:<logical_id>；旧的 bound REST 接口仅用 web:<binding_id>，两者不是同一身份。resume_token 恢复保留 logical_id，dispose 后重新 initialize 则创建新 logical_id。相同绑定前缀、显示名称或脚本 agent 配置不证明 owner 相同。跨 owner 查询统一 not_found，不泄露该操作是否存在。裁剪回执只保留 tombstone，不删除 ID/owner，不能单独造成同 owner 查询 not_found。

phone-tools.db 是去重 journal，phone-connections.db 的 activity 才记录网络收到后的 queued/running 与结束状态，两者需按 request_id/完整 owner 对照。用 phone_shell 复制数据库时，快照可以包含这次复制自身尚未结束的记录；必须先用 activity 确认工具名与调用时间，不能把它当成 caps/list_devices 的挂起证据。无记录、已有 unknown tombstone、已收到但未开始执行是三种不同情况。

优先在同 owner 下按已知 operation_id 查询，不通过不断复制整库寻找故障。确需数据库快照时，先完成一个已知 ID 的 marker 并保留其回执，再记录复制调用自己的 ID，将其排除出目标证据；活动 SQLite 数据库应采用一致性快照方法，裸 cp 及缺少 WAL 的副本不能保证同一时刻的数据完整性。复制调用本身也会写 journal，不得把它的未完成行当作原工具卡住。

诊断格式更新后的 journal 状态如下；这是手机端源码变化，未安装对应新包时不能要求出现新状态/字段：

| state | state_reason | 可证明的阶段 |
| --- | --- | --- |
| claimed | claimed | 去重 claim 已接纳；连接登记、guard、selector 等前置工作可能尚未完成，不证明工具主体开始。 |
| executing | executing | 已完成前置定位并持久化执行标记；最终停止/截止时间复核后才能进入工具主体，不证明副作用已经发生。 |
| completed | completed | 最终工具回执已保存，可能是成功或错误，业务结果看 result。 |
| completed_pruned | receipt_pruned | 曾保存最终回执，正文因容量限制被裁剪；仍保留 ID/digest/owner，不允许重放。 |
| interrupted | journal_reopened | journal 重新打开时发现 claimed/executing 遗留；恢复事件不等于精确进程终止时间。 |
| unknown | legacy_unknown | 升级前已混合的历史 tombstone；不能事后可靠分成裁剪或中断。 |

status 额外返回 created（claim 的 Unix 毫秒时间）、started_at（工具执行接纳标记）、finished_at（最终回执持久化时间）与 receipt_available。未经过的阶段及旧记录无法补算的时间为 null；guard 失败可有 finished_at 而 started_at=null。当前结果缓存保留刚完成的回执及其他最近 39 份，按完成时间而非创建时间裁剪，避免慢请求刚完成就丢正文。查询工具自身的 execution 不能替代被查询操作的 state/result。上述诊断细分不改变不重放规则：claimed/executing/interrupted/completed_pruned/unknown 都不会因重复调用而重新执行；仍有完整回执的 completed 返回原回执。

completed_pruned 明确返回 receipt_available=false、state_reason=receipt_pruned；重复请求仍返回 delivery_unknown，但文案说明“已有最终回执，正文已裁剪”，不会伪称仍在执行。执行标记写入在 connectionGate 外，写失败不继续工具主体；写前与写后均检查用户停止，避免落盘等待期间错过断开。started_at 是持久执行接纳标记，不是副作用发生证明，写后若被停止也可能没有进入工具主体。onStarted 的 activity 更新仍在最终检查区，本次仅移出 journal 写入，不宣称整个 gate 已无磁盘访问。

status 的 state_reason 与 existing 重复请求错误的 details.state_reason 使用同一映射。第二次复核因取消、超时或 owner 被停止而失败时，错误仍经过 Engine 的统一回执补齐与 journal.finish：正常持久化后为 completed，result.structuredContent.execution=not_started；started_at 可以非 null，因为持久执行标记在最终复核前写入。不能仅凭 started_at 断言工具主体跑过，也不能仅看 catch 内部就认为函数已返回。进程中断或最终回执写入本身失败是另一个边界，不能保证这种情况下已有 completed 回执。

耗时分层：Phone.transport 保存最近一次 HTTP 的 http_ms、response_bytes 和 serialize_ms；同时可读取响应头 X-RemoteTool-Serialize-Ms、X-RemoteTool-Response-Bytes 与结果 metrics（设备执行/通道排队）。对比效率需同设备、同任务实测，不能把编译测试通过当成手机提速证明。

### 暂态故障与有限重试

真机发现新会话首次 caps/list_devices 偶发 delivery_unknown，并非仅在升级之后发生；根因仍待排查。phone_list_devices 在 Android 上只返回当前安装的设备描述，不扫描网络或分页。当前普通转发调用的超时为 35 秒执行预算加 5 秒回执等待，约 40 秒返回不证明工具计算用了 40 秒。保留 operation_id、relay_metrics 及同一 owner 的后续调用耗时，按传输、排队和执行分层定位。

对 phone_get_capabilities、phone_list_devices、phone_observe、phone_find_nodes 这类无业务写入的诊断读取，先查询原 operation_id 回执；若确需获取当前状态，可在退避后发起至多一次新的只读请求，并保存新旧操作的关联。这是 Agent 的有限恢复策略，不是 SDK 已自动重试的承诺，也不改变原 delivery_unknown 的执行未知语义。第二次仍失败则停止并报告。不得将该规则扩展到动作、消息发送、安装、设置修改或包含写步骤的 phone_execute；不通过重建身份、清缓存或换 ID 重放未知写入。

在此设备观察到 UiAutomation 读取之后 Accessibility 暂时不可用，甚至两后端短时同时失败，约 25–30 秒后恢复。系统 enabled_accessibility_services/accessibility_enabled 仍开启不等于服务已连接。AOSP 的旧式 uiautomator dump 使用默认 UiAutomation.connect；Android 另有 FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES，说明自动化连接可影响其他无障碍服务。这是可信机制，尚不能仅凭设置列表断言本机每次故障的精确原因。实现依据：[UiAutomationShellWrapper](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-31/+/refs/heads/main/com/android/uiautomator/core/UiAutomationShellWrapper.java)、[UiAutomation flags](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/app/UiAutomation.java)。

遇到 permission_required/backend_unavailable，先读 caps 的 accessibility_authorized 与 accessibility（当前连接）及 privilege 状态。只有已授权、且有近期后端切换或服务重绑证据时，才按可能瞬态处理：先退避 2–5 秒，在最多 30 秒诊断窗口内少量只读探测；恢复未确认则停止并报告。不要在两后端间快速交替 dump，不强行开关系统授权或抢占其他 Agent。真实未授权、用户关闭服务、Root/Shizuku 未授权、invalid_arguments 都不适用瞬态重试。25–30 秒是一次设备现象，不是协议冷却时间或固定 sleep 要求；当前 SDK 不会自动区分这些错误。

## 清理

完成后 phone_end_ui → phone_disconnect → DELETE 当前 MCP 会话。正常返回 204 后删除本地缓存。断开当前连接不撤销共享绑定，不影响其他 Agent。用户手动断开优先于脚本恢复与收尾；遇到明确停止标记立即停止。

initialize 返回 _meta.remotetool.self_cleanup="resume-token" 时，也可仅发送 DELETE 原 /mcp URL，并携带绑定/App Bearer 和 X-RemoteTool-Resume-Token（私密恢复凭据），不带 Mcp-Session-Id。它仅关闭这份恢复凭据对应的逻辑 owner，传输过期或已替换仍可清理；不会关闭同绑定的其他 Agent。两种 owner 凭据只能选一种，无 owner 凭据不会执行批量删除。执行中返回 409 device_busy、execution=not_started 和 retry_after_ms；优先等回执，而非连续重发删除。

Phone.cleanup_cached()/phone_mcp.py cleanup 不执行 initialize；服务未公布能力时明确拒绝，保留缓存。正常 dispose 在能力已公布时合并为一次自助 DELETE，204 后才删除本地缓存。不要在 resume 成功后再静默 DELETE 旧 sid：旧传输本已被替换，404 不代表清理了另一个身份。只有当未知恢复凭据返回 resume_unknown 后，由用户明确决定是否创建新连接。

UI CLI 的 --close-on-exit 面向独立任务，在异常路径也尝试收尾，保留原始异常；默认不自动关闭，允许多命令共享一个 owner。不能给每条 screen/tap 命令都加自动关闭。

## 真机记录与证据边界

- 本次手机（Android API 37）新版群聊样本中，include_unimportant=true 为 55 个节点、false 为 41 个；测试 limit=300/600/1200 均 truncated=false、visited_nodes 约 229，正文均可读。这是单页样本，不保证所有群聊页都不会截断。接口 limit 上限是 600，传 1200 会被钳制，不会实际扩大到 1200；应先检查 truncated/visited_nodes，不要仅因历史长就放大 limit。
- raw 模式可包含不可见诊断行；compact/text 主要返回可见语义节点，不能将“所有无障碍树都只含当前屏幕”当作契约。
- UiAutomation 的 p 节点只读，后续动作必须在 Accessibility 上重新定位。新错误码为 read_only_backend；历史版本可能返回 stale_snapshot。
- 曾观察到 dispose 后立刻重连出现 delivery_unknown、等待 30–45 秒后恢复。这是待定位现象，不是协议冷却时间，不应作为固定 sleep 规则。服务端 relay_metrics 的 total_ms/send_ms/reply_wait_ms/send_completed 与设备 metrics/客户端 transport 分层定位；超时不自动重放写操作。
- 悬浮窗计数来自 AgentActivityLedger 的当前连接视图，PhoneConnectionLog 的活动/历史记录是另一层。2026-10-27 已对 Web 转发进行 305 秒真实空闲测试：旧传输返回 404，手机空闲名称撤下，保活观察者仍在，恢复后同一 logical agent 的回执可查询。直连服务的手机端五分钟规则需安装对应新 App 后验证，不能仅从日志 active 推断悬浮窗连接数。
