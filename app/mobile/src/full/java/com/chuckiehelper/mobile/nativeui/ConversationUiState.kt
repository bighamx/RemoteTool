package com.chuckiehelper.mobile.nativeui

data class ConversationUiEntry(val draft: String = "", val error: String? = null, val submitting: Boolean = false)

/** An async result always updates its originating conversation. */
data class ConversationUiState(private val entries: Map<String?, ConversationUiEntry> = emptyMap()) {
    fun entry(session: String?) = entries[session] ?: ConversationUiEntry()
    fun draft(session: String?, value: String) = update(session) { it.copy(draft = value) }
    fun error(session: String?, value: String?) = update(session) { it.copy(error = value) }
    fun submitting(session: String?, value: Boolean) = update(session) { it.copy(submitting = value) }
    fun forget(session: String?) = copy(entries = entries - session)
    fun hasSubmitting() = entries.values.any { it.submitting }
    fun savedDrafts(): String = org.json.JSONObject().apply {
        entries.forEach { (session, state) -> if (state.draft.isNotEmpty()) put(session.orEmpty(), state.draft) }
    }.toString()
    companion object {
        fun restoreDrafts(raw: String?): ConversationUiState = runCatching {
            val rows = org.json.JSONObject(raw ?: "{}")
            ConversationUiState(rows.keys().asSequence().associate { key ->
                key.takeIf { it.isNotEmpty() } to ConversationUiEntry(draft = rows.getString(key))
            })
        }.getOrDefault(ConversationUiState())
    }
    private fun update(session: String?, transform: (ConversationUiEntry) -> ConversationUiEntry) =
        copy(entries = entries + (session to transform(entry(session))))
}

data class PendingAgentSubmission(
    val key: String, val input: String, val attachmentIds: List<String>, val timestamp: Long,
    val questionId: String? = null,
    val modelSettings: String? = null,
    val accountId: String? = null,
)
