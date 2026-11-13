package com.chuckiehelper.mobile.nativeui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.URLSpan
import android.view.View
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.core.spans.CodeSpan
import java.net.URI
import org.json.JSONObject

private val citationMarker = Regex("\uE200cite\uE202([^\uE200\uE201\\r\\n]*)\uE201")
private const val citationPrefix = "\uE200cite\uE202"

internal fun citationUrl(value: String): String? = runCatching {
    if (value.any { it <= ' ' || it in "<>\\" }) return null
    val uri = URI(value)
    value.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrBlank() && uri.rawUserInfo == null }
}.getOrNull()

internal fun renderCodexCitations(source: Spanned, sources: List<JSONObject>, streaming: Boolean,
    open: (View, String) -> Unit): Spanned {
    val text = source.toString()
    val result = SpannableStringBuilder(source)
    val numbers = linkedMapOf<String, Int>()
    var missing = false
    data class Replacement(val start: Int, val end: Int, val value: CharSequence)
    val replacements = mutableListOf<Replacement>()
    fun literal(start: Int, end: Int) = source.getSpans(start, end, CodeSpan::class.java).isNotEmpty() ||
        source.getSpans(start, end, CodeBlockSpan::class.java).isNotEmpty() ||
        source.getSpans(start, end, ClickableSpan::class.java).isNotEmpty()
    fun label(key: String, citation: JSONObject?): CharSequence {
        val url = citation?.optString("url")?.let(::citationUrl)
        val number = numbers.getOrPut(key) { numbers.size + 1 }
        val value = SpannableStringBuilder("[$number]")
        if (url == null) missing = true
        else value.setSpan(object : URLSpan(url) { override fun onClick(widget: View) = open(widget, url) }, 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        value.setSpan(RelativeSizeSpan(.85f), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return value
    }
    for (match in citationMarker.findAll(text)) {
        if (literal(match.range.first, match.range.last + 1)) continue
        val ids = match.groupValues[1].split('\uE202').toMutableList()
        if (ids.lastOrNull()?.matches(Regex("L\\d+(?:-L\\d+)?")) == true) ids.removeAt(ids.lastIndex)
        if (ids.isEmpty() || ids.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,127}")) }) {
            replacements += Replacement(match.range.first, match.range.last + 1, "[引用]"); missing = true; continue
        }
        val group = sources.filter { it.optString("marker") == match.value && citationUrl(it.optString("url")) != null }.distinctBy { it.optString("url") }
        val replacement = SpannableStringBuilder()
        if (group.isNotEmpty()) group.forEach { replacement.append(label("url:" + it.optString("url"), it)) }
        else ids.distinct().forEach { id ->
            val citation = sources.firstOrNull { it.optString("reference_id") == id && citationUrl(it.optString("url")) != null }
            replacement.append(label(citation?.let { "url:" + it.optString("url") } ?: "ref:$id", citation))
        }
        replacements += Replacement(match.range.first, match.range.last + 1, replacement)
    }
    val last = text.lastIndexOf('\uE200')
    if (last >= 0 && !literal(last, text.length)) {
        val tail = text.substring(last)
        if (citationPrefix.startsWith(tail) || tail.startsWith(citationPrefix) && !tail.contains('\uE201')) {
            replacements += Replacement(last, text.length, "")
            if (!streaming) missing = true
        }
    }
    replacements.asReversed().forEach { result.replace(it.start, it.end, it.value) }
    if (missing && !streaming) {
        val start = result.length
        result.append("\n\n部分引用链接未随消息返回")
        result.setSpan(RelativeSizeSpan(.8f), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return result
}
