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

    @Test fun userExplanationIsNeverConvertedIntoSmallAttachmentWarning() {
        val text = "MEDIA:PATH 以这样形式的文字就可以把文件通过app发给我"
        val shown = presentHermesMessage(text, emptyList(), emptyList(), "user")
        assertEquals(text, shown.text)
        assertTrue(shown.unavailable.isEmpty())
        assertTrue(shown.files.isEmpty())
        val absolute = "MEDIA:C:\\example\\file.apk"
        assertEquals(absolute, presentHermesMessage(absolute, emptyList(), emptyList(), "user").text)
    }

    @Test fun codeExamplesRemainVisibleInsteadOfLeavingAnEmptyGrayBlock() {
        val example = "每个文件独立一行：\n\n```text\nMEDIA:C:\\完整路径\\文件名.apk\n```\n\n说明"
        val shown = presentHermesMessage(example, emptyList(), emptyList())
        assertEquals(example, shown.text)
        assertTrue(shown.unavailable.isEmpty())
        assertTrue(extractHermesMedia(example).paths.isEmpty())
        val tilde = "~~~~text\nMEDIA:/tmp/file.apk\n~~~\nMEDIA:/tmp/inside.apk\n~~~~"
        assertEquals(tilde, extractHermesMedia(tilde).text)
        assertTrue(extractHermesMedia(tilde).paths.isEmpty())
        val indented = "    MEDIA:/tmp/file.apk"
        assertEquals(indented, extractHermesMedia(indented).text)
    }

    @Test fun unresolvedAbsoluteMarkerKeepsItsOriginalMessageText() {
        val text = "MEDIA:/tmp/not-yet-indexed.apk"
        val shown = presentHermesMessage(text, emptyList(), emptyList())
        assertEquals(text, shown.text)
        assertEquals(listOf("not-yet-indexed.apk"), shown.unavailable)
        assertEquals("MEDIA:PATH", presentHermesMessage("MEDIA:PATH", emptyList(), emptyList()).text)
    }
}
