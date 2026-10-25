package com.chuckiehelper.mobile.tunnel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.io.IOException
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** One WS generation. All sockets belong to it and are discarded on reconnect. */
internal class TunnelPeer(private val ws: WebSocket, private val rules: List<TunnelRule>, parent: CoroutineContext,
    private val sent: AtomicLong, private val received: AtomicLong, private val dropped: AtomicLong,
    private val onError: (String) -> Unit) : Closeable {
    private val job = SupervisorJob(parent[Job])
    private val scope = CoroutineScope(parent + job + Dispatchers.IO.limitedParallelism(192))
    private val flows = ConcurrentHashMap<Int, Flow>()
    private val listeners = mutableListOf<Closeable>()
    private val inbound = Channel<ByteArray>(128)
    private val queued = AtomicInteger()
    private val sequence = AtomicInteger(-1)
    private var lastPeer = 0
    private val sendLock = Any()
    private val flowLock = Any()
    private val stopped = AtomicBoolean()
    val finished = CompletableDeferred<Throwable?>()
    val active get() = flows.size
    private fun register(listener: Closeable) {
        synchronized(listeners) {
            if (stopped.get()) { listener.close(); throw IOException("隧道已停止") }
            listeners.add(listener)
        }
    }

    fun startReceiving() {
        scope.launch {
            try {
                for (raw in inbound) { queued.addAndGet(-raw.size); handle(TunnelFrame.decode(raw)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { close(error) }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                flows.values.filter { it.rule.udp && android.os.SystemClock.elapsedRealtime() - it.lastActivity > 60_000 }.forEach { drop(it.id, true) }
            }
        }
    }
    fun receive(raw: ByteArray) {
        if (raw.size > TunnelFrame.MAX_FRAME || queued.addAndGet(raw.size) > 2 * 1024 * 1024 || !inbound.trySend(raw).isSuccess)
            close(IOException("隧道接收队列已满"))
    }
    fun startListeners() {
        try {
            rules.filter { it.phoneListens }.forEach { rule ->
                val address = InetSocketAddress(InetAddress.getByName(rule.listenHost), rule.listenPort)
                if (rule.udp) {
                    val udp = DatagramSocket(null)
                    try { udp.reuseAddress = false; udp.bind(address); register(Closeable { udp.close() }) }
                    catch (error: Exception) { udp.close(); throw error }
                    launchListener { listenUdp(rule, udp) }
                } else {
                    val tcp = ServerSocket()
                    try { tcp.reuseAddress = true; tcp.bind(address, 64); register(tcp) }
                    catch (error: Exception) { tcp.close(); throw error }
                    launchListener { listenTcp(rule, tcp) }
                }
            }
        } catch (error: Exception) { close(error); throw error }
    }
    override fun close() = close(null)
    fun close(error: Throwable?) {
        if (!stopped.compareAndSet(false, true)) return
        finished.complete(error)
        job.cancel(); inbound.close(); ws.cancel()
        synchronized(listeners) { listeners.forEach { runCatching { it.close() } }; listeners.clear() }
        flows.values.forEach { it.close() }; flows.clear()
    }
    private fun launchListener(action: suspend () -> Unit) { scope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (!stopped.get()) close(error) }
    } }
    private fun launchFlow(flow: Flow, action: suspend () -> Unit) { scope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!stopped.get()) {
                onError("${flow.rule.name.ifBlank { "规则 ${flow.rule.id}" }}：${error.message ?: "连接失败"}")
                runCatching { send(TunnelFrame(TunnelFrame.ERROR, flow.id, payload = "目标连接失败".toByteArray())) }
            }
        } finally { flow.closeSockets(); drop(flow.id, false) }
    } }
    private suspend fun send(frame: TunnelFrame): Boolean {
        if (frame.type == TunnelFrame.DATAGRAM && ws.queueSize() > 256 * 1024) { dropped.incrementAndGet(); return false }
        withTimeout(15_000) {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (stopped.get()) throw IOException("隧道已断开")
                val accepted = synchronized(sendLock) {
                    if (ws.queueSize() > 1024 * 1024) false
                    else { if (!ws.send(frame.encode().toByteString())) throw IOException("隧道发送失败"); true }
                }
                if (accepted) return@withTimeout
                delay(10)
            }
        }
        return true
    }
    private fun create(id: Int, rule: TunnelRule): Flow? = synchronized(flowLock) {
        if (stopped.get() || flows.size >= 64 || flows.containsKey(id)) null
        else Flow(id, rule).also { flows[id] = it }
    }
    private fun drop(id: Int, notify: Boolean) {
        val flow = flows.remove(id) ?: return
        flow.close()
        if (notify && !stopped.get()) scope.launch { try { send(TunnelFrame(TunnelFrame.CLOSE, id)) } catch (_: Exception) { } }
    }
    private suspend fun handle(frame: TunnelFrame) {
        if (frame.type == TunnelFrame.OPEN) {
            require(frame.id % 2 == 0 && frame.id > lastPeer) { "连接编号无效" }; lastPeer = frame.id
            val rule = rules.firstOrNull { it.id == frame.aux && !it.phoneListens } ?: throw IOException("转发方向不符")
            val flow = create(frame.id, rule)
            if (flow == null) { send(TunnelFrame(TunnelFrame.ERROR, frame.id, payload = "连接数已满".toByteArray())); return }
            launchFlow(flow) {
                if (rule.udp) {
                    val target = withTimeout(10_000) {
                        val addresses = InetAddress.getAllByName(rule.targetHost)
                        InetSocketAddress(addresses.firstOrNull { it is Inet4Address } ?: addresses.first(), rule.targetPort)
                    }
                    flow.targetUdp = DatagramSocket().apply { connect(target) }
                } else {
                    flow.tcp = Socket().apply { tcpNoDelay = true }
                    withTimeout(10_000) { flow.tcp!!.connect(InetSocketAddress(rule.targetHost, rule.targetPort), 10_000) }
                }
                flow.opened.complete(Unit); send(TunnelFrame(TunnelFrame.OPENED, flow.id)); pump(flow)
            }
            return
        }
        val flow = flows[frame.id] ?: return
        when (frame.type) {
            TunnelFrame.OPENED -> flow.opened.complete(Unit)
            TunnelFrame.WINDOW -> require(!flow.rule.udp && flow.grant(frame.aux)) { "流量窗口无效" }
            TunnelFrame.DATA -> {
                require(!flow.rule.udp && flow.receive(frame.payload)) { "接收窗口已满" }
                received.addAndGet(frame.payload.size.toLong())
            }
            TunnelFrame.FIN -> require(!flow.rule.udp && flow.receiveFin()) { "半关闭无效" }
            TunnelFrame.DATAGRAM -> {
                require(flow.rule.udp) { "数据报类型无效" }
                if (flow.queueDatagram(frame)) received.addAndGet(frame.payload.size.toLong()) else dropped.incrementAndGet()
            }
            TunnelFrame.CLOSE, TunnelFrame.ERROR -> {
                if (frame.type == TunnelFrame.ERROR) onError("${flow.rule.name.ifBlank { "规则 ${flow.rule.id}" }}：${frame.payload.toString(Charsets.UTF_8)}")
                drop(frame.id, false)
            }
            else -> throw IOException("隧道消息无效")
        }
    }
    private suspend fun listenTcp(rule: TunnelRule, listener: ServerSocket) {
        while (scope.isActive) {
            val socket = listener.accept().apply { tcpNoDelay = true }
            val flow = create(sequence.addAndGet(2), rule)
            if (flow == null) { socket.close(); continue }
            flow.tcp = socket
            launchFlow(flow) {
                send(TunnelFrame(TunnelFrame.OPEN, flow.id, rule.id))
                withTimeout(10_000) { flow.opened.await() }; pump(flow)
            }
        }
    }
    private suspend fun listenUdp(rule: TunnelRule, listener: DatagramSocket) {
        val peers = mutableMapOf<SocketAddress, Flow>()
        val buffer = ByteArray(TunnelFrame.MAX_UDP + 1)
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size); listener.receive(packet)
            if (packet.length > TunnelFrame.MAX_UDP) { dropped.incrementAndGet(); continue }
            val address = packet.socketAddress
            var flow = peers[address]?.takeIf { flows.containsKey(it.id) }
            if (flow == null) {
                peers.entries.removeAll { !flows.containsKey(it.value.id) }
                flow = create(sequence.addAndGet(2), rule)
                if (flow == null) { dropped.incrementAndGet(); continue }
                flow.listenerUdp = listener; flow.peer = address; peers[address] = flow
                val opened = flow
                launchFlow(opened) {
                    send(TunnelFrame(TunnelFrame.OPEN, opened.id, rule.id))
                    withTimeout(10_000) { opened.opened.await() }; pump(opened)
                }
            }
            flow.lastActivity = android.os.SystemClock.elapsedRealtime()
            if (!flow.queueOutgoing(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))) dropped.incrementAndGet()
        }
    }
    private suspend fun pump(flow: Flow) = coroutineScope {
        if (flow.rule.udp) {
            launch { try { udpRead(flow) } finally { flow.closeSockets() } }
            launch { try { udpWrite(flow) } finally { flow.closeSockets() } }
        } else {
            launch { try { tcpRead(flow) } catch (error: Exception) { flow.closeSockets(); throw error } }
            launch { try { tcpWrite(flow) } catch (error: Exception) { flow.closeSockets(); throw error } }
        }
    }
    private suspend fun tcpRead(flow: Flow) {
        val stream = flow.tcp!!.getInputStream(); val buffer = ByteArray(TunnelFrame.CHUNK)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count < 0) { send(TunnelFrame(TunnelFrame.FIN, flow.id)); flow.readDone.complete(Unit); return }
            if (count == 0) continue
            flow.takeCredit(count)
            send(TunnelFrame(TunnelFrame.DATA, flow.id, payload = buffer.copyOf(count))); sent.addAndGet(count.toLong())
        }
    }
    private suspend fun tcpWrite(flow: Flow) {
        while (true) {
            val frame = flow.readTcp()
            if (frame.type == TunnelFrame.FIN) { flow.tcp!!.shutdownOutput(); flow.readDone.await(); return }
            flow.tcp!!.getOutputStream().write(frame.payload)
            flow.consumed(frame.payload.size)
            send(TunnelFrame(TunnelFrame.WINDOW, flow.id, frame.payload.size))
        }
    }
    private suspend fun udpRead(flow: Flow) {
        val socket = flow.targetUdp
        if (socket != null) {
            val buffer = ByteArray(TunnelFrame.MAX_UDP + 1)
            while (true) {
                currentCoroutineContext().ensureActive()
                val packet = DatagramPacket(buffer, buffer.size); socket.receive(packet)
                if (packet.length > TunnelFrame.MAX_UDP) { dropped.incrementAndGet(); continue }
                flow.lastActivity = android.os.SystemClock.elapsedRealtime()
                if (send(TunnelFrame(TunnelFrame.DATAGRAM, flow.id, payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)))) sent.addAndGet(packet.length.toLong())
            }
        } else for ((at, bytes) in flow.outgoing) {
            flow.outgoingBytes.addAndGet(-bytes.size)
            if (android.os.SystemClock.elapsedRealtime() - at > 2000) { dropped.incrementAndGet(); continue }
            if (send(TunnelFrame(TunnelFrame.DATAGRAM, flow.id, payload = bytes))) sent.addAndGet(bytes.size.toLong())
        }
    }
    private suspend fun udpWrite(flow: Flow) {
        for (frame in flow.input) {
            flow.datagramBytes.addAndGet(-frame.payload.size)
            flow.lastActivity = android.os.SystemClock.elapsedRealtime()
            if (flow.targetUdp != null) flow.targetUdp!!.send(DatagramPacket(frame.payload, frame.payload.size))
            else flow.listenerUdp!!.send(DatagramPacket(frame.payload, frame.payload.size, flow.peer))
        }
    }
    private class Flow(val id: Int, val rule: TunnelRule) {
        @Volatile var tcp: Socket? = null
        @Volatile var targetUdp: DatagramSocket? = null
        var listenerUdp: DatagramSocket? = null
        var peer: SocketAddress? = null
        @Volatile var lastActivity = android.os.SystemClock.elapsedRealtime()
        val opened = CompletableDeferred<Unit>()
        val readDone = CompletableDeferred<Unit>()
        val input = Channel<TunnelFrame>(16)
        val outgoing = Channel<Pair<Long, ByteArray>>(16)
        val outgoingBytes = AtomicInteger()
        val datagramBytes = AtomicInteger()
        fun queueDatagram(frame: TunnelFrame): Boolean {
            if (datagramBytes.addAndGet(frame.payload.size) <= TunnelFrame.CREDIT && input.trySend(frame).isSuccess) return true
            datagramBytes.addAndGet(-frame.payload.size); return false
        }
        fun queueOutgoing(bytes: ByteArray): Boolean {
            if (outgoingBytes.addAndGet(bytes.size) <= TunnelFrame.CREDIT && outgoing.trySend(android.os.SystemClock.elapsedRealtime() to bytes).isSuccess) return true
            outgoingBytes.addAndGet(-bytes.size); return false
        }
        private val creditChanged = Channel<Unit>(Channel.CONFLATED)
        private val dataChanged = Channel<Unit>(Channel.CONFLATED)
        private val incoming = ByteArray(if (rule.udp) 0 else TunnelFrame.CREDIT)
        private var head = 0
        private var count = 0
        private var fin = false
        private var credit = TunnelFrame.CREDIT
        private var outstanding = 0
        private val closed = AtomicBoolean()
        @Synchronized fun receive(bytes: ByteArray): Boolean {
            if (fin || outstanding + bytes.size > TunnelFrame.CREDIT) return false
            val tail = (head + count) % incoming.size
            val first = minOf(bytes.size, incoming.size - tail)
            bytes.copyInto(incoming, tail, 0, first); bytes.copyInto(incoming, 0, first)
            count += bytes.size; outstanding += bytes.size; dataChanged.trySend(Unit); return true
        }
        @Synchronized fun receiveFin(): Boolean {
            if (fin) return false
            fin = true; dataChanged.trySend(Unit); return true
        }
        suspend fun readTcp(): TunnelFrame {
            while (true) {
                val result = synchronized(this) {
                    if (count > 0) {
                        val size = minOf(count, TunnelFrame.CHUNK); val bytes = ByteArray(size)
                        val first = minOf(size, incoming.size - head)
                        incoming.copyInto(bytes, 0, head, head + first); incoming.copyInto(bytes, first, 0, size - first)
                        head = (head + size) % incoming.size; count -= size
                        TunnelFrame(TunnelFrame.DATA, id, payload = bytes)
                    } else if (fin) TunnelFrame(TunnelFrame.FIN, id) else null
                }
                if (result != null) return result
                dataChanged.receive()
            }
        }
        @Synchronized fun consumed(size: Int) { outstanding -= size }
        @Synchronized fun grant(size: Int): Boolean {
            if (size <= 0 || size > TunnelFrame.CREDIT || credit + size > TunnelFrame.CREDIT) return false
            credit += size; creditChanged.trySend(Unit); return true
        }
        suspend fun takeCredit(size: Int) {
            while (true) {
                if (synchronized(this) { if (credit >= size) { credit -= size; true } else false }) return
                creditChanged.receive()
            }
        }
        fun closeSockets() { runCatching { tcp?.close() }; targetUdp?.close() }
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            closeSockets(); input.cancel(); outgoing.cancel(); creditChanged.cancel(); dataChanged.cancel(); opened.cancel(); readDone.cancel()
        }
    }
}
