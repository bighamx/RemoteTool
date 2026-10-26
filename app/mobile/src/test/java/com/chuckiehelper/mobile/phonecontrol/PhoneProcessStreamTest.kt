package com.chuckiehelper.mobile.phonecontrol

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneProcessStreamTest {
    @Test fun largeApkBytesCrossOneProcessStdinWithoutChunkShellCalls() {
        val dir = Files.createTempDirectory("phone-stream-test").toFile()
        try {
            val helper = File(dir, "StreamProbe.java").apply { writeText("""
                import java.security.*;
                import java.util.*;
                class StreamProbe { public static void main(String[] args) throws Exception {
                    byte[] bytes = System.in.readAllBytes();
                    System.out.print(bytes.length + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                } }
            """.trimIndent()) }
            val bytes = ByteArray(10 * 1024 * 1024 + 73) { ((it * 31) % 251).toByte() }
            val java = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            val result = PhoneProcesses.runProcess(listOf(java.absolutePath, helper.absolutePath), 20_000, bytes.inputStream(), bytes.size.toLong())
            assertEquals(result.toString(), 0, result.getInt("exit_code"))
            assertEquals(bytes.size.toLong(), result.getLong("bytes_sent"))
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("${bytes.size}:$digest", result.getString("stdout"))
            assertFalse(result.getBoolean("timed_out"))
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
}
