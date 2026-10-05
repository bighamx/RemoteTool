package com.chuckiehelper.mobile.nativeui

data class ConversationUiEntry(val draft: String = "", val error: String? = null, val submitting: Boolean = false)

/** An async result always updates its originating conversation. */
data class ConversationUiState(private val entries: Map<String?, ConversationUiEntry> = emptyMap()) {
    fun entry(session: String?) = entries[session] ?: ConversationUiEntry()
    fun draft(session: String?, value: String) = update(session) { it.copy(draft = value) }
    fun error(session: String?, value: String?) = update(session) { it.copy(error = value) }
    fun submitting(session: String?, value: Boolean) = update(session) { it.copy(submitting = value) }
    fun forget(session: String?) = copy(entries = entries - session)
    private fun update(session: String?, transform: (ConversationUiEntry) -> ConversationUiEntry) =
        copy(entries = entries + (session to transform(entry(session))))
}

data class PendingAgentSubmission(
    val key: String, val input: String, val attachmentIds: List<String>, val timestamp: Long,
    val questionId: String? = null,
)
