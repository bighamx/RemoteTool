package com.chuckiehelper.mobile.phonecontrol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneAppLaunchTest {
    // Captured on HyperOS: securitycenter owns all five text nodes in one window.
    private fun dialog(caller: String = "手机操控", target: String = "酷安") = JSONObject()
        .put("foreground_package", PhoneAppLaunch.SYSTEM_PACKAGE).put("snapshot_id", "current")
        .put("nodes", JSONArray(listOf("启动应用", "$caller 想要打开 $target，是否允许？", "本次允许", "始终允许", "拒绝")
            .mapIndexed { i, text -> JSONObject().put("text", text).put("node_id", "n$i").put("window_id", 5949)
                .put("package_name", PhoneAppLaunch.SYSTEM_PACKAGE).put("visible", true).put("enabled", true)
                .put("bounds_valid", true).put("occluded", false) }))

    @Test fun capturedHyperOsDialogSelectsAlwaysAllowOnly() {
        val button = PhoneAppLaunch.alwaysAllow(dialog(), "手机操控", "酷安")!!
        assertEquals("始终允许", button.getString("text"))
        assertEquals("n3", button.getString("node_id"))
        assertNotNull(PhoneAppLaunch.alwaysAllow(dialog("RemoteTool"), "RemoteTool", "酷安"))
    }
    @Test fun unrelatedLaunchesAndOrdinaryPermissionPromptsAreNotApproved() {
        assertNull(PhoneAppLaunch.alwaysAllow(dialog(), "其他来源", "酷安"))
        assertNull(PhoneAppLaunch.alwaysAllow(dialog(), "手机操控", "其他应用"))
        assertNull(PhoneAppLaunch.alwaysAllow(dialog().put("foreground_package", "untrusted.app"), "手机操控", "酷安"))
        val other = dialog(); other.getJSONArray("nodes").getJSONObject(0).put("text", "访问相机")
        assertNull(PhoneAppLaunch.alwaysAllow(other, "手机操控", "酷安"))
    }
    @Test fun spoofedHiddenAmbiguousAndMixedWindowNodesAreRejected() {
        for (mutation in listOf<(JSONObject) -> Unit>(
            { it.put("visible", false) }, { it.put("enabled", false) }, { it.put("occluded", true) },
            { it.put("bounds_valid", false) }, { it.put("package_name", "untrusted.app") }, { it.put("window_id", 6000) })) {
            val d = dialog(); mutation(d.getJSONArray("nodes").getJSONObject(3))
            assertNull(PhoneAppLaunch.alwaysAllow(d, "手机操控", "酷安"))
        }
        val duplicate = dialog(); duplicate.getJSONArray("nodes").put(JSONObject(duplicate.getJSONArray("nodes").getJSONObject(3).toString()))
        assertNull(PhoneAppLaunch.alwaysAllow(duplicate, "手机操控", "酷安"))
        assertNull(PhoneAppLaunch.alwaysAllow(dialog().put("truncated", true), "手机操控", "酷安"))
    }
    @Test fun dialogAppearingAfterLaunchIsClickedOnceAndTargetIsVerified() {
        var now = 0L; var clicks = 0
        val r = PhoneAppLaunch.settle("com.coolapk.market", "手机操控", "酷安", { now }, {},
            observe = { if (clicks > 0) JSONObject().put("foreground_package", "com.coolapk.market")
                else if (now >= 100) dialog() else JSONObject().put("foreground_package", "com.miui.home") },
            click = { snapshot, button -> assertEquals("current", snapshot.getString("snapshot_id"));
                assertEquals("始终允许", button.getString("text")); clicks++; true },
            pause = { _, ms -> now += ms })
        assertEquals(1, clicks); assertTrue(r.getBoolean("auto_approved")); assertTrue(r.getBoolean("verified"))
        assertTrue(now <= 150)
    }
    @Test fun alreadyForegroundReturnsImmediatelyAndMissingDialogHasShortBudget() {
        var now = 0L
        fun settle(snapshot: JSONObject) = PhoneAppLaunch.settle("target", "手机操控", "酷安", { now }, {},
            { snapshot }, { _, _ -> error("must not click") }, { _, ms -> now += ms })
        assertTrue(settle(JSONObject().put("foreground_package", "target")).getBoolean("verified"))
        assertEquals(0L, now)
        assertEquals("not_observed", settle(JSONObject()).getString("status"))
        assertEquals(600L, now)
    }
    @Test fun rejectedClickIsNotRetriedAndCancellationBeforeClickIsHonored() {
        var clicks = 0
        val rejected = PhoneAppLaunch.settle("target", "手机操控", "酷安", { 0 }, {}, { dialog() },
            { _, _ -> clicks++; false }, { _, _ -> error("must not wait") })
        assertEquals("click_rejected", rejected.getString("status")); assertEquals(1, clicks)
        var checks = 0
        try {
            PhoneAppLaunch.settle("target", "手机操控", "酷安", { 0 }, { if (++checks == 2) error("cancelled") },
                { dialog() }, { _, _ -> error("must not click") }, { _, _ -> error("must not wait") })
            fail("cancellation expected")
        } catch (e: IllegalStateException) { assertEquals("cancelled", e.message) }
    }
}
