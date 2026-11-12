package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class MobileNavigationTest {
    @Test fun defaultHasFiveDestinationsPlusMoreWithClaudeVisible() {
        val order = normalizeMobileNavigation(null)
        assertEquals(listOf("系统", "远程", "Codex", "Claude Code", "Hermes"), order.take(5))
        assertEquals(mobileDestinations.size, order.size)
        assertEquals(order.size, order.distinct().size)
        assertFalse("更多" in order)
    }
    @Test fun invalidDuplicateAndRemovedItemsCannotHideNewEntrances() {
        val order = normalizeMobileNavigation(listOf("obsolete", "文件", "文件", "Claude Code", "更多"))
        assertEquals(listOf("文件", "Claude Code"), order.take(2))
        assertEquals(mobileDestinations.map { it.route }.toSet(), order.toSet())
    }
    @Test fun movingFromOverflowReplacesFifthTabAndRetainsEveryDestination() {
        val original = normalizeMobileNavigation(null)
        val moved = moveMobileDestination(original, original.indexOf("文件"), 4)
        assertEquals("文件", moved[4])
        assertEquals("Hermes", moved[5])
        assertEquals(original.toSet(), moved.toSet())
        assertEquals(original, moveMobileDestination(original, 0, -1))
        assertEquals(original, moveMobileDestination(original, 100, 0))
    }
}
