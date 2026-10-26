# Android 手机操控 Agent 使用指南

## 连接原则

一个 Agent 在一个持续任务中只使用一个逻辑 MCP 会话，持续复用至任务完成。HTTP 连接可短开短关，MCP 会话不可每次重建。优先调用已经配置的 MCP 或 RemoteTool 注入的 `phone_*` 工具，不要另外生成脚本、重复初始化。`clientInfo.name` 只决定显示名称；同名重复 `initialize` 仍会产生不同身份。

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

UI、节点快照、协助通知均按会话隔离。不能在 A 上 `begin_ui` 后改用 B 点击、读 A 的通知或释放 A 的占用。观察和截图也是 UI 操作。Shell 读取或改变屏幕时设 `ui_operation=true`。节点使用本会话最新 `snapshot_id/node_id`，30 秒后失效，页面变化后重新观察。

`phone_end_ui` 仅结束一批 UI 操作，不断开连接。下一批再次 `begin_ui`，不再 `initialize`。UI 无须续期，10 分钟无 UI 操作自动释放；其他连接可并行处理非 UI 任务。同一批中的观察、操作、核验按顺序执行，不并发点击和读取变化中的页面。

普通操作用可选 `explanation` 简述动作与目的（用户语言、最多 240 字符），不写凭据、敏感输入或内部推理。需要用户解锁、授权或人工处理时使用 `phone_notify_user(kind=help)`，保留 `notice_id`，释放 UI 后用同一连接以合理间隔查 `phone_get_user_reply`；pending、超时、dismissed 不代表完成。得到回复后重新获取 UI 并观察，再继续依赖该步骤的操作。

## 遇到问题

- `ui_busy/device_busy`：等待或说明冲突，不新建身份绕过，不尝试释放其他连接。自己失去旧会话时，无法从新身份替旧身份 `end_ui`；可请用户在连接管理中断开明确属于自己的旧连接，或等待空闲释放。
- HTTP 404 `session_not_found`：旧会话已失效，删除对应缓存，串行重新初始化一次；检查能力、重新取得 UI 并观察。不要把任意 404、401、超时都当作会话过期，不盲目重放原操作。
- `connection_closed_by_user`：用户主动断开，停止并明确告知；不得自动初始化绕过。只有用户明确要求继续后，才新建会话。
- `delivery_unknown`、超时、丢失响应：操作可能已发生，不能自动重发写操作或换 ID 绕过去重。先查回执或实际状态。`request_id` 用于同一逻辑操作的去重，同 ID 不可用于不同操作；它不等于 MCP 会话 ID 或 JSON-RPC 消息 `id`。
- `phone_offline`、权限/后端不可用：说明具体阻碍，必要时请求用户协助。不要把连接成功、命令已提交、点击成功当成最终业务成功。

## 结束与历史记录

任务完成、失败、受阻或放弃均清理自己的 UI，最后调用 `phone_disconnect`；不要在每步或每批之间断开。HTTP 客户端可再 DELETE 原 MCP URL，释放该传输会话并删除对应缓存。只退出脚本不等于断开。清理后不要再截图、通知或重新初始化来“验证已断开”；不要撤销设备级绑定，也不要清理其他 Agent。

Agent 主动 `phone_disconnect` 结束当前手机活动，但不销毁 MCP 传输会话；后续有意继续的新工具调用可重新激活原身份。手机端用户手动断开则会拒绝旧身份继续调用，必须遵循上面的用户授权规则。断开后历史日志保留，不代表仍在连接；“清除历史”不能代替断开活跃连接。服务端会话空闲回收与手机展示清理是独立机制，当前不会即时同步结束。
