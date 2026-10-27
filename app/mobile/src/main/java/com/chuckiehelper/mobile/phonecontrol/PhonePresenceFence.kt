package com.chuckiehelper.mobile.phonecontrol

/** An expired transport must never disconnect a newer resumed generation of the same owner. */
internal class PhonePresenceFence {
    private data class Entry(val generation: Int, val connected: Boolean)
    private val entries = linkedMapOf<String, Entry>()
    @Synchronized fun accept(owner: String, generation: Int?, connected: Boolean): Boolean {
        if (generation == null) return true
        val previous = entries[owner]
        if (previous != null && (generation < previous.generation ||
            generation == previous.generation && !previous.connected && connected)) return false
        entries[owner] = Entry(generation, connected)
        while (entries.size > 1024) entries.remove(entries.keys.first())
        return true
    }
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun canTouch(owner: String, generation: Int?): Boolean {
        return accept(owner, generation, true)
    }
}
