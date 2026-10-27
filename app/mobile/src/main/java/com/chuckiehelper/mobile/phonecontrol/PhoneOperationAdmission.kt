package com.chuckiehelper.mobile.phonecontrol

/** Persist outside the connection gate, then recheck stop before admitting execution. */
internal object PhoneOperationAdmission {
    fun begin(gate: Any, checkOpen: () -> Unit, persist: () -> Unit, started: () -> Unit) {
        synchronized(gate) { checkOpen() }
        persist()
        synchronized(gate) { checkOpen(); started() }
    }
}
