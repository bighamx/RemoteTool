package com.chuckiehelper.mobile.phonecontrol

import okhttp3.Request
import org.json.JSONObject

/** Only the web operations needed by the shared phone-control settings screen. */
interface PhoneWebApi {
    val base: String
    fun cookie(): String
    fun request(path: String, body: JSONObject? = null): Request
    suspend fun json(path: String, body: JSONObject? = null): JSONObject
    suspend fun json(request: Request): JSONObject
}

/** Optional host hooks; the independent app does not depend on RemoteTool startup code. */
object PhoneControlHost {
    var bootRestorePaused: () -> Boolean = { false }
    var rememberForBoot: (android.content.Context, String?) -> Unit = { _, _ -> }
    var pauseBootRestore: () -> Unit = {}
}
