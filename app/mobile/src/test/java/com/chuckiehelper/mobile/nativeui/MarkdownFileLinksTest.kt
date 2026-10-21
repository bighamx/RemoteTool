package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class MarkdownFileLinksTest {
    @Test fun encodedAbsolutePathsAndLineNumbersResolveToTheSameFile() {
        assertEquals("E:/My Files/readme.md",markdownFilePath("E:/My%20Files/readme.md:42"))
        assertEquals("E:/My Files/readme.md",markdownFilePath("file:///E:/My%20Files/readme.md#L42"))
        assertEquals("/tmp/a+b.txt",markdownFilePath("/tmp/a+b.txt"))
        assertTrue(sameAttachmentPath("E:/My Files/readme.md","e:\\my files\\README.MD"))
    }
    @Test fun nonLocalPathsNeverBecomeAuthenticatedFileDownloads() {
        for (path in listOf("relative.md","https://example.com/file.md","//server/share/file.md","\\\\server\\file.md","file://server/share/file.md"))
            assertNull(path,markdownFilePath(path))
    }
    @Test fun commonmarkKeepsCodeExamplesOutAndDeduplicatesLinks() {
        val text = """
            [real](E:/work/readme.md)
            [again](e:/work/README.md:20)
            `[example](E:/secret/code.md)`
            ```
            [example](E:/secret/block.md)
            ```
            [ref][doc]

            [doc]: </tmp/my notes.md>
        """.trimIndent()
        assertEquals(listOf("E:/work/readme.md","/tmp/my notes.md"),markdownFilePaths(text))
    }
}
