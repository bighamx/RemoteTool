package com.chuckiehelper.mobile.nativeui

internal fun missingSseEvents(previous: Long, next: Long): Boolean = previous >= 0 && next > previous && next - previous > 1
