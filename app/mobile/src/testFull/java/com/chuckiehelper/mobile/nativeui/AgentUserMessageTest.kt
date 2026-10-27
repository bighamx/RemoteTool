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

    @Test fun runtimeImageNotesDoNotBreakTheSendReceiptMatch() {
        val input = "重启是否已经完成？还是无法继续对话 local-cfc1c897ff57"
        val stored = "$input\n\n[Image attached at: C:\\Users\\chuckie\\AppData\\Local\\hermes\\x.jpg]\n[screenshot]"
        assertEquals(input, agentUserMessageText("hermes", stored).trim())
        assertTrue(hermesUserMessageContains(stored, input))
    }

    @Test fun whitespaceRewritesByTheGatewayStillMatch() {
        val input = "重启是否已经完成？还是无法继续对话 local-cfc1c897ff57"
        val stored = "重启是否已经完成？ 还是无法继续对话 local-cfc1c897ff57"
        assertTrue(hermesUserMessageContains(stored, input))
    }

    @Test fun runtimeSkillNoticesAreStrippedFromTheChat() {
        val input = "local-9858c11742bcf8cafe"
        val stored = "$input\n\n[Image attached at: C:\\x.jpg]\n[screenshot]\n[System: The skills available in this conversation changed after its system prompt was built.\nNow available: foo]"
        assertEquals(input, agentUserMessageText("hermes", stored).trim())
        assertTrue(hermesUserMessageContains(stored, input))
    }
}
