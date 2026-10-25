package com.chuckiehelper.mobile.nativeui

import com.chuckiehelper.mobile.tunnel.TunnelFrame
import com.chuckiehelper.mobile.tunnel.TunnelRule
import org.junit.Assert.*
import org.junit.Test

class TunnelProtocolTest {
    @Test fun headerUsesTheServerBigEndianWireFormat() {
        val bytes = TunnelFrame(TunnelFrame.OPEN, 0x01020304, 32).encode()
        assertArrayEquals(byteArrayOf(1, 1, 0, 0, 1, 2, 3, 4, 0, 0, 0, 32), bytes)
        val decoded = TunnelFrame.decode(bytes)
        assertEquals(0x01020304, decoded.id)
        assertEquals(32, decoded.aux)
    }
    @Test fun datagramsPreserveEmptyAndLargePayloadBoundaries() {
        for (size in listOf(0, 1000, TunnelFrame.MAX_UDP)) {
            val data = ByteArray(size) { (it % 251).toByte() }
            val decoded = TunnelFrame.decode(TunnelFrame(TunnelFrame.DATAGRAM, 2, payload = data).encode())
            assertArrayEquals(data, decoded.payload)
        }
    }
    @Test fun malformedFramesAndRulesAreRejected() {
        fun rejects(action: () -> Unit) { try { action(); fail("invalid frame accepted") } catch (_: IllegalArgumentException) { } }
        rejects { TunnelFrame.decode(ByteArray(5)) }
        rejects { TunnelFrame.decode(TunnelFrame(TunnelFrame.DATA, 1, payload = byteArrayOf()).encode()) }
        rejects { TunnelFrame.decode(TunnelFrame(TunnelFrame.OPEN, 0).encode()) }
        rejects { TunnelFrame.decode(TunnelFrame(TunnelFrame.FIN, 1, payload = byteArrayOf(1)).encode()) }
        rejects { TunnelRule(1, "", "tcp", "phone_to_pc", "127.0.0.1", 80, "127.0.0.1", 8080).validate() }
        rejects { TunnelRule(1, "", "udp", "pc_to_phone", "127.0.0.1", 18080, "http://target", 8080).validate() }
    }
}
