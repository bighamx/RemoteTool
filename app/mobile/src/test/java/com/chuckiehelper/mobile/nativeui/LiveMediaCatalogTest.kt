package com.chuckiehelper.mobile.nativeui
import org.junit.Assert.*
import org.junit.Test

class LiveMediaCatalogTest {
    @Test fun explicitMediaPathsDisambiguateIdenticalNames() {
        val first = file("a").put("mediaPath", "D:/first/icon.png").put("messageKey", "")
        val second = file("b").put("mediaPath", "D:/second/icon.png").put("messageKey", "")
        val display = presentHermesMessage("MEDIA:D:/second/icon.png", listOf(first, second), emptyList())
        assertTrue(display.unavailable.isEmpty())
        assertEquals("", display.text)
    }
    private val message = "图片已生成\nMEDIA:C:\\ProgramData\\outbox\\current\\icon.png"
    private fun file(key: String) = obj("id" to key,"name" to "icon.png","messageKey" to key,"mime" to "image/png","url" to "/file/$key")
    @Test fun interimImageRequestsAFilesRefreshBeforeTheTaskCompletes() {
        assertTrue(needsMediaCatalogRefresh(message,emptyList()))
        assertFalse(needsMediaCatalogRefresh(message,listOf(file("current"))))
        assertEquals(1,presentHermesMessage(message,emptyList(),listOf(file("current"))).files.size)
    }
    @Test fun aSameNameImageFromAnOlderTaskCannotSuppressTheRefresh() {
        assertTrue(needsMediaCatalogRefresh(message,listOf(file("old"))))
        assertEquals(listOf("icon.png"),presentHermesMessage(message,emptyList(),listOf(file("old"))).unavailable)
    }
    @Test fun repeatedResolvedMediaDoesNotNeedRepeatedNetworkReads() {
        assertFalse(needsMediaCatalogRefresh("普通旁白",emptyList()))
        assertFalse(needsMediaCatalogRefresh("$message\nMEDIA:C:\\ProgramData\\outbox\\current\\icon.png",listOf(file("current"))))
    }
}
