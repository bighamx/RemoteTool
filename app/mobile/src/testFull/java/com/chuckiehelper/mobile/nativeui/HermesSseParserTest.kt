package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class HermesSseParserTest {
    @Test
    fun commentsAndEnvelopeFieldsDoNotBecomeMessageText() {
        val parser = HermesSseParser()
        assertNull(parser.line(": keepalive"))
        assertNull(parser.line(""))
        assertNull(parser.line("id: 7"))
        assertNull(parser.line("event: message.delta"))
        assertNull(parser.line("data: {\"event\":\"message.delta\",\"delta\":\"你好\"}"))
        assertEquals("{\"event\":\"message.delta\",\"delta\":\"你好\"}", parser.line(""))
        assertNull(parser.line(""))
    }

    @Test
    fun multilineDataIsJoinedAtEventBoundary() {
        val parser = HermesSseParser()
        parser.line("data: one")
        parser.line("data: two")
        assertEquals("one\ntwo", parser.line(""))
    }
}
