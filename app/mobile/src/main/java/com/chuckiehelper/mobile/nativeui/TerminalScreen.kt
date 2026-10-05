package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.*

@Composable
fun TerminalScreen(api: NativeApi, onError: (String) -> Unit) {
    var type by rememberSaveable { mutableStateOf("powershell") }
    var input by rememberSaveable { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("连接中") }
    var attempt by remember { mutableIntStateOf(0) }
    var socket by remember { mutableStateOf<WebSocket?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val buffer = remember(api, type, attempt) { TerminalBuffer() }
    DisposableEffect(api, type, attempt) {
        val live = java.util.concurrent.atomic.AtomicBoolean(true)
        output = ""
        state = "连接中"
        val request =
            Request.Builder()
                .url(api.base.replaceFirst("http", "ws") + "/ws/terminal?type=$type")
                .header("Cookie", api.cookie())
                .build()
        val ws =
            NativeApi.client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        scope.launch { if (live.get()) state = "已连接" }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val value = buffer.append(text)
                        scope.launch { if (live.get()) output = value }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        scope.launch {
                            if (!live.get()) return@launch
                            state = "已断开"
                            onError("终端连接失败：${t.message}")
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        scope.launch { if (live.get()) state = "已断开" }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            )
        socket = ws
        onDispose {
            live.set(false)
            socket = null
            ws.close(1000, "Leaving terminal")
        }
    }
    LaunchedEffect(output) { if (!scroll.isScrollInProgress) scroll.scrollTo(scroll.maxValue) }
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRowCompat {
            listOf("shell" to "Shell", "powershell" to "PowerShell", "cmd" to "CMD").forEach {
                (kind, label) ->
                FilterChip(type == kind, { type = kind }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(state, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { attempt++ }) { Text("重连") }
        }
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .background(Color(0xff0a111b))
                .verticalScroll(scroll)
                .padding(12.dp)
        ) {
            SelectionContainer {
                Text(
                    output.ifEmpty { "正在等待终端输出…" },
                    color = Color(0xffc7ded7),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        FlowRowCompat {
            TextButton(onClick = { socket?.send("\u0003") }) { Text("Ctrl+C") }
            TextButton(onClick = { socket?.send("\u0004") }) { Text("Ctrl+D") }
            TextButton(
                onClick = {
                    buffer.clear()
                    output = ""
                }
            ) {
                Text("清屏")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input,
                { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("输入命令") },
                maxLines = 3,
            )
            Button(
                enabled = state == "已连接",
                onClick = { if (socket?.send(input) == true) input = "" else onError("终端未连接") },
            ) {
                Text("发送")
            }
        }
    }
}

/**
 * Incremental terminal text handling, including split ANSI sequences and carriage-return progress
 * updates.
 */
internal class TerminalBuffer {
    private val lines = mutableListOf(StringBuilder())
    private var row = 0
    private var column = 0
    private var escape = ""
    private var savedRow = 0
    private var savedColumn = 0

    @Synchronized
    fun clear() {
        lines.clear()
        lines.add(StringBuilder())
        row = 0
        column = 0
        escape = ""
    }

    @Synchronized
    fun append(text: String): String {
        for (c in text) {
            if (escape.isNotEmpty()) {
                escape += c
                if (escape.startsWith("\u001b[")) {
                    if (c in '@'..'~' && escape.length > 2) {
                        sequence(escape.substring(2))
                        escape = ""
                    }
                } else if (escape.startsWith("\u001b]")) {
                    if (c == '\u0007' || escape.endsWith("\u001b\\")) escape = ""
                } else if (escape.length >= 2 && c != '[' && c != ']') escape = ""
                if (escape.length > 4096) escape = ""
                continue
            }
            when (c) {
                '\u001b' -> escape = c.toString()
                '\r' -> column = 0
                '\n' -> {
                    row++
                    column = 0
                    ensure()
                }
                '\b' -> column = (column - 1).coerceAtLeast(0)
                '\t' -> column = ((column / 8) + 1) * 8
                else ->
                    if (c >= ' ') {
                        ensure()
                        val line = lines[row]
                        while (line.length <= column) line.append(' ')
                        line.setCharAt(column, c)
                        column++
                    }
            }
        }
        while (lines.size > 4000) {
            lines.removeAt(0)
            row = (row - 1).coerceAtLeast(0)
        }
        return lines.joinToString("\n").takeLast(250000)
    }

    private fun ensure() {
        row = row.coerceIn(0, 4999)
        column = column.coerceIn(0, 4095)
        while (lines.size <= row) lines.add(StringBuilder())
    }

    private fun sequence(s: String) {
        val command = s.last()
        val args = s.dropLast(1).removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        val n = (args.firstOrNull() ?: 0).coerceAtLeast(1)
        when (command) {
            'A' -> row = (row - n).coerceAtLeast(0)
            'B' -> row += n
            'C' -> column += n
            'D' -> column = (column - n).coerceAtLeast(0)
            'G' -> column = n - 1
            'H',
            'f' -> {
                row = n - 1
                column = ((args.getOrNull(1) ?: 1).coerceAtLeast(1)) - 1
            }
            'J' ->
                if (args.firstOrNull() in listOf(2, 3)) {
                    lines.clear()
                    lines.add(StringBuilder())
                    row = 0
                    column = 0
                }
            'K' -> {
                ensure()
                val line = lines[row]
                when (args.firstOrNull()) {
                    2 -> line.clear()
                    1 -> {
                        for (i in 0 until minOf(line.length, column + 1)) line.setCharAt(i, ' ')
                    }
                    else -> if (column < line.length) line.setLength(column)
                }
            }
            's' -> {
                savedRow = row
                savedColumn = column
            }
            'u' -> {
                row = savedRow
                column = savedColumn
            }
        }
        ensure()
    }
}
