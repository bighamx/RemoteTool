package com.chuckiehelper.mobile.nativeui

import android.content.Context
import android.text.Spanned
import android.text.style.URLSpan
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CodexCitationsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val first="turn792085news25"
    private val second="turn784471view0"
    private fun marker(vararg ids:String)="\uE200cite\uE202"+ids.joinToString("\uE202")+"\uE201"
    private fun render(text:String,sources:List<org.json.JSONObject> = emptyList(),streaming:Boolean=false):MessageTextView {
        val view=MessageTextView(context)
        val renderer=createMessageMarkwon(context,null,true,sources,streaming)
        view.renderMarkdown(text,renderer.toMarkdown(text),renderer)
        return view
    }
    @Test fun actualMarkwonRenderingMakesHistoricalSourcesReadableAndPreservesCode() {
        val raw=marker(first,second)
        val text=render("回答$raw 再引用${marker(first)}").text.toString()
        assertEquals(2,Regex("\\[1]").findAll(text).count());assertTrue(text.contains("[2]"));assertFalse(text.contains('\uE200'))
        assertEquals(1,Regex("部分引用链接未随消息返回").findAll(text).count())
        for(code in listOf("`$raw`","```text\n$raw\n```","    $raw","[$raw](https://example.com)")) {
            val result=render(code).text.toString();assertTrue(result.contains(raw));assertFalse(result.contains("未随消息"))
        }
    }
    @Test fun sourcesCreateWorkingSpansAndMetadataUpdatesAnUnchangedMessage() {
        val raw=marker(first)
        val view=render(raw)
        val sources=listOf(obj("reference_id" to first,"url" to "https://example.com/report"))
        var opened=""
        val renderer=createMessageMarkwon(context,{opened=it;true},true,sources,false)
        view.renderMarkdown(raw,renderer.toMarkdown(raw),renderer)
        val text=view.text as Spanned
        val links=text.getSpans(0,text.length,URLSpan::class.java)
        assertEquals(1,links.size);links.single().onClick(view);assertEquals("https://example.com/report",opened)
        assertFalse(text.toString().contains("未随消息"))
        assertNull(citationUrl("javascript:alert(1)"));assertNull(citationUrl("https://user:secret@example.com"))
    }
    @Test fun fragmentedStreamsAndCachedHistoryUseTheSamePresentation() {
        for(tail in listOf("\uE200","\uE200c","\uE200cite\uE202turn792085")) {
            val text=render("回答$tail",streaming=true).text.toString();assertEquals("回答",text)
        }
        val source=obj("reference_id" to first,"url" to "https://example.com/report")
        val row=obj("id" to 17,"role" to "assistant","content" to marker(first),"citations" to JSONArray().put(source))
        val message=agentHistoryMessage("codex",row)!!
        val restored=decodeHistorySnapshot(encodeHistorySnapshot(listOf(message))).single()
        assertEquals(message.text,restored.text);assertEquals(source.toString(),restored.citations.single().toString())
        assertFalse(render(restored.text,restored.citations).text.toString().contains("未随消息"))
        assertTrue(agentHistoryMessage("hermes",row)!!.citations.isEmpty())
    }
}
