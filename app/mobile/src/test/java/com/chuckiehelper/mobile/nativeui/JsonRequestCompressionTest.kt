package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

class JsonRequestCompressionTest {
    @Test fun compressionRequiresServerAdvertisementAndClearsOnLegacyResponse() {
        val received = mutableListOf<okhttp3.Request>()
        val compression = JsonRequestCompression()
        val original = obj("message" to "重复的中文内容".repeat(300)).toString().toByteArray(Charsets.UTF_8)
        repeat(3) {
            val request = okhttp3.Request.Builder().url("http://localhost/api/test")
                .post(original.toRequestBody("application/json".toMediaType())).build()
            val chain = java.lang.reflect.Proxy.newProxyInstance(okhttp3.Interceptor.Chain::class.java.classLoader,
                arrayOf(okhttp3.Interceptor.Chain::class.java)) { _, method, arguments ->
                when (method.name) {
                    "request" -> request
                    "proceed" -> {
                        val sent = arguments!![0] as okhttp3.Request
                        received += sent
                        okhttp3.Response.Builder().request(sent).protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200).message("OK").apply {
                                if (received.size == 1) header("X-RemoteTool-Request-Compression", "gzip")
                            }.body("{}".toResponseBody()).build()
                    }
                    else -> error("Unexpected interceptor operation: ${method.name}")
                }
            } as okhttp3.Interceptor.Chain
            compression.intercept(chain).close()
        }
        fun bytes(request: okhttp3.Request): ByteArray = okio.Buffer().also { request.body!!.writeTo(it) }.readByteArray()
        assertNull(received[0].header("Content-Encoding"))
        assertEquals("gzip", received[1].header("Content-Encoding"))
        assertArrayEquals(original, GZIPInputStream(bytes(received[1]).inputStream()).use { it.readBytes() })
        assertNull(received[2].header("Content-Encoding"))
        assertArrayEquals(original, bytes(received[2]))
    }
    @Test fun unicodeJsonRoundTripsAndShrinks() {
        val original = obj("message" to "中文消息与附件路径".repeat(300)).toString().toByteArray(Charsets.UTF_8)
        val compressed = gzipJsonIfSmaller(original)!!
        assertTrue(compressed.size < original.size / 4)
        assertArrayEquals(original, GZIPInputStream(compressed.inputStream()).use { it.readBytes() })
    }
    @Test fun smallMessagesAvoidCompressionOverhead() {
        assertNull(gzipJsonIfSmaller("{\"message\":\"你好\"}".toByteArray(Charsets.UTF_8)))
    }
}
