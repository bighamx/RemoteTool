package com.chuckiehelper.mobile.nativeui

/** Hermes can persist several buffered steers as one user message. Match complete text blocks. */
internal fun hermesUserMessageContains(raw: String, input: String): Boolean {
    val text = agentUserMessageText("hermes", raw).trim()
    val expected = agentUserMessageText("hermes", input).trim()
    return expected.isNotEmpty() && (text == expected || ("\n$text\n").contains("\n$expected\n"))
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
    return if (agent == "codex") text.substringBefore("\n\n附件文件：\n") else text
}
