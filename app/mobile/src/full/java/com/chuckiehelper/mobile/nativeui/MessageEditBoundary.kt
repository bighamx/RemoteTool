package com.chuckiehelper.mobile.nativeui

internal data class MessageEditBoundary(val lastTurnId: String?, val latestUserId: Long?, val nativeLatestUserId: String? = null)

internal fun messageEditBoundary(messages: List<HermesMessage>) = MessageEditBoundary(
    messages.lastOrNull { it.serverId > 0 && !it.narration && !it.nativeTurnId.isNullOrBlank() &&
        it.nativeTurnId !in setOf("toolSummary", "interaction") }?.nativeTurnId,
    messages.lastOrNull { it.role == "user" && it.serverId > 0 }?.serverId,
    messages.lastOrNull { it.role == "user" && it.serverId > 0 }?.nativeId,
)
