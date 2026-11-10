# Claude Code 本机接入

RemoteTool 通过官方 `@anthropic-ai/claude-agent-sdk` 驱动本机 Claude Code。模型执行、项目文件和 Claude 配置留在服务端机器；浏览器与 Android 主 App 使用同一组 `/api/claude` 接口。入口为 Web 的 Claude Code，以及主 App「更多 → Claude Code」。独立手机操控 App 不包含此功能。

## 安装

1. 在运行 RemoteTool 的操作系统用户下安装并配置官方 Claude Code（当前桥接要求支持 `claude auth status --json` 和 `claude agents --json` 的版本；以 2.1.322 验证）。先在本机执行 `claude auth login`，再用 `claude auth status` 确认官方认证可用。Windows 服务若由 SYSTEM 启动，不会继承桌面用户的登录和钥匙串；应让 RemoteTool 使用已配置 Claude 的专用用户运行。
2. 安装 Node.js 20+。
3. 源码运行：在 `WebApplication/Tools/ClaudeBridge` 执行 `npm ci --omit=dev --omit=optional`。发布包运行：在发布目录 `claude` 下执行同一命令。桥接始终使用本机已安装的官方 Claude 可执行文件，因此省略 SDK 额外捆绑的可选二进制。发布包包含桥接源码和锁定依赖清单，不打包平台专属的 node_modules，也不会在服务启动时联网安装依赖。
4. 启动 RemoteTool，打开 Claude Code 页面检查就绪状态。只检查安装/认证，不会发送提示词。

可选服务端配置（环境变量用双下划线，例如 `Claude__Executable`）：

```json
{
  "Claude": {
    "Enabled": true,
    "Executable": "/absolute/path/to/claude",
    "NodeExecutable": "/absolute/path/to/node"
  }
}
```

未配置程序路径时，在 PATH、用户 `.local/bin` 和常见 macOS 安装路径查找。Windows 路径需指向真正的 `claude.exe` / `node.exe`，不通过 shell 执行命令。用户配置、CLAUDE.md、skills、插件和 MCP 由官方 Claude 加载，RemoteTool 不复制凭据、不提供远程登录令牌输入框。不支持备用 `CLAUDE_CONFIG_DIR`、第三方模型网关和非官方云提供方。

官方 CLI/SDK 的实际认证可用性受 Anthropic 当前产品规则约束。本机 claude.ai Pro 登录已完成 SDK 初始化验证，未发送模型提示词；这不代表第三方产品可以向其他用户提供 claude.ai 登录或订阅额度。发行与使用应遵循 [官方 SDK 认证说明](https://code.claude.com/docs/en/agent-sdk/overview)。官方 API 认证需用户自行在本机配置。

## 支持范围

- 新建 RemoteTool 管理的 Claude 会话，选择本机项目目录与 `default`、`acceptEdits`、`plan` 权限模式；不默认绕过权限。发送消息会执行项目内的原生配置、hooks、skills 与 MCP。
- 流式文本、工具调用/结果、按次授权、AskUserQuestion（包括多选/自填）、停止、Token 和 SDK 参考费用（不是订阅账户的实际扣费账单）。
- 最近 300 个本机会话历史查看（RemoteTool 受管会话另行完整列出）；RemoteTool 创建的会话可在后续轮次恢复。电脑其他入口创建的会话本版仅只读，不接管其进程。每次启动轮次前通过官方进程列表核对会话是否仍由其他进程持有，无法确认时拒绝写入。
- 暂不支持附件、修改历史、模型切换、订阅余量、执行中追加消息或导入外部会话写入。UI 不提供这些尚未实现的入口。

会话拥有者检查是开始执行前的检查，不是对其他桌面程序施加全局写锁；远程任务运行期间请勿在另一个终端恢复同一会话。

## 任务与回执

桥接是按需启动、仅监听回环地址且有独立 Bearer 鉴权的本机 Node 进程。它独立持有任务，页面离开/网络断开/RemoteTool Web 服务重启不会主动结束轮次（macOS 进程复用已验证；Windows/IIS 若由宿主作业对象终止子进程，将进入下述“结果待核对”恢复流程，尚需 Windows 真机验证）。RemoteTool API 继续要求登录，回环令牌不发给客户端。`Claude:Enabled=false` 禁止新的接入请求，不会强杀已有 worker。

数据位于 RemoteTool 数据目录的 `claude-bridge/`，包括仅当前操作系统用户可读写的连接文件、会话元信息、发送幂等回执和增量事件日志。官方 transcript 保存在用户原生 Claude 目录；不改写用户历史。回执在执行前落盘，同一标识不可用于不同消息；浏览器和 App 保留未确认标识并提供只读回执核对，未知结果不自动重放。

若桥接进程本身退出，恢复后的未结束任务显示“结果待核对”，不冒充停止/失败，也不重发原任务。此会话的新轮次会被阻止；应先核对本机历史和工具实际结果，必要时新建会话继续。授权仅对应本轮的特定请求 ID，不创建额外的持久 allow 规则。停止可能发生在命令已开始后，请核对工具副作用。

维护或更新 bridge 源码前，等待所有任务与待确认请求结束，再停止旧 worker；不能仅为升级强杀运行中的进程。服务端与 SDK 文件更新不会热替换正在运行的 worker。

## 验证

- `node --test tests/ClaudeBridge/*.test.mjs tests/ClaudeBridge/*.test.cjs`：幂等、并发、授权、问答、停止、恢复与历史保护。
- `node tests/claude-browser.test.cjs`：隔离的后端与浏览器交互，需要 Playwright 与 Chrome、已构建的 WebApplication.dll。
- Android：`:mobile:assembleFullDebug :mobile:testFullDebugUnitTest :mobile:lintFullDebug`；没有修改独立 phone flavor。
- 自动化任务执行使用模拟 SDK/接口（鉴权/进程测试会启动真实桥接但不提交提示词），不调用模型、不改动个人 Claude 配置；真实 CLI/SDK 初始化验证和完整模型推理验收分别记录。
