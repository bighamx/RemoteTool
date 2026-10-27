package com.chuckiehelper.mobile.nativeui

private val imageNote = Regex("\\n\\s*\\[Image attached at:[^\\]]*\\]")
private val trailingImageMarker = Regex("\\n\\s*\\[(?:screenshot|截图)\\]")
private val trailingSystemNote = Regex("\\n\\[System: [\\s\\S]*$")

/** Hermes can persist several buffered steers as one user message. Match complete text blocks. */
internal fun hermesUserMessageContains(raw: String, input: String): Boolean {
    val text = agentUserMessageText("hermes", raw).trim()
    val expected = agentUserMessageText("hermes", input).trim()
    if (expected.isEmpty()) return false
    if (text == expected || ("\n$text\n").contains("\n$expected\n")) return true
    // 网关偶尔改写空白；按行去掉所有空白后仍要求整行边界一致，避免匹配到行内片段。
    val textLines = text.lines().map { it.replace(Regex("\\s+"), "") }
    val expectedLines = expected.lines().map { it.replace(Regex("\\s+"), "") }
    val joinedText = "\n" + textLines.joinToString("\n") + "\n"
    val joinedExpected = "\n" + expectedLines.joinToString("\n") + "\n"
    return joinedExpected.trim('\n').isNotEmpty() && joinedText.contains(joinedExpected)
}

fun agentUserMessageText(agent: String, raw: String): String {
    var text = raw.replace("\r\n", "\n")
    if (agent == "hermes") {
        val trimmed = text.trim()
        val close = "[/OUT-OF-BAND USER MESSAGE]"
        if (trimmed.startsWith("[OUT-OF-BAND USER MESSAGE") && trimmed.endsWith(close)) {
            val header = trimmed.indexOf("]\n")
            if (header >= 0) text = trimmed.substring(header + 2).removeSuffix(close).trimEnd()
        }
    }
    text = text.substringBefore("\n\n[ChuckieHelper 持久附件]")
    // 运行时给带图消息追加的注记不属于正文：留着既会显示成“注入文本”，
    // 也会让发送回执无法与历史行对上（同一条消息渲染两次）。
    text = text.replace(imageNote, "")
    text = text.replace(trailingImageMarker, "")
    text = text.replace(trailingSystemNote, "")
    return if (agent == "codex") text.substringBefore("\n\n附件文件：\n") else text
}
