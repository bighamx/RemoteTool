package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** Older servers omit this optional field; never display JSON null or an unbounded value. */
internal fun JSONObject.serverBuildVersion(): String? = (opt("serverVersion") as? String)
    ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl) }

internal fun JSONObject.serverVersionDescription(): String? {
    val protocol = (opt("protocolVersion") as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it >= 1 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
    val build = serverBuildVersion()
    return listOfNotNull(protocol?.let { "协议 v$it" }, build?.let { "编译 $it" })
        .joinToString(" · ").takeIf { it.isNotEmpty() }
}
