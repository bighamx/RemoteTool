package com.chuckiehelper.mobile.nativeui

internal data class MessageEditBoundary(val lastTurnId: String?, val latestUserId: Long?)

internal fun messageEditBoundary(messages: List<HermesMessage>) = MessageEditBoundary(
    messages.lastOrNull { it.nativeTurnId != null }?.nativeTurnId,
    messages.lastOrNull { it.role == "user" && it.serverId > 0 }?.serverId,
)
