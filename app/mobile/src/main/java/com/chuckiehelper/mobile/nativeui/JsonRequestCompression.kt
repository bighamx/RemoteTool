package com.chuckiehelper.mobile.nativeui

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

internal fun gzipJsonIfSmaller(bytes: ByteArray): ByteArray? {
    if (bytes.size < 1024) return null
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(bytes) }
    return output.toByteArray().takeIf { it.size < bytes.size }
}

/** Opt in per server origin only after it advertises request decompression support. */
internal class JsonRequestCompression : Interceptor {
    private val supported = ConcurrentHashMap.newKeySet<String>()
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val origin = "${request.url.scheme}://${request.url.host}:${request.url.port}"
        val body = request.body
        if (origin in supported && request.url.encodedPath.startsWith("/api/") &&
            request.header("Content-Encoding") == null && body != null && !body.isOneShot() && !body.isDuplex() &&
            body.contentType()?.subtype == "json" && body.contentLength() in 1024..4L * 1024 * 1024) {
            val buffer = Buffer()
            body.writeTo(buffer)
            gzipJsonIfSmaller(buffer.readByteArray())?.let { packed ->
                request = request.newBuilder().header("Content-Encoding", "gzip")
                    .removeHeader("Content-Length").method(request.method, packed.toRequestBody(body.contentType())).build()
            }
        }
        val response = chain.proceed(request)
        if (request.url.encodedPath.startsWith("/api/")) {
            if (response.header("X-RemoteTool-Request-Compression") == "gzip") supported.add(origin)
            else supported.remove(origin)
        }
        return response
    }
}
