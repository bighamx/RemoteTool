package com.chuckiehelper.mobile.phonecontrol

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhonePermissionStatusTest {
    @Test fun accessibilityUpdatesPreserveOtherAppsAndNormalizeOwnAliases() {
        val own = "app.own/app.own.Accessibility"
        val others = "app.first/.Service:app.second/app.second.Service"
        assertEquals("$others:$own", PhoneAccessibilityControl.merge(others, own, true))
        assertEquals(others, PhoneAccessibilityControl.merge("app.own/.Accessibility:$others:$own", own, false))
        assertEquals("$others:$own", PhoneAccessibilityControl.merge("app.own/.Accessibility:$others:$own", own, true))
        assertEquals(own, PhoneAccessibilityControl.merge("null", own, true))
        assertEquals("", PhoneAccessibilityControl.merge(own, own, false))
    }
    @Test fun authorizationAndConnectionAreSeparateAndUnknownDoesNotMeanDenied() {
        assertEquals("已授权 · 服务未连接", PhonePermissionState.serviceLabel(true, false))
        assertEquals("已授权 · 运行中", PhonePermissionState.serviceLabel(true, true))
        assertEquals("未授权", PhonePermissionState.serviceLabel(false, false))
        assertEquals("状态未知", PhonePermissionState.label(null))
    }
}
