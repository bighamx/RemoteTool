package com.chuckiehelper.mobile.nativeui
import kotlinx.coroutines.flow.MutableSharedFlow
import java.security.MessageDigest

/** Disk cleanup invalidates only corresponding in-memory offline snapshots. */
internal object StorageCleanupSignals {
    val history=MutableSharedFlow<Set<String>>(extraBufferCapacity=4)
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    fun key(identity:String,session:String)=digest(identity)+"/"+digest(session)+".json"
}
