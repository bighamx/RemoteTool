# Hermes 原生模块

入口：底部 Hermes 标签。当前电脑的 RemoteTool 服务端连接本机 Hermes；手机沿用设备地址与现有登录，不直接访问 8642，也不保存 API Server 密钥。

## 界面与操作

- 会话列表：搜索、分页、选择已有会话。点“+”或 `/new` 直接创建并进入 `手机对话yyyyMMdd_HHmmss`，无需名称确认；点击聊天标题可改名，列表菜单也支持改名与删除。删除有确认提示，正在执行任务的会话禁止直接删除；服务端通过官方 Session API 修改和删除，不修改 Hermes 数据库。
- 聊天：用户/Agent 消息、可复制回复、工具与执行进度、待审批卡片、停止按钮。现有 CLI 会话通过会话 ID 续接，不能承诺自动退出 CLI 或无缝同时插话。
- 输入框旁的斜杠菜单：`/new`、`/model`、`/sessions`、`/providers`、`/status`、`/stop`。这些命令由客户端映射到原生操作，不作为普通提示词交给模型。
- 模型选择：读取 Hermes 的实际 Provider/模型目录；当前会话通过 model-lock API 锁定，全局默认通过官方配置处理函数保存。
- Provider 管理：新增和编辑 OpenAI Chat Completions、Responses、Anthropic Messages 兼容端点；保存时可提供新密钥，编辑留空保留已有值。已有密钥及其预览片段均不会返回手机。内置 OAuth Provider 使用 Hermes 中已完成的登录，不在此模块伪造 OAuth 登录流程。

## 协议选择

### 附件与存储清理

- 发送图片和文件：单个附件不超过 500 MB，每条消息最多 8 个；图片模式压缩到最长边 2048 像素，文件模式保留字节。聊天内显示图片，支持原生 Markdown。
- 回复中的独立 `MEDIA:路径` 映射为附件，避免展示机器路径；按 outbox 请求标识匹配，防止同名文件串到其他轮次。无法对应的附件显示“暂不可用”，不把任意路径直接转为可访问文件。
- 点击附件直接打开：图片适应屏幕并可缩放，视频显示首帧缩略图与播放按钮，视频和音频使用原生播放器。1 MiB 内文本在 App 预览，其余文档缓存到私有目录，再通过 FileProvider 授予查看应用临时读取权限；没有查看应用时显示明确提示。下载是单独操作。
- 视频首帧由手机通过支持 Range 的附件接口读取，服务端不做转码。预览文档与视频缩略图可随选中附件的手机副本一起清理。
- 输入栏内提供快捷命令、附件和发送按钮。菜单贴近按钮上方，不抢输入法焦点，再次点击关闭。
- 服务端附件保存在 `C:\ProgramData\RemoteTool\hermes-attachments\<session>`，生成文件保存在独立请求的 outbox。消息与附件的关联由项目自身保存。
- Hermes 0.21.5 的持久历史会把多模态图片投影为文字占位符。本项目在消息文本中持久保留本机附件路径，续聊可由 Hermes 工具重新读取；未修改 Hermes 源码，不承诺 CLI 自动重新注入图片像素。
- 手机上传临时副本在上传后删除，相册原图保留。预览由 Coil 缓存；手动下载由 DownloadManager 写入下载目录。
- 附件菜单的“查看会话附件”显示缩略图，勾选后可“清理手机副本”：删除对应缓存和本版本记录的 App 下载文件，保留原图、服务端附件和会话文本。旧版本未记录的下载可用系统文件管理器删除。再次查看附件会重新产生缓存。
- 服务端启动后及每天自动清理：最后修改时间早于当前 UTC 时间减 3 个日历月，且单个文件严格大于 100 MiB。跳过链接、内部索引和正在使用的文件；只扫描 Hermes 附件目录，不处理 Hermes 工作目录或其他文件。此规则适用于上传附件和 Hermes 生成的文件。


官方 `POST /v1/responses` 支持 `conversation` 或 `previous_response_id` 续聊，工具在 Hermes 服务端执行。手机模块采用 `/api/sessions` 持久会话 + `/v1/runs`：适合长时间工具执行、页面切换和网络断开后的恢复；同一会话 ID 自动加载 Hermes 历史，无需重发整个消息列表。

Runs 提交前在手机私有存储保存唯一 Idempotency-Key 和请求内容；失败后仅由用户主动核对并复用原 key 重试。取得 run_id 后只查询该任务与订阅事件，不重复提交。每台设备目前跟踪一个正在执行的 App 任务。

SSE 解析跳过注释与 keepalive，支持多行 data 和事件序号，重连使用 Last-Event-ID。最终完成状态由 run 查询确认，工具进度不当作客户端待执行任务。离开页面不停止 Hermes 任务，停止需要用户操作。

## 服务端部署

- 配置 `HERMES_API_KEY_FILE` 为本机 Hermes `.env` 路径，例如 `C:\Users\chuckie\AppData\Local\hermes\.env`，也可使用 `Hermes:KeyFile`。
- 常规 API 仅读取 `API_SERVER_KEY` 这一项。默认根地址为 `http://127.0.0.1:8642/`；只允许服务端回环地址，不接受手机指定 upstream URL。
- 所有 `/api/hermes/*` 均要求 RemoteTool 登录。无密钥值、环境文件内容或请求提示词日志；不自动重试 Agent POST，不跟随 HTTP 重定向。
- 模型管理辅助脚本复制到 Web 输出目录 `hermes/hermes_management.py`。默认安装目录为 `.env` 同级的 `hermes-agent`，使用其 `venv/Scripts/python.exe`；可用 `Hermes:SourceDirectory` 指定。
- 本机 0.21.5 的 API Server 未开放全局配置管理。受限辅助脚本仅调用已安装 Hermes 官方 Dashboard 的 `list_custom_endpoints`、`upsert_custom_endpoint`、`get_model_info`、`set_model_assignment`，操作白名单为四项，没有任意命令或任意配置文件接口。
- 修改模型设置前在 Hermes 自己的 `backups/chuckie-helper` 创建配置备份。测试在独立配置目录中完成，不更改本机实际默认模型。

## 上下文与手动压缩

- 显示当前消息上下文与模型窗口，优先采用 Hermes 的持久用量锚点；无锚点时标记估算。窗口使用网关的模型解析链。
- 输入框快捷命令及 `/compact`、`/compress` 使用本项目的异步压缩任务，右上角菜单不显示压缩入口，不把斜杠命令当作普通模型提示。
- 调用已安装 Hermes 的 `compress_now` 与事务内归档压缩，保持会话 ID。原始消息归档保留，提交前使用 SQLite backup API 建立可恢复快照；没有修改 Hermes 源码。
- 获得 Hermes 跨进程会话回合租约后才压缩，其他端正在运行时拒绝。任务最多运行 15 分钟，停止会结束独立辅助进程树；结果不确定时提示核对，不自动再压缩。
- 请求标识及任务结果持久化，网络断开后可以继续查询。较短上下文返回无需压缩。
- 辅助脚本在读取 stdin 前初始化 Hermes 官方运行环境，兼容托管 Python 依赖代际切换；只输出经过筛选的最终 JSON，启动诊断不发送到手机。
- 当前 HTTP API 的审批响应不等同于 `clarify` 的结构化问答。安装版本的 HTTP Agent 没有配置 clarify 回调；普通追问可以通过下一条消息或 steer 回答。

官方参考：https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
