package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject

fun codexQuestionReply(request: JSONObject, answers: JSONObject): String {
    val replies = JSONArray()
    request.array("questions").objects().forEachIndexed { index, question ->
        val answer = answers.optJSONObject(question.getString("id"))?.array("answers")?.let { values ->
            (0 until values.length()).joinToString("、") { values.optString(it) }
        }.orEmpty()
        replies.put(obj("questionItemId" to JSONArray(listOf("request_user_input_async", request.getString("request_id"), index)).toString(),
            "question" to question.optString("question"), "answer" to answer))
    }
    return "<send_user_message_question_reply>\n$replies\n</send_user_message_question_reply>"
}

fun codexQuestionReplyDisplay(text: String): String = Regex(
    "<send_user_message_question_reply>\\s*([\\s\\S]*?)\\s*</send_user_message_question_reply>"
).replace(text) { match ->
    try {
        val raw = match.groupValues[1].trim()
        val replies = if (raw.startsWith("[")) JSONArray(raw) else JSONArray().put(JSONObject(raw))
        replies.objects().joinToString("\n\n") { reply ->
            reply.optString("question").trim() + "\n回答：" + reply.optString("answer").trim()
        }.ifBlank { match.value }
    } catch (_: Exception) { match.value }
}
