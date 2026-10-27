---
name: android-phone-ui-automation
description: 使用 RemoteTool 手机 MCP 操作 Android 应用，读取界面、点击、输入、滚动和验证结果。
version: 3.1.2
author: Hermes Agent
license: MIT
tags: [android, ui-automation, remote-tool, accessibility, mcp]
metadata:
  hermes:
    related_skills: [android-device-inspection, phone-notify-channels, remotetool-app]
---

# 手机界面操作

适用于用户授权的手机应用操作、页面读取、查询和消息处理。使用 RemoteTool schema 3 的 HTTP MCP；脚本不兼容旧服务。先读 references/protocol.md 了解身份、回执和错误处理。

动作附带 wait_for 默认要求窗口或内容相对 state_before 发生变化，再判断条件；用目标页独有控件验证跳转。仅在预期界面不变时显式 require_change=false。独立 phone_wait 默认允许当前条件立即命中。revision 是同一 revision_epoch 内的跨窗口观察序号，增加不证明内容改变；查看 window_id/signature 和 state_changed。

## 接入

两种地址使用同一套工具：

- 直连手机：http://<手机可达地址>:18791/mcp，由本应用直接提供 HTTP MCP。
- 转发通道：http://<RemoteTool服务端>/api/phone/v1/bound/<绑定ID>/mcp，可跨网络访问在线手机。

配置优先级：显式 url/token → PHONE_MCP_URL/PHONE_MCP_TOKEN → $HERMES_HOME/state/phone-mcp.json → 当前会话的 remotetool-phone-bindings.json 精确绑定。不选最后一条绑定，也不做模糊匹配。

每个聊天设置独立且稳定的 PHONE_MCP_AGENT，或使用实际 HERMES_SESSION_ID。短进程复用该聊天的缓存身份。不能给所有 Agent 共用一个名称。令牌只取用户配置或当前聊天绑定；工具未绑定时报告配置缺失，不搜索源码、其他会话或凭据库。

配置文件也支持 agent，例如 {"url":"http://<地址>/mcp","token":"<令牌>","agent":"当前聊天的稳定ID"}。身份优先级为显式 agent → PHONE_MCP_AGENT → HERMES_SESSION_ID → 配置文件 agent；不自动生成全局默认身份。多聊天共用配置时，以各自的运行时身份覆盖文件值。

连接超时、拒绝连接或 DNS 失败时，先检查地址、网络和手机在线状态：手机局域网 IP 可能随 DHCP 变化；转发绑定也可能已撤销或失效。401/403 则按鉴权或访问限制排查，不因网络失败盲目更换令牌。直连 App token 与服务端绑定 token 是不同凭据；直连 token 可主动轮换，绑定撤销/重建也会影响接入，不能假定令牌只在换机或重装后变化。多台手机按任务同时指定匹配的 --url/--token 或 PHONE_MCP_URL/PHONE_MCP_TOKEN，配置文件只承载一个端点，不使用第二套多机注册表，不自动选机。

Agent 应声明真实显示名称：Phone(client_name="Hermes 商品查询", agent="稳定聊天ID")，或 UI.begin(client_name="Hermes 商品查询")；CLI 提供 --client-name。MCP 通过 clientInfo.name 上报，phone_begin_ui 通过 client_name 上报，均不是鉴权或接管凭据。

## 推荐操作方式

在一个 Python 进程中完成一个阶段，避免每个动作都启动终端。默认先读 compact UI 树；动作使用 selector、guard、wait_for、return_state，直接复用返回的状态，避免“点击 → 固定 sleep → 再 observe”。

    from phone_ui import UI
    ui = UI(agent="当前聊天的稳定ID", explanation="查询用户指定商品")
    try:
        ui.caps()
        ui.begin("查询商品")
        ui.launch("com.example.shop")
        print(ui.screen())  # 复用 launch 返回的状态
        ui.tap(selector={"resource_id": "com.example.shop:id/search"},
               guard={"package_name": "com.example.shop"})
        ui.type("用户指定商品")
        ui.tap(selector={"text": "搜索"},
               wait_for={"text_regex": "价格|结果", "timeout_ms": 10000})
        print(ui.screen())
    finally:
        ui.p.dispose()

例子中的包名和 resource_id 是占位符；先观察实际页面再选目标。每个调用附简短 explanation，不能含令牌、密码或内部推理。

## 减少往返

已确定的短步骤可用 ui.execute(steps, max_ms=15000, request_id="任务内唯一操作ID")，在手机端串行执行并返回每步回执和最终状态：

    steps = [
        {"tool": "phone_tap", "arguments": {
            "selector": {"resource_id": "com.example.shop:id/search"},
            "guard": {"package_name": "com.example.shop"},
            "wait_for": {"selector": {"editable": True}, "timeout_ms": 5000}}},
        {"tool": "phone_input_text", "arguments": {
            "selector": {"editable": True}, "text": "用户指定商品"}}
    ]

