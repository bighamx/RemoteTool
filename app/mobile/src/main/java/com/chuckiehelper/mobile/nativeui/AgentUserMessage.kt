package com.chuckiehelper.mobile.nativeui

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
