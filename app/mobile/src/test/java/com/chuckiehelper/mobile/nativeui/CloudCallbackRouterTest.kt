package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class CloudCallbackRouterTest {
    @Test fun companionAndFullAppCallbacksHaveDistinctOwnersButKeepLegacyFullAppLogins() {
        assertEquals("com.chuckiehelper.phonecontrol", CloudCallbackRouter.targetPackage(CloudCallbackRouter.statePrefix(true) + "random-nonce"))
        assertEquals("com.chuckiehelper.mobile", CloudCallbackRouter.targetPackage(CloudCallbackRouter.statePrefix(false) + "random-nonce"))
        assertEquals("com.chuckiehelper.mobile", CloudCallbackRouter.targetPackage("existing-unprefixed-random-nonce"))
    }
}
