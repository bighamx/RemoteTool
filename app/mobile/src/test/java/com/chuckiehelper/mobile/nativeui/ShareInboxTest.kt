package com.chuckiehelper.mobile.nativeui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.robolectric.shadows.ShadowContentResolver
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShareInboxTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun fileUri(file: File): Uri {
        val uri = Uri.parse("content://share-fixture/${file.name}")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/pdf"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(uri.lastPathSegment)) }
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(File(file.parentFile, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "share-fixture" })
        ShadowContentResolver.registerProviderInternal("share-fixture", provider)
        return uri
    }
    private fun waitUntil(done: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("Share import timed out")
    }

    @Test fun singleAndMultipleSharesUseStreamsAndClipWithoutDuplicates() {
        val first = Uri.parse("content://sender/one"); val second = Uri.parse("content://sender/two")
        val single = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)
        single.clipData = ClipData.newRawUri("file", first)
        assertEquals(listOf(first), sharedUris(single))
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        multiple.clipData = ClipData.newRawUri("file", second)
        assertEquals(listOf(first, second), sharedUris(multiple))
        assertTrue(sharedUris(Intent(Intent.ACTION_MAIN).putExtra(Intent.EXTRA_STREAM, first)).isEmpty())
        assertTrue(sharedUris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private/path"))).isEmpty())
    }

    @Test fun copiesFileBeforeSenderDisappearsAndRestoresAfterRecreation() {
        val source = File(app.cacheDir, "chat-camera/${UUID.randomUUID()}.pdf")
        source.parentFile!!.mkdirs(); source.writeText("shared original content")
        val uri = fileUri(source)
        val saved = SavedStateHandle()
        val inbox = ShareInboxModel(app, saved)
        inbox.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, "说明"))
        waitUntil { !inbox.importing }
        assertNull(inbox.error); assertTrue(inbox.readable)
        assertEquals(source.name, inbox.files.single().name)
        source.delete()
        assertEquals("shared original content", inbox.files.single().readText())
        inbox.text = "补充说明"
        val restored = ShareInboxModel(app, saved)
        assertTrue(restored.pending); assertTrue(restored.readable)
        assertEquals("补充说明", restored.text)
        assertEquals("shared original content", restored.files.single().readText())
        val copied = restored.files.single()
        restored.dismiss()
        assertFalse(copied.exists()); assertFalse(restored.pending)
    }

    @Test fun rejectsOversizeBatchWithoutSilentlyDroppingFiles() {
        val inbox = ShareInboxModel(app, SavedStateHandle())
        inbox.receive(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,
            ArrayList((1..9).map { Uri.parse("content://sender/$it") })))
        assertTrue(inbox.pending); assertFalse(inbox.readable)
        assertTrue(inbox.error!!.contains("8")); assertTrue(inbox.files.isEmpty())
        inbox.dismiss()
    }

    @Test fun filenamesCannotEscapePrivateImportDirectory() {
        assertEquals("sample.pdf", sharedFileName("../../sample.pdf"))
        assertEquals("sample.pdf", sharedFileName("C:\\tmp\\sample.pdf"))
        assertEquals("attachment", sharedFileName(".."))
        assertFalse(sharedFileName("bad\nname?.txt").contains('\n'))
    }

    @Test fun retryUploadsOnlyRemainingFilesAndNeverSendsAnAgentMessage() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val responder = thread(isDaemon = true) {
            try { repeat(3) { server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().buffered()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) bytes.write(b) }
                    return bytes.toString("UTF-8")
                }
                paths += line().split(" ")[1]
                var length = 0
                while (true) {
                    val header = line(); if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(":").trim().toInt()
                }
                val body = ByteArray(length); var offset = 0
                while (offset < length) { val n = input.read(body, offset, length-offset); if (n < 0) break; offset += n }
                bodies += body.decodeToString()
                val number = requests.incrementAndGet()
                val failed = number == 2
                val response = if (failed) "{\"message\":\"try again\"}" else "{\"id\":\"file-$number\",\"name\":\"document.pdf\"}"
                val status = if (failed) "503 Unavailable" else "200 OK"
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n" + response).toByteArray()); flush()
                }
            } } } catch (_: java.io.IOException) { }
        }
        val originals = (1..2).map { File(app.cacheDir, "chat-camera/${UUID.randomUUID()}.pdf").apply {
            parentFile!!.mkdirs(); writeText("file $it")
        } }
        val inbox = ShareInboxModel(app, SavedStateHandle())
        try {
            inbox.receive(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                ArrayList(originals.map { fileUri(it) })))
            waitUntil { !inbox.importing }
            val api = NativeApi("http://127.0.0.1:${server.localPort}")
            val session = JSONObject().put("id", "session-1").put("title", "target")
            inbox.prepare(api, "device-1", "codex", session)
            waitUntil { !inbox.uploading }
            assertNotNull(inbox.error); assertNull(inbox.ready)
            assertTrue(inbox.files.all { it.isFile })
            inbox.prepare(api, "device-1", "codex", session)
            waitUntil { !inbox.uploading }
            assertEquals(3, requests.get())
            assertTrue(bodies.all { it.contains("Content-Type: application/pdf") })
            assertEquals(2, inbox.ready!!.attachments.size)
            assertEquals("device-1", inbox.ready!!.deviceId)
            assertTrue(paths.all { it == "/api/codex/sessions/session-1/files" })
            inbox.finishDelivery(true)
            assertFalse(inbox.pending)
        } finally { server.close(); responder.join(1000); inbox.dismiss(); originals.forEach { it.delete() } }
    }

    @Test fun directoryShareConfirmsConflictsAndRetriesOnlyTheFailedFile() {
        val folder = File(app.cacheDir, "directory-share-${UUID.randomUUID()}").apply { mkdirs() }
        val local = listOf("one.pdf", "two.pdf").map { name -> File(folder, name).apply { writeText(name) } }
        val inbox = ShareInboxModel(app, SavedStateHandle(mapOf("pending" to true, "readable" to true,
            "files" to ArrayList(local.map { it.absolutePath }), "mimes" to arrayListOf("application/pdf", "application/pdf"))))
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val responses = listOf(
            200 to "{\"data\":[{\"name\":\"one.pdf\"}]}",
            200 to "{\"data\":[{\"name\":\"one.pdf\"}]}",
            200 to "{\"success\":true}",
            503 to "{\"message\":\"retry\"}",
            200 to "{\"data\":[{\"name\":\"one.pdf\"}]}",
            200 to "{\"success\":true}")
        val responder = thread(isDaemon = true) {
            try { responses.forEach { (code, response) -> server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().buffered()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) bytes.write(b) }
                    return bytes.toString("UTF-8")
                }
                paths += line()
                var length = 0
                while (true) {
                    val header = line(); if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(":").trim().toInt()
                }
                val body = ByteArray(length); var offset = 0
                while (offset < length) { val n = input.read(body, offset, length-offset); if (n < 0) break; offset += n }
                bodies += body.decodeToString()
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 $code Result\r\nContent-Type: application/json\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n" + response).toByteArray()); flush()
                }
            } } } catch (_: java.io.IOException) { }
        }
        val api = NativeApi("http://127.0.0.1:${server.localPort}")
        val destination = "/shared/空 格&文件"
        try {
            inbox.uploadToDirectory(api, "device-1", destination)
            waitUntil { !inbox.uploading }
            assertEquals(listOf("one.pdf"), inbox.conflictsForDirectory("device-1", destination))
            assertTrue(inbox.conflictsForDirectory("device-2", destination).isEmpty())
            assertEquals(1, paths.size) // No writes before explicit overwrite confirmation.
            inbox.uploadToDirectory(api, "device-1", destination, overwrite = true)
            waitUntil { !inbox.uploading }
            assertNotNull(inbox.error); assertEquals(1, inbox.uploadedCount); assertNull(inbox.directoryResult)
            inbox.uploadToDirectory(api, "device-1", destination)
            waitUntil { !inbox.uploading }
            assertNull(inbox.error)
            assertEquals(2, inbox.directoryResult!!.count); assertEquals(destination, inbox.directoryResult!!.path)
            val posts = paths.withIndex().filter { it.value.startsWith("POST ") }
            assertEquals(3, posts.size)
            assertTrue(bodies[posts[0].index].contains("one.pdf"))
            assertTrue(posts.drop(1).all { bodies[it.index].contains("two.pdf") })
            assertTrue(paths.all { java.net.URLDecoder.decode(it.split(" ")[1].substringAfter("?path="), "UTF-8") == destination })
            assertTrue(local.all { it.isFile })
            inbox.dismiss(); assertFalse(local.any { it.exists() })
        } finally { server.close(); responder.join(1000); inbox.dismiss(); folder.deleteRecursively() }
    }
}
