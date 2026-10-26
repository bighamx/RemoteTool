# RemoteTool 手机 MCP 契约

本脚本面向 schema_version=3。能力清单共 30 个工具，实际 tools/list 会按权限过滤；无需为旧服务保留执行分支。标准 MCP 协议版本协商与应用工具 Schema 是两个不同维度。

## 地址与鉴权

直连手机使用 http://<可达手机地址>:18791/mcp 和手机工具令牌。转发通道使用 http://<服务端>/api/phone/v1/bound/<绑定ID>/mcp 和该绑定的令牌；不是 Web 登录 JWT。直连地址需要网络可达，不强制同一网段。

配置文件 $HERMES_HOME/state/phone-mcp.json 内容为 {"url":".../mcp","token":"..."}。bindings 文件只接受当前 HERMES_SESSION_ID 或 --binding 精确键。独立聊天用独立 PHONE_MCP_AGENT；同一聊天的多进程沿用该值。

## HTTP 与逻辑身份

1. POST initialize，客户端声明 protocolVersion=2025-11-25。
2. 保存响应头 Mcp-Session-Id、result.protocolVersion 和 result._meta.remotetool.resume_token。
3. 发送 notifications/initialized，返回 202；后续请求带会话头和 MCP-Protocol-Version。普通响应为 JSON；GET 为 405，不依赖 SSE。
4. 只有明确 HTTP 404/code=session_not_found 才串行恢复：initialize.params._meta["remotetool/resume_token"]=原密钥。沿用同一逻辑 owner，新传输替代旧传输。不是按 clientInfo.name 合并身份。
5. 其他 404、401、网络超时、device_busy 不触发重建。session_recovery_failed/connection_closed_by_user 必须停止；不静默丢弃密钥另建身份。

传输空闲 30 分钟回收，恢复凭据最长空闲 24 小时，受容量上限影响。恢复表在内存中，服务重启后不能恢复原逻辑身份。首次 initialize 回包丢失时无法恢复尚未收到的密钥，不自动重试。

缓存以端点、令牌、聊天标识隔离，用 OS 跨进程锁串行初始化、原子文件写入。密钥不写到输出、日志或说明文本。缓存损坏时显式失败，不自动换身份。

## UI 与快照

phone_begin_ui 后才能 UI 观察/动作；UI 占用独享，不能抢其他 owner。compact 默认选择应用窗口并过滤不可见/非法节点；raw 和 include_system 供诊断按需使用。文本模式仍是无障碍树文本，不保证识别画面全部内容。

观察上限 600 节点；filter 先筛选再受输出上限限制，fields 可投影。快照 owner 隔离，保留 30 秒；旧节点不得搭配新快照。changed_since 仅用于相同 owner、窗口和查询范围，范围变化会返回完整状态。节点 bounds/tap_point 为物理屏幕像素；截图另含 image_to_screen 缩放信息。

tap/input 可用 selector；action 可带 guard、wait_for、return_state。selector 要唯一，guard 在执行前检查；matched=false 表示动作后等待超时，必须核对当前状态。phone_wait 支持 text、text_regex、package_name、selector、absent 与 stable_ms；超时 1..10000ms，stable_ms 0..2000ms，直接返回快照。

phone_execute 参数为 steps=[{"tool":"phone_tap","arguments":{...}}]、max_ms、return_state。1..20 步，预算 100..30000ms。不会回滚已执行步骤；已开始的底层动作可能超过总预算后完成。批次只允许公布的 UI/观察工具，不允许 shell、递归批次或连接生命周期工具。

## 回执与错误

tools/call 的 params._meta["remotetool/request_id"] 标识一个逻辑操作，区别于 JSON-RPC id。恢复会话后重用同一操作 ID，不能把部分批次换新 ID 整批重放。

结果 structuredContent 包含 operation_id、execution（not_started/receipt_available/unknown）、retryable 和 metrics。isError 是工具失败标记，不能用字段数量判断错误。部分批次还含步骤回执和 completed_steps；应保存失败响应中的 state。

phone_get_operation({"operation_id":"原ID"}) 仅查询当前 owner 的记录。delivery_unknown、无有效 JSON 响应或连接中断后，先查询/核对状态，不自动重发。accepted、matched 或 verified 都不直接等于消息送达/订单成功。

耗时分层：Phone.transport 保存最近一次 HTTP 的 http_ms、response_bytes 和 serialize_ms；同时可读取响应头 X-RemoteTool-Serialize-Ms、X-RemoteTool-Response-Bytes 与结果 metrics（设备执行/通道排队）。对比效率需同设备、同任务实测，不能把编译测试通过当成手机提速证明。

## 清理

完成后 phone_end_ui → phone_disconnect → DELETE 当前 MCP 会话。正常返回 204 后删除本地缓存。断开当前连接不撤销共享绑定，不影响其他 Agent。用户手动断开优先于脚本恢复与收尾；遇到明确停止标记立即停止。
