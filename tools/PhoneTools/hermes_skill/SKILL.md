---
name: android-phone-ui-automation
description: 使用 RemoteTool 手机 MCP 操作 Android 应用，读取界面、点击、输入、滚动和验证结果。
version: 3.0.0
author: Hermes Agent
license: MIT
tags: [android, ui-automation, remote-tool, accessibility, mcp]
metadata:
  hermes:
    related_skills: [android-device-inspection, phone-notify-channels, remotetool-app]
---

# 手机界面操作

适用于用户授权的手机应用操作、页面读取、查询和消息处理。使用 RemoteTool schema 3 的 HTTP MCP；脚本不兼容旧服务。先读 references/protocol.md 了解身份、回执和错误处理。

## 接入

两种地址使用同一套工具：

- 直连手机：http://<手机可达地址>:18791/mcp，由本应用直接提供 HTTP MCP。
- 转发通道：http://<RemoteTool服务端>/api/phone/v1/bound/<绑定ID>/mcp，可跨网络访问在线手机。

配置优先级：显式 url/token → PHONE_MCP_URL/PHONE_MCP_TOKEN → $HERMES_HOME/state/phone-mcp.json → 当前会话的 remotetool-phone-bindings.json 精确绑定。不选最后一条绑定，也不做模糊匹配。

每个聊天设置独立且稳定的 PHONE_MCP_AGENT，或使用实际 HERMES_SESSION_ID。短进程复用该聊天的缓存身份。不能给所有 Agent 共用一个名称。令牌只取用户配置或当前聊天绑定；工具未绑定时报告配置缺失，不搜索源码、其他会话或凭据库。

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

- observe 默认 compact；dump_text 使用 mode=text，不默认执行 shell/uiautomator。自绘页面可能两者都读不到，应按需截图核验，不能声称 shell 一定能读到。
- 优先服务端 phone_find_nodes/filter/fields 获取相关内容，减少输出量；同查询范围可用 changed_since 获取变化。
- ui.wait 支持正则或 selector 条件，并直接保存服务器返回的快照。单次等待最多 10 秒，较长等待由同一流程分段完成；不重做此前动作。
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

## 回执与收尾

delivery_unknown 或网络中断后，保留 operation_id，调用 phone_get_operation 查询，再结合当前状态决定下一步。不要创建新 ID 重发写操作。matched=false 表示后置条件未满足；不是动作一定未发生。

用户在手机端断开、要求停止、ui_busy/device_busy 或身份恢复失败时，停止相应操作，不能新建身份绕过限制。正常或失败收尾调用 dispose：end_ui → disconnect → DELETE 当前会话。收尾失败保留缓存待核对，不创建额外连接验证是否断开。

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

只报告实际观察和回执支持的结果。读取到商品卡片所需信息即可停，不必每次进入详情页。发送类汇报区分已提交、界面回显与送达；未验证就明确说明。没有实测任务耗时，不报告提速百分比。
