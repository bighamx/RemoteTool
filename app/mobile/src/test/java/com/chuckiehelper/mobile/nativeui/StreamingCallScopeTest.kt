package com.chuckiehelper.mobile.nativeui

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class StreamingCallScopeTest {
    @Test fun cancellationUnblocksAnHttpBodyAfterHeadersHaveArrived() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val release = CountDownLatch(1)
        val responder = thread(isDaemon = true) {
            try { server.accept().use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray()); flush() }
                release.await(5, TimeUnit.SECONDS)
            } } catch (_: java.io.IOException) { }
        }
        val client = OkHttpClient()
        try {
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
            val headersArrived = CompletableDeferred<Unit>()
            val read = launch(Dispatchers.IO) {
                try { withStreamingCallCancellation(call) {
                    call.execute().use { response -> headersArrived.complete(Unit); response.body!!.string() }
                } } catch (error: java.io.IOException) { currentCoroutineContext().ensureActive(); throw error }
            }
            withTimeout(3000) { headersArrived.await() }
            read.cancel()
            withTimeout(2000) { read.join() }
            assertTrue(call.isCanceled())
            assertTrue(read.isCancelled)
        } finally {
            release.countDown(); server.close(); responder.join(1000)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun normalCompletionReturnsResultAndDisposesTheCancellationWatcher() = runBlocking {
        val client = OkHttpClient()
        try {
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:1/").build())
            assertEquals("completed", withStreamingCallCancellation(call) { "completed" })
            assertTrue(call.isCanceled())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
