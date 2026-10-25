package com.chuckiehelper.mobile.nativeui

/** A native image/file-only message is still a message, even without a text caption. */
internal fun visibleAgentMessage(role: String, text: String, attachmentCount: Int): Boolean =
    role in setOf("user", "assistant") && (text.isNotBlank() && text != "null" || attachmentCount > 0)
