package com.chuckiehelper.mobile.nativeui

fun shouldScrollChatToLatest(opening: Boolean, nearBottom: Boolean, sendingMessage: Boolean): Boolean =
    opening || nearBottom || sendingMessage
