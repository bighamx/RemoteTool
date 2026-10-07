package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatePolicyTest {
    private val hash = "ab".repeat(32)
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("packageId", UPDATE_PACKAGE)
        .put("versionCode", 4).put("versionName", "0.4.0").put("sha256", hash).put("size", 1234)
        .put("apkAsset", "ChuckieHelper.apk")
    private fun release() = JSONObject().put("body", "更新说明").put("assets", JSONArray()
        .put(JSONObject().put("name", UPDATE_MANIFEST))
        .put(JSONObject().put("name", "ChuckieHelper.apk").put("size", 1234)
            .put("browser_download_url", "https://github.com/$UPDATE_REPOSITORY/releases/download/android-v0.4.0/ChuckieHelper.apk")))

    @Test fun skipsWebOnlyDraftAndPreviewReleases() {
        val list = JSONArray().put(JSONObject().put("assets", JSONArray()))
            .put(release().put("draft", true)).put(release().put("prerelease", true)).put(release())
        assertEquals(1, appReleaseCandidates(list).size)
    }
    @Test fun bindsManifestToExactAssetAndActualSize() {
        val value = parseAppRelease(manifest(), release())
        assertEquals(4L, value.versionCode)
        assertEquals("更新说明", value.notes)
        assertEquals(value, restoreAppRelease(value.json()))
        assertThrows(IllegalArgumentException::class.java) { parseAppRelease(manifest().put("size", 999), release()) }
        assertThrows(IllegalArgumentException::class.java) { parseAppRelease(manifest().put("sha256", ""), release()) }
        assertThrows(IllegalArgumentException::class.java) { parseAppRelease(manifest().put("packageId", "other.app"), release()) }
    }
    @Test fun doesNotFollowArbitraryOrCleartextDownloadAddresses() {
        assertTrue(isUpdateTransportUrl("https://release-assets.githubusercontent.com/signed-file?x=1"))
        assertTrue(isUpdateTransportUrl("https://api.github.com/repos/$UPDATE_REPOSITORY/releases?per_page=30"))
        listOf("http://github.com/$UPDATE_REPOSITORY/releases/download/v4/a.apk",
            "https://github.com.evil.test/$UPDATE_REPOSITORY/releases/download/v4/a.apk",
            "https://github.com/other/repo/releases/download/v4/a.apk", "https://localhost/a.apk",
            "https://api.github.com/repos/other/repo/releases", "https://user@release-assets.githubusercontent.com/a")
            .forEach { assertFalse(it, isUpdateTransportUrl(it)) }
    }
    @Test fun rejectsDowngradeWrongVersionAndUnsignedOrForeignApk() {
        val value = parseAppRelease(manifest(), release())
        validateUpdateIdentity(UPDATE_PACKAGE, 4, setOf("installed"), 3, setOf("installed"), value)
        assertThrows(IllegalArgumentException::class.java) { validateUpdateIdentity(UPDATE_PACKAGE, 4, setOf("installed"), 4, setOf("installed"), value) }
        assertThrows(IllegalArgumentException::class.java) { validateUpdateIdentity(UPDATE_PACKAGE, 5, setOf("installed"), 3, setOf("installed"), value) }
        assertThrows(IllegalArgumentException::class.java) { validateUpdateIdentity(UPDATE_PACKAGE, 4, emptySet(), 3, emptySet(), value) }
        assertThrows(IllegalArgumentException::class.java) { validateUpdateIdentity(UPDATE_PACKAGE, 4, setOf("foreign"), 3, setOf("installed"), value) }
    }
}
