package com.chuckiehelper.mobile.nativeui

/** Remembers playback intent rather than isPlaying, which is false while buffering. */
internal class PlaybackVisibility {
    private var resume = false
    fun hide(playWhenReady: Boolean): Boolean {
        if (playWhenReady) resume = true
        return playWhenReady
    }
    fun show(): Boolean = resume.also { resume = false }
}
