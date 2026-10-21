package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

internal fun JSONObject.cleanSetting(key: String): String = optString(key).takeUnless { it == "null" }.orEmpty()

internal fun modelMetadata(provider: JSONObject?, model: String?): JSONObject =
    provider?.array("models")?.objects()?.firstOrNull {
        it.cleanSetting("model").ifBlank { it.cleanSetting("id") } == model
    } ?: JSONObject()

internal fun reasoningChoices(agent: String, provider: JSONObject?, model: String?): List<String> {
    if (agent == "codex") return modelMetadata(provider, model).array("supportedReasoningEfforts")
        .objects().map { it.cleanSetting("reasoningEffort") }.filter { it.isNotBlank() }.distinct()
    val caps = provider?.optJSONObject("capabilities")?.optJSONObject(model.orEmpty())
    if (caps != null && !caps.optBoolean("reasoning", true)) return emptyList()
    return (if (caps?.optBoolean("can_disable_reasoning", true) != false) listOf("none") else emptyList()) +
        listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")
}

internal fun fastTier(provider: JSONObject?, model: String?): String? =
    modelMetadata(provider, model).array("serviceTiers").objects().firstOrNull {
        it.cleanSetting("id") in listOf("priority", "fast") || it.cleanSetting("name").equals("fast", true)
    }?.cleanSetting("id")

internal fun supportsServiceTier(provider: JSONObject?): Boolean =
    provider?.let { it.optBoolean("supports_service_tier", it.cleanSetting("slug") == "openai") } == true

internal fun providerMatches(provider: JSONObject?, id: String): Boolean = id.isNotBlank() && provider != null &&
    (provider.cleanSetting("slug") == id || (0 until provider.array("aliases").length()).any { provider.array("aliases").optString(it) == id })

internal fun providerSelectionId(provider: JSONObject, current: String): String =
    if (providerMatches(provider, current)) current else provider.cleanSetting("slug")

internal fun effortLabel(effort: String): String = when (effort) {
    "" -> "默认"
    "none" -> "关闭"
    "minimal" -> "最低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "很高"
    "max" -> "最高"
    "ultra" -> "Ultra"
    else -> effort
}

internal data class AgentSelection(val provider: String, val model: String, val effort: String, val tier: String)

/** Display and dispatch resolve the same settings. Codex's custom is a real provider ID. */
internal fun effectiveAgentSelection(agent: String, session: AgentSelection, defaults: JSONObject): AgentSelection = AgentSelection(
    session.provider.takeIf { it.isNotBlank() && (agent == "codex" || it != "custom") }
        ?: defaults.cleanSetting("provider"),
    session.model.ifBlank { defaults.cleanSetting("model") },
    if (session.model.isNotBlank()) session.effort else defaults.cleanSetting("reasoning_effort"),
    if (session.model.isNotBlank()) session.tier else defaults.cleanSetting("service_tier"),
)

internal fun codexModelSettingsSnapshot(selection: AgentSelection): String? =
    if (selection.provider.isBlank() || selection.model.isBlank()) null else JSONObject()
        .put("provider", selection.provider).put("model", selection.model)
        .put("reasoning_effort", selection.effort).put("service_tier", selection.tier).toString()

/** Hermes 自定义提供方去掉 custom: 前缀；Codex 展示名优先由 Provider 目录解析。 */
internal fun displayProvider(raw: String): String {
    val stripped = raw.removePrefix("custom:")
    return if (stripped == "custom" || stripped.isBlank()) "" else stripped
}
internal fun readAgentSelection(session: JSONObject): AgentSelection {
    val raw = session.opt("model_config")
    val config = if (raw is JSONObject) raw else if (raw is String) runCatching { JSONObject(raw) }.getOrNull() else null
    val lock = config?.optJSONObject("browser_model_lock")
    val reasoning = lock?.optJSONObject("model_options")?.optJSONObject("reasoning")
    val effort = if (reasoning?.optBoolean("enabled", true) == false) "none" else reasoning?.cleanSetting("effort").orEmpty()
    return AgentSelection(
        lock?.cleanSetting("provider").orEmpty().ifBlank { session.cleanSetting("provider").ifBlank { config?.cleanSetting("requested_provider").orEmpty() } },
        lock?.cleanSetting("model").orEmpty().ifBlank { config?.cleanSetting("requested_model").orEmpty().ifBlank { session.cleanSetting("model") } },
        if (lock != null) effort else session.cleanSetting("reasoning_effort"),
        session.cleanSetting("service_tier"),
    )
}

internal fun hermesReasoning(effort: String): JSONObject = if (effort == "none") obj("enabled" to false)
    else if (effort.isBlank()) JSONObject() else obj("enabled" to true, "effort" to effort)
