package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class AgentSetupCardTest {
    @Test fun configurationProgressSurvivesReenteringTheScreen() {
        assertTrue(agentSetupBusy(obj("state" to "installing", "install" to obj("phase" to "installing_sdk", "busy" to true))))
        assertTrue(agentSetupBusy(obj("state" to "not_running", "install" to obj("phase" to "validating", "busy" to true))))
    }
    @Test fun legacyProgressAndTerminalStatesRemainCompatible() {
        assertTrue(agentSetupBusy(obj("state" to "installing")))
        assertFalse(agentSetupBusy(obj("state" to "sign_in", "install" to obj("phase" to "ready", "busy" to false))))
        assertFalse(agentSetupBusy(obj("state" to "not_installed", "install" to obj("phase" to "failed", "busy" to false))))
        assertFalse(agentSetupBusy(null))
    }
}
