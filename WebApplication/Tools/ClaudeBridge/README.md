# Claude Code 本机接入

RemoteTool 通过官方 `@anthropic-ai/claude-agent-sdk` 驱动服务端机器上的 Claude Code。模型执行、项目文件、登录与 Claude 配置都留在服务端；Web 使用与 Codex / Hermes 相同的会话工作台（`/#/claude`），接口为 `/api/claude`，契约与 `/api/codex` 一致。Android 主 App 同样复用共享原生会话工作台；需要支持本版协议 v2 的服务端。

## 安装

1. 在运行 RemoteTool 的系统用户下安装官方 Claude Code（需支持 `claude auth status --json` 与 `claude agents --json`；以 2.1.322 验证）和 Node.js 20+。
2. 在该用户下执行 `claude auth login`，用 `claude auth status` 确认已登录。Windows 服务以 SYSTEM 运行时，桥接会像 Codex 一样在已登录的桌面用户会话中启动（需要桌面用户已登录）。
3. 源码运行：在 `WebApplication/Tools/ClaudeBridge` 执行 `npm ci --omit=dev --omit=optional`。发布包：在发布目录 `claude` 下执行同一命令。桥接使用本机已安装的 `claude`，不需要 SDK 捆绑的可选二进制。
4. 打开 Web 或 Android 主 App 的 Claude Code 页面，就绪卡片只检查安装与登录，不发送提示词。

可选配置（环境变量用双下划线，如 `Claude__Executable`）：

```json
{
  "Claude": {
    "Enabled": true,
    "Executable": "/absolute/path/to/claude",
    "NodeExecutable": "/absolute/path/to/node",
    "AllowCustomEndpoint": false
  }
}
```

- 未配置路径时在 PATH、用户 `.local/bin`、`/opt/homebrew/bin`、`/usr/local/bin` 查找；Windows 需指向 `claude.exe` / `node.exe`。
- `AllowCustomEndpoint=false`（默认）时，配置了非官方 `ANTHROPIC_BASE_URL` 或 Bedrock/Vertex/Foundry 的项目会被拒绝执行；设为 `true` 后按本机 Claude Code 配置原样执行。
- 用户配置、CLAUDE.md、hooks、skills、插件和 MCP 由官方 Claude Code 加载；RemoteTool 不复制凭据，不提供远程登录。

## 功能

- 会话：列出本机全部 Claude Code 会话（含终端创建的），新建（选择工作目录与权限模式）、重命名、置顶、删除、从任一消息分叉、撤回并编辑（下一轮以 `resumeSessionAt` 从所选位置继续，发送前不改动原始记录，可取消）。
- 执行：流式回复与思考过程、工具进度、执行中插话（同一进程的流式输入）、停止（先 `interrupt()`，10 秒未结束才强制终止）、`/compact` 压缩、附件（图片内联，其他文件给出路径）与 `MEDIA:` 回传文件。
- 审批：工具授权“仅允许本次 / 拒绝”；只有 Claude 原生给出建议规则（`suggestions`）时才显示“允许并保存 Claude 建议的规则”，保存位置由原生建议决定。`AskUserQuestion` 在页面内选择或自填回答。计划模式的 `ExitPlanMode` 作为审批显示计划内容。
- 设置：每个会话的模型与推理强度（下一轮生效），权限模式 `default` / `acceptEdits` / `plan`（执行中修改立即作用于当前任务）。不提供绕过权限模式。
- 状态：上下文用量（原生 `contextWindow`，未知时估算并标注）、累计 Token、通过官方 SDK 的结构化 `/usage` 控制接口主动读取账户额度（不发送提示词、不调用模型、不保存临时会话；60 秒缓存并合并并发请求）。失败时标注上次结果，API key 等不适用订阅额度的登录方式明确说明；不会把 `allowed` 事件或空使用率当成剩余 100%。

## 会话占用与安全

