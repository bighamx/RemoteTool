package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class AgentUserMessageTest {
    @Test fun mergedSteersPreserveOtherDevicesTextAndMatchMultilineInput() {
        val config = "{\n  \"mcpServers\": {}\n}"
        val combined = "手机不在home局域网\n在office局域网\nphonetool直连是启用了的\n$config"
        val raw = "[OUT-OF-BAND USER MESSAGE — a direct message from the user]\n$combined\n[/OUT-OF-BAND USER MESSAGE]"
        assertEquals(combined, agentUserMessageText("hermes", raw))
        assertTrue(hermesUserMessageContains(raw, config))
        assertTrue(hermesUserMessageContains(raw, "在office局域网"))
        assertFalse(hermesUserMessageContains(raw, "office局域网"))
        assertFalse(hermesUserMessageContains(raw, ""))
    }
    @Test fun hermesSteeringEnvelopeAndPersistenceNotesAreHiddenFromTheChat() {
        val input = "请检查图片"
        val raw = "[OUT-OF-BAND USER MESSAGE — a direct message from the user]\n" + input +
            "\n\n[ChuckieHelper 持久附件]\nimage.png：C:\\file.png\n[/OUT-OF-BAND USER MESSAGE]"
        assertEquals(input, agentUserMessageText("hermes", raw))
        assertEquals(input, agentUserMessageText("hermes", raw.replace("\n", "\r\n")))
        assertEquals(input, agentUserMessageText("codex", input + "\n\n附件文件：\n\"C:\\file.png\""))
    }
    @Test fun ordinaryMessageContainingAnEnvelopeWordIsPreserved() {
        val raw = "介绍 [OUT-OF-BAND USER MESSAGE] 的作用"
        assertEquals(raw, agentUserMessageText("hermes", raw))
    }
}
