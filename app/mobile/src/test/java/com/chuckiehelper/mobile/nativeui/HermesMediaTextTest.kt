package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class HermesMediaTextTest {
    @Test fun parsesWindowsReturnedMediaWithoutDisplayingPaths() {
        val value = extractHermesMedia("文件已生成\r\n\r\nMEDIA:C:\\ProgramData\\outbox\\run-id\\test image.png\r\nMEDIA:\"C:\\ProgramData\\outbox\\run-id\\test_video.mp4\"\r\n\r\n说明保留")
        assertEquals(listOf("C:/ProgramData/outbox/run-id/test image.png", "C:/ProgramData/outbox/run-id/test_video.mp4"), value.paths)
        assertFalse(value.text.contains("ProgramData"))
        assertTrue(value.text.contains("说明保留"))
    }
    @Test fun keepsOrdinaryMediaExplanationsAndRecognizesAttachmentOnlyReply() {
        assertEquals("使用 MEDIA: 标记发送", extractHermesMedia("使用 MEDIA: 标记发送").text)
        val value = extractHermesMedia("MEDIA:/tmp/report.txt")
        assertEquals("", value.text)
        assertEquals(listOf("/tmp/report.txt"), value.paths)
    }
}