- 开始每一轮前用 `claude agents --json` 检查会话是否被电脑上的其他 Claude 进程持有；持有时只读，并显示 PID 和该进程空闲/执行中。
- 接管：用户确认后，向占用进程发送 SIGTERM 正常退出（不强杀），等它从占用列表消失后放行；10 秒未退出则报错。发信号前会用确认时看到的 PID 与启动时间实时复核，进程已变化则拒绝。会话记录保留，电脑上可用 `claude --resume` 再打开；执行中的进程会被中断，页面会提前说明。桥接自身的 SDK 子进程在 Unix 上按父进程识别，不会被误判。这是开始前的检查，不是全局写锁：远程任务执行期间请勿在终端恢复同一会话。
- 发送使用幂等标识；回执在执行前落盘，同一标识不能用于不同内容。超时或断线时客户端只做只读核对，不自动重放。
- 进程启动前就失败（未登录、路径无效等）记为“未完成”，可直接重发；已开始执行后连接中断，或桥接进程退出，记为“结果待核对”，该会话的新消息被阻止，直到用户在页面上确认“我已核对”。原任务不会被重发。
- 错误信息使用固定文案，不回显 SDK/插件的原始异常（可能含凭据）。

## 进程与数据

- 桥接是按需启动、仅监听 `127.0.0.1`、使用独立 Bearer 令牌的 Node 进程；令牌不发给客户端。它独立持有任务，页面关闭或 RemoteTool 服务重启不会结束任务；不重定向标准输出，宿主退出不会因管道关闭而终止桥接。
- 数据位于 RemoteTool 数据目录 `claude-bridge/`：连接文件、`journal/state-v2.json`（会话元信息、任务、回执；只在状态变化时写入，保留最近 300 个已结束任务，回执保留 14 天）。流式事件只在内存中保留（每个任务最多 2000 条，结束后 15 分钟），重连通过 `Last-Event-ID` 续传，缺口会发出 `stream.gap`。
- 附件位于 `claude-attachments/`，可在“空间管理”中清理；执行中的会话附件不会被清理。
- 升级：`PROTOCOL`（接口契约）与 `BRIDGE_VERSION`（实现版本，对应 `RemoteToolPaths.ClaudeBridgeVersion`）不一致时，宿主只在桥接空闲时请求其退出并启动新版本；有任务执行或等待确认时继续使用旧进程（协议兼容时）或提示稍后重试，不强杀。服务端更新的空闲检查包含 Claude 任务；桥接无响应时视为未确认空闲。

## Android 主 App

Claude 与 Codex/Hermes 共用原生会话工作台、Markdown 消息、附件、工具详情、模型选择、审批与问答组件；不再维护独立聊天页面。使用协议 v2 的 `/api/claude`，保留原始字符串消息 ID；分页、回执核对和 SSE 恢复沿用共享状态管理。工作目录通过通用远程目录选择器选择。

会话权限只显示本机规则询问、自动批准文件编辑、计划模式；持续规则只来自原生审批建议。桌面接管提交用户确认的 PID、启动时间和空闲/执行状态；结果未知时先核对并明确确认，不自动重发。不显示 Claude 服务端未支持的 Provider 管理、账户切换和定时发送入口。

## 验证

- Android：`:mobile:testFullDebugUnitTest` 中的 `ClaudeProtocolTest` / `ClaudeWorkspaceTest` 覆盖共享历史/缓存、原始 ID 分叉、分页、审批多选、待落盘输出、发送超时与回执恢复（本机模拟服务，不调用模型）。

- `node --test tests/ClaudeBridge/bridge.test.mjs`：历史投影、回执幂等、审批与建议规则、问答、插话、停止、启动前失败与待核对、外部占用、撤回、重启恢复、HTTP 鉴权与 SSE 续传（模拟 SDK，不调用模型）。
- `node tests/claude-browser.test.cjs`：共享工作台中的 Claude 交互（模拟接口），需要已构建的 WebApplication.dll、Chrome 与 Playwright。
- `tests/UpdaterReadiness`：Claude 任务执行中、等待确认、桥接无响应时阻止服务端更新。
- 真实运行（macOS，官方 Claude Code 2.1.322 + claude.ai 登录，Haiku）：流式回复、文件写入审批、AskUserQuestion、停止、插话、重命名、撤回并编辑、分叉、删除均已验证。Windows 交互会话启动与 Linux systemd 尚未真机验证。
