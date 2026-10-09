package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexToolPreviewTest {
    @Test fun quotedWindowsLauncherLeavesTheActualCommandAndItsQuotes() {
        assertEquals("pwsh Get-Content 'C:\\Users\\chuckie\\notes.txt'",
            compactCodexToolPreview("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\" -Command \"Get-Content 'C:\\Users\\chuckie\\notes.txt'\""))
    }

    @Test fun truncatedPreviewAndEscapedPathStillShowUsefulCommandText() {
        assertEquals("pwsh rg -n 'Helper|8888' ../docs…",
            compactCodexToolPreview("\"C:\\\\Program Files\\\\PowerShell\\\\7\\\\pwsh.exe\" -Command \"rg -n 'Helper|8888' ../docs…"))
        assertEquals("pwsh Write-Output 'hello'",
            compactCodexToolPreview("pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -c \"Write-Output 'hello'\""))
    }

    @Test fun otherExecutablesAndAlreadyCompactPreviewsArePreserved() {
        for (preview in listOf("cmd.exe /c echo hello", "pwsh Get-Content 'test.txt'", "rg -n pwsh.exe file.txt", "pwsh.exe -File test.ps1"))
            assertEquals(preview, compactCodexToolPreview(preview))
    }
}
