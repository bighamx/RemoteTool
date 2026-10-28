package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** Older servers omit this optional field; never display JSON null or an unbounded value. */
internal fun JSONObject.serverBuildVersion(): String? = (opt("serverVersion") as? String)
    ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl) }
