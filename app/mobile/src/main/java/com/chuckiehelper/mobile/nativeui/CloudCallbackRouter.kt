package com.chuckiehelper.mobile.nativeui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Both signed variants use the same public OIDC client and PKCE callback. The
 * state prefix routes to the owner; the receiving model still validates the full
 * random state, lifetime and its private PKCE verifier before exchanging a code. */
internal object CloudCallbackRouter {
    fun statePrefix(companion: Boolean) = if (companion) "phone." else "full."
    fun targetPackage(state: String) = if (state.startsWith("phone."))
        "com.chuckiehelper.phonecontrol" else "com.chuckiehelper.mobile"

    fun forwardIfNeeded(context: Context, uri: Uri?): Boolean {
        if (uri?.scheme != "remotetool" || uri.host != "cloud" || uri.path != "/login") return false
        val state = uri.getQueryParameter("state").orEmpty()
        if (state.isBlank()) return false
        val target = targetPackage(state)
        if (target == context.packageName) return false
        // Never send an authorization code to an unrelated package using the same URI scheme.
        if (context.packageManager.checkSignatures(context.packageName, target) != PackageManager.SIGNATURE_MATCH) return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri)
                .setComponent(ComponentName(target, "com.chuckiehelper.mobile.NativeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            true
        }.getOrDefault(false)
    }
}
