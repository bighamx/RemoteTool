package com.chuckiehelper.mobile.nativeui

/** Observe without consuming: scrolling and native long-press selection retain ownership. */
internal class BubbleTapGesture(private val slop: Float, private val longPressMillis: Long) {
    private var started = 0L
    private var x = 0f
    private var y = 0f
    private var eligible = false
    fun down(x: Float, y: Float, time: Long) { this.x = x; this.y = y; started = time; eligible = true }
    fun move(x: Float, y: Float) {
        if ((x - this.x) * (x - this.x) + (y - this.y) * (y - this.y) > slop * slop) eligible = false
    }
    fun cancel() { eligible = false }
    fun up(x: Float, y: Float, time: Long): Boolean {
        move(x, y)
        val tap = eligible && time - started in 0 until longPressMillis
        eligible = false
        return tap
    }
}
