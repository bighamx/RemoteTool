package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class NativeBehaviorTest {
    @Test
    fun composeShortcutRecognizesStandardAndOverrideNames(){
        assertTrue(isComposeFile("docker-compose.yml"))
        assertTrue(isComposeFile("compose.yaml"))
        assertTrue(isComposeFile("docker-compose.override.yaml"))
        assertFalse(isComposeFile("settings.yaml"))
    }
    @Test
    fun utcScheduleIsDisplayedInPhoneTimeZone() {
        assertEquals(
            "2026-10-03 15:00:00",
            displayTime("2026-10-03T07:00:00Z", java.time.ZoneId.of("Asia/Hong_Kong")),
        )
        assertEquals("暂无", displayTime("null"))
    }

    @Test
    fun folderBackTraversesWindowsAndUnixRoots() {
        assertEquals("D:\\GIT\\", parentPath("D:\\GIT\\ChuckieHelper"))
        assertEquals("D:\\", parentPath("D:\\GIT\\"))
        assertEquals("", parentPath("D:\\"))
        assertEquals("/var/", parentPath("/var/log"))
        assertEquals("/", parentPath("/var/"))
        assertEquals("", parentPath("/"))
    }

    @Test
    fun terminalPreservesSplitEscapeSequencesAndUnicode() {
        val buffer = TerminalBuffer()
        assertEquals("", buffer.append("\u001b[31"))
        assertEquals("你好42", buffer.append("m你好42\u001b[0m"))
        assertEquals("你好42\nnext", buffer.append("\r\nnext"))
    }

    @Test
    fun terminalProgressRewritesAndClearDoNotRestoreHistory() {
        val buffer = TerminalBuffer()
        buffer.append("Downloading 1%")
        assertEquals("Done", buffer.append("\rDone\u001b[K"))
        buffer.clear()
        assertEquals("new", buffer.append("new"))
    }

    @Test
    fun terminalScrollbackIsBounded() {
        val buffer = TerminalBuffer()
        val text = buffer.append((0..6000).joinToString("\r\n") { "row $it" })
        assertTrue(text.lines().size <= 4000)
        assertTrue(text.length <= 250000)
    }
}