批次限 1–20 步，总预算 100–30000ms；只接受公布的 UI/观察工具。遇到错误或条件未满足立即停止。不能将需要阅读、判断后才能决定的动作提前拼成大批次。部分执行后不要用新 request_id 重放整个批次。

ui.run(steps) 是本地编排，和手机端 execute 不同，每步格式如 {"tap": {"selector": {"text": "搜索"}}}。它也在任何一步异常时立即停止。

## 观察、等待和点击

- 验证页面跳转时，必须选择实际观察确认属于目标页的独有条件；源页也有的标题、宽泛正则、revision 增加或任意内容变化，均不能独自作为跳转成功证明。
- 同页刷新且结果可能不变时，可显式 require_change=false，但 matched 仅证明条件成立，不能证明刷新请求完成；需要应用的加载结束标记或业务回显。慢加载超时后先核对回执和当前状态，再用独立 phone_wait 分段继续等同一目标条件，不重做动作；单次 timeout_ms 最多 10000。
- observe 默认 compact；dump_text 使用 mode=text，不默认执行 shell/uiautomator。自绘页面可能两者都读不到，应按需截图核验，不能声称 shell 一定能读到。
- UI.observe(backend="accessibility", include_unimportant=True, mode="text") 显式使用快速无障碍；UI.observe(backend="uiautomator", include_unimportant=True, privilege_backend="root", mode="text") 按需读取未压缩 XML。auto 优先无障碍。include_unimportant=False 对 UiAutomation 表示压缩 dump，可能丢失 QQ 正文；mode 只改变输出投影。UiAutomation 节点只读，操作前应重新用 Accessibility 定位。CLI 的 screen/find/dump 支持 --backend 和 --no-include-unimportant。
- 优先服务端 phone_find_nodes/filter/fields 获取相关内容，减少输出量；同查询范围可用 changed_since 获取变化。
- ui.wait 支持正则或 selector 条件，并直接保存服务器返回的快照。单次等待最多 10 秒，较长等待由同一流程分段完成；不重做此前动作。
- 等待前台应用传 package_name，例如 phone_wait({"package_name":"com.jingdong.app.mall","timeout_ms":8000})；foreground_package 是结果字段，不是请求参数。invalid_arguments 应修正参数，不能按瞬态故障重试。
- tap(pattern) 是服务端文本/描述正则 selector；resource_id 使用显式 selector，不能把 resource_id 当文本正则。
- selector 必须唯一。多目标、过期节点、无效矩形或遮挡都要重新观察并缩小条件；不自动点第一个，也不降级猜坐标。
- screen() 读取最近状态；需要独立刷新时用 screen(refresh=True)。节点绑定其原快照，不能把旧节点配上新 snapshot_id。
- 不盲信旧分辨率或系统悬浮窗。使用本次观察返回的物理屏幕尺寸、foreground_package 和窗口信息；截图坐标需按 image_to_screen 比例转换。

## 输入和发送

ui.type 默认选择唯一聚焦输入框，否则必须有唯一输入框；也可提供 selector。输入后核对唯一输入框的实际文本，失败则停止后续步骤。中文使用 phone_input_text。

仅在用户已经明确授权发送内容与目标时使用：

    result = ui.send("用户授权正文", expected_chat="精确会话标题",
                     package_name="com.tencent.mobileqq")

输入和发送均带包名及会话标题 guard。脚本区分 submitted、verified、delivery_confirmed：点击提交不等于送达，输入框清空且消息回显也只证明界面反馈。验证不足时检查截图或应用状态，不自动重发。标题带人数等附加内容时先读取实际完整标题；目标不明确则先澄清，不能把“换个文案”理解成发送授权。

使用简称时，必须同时提供实际观察到的标题控件资源 ID：ui.send("正文", expected_chat="后花园", package_name="com.tencent.mobileqq", chat_resource_id="<真实标题resource_id>")。脚本先刷新界面，在指定控件中唯一匹配简称，再以完整标题和资源 ID 做执行前 guard；不会把正文中的群名当标题，也不会直接把 guard 放宽成任意包含匹配。CLI 对应 --chat-resource-id。

核验失败时返回 verification_reason。只有观察被截断才补读到 600 节点；compact/text 共用无障碍数据，自绘气泡不可读时换成 mode=text 不能恢复缺失内容，应截图人工或视觉核验。补读失败仍保留 submitted 和原 operation_id，不将提交包装成可以重发的失败。

## 回执与收尾

delivery_unknown 或网络中断后，保留 operation_id，调用 phone_get_operation 查询，再结合当前状态决定下一步。不要创建新 ID 重发写操作。matched=false 表示后置条件未满足；不是动作一定未发生。

