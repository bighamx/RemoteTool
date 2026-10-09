# 手机会话置顶

在 Codex 或 Hermes 会话卡片的三点菜单选择「置顶会话」；置顶后标题前显示图钉，并始终排在未置顶会话之前。再次选择「取消置顶」恢复普通列表。搜索仍按搜索条件过滤。

- Hermes：调用原生 `PATCH /api/sessions/{id}` 的 `pinned` 字段，使用 SessionDB 的持久置顶记录；CLI、桌面和手机读取同一记录。原生置顶还会阻止自动归档。
- Codex：官方新协议文档包含 `isPinned`，但本机 `9691020b546a15b2` 版本导出的 `ThreadMetadataUpdateParams` 和 `ThreadListParams` 尚未包含该字段。当前使用 Codex Home 下的 `remotetool-session-pins.json` 保存 RemoteTool 置顶，仅保存会话 ID，以原子替换写入。不同设备、不同用户目录分别保存；暂不与 Codex 桌面置顶同步。
- 统一手机接口：`POST /api/{hermes|codex}/sessions/{id}/pin`，请求 `{ "pinned": true }` 或 `false`。使用现有登录认证；确认服务端返回状态后才更新 UI，提交期间禁止重复点击。
- 第一页补齐较旧的置顶会话，并保持游标／分页偏移与实际普通列表一致；置顶分组内保持服务端顺序。Codex 补齐使用只读 `thread/read`，不恢复会话，不获取写入权限，不启动模型任务。

这项功能需要同时更新手机 APK 和 Web 服务端（包括 Codex bridge）。
