# Hermes 消息身份与实时历史接管

核对日期：2026-11-06。适用于 RemoteTool Web 与主 Android App。

## 已核实的运行差异

- Hermes `messages.message_uid` 是稳定身份；数值 `id` 继续用于分页、编辑及删除接口。
- 本机当前运行的 Hermes 历史 HTTP 响应没有返回 `message_uid`，虽然 SQLite 已保存它。不能只根据磁盘上的新源码判断运行能力。
- 当前 `message.interim` 只有 `text`、`already_streamed` 等字段，没有 UID。历史有 UID 本身不足以完成实时消息的精确对账。
- 原生历史中出现过用户/助手时间戳相差数毫秒且倒挂的情况，显示顺序必须保留原生数组顺序，不能重新按时间戳排列。

## RemoteTool 的处理

服务端在已有的历史元数据读取中，按当前 Profile、会话和消息行号只读补齐缺失 UID；保留上游已有 UID。不写 Hermes 数据库，不另造消息 ID。

Web/App 将 UID 保存到历史及离线缓存，优先用于 UI 身份和实时/历史接管。正文相同但 UID 不同的消息分别保留；同一 UID 的行号变化不会产生新气泡。原生历史暂时只有前缀时，保留同一 UID 的尚未落盘尾部，直至历史接管。

旧实时事件没有 UID 时，兼容逻辑使用当前回合、用户边界和完全相同的可见正文进行一对一确认，避免一条历史消息吞掉多段相同旁白。只清理助手正文开头的思考包装标签，不改用户、代码块或行内引用。实时内容清理后仍等待历史确认，不以一次历史刷新作为丢弃依据。

## Hermes 上游需要透传的字段

`message.interim` 建议使用以下结构，`message_uid` 必须等于该条持久化助手消息的 UID：

```json
{
  "event": "message.interim",
  "text": "可见旁白",
  "already_streamed": true,
  "message_uid": "与历史记录相同的32位hex"
}
```

有 UID 的 `message.started`、`message.delta`、`message.snapshot`、`message.completed` 同样透传此字段。无法确定 UID 时可以省略；不能为推送另造一个与落库无关的 UID。

普通工具回合的旁白在数据库追加成功后才发出，可以直接从已同步持久化标记的助手消息对象读取 UID。需要沿 `agent/stream_delivery.py` → `gateway/session_api_turn.py` → API/TUI 回调链完整透传；实时专属路径若早于落库，则需保证后续 interim/completed 使用最终落库的 UID。

本次只修改 RemoteTool；没有修改、重启本机 Hermes 安装。

## 回归要求

覆盖实际 SSE 分发到历史接管的调用链：拆分标签、工具边界、重复事件、插话时间倒挂、落盘延迟、相同正文不同 UID、同 UID 行号变化、interim 才补 UID、历史只有前缀、离线缓存恢复，以及 Codex 原有消息行为。服务端测试验证 UID 不跨会话回填、上游字段优先且 SQLite 文件内容不变。