新会话首次只读调用也可能 delivery_unknown，不能仅归因于升级。caps/list_devices/observe/find_nodes 可按 references/protocol.md 的有限策略，在查原回执、退避后最多另发一次只读请求；SDK 并未自动重试，写操作与有写批次仍禁止盲目重发。UiAutomation 后若无障碍短暂断连，先核对 caps 的授权与连接状态，仅对已授权且有重绑证据的情况有限退避；不要把所有 permission_required/backend_unavailable 都视为瞬态，也不要快速交替 dump 阻碍恢复。

ui_busy 的错误 structuredContent.details（Python 异常的 receipt.details）包含 owner、owner_name 与 idle_release_after_ms。它是当前占用者距离空闲释放的剩余时间，空闲上限 5 分钟；占用者继续进行 UI 操作会刷新，不能当作固定完成倒计时。据此等待并合理退避，或请用户在手机端结束占用；不自行清理他人身份，也不新建身份绕过占用。

UI.get_operation(operation_id) 可直接查询回执，不获取 UI 占用。UI.run 中途失败的 PhoneError.receipt.local_run 包含 completed_steps、failed_index（从 0 开始）和此前步骤结果；原 operation_id 与失败回执仍保留。

查询必须复用原逻辑 owner：resume 恢复可保留身份，dispose 后新建连接则不能查看已清理 owner 的回执；相同绑定/名称不够。排查日志先将 phone-tools.db 的 ID 与 phone-connections.db activity 的工具名、完整 owner 对上，避免把复制数据库调用自身的执行中快照当成目标工具卡死。新 journal 的 claimed/executing/completed/completed_pruned/interrupted/历史 unknown、阶段时间和不可重放规则详见 references/protocol.md；尚未装对应包时不要求这些字段出现。

用户在手机端断开、要求停止、ui_busy/device_busy 或身份恢复失败时，停止相应操作，不能新建身份绕过限制。正常或失败收尾调用 dispose：end_ui → disconnect → DELETE 当前会话。收尾失败保留缓存待核对，不创建额外连接验证是否断开。

当前 UI 包装类没有 UI.dispose()；持有 ui 时使用 ui.p.dispose()，持有 Phone 时使用 phone.dispose()。整个任务用 try/finally 包住，任务中途异常也要尝试清理自己的连接与占用；清理失败应保留缓存和原回执，不能转而删除他人身份。不要只在正常返回路径收尾。

服务 initialize 公布 _meta.remotetool.self_cleanup="resume-token" 时，dispose 可用一次 DELETE 完成同一操作者的释放和断开；不再额外发送多个断开请求。phone_mcp.py cleanup 只使用当前缓存中的恢复凭据清理该 owner，不新建会话、不删除共享绑定、不枚举其他 Agent。执行中返回 device_busy，需先等待原操作回执；resume_unknown 表示记录不存在/失效/撤销，必须显式重新授权，不能静默换身份。

用户明确授权重建连接后，可用 phone_mcp.py reset --confirm-reset 或 Phone.forget_cached(confirm_reset=True) 清除当前逻辑 Agent 的本地缓存；它不删除远端会话、不影响其他 Agent，也不立即创建新身份。不能因未知结果、用户停止或 ui_busy 而自行调用重置。

独立 CLI 任务可用 --close-on-exit，在 finally 中收尾；连续使用 screen→tap→wait 等多条 CLI 命令时不加此参数，在整个流程结束后 end。库用法仍使用 try/finally。read_only_backend 表示 UiAutomation 节点只读，动作前必须 backend=accessibility 重观察，不能对同一只读后端反复重试。正文缺失按 include_unimportant=true → 显式未压缩 UiAutomation → 截图核验的顺序诊断，不用 mode=text 作为切换后端的兜底。

## CLI

在 scripts 目录执行，身份和配置按前述环境变量提供：

    python phone_ui.py caps
    python phone_ui.py screen
    python phone_ui.py tap "精确按钮文字"
    python phone_ui.py wait "结果|价格" --timeout 10
    python phone_ui.py dump
    python phone_ui.py execute steps.json --timeout 15
    python phone_ui.py send "正文" --chat "精确标题" --package com.tencent.mobileqq
    python phone_ui.py end

phone_mcp.py 支持 tools、caps、call、sessions、probe、end。call 可用 --args-file UTF-8 参数文件及 --request-id；sessions 不输出恢复密钥。不要在命令日志中打印令牌或缓存正文。

## 汇报

确认手机升级时，优先检查 phone_get_capabilities.app 的版本与包名，再核对实际观察回执的 requested_limit/effective_limit、revision_epoch/window_revision。schema_version=3 不能独自证明这些增量能力已经装机。字段缺失时将对应子功能标为待装机/待核验，不能记为已验证，也不能仅凭缺少字段断言精确安装版本；不同采集后端并不保证返回相同的全部字段（如 revision_epoch/window_revision 属于 Accessibility）。

只报告实际观察和回执支持的结果。读取到商品卡片所需信息即可停，不必每次进入详情页。发送类汇报区分已提交、界面回显与送达；未验证就明确说明。没有实测任务耗时，不报告提速百分比。
