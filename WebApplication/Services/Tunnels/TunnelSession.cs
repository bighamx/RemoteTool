using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

namespace RemoteTool.WebApi.Services.Tunnels;

internal sealed class TunnelSession : IDisposable
{
    public string Id { get; }
    public string RunId { get; }
    private readonly string name;
    private readonly WebSocket socket;
    private readonly TunnelRule[] rules;
    private readonly CancellationTokenSource lifetime;
    private readonly SemaphoreSlim sending = new(1, 1);
    private readonly ConcurrentDictionary<int, Flow> flows = new();
    private readonly List<IDisposable> listeners = [];
    private readonly object flowGate = new();
    private int sequence, lastPeerId, disposed;
    private long sent, received, dropped;
    private readonly DateTimeOffset started = DateTimeOffset.UtcNow;
    private string state = "连接中";
    private readonly CancellationToken token;
    private CancellationToken Token => token;

    public TunnelSession(string id, string runId, string name, WebSocket socket, TunnelRule[] rules, CancellationTokenSource lifetime) {
        Id = id; RunId = runId; this.name = name; this.socket = socket; this.rules = rules; this.lifetime = lifetime; token = lifetime.Token;
    }
    public object Status() => new { id = Id, name, state, started_at = started, active = flows.Count,
        sent = Interlocked.Read(ref sent), received = Interlocked.Read(ref received), dropped = Interlocked.Read(ref dropped), rules };
    public async Task Stop() {
        try { using var timeout = new CancellationTokenSource(2000); await SendText(JsonSerializer.SerializeToUtf8Bytes(new { type = "stopped" }), timeout.Token); }
        finally { Dispose(); }
    }
    public void Dispose() {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        state = "已断开"; lifetime.Cancel(); socket.Abort();
        lock (listeners) { foreach (var listener in listeners) listener.Dispose(); listeners.Clear(); }
        foreach (var flow in flows.Values) flow.Dispose(); flows.Clear();
    }
    public async Task SendText(byte[] bytes, CancellationToken ct) {
        await sending.WaitAsync(ct);
        try { await socket.SendAsync(bytes, WebSocketMessageType.Text, true, ct); }
        finally { sending.Release(); }
    }
    private async Task Send(TunnelFrame frame) {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(Token); budget.CancelAfter(TimeSpan.FromSeconds(15));
        await sending.WaitAsync(budget.Token);
        try { await socket.SendAsync(frame.Encode(), WebSocketMessageType.Binary, true, budget.Token); }
        finally { sending.Release(); }
    }
    private void Work(Func<Task> action) => _ = Task.Run(async () => {
        try { await action(); }
        catch (Exception) { if (!Token.IsCancellationRequested) Dispose(); }
    });
    private void Register(IDisposable resource) {
        lock (listeners) {
            if (disposed != 0) { resource.Dispose(); throw new OperationCanceledException(Token); }
            listeners.Add(resource);
        }
    }

    public async Task Run(Func<bool> authorized) {
        // Bind every computer listener before announcing readiness; a partial configuration is never retained.
        foreach (var rule in rules.Where(rule => !rule.PhoneListens)) {
            var endpoint = new IPEndPoint(IPAddress.Parse(rule.ListenHost), rule.ListenPort);
            if (rule.Udp) {
                var udp = new UdpClient(endpoint); Register(udp);
                Work(() => ListenUdp(rule, udp));
            } else {
                var tcp = new TcpListener(endpoint);
                if (OperatingSystem.IsWindows()) tcp.Server.ExclusiveAddressUse = true;
                else tcp.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                try { tcp.Start(64); } catch { tcp.Server.Dispose(); throw; }
                Register(tcp.Server);
                Work(() => ListenTcp(rule, tcp));
            }
        }
        state = "已连接";
        await SendText(JsonSerializer.SerializeToUtf8Bytes(new { type = "ready", version = 1 }), Token);
        Work(async () => {
            while (!Token.IsCancellationRequested) {
                await Task.Delay(5000, Token);
                if (!authorized()) { await SendText(JsonSerializer.SerializeToUtf8Bytes(new { type = "auth_expired" }), Token); Dispose(); return; }
                foreach (var flow in flows.Values.Where(flow => flow.Rule.Udp && Environment.TickCount64 - flow.LastActivity > 60_000)) CloseFlow(flow.Id, true);
                await SendText(JsonSerializer.SerializeToUtf8Bytes(new { type = "stats", active = flows.Count,
                    sent = Interlocked.Read(ref sent), received = Interlocked.Read(ref received), dropped = Interlocked.Read(ref dropped) }), Token);
            }
        });
        while (!Token.IsCancellationRequested) {
            var message = await TunnelGateway.Read(socket, Token);
            if (message.Type != WebSocketMessageType.Binary) throw new InvalidDataException("需要二进制隧道数据");
            var frame = TunnelFrame.Decode(message.Bytes);
            if (frame.Type == TunnelFrame.Open) {
                if (frame.Id % 2 != 1 || frame.Id <= lastPeerId) throw new InvalidDataException("无效的连接编号");
                lastPeerId = frame.Id;
                var rule = rules.FirstOrDefault(rule => rule.Id == frame.Aux && rule.PhoneListens);
                if (rule == null) throw new InvalidDataException("转发规则不存在或方向不符");
                var flow = Create(frame.Id, rule);
                if (flow == null) { await Send(new(TunnelFrame.Error, frame.Id, 0, Encoding.UTF8.GetBytes("连接数量已达上限"))); continue; }
                Work(() => Dial(flow)); continue;
            }
            if (!flows.TryGetValue(frame.Id, out var current)) continue;
            switch (frame.Type) {
                case TunnelFrame.Opened: current.Opened.TrySetResult(); break;
                case TunnelFrame.Window:
                    if (current.Rule.Udp || !current.Grant(frame.Aux)) throw new InvalidDataException("无效的流量窗口"); break;
                case TunnelFrame.Data:
                    if (current.Rule.Udp || !current.Receive(frame.Payload))
                        throw new InvalidDataException("对端超出接收窗口");
                    Interlocked.Add(ref received, frame.Payload.Length); break;
                case TunnelFrame.Fin:
                    if (current.Rule.Udp || !current.ReceiveFin()) throw new InvalidDataException("无效的半关闭"); break;
                case TunnelFrame.Datagram:
                    if (!current.Rule.Udp) throw new InvalidDataException("数据报类型不符");
                    if (!current.QueueDatagram(frame)) Interlocked.Increment(ref dropped);
                    else Interlocked.Add(ref received, frame.Payload.Length); break;
                case TunnelFrame.Close: case TunnelFrame.Error: CloseFlow(frame.Id, false); break;
                default: throw new InvalidDataException("不支持的隧道消息");
            }
        }
    }
    private Flow Create(int id, TunnelRule rule) {
        lock (flowGate) {
            if (flows.Count >= 64 || disposed != 0 || flows.ContainsKey(id)) return null;
            var flow = new Flow(id, rule, Token); flows[id] = flow; return flow;
        }
    }
    private void CloseFlow(int id, bool notify) {
        if (!flows.TryRemove(id, out var flow)) return;
        flow.Dispose();
        if (notify && !Token.IsCancellationRequested) Work(() => Send(new(TunnelFrame.Close, id, 0, [])));
    }
    private async Task Guard(Flow flow, Func<Task> action) {
        try { await action(); }
        catch (Exception error) when (error is IOException or SocketException or TimeoutException or OperationCanceledException or WebSocketException or ObjectDisposedException) {
            if (!Token.IsCancellationRequested) {
                try { await Send(new(TunnelFrame.Error, flow.Id, 0, Encoding.UTF8.GetBytes("连接关闭：" + error.GetType().Name))); } catch { }
            }
        } finally { flow.Dispose(); CloseFlow(flow.Id, false); }
    }
    private async Task Dial(Flow flow) => await Guard(flow, async () => {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(flow.Token); budget.CancelAfter(TimeSpan.FromSeconds(10));
        if (flow.Rule.Udp) {
            var addresses = await Dns.GetHostAddressesAsync(flow.Rule.TargetHost, budget.Token);
            var address = addresses.FirstOrDefault(address => address.AddressFamily == AddressFamily.InterNetwork) ?? addresses.FirstOrDefault() ?? throw new SocketException();
            var udp = new UdpClient(address.AddressFamily); flow.TargetUdp = udp;
            udp.Connect(new IPEndPoint(address, flow.Rule.TargetPort));
        } else {
            flow.Tcp = new TcpClient { NoDelay = true };
            await flow.Tcp.ConnectAsync(flow.Rule.TargetHost, flow.Rule.TargetPort, budget.Token);
        }
        flow.Opened.TrySetResult();
        await Send(new(TunnelFrame.Opened, flow.Id, 0, []));
        await Pump(flow);
    });
    private async Task ListenTcp(TunnelRule rule, TcpListener listener) {
        while (!Token.IsCancellationRequested) {
            var tcp = await listener.AcceptTcpClientAsync(Token); tcp.NoDelay = true;
            var id = Interlocked.Add(ref sequence, 2); var flow = Create(id, rule);
            if (flow == null) { tcp.Dispose(); continue; }
            flow.Tcp = tcp;
            Work(() => Guard(flow, async () => {
                await Send(new(TunnelFrame.Open, flow.Id, rule.Id, []));
                await flow.Opened.Task.WaitAsync(TimeSpan.FromSeconds(10), flow.Token);
                await Pump(flow);
            }));
        }
    }
    private async Task ListenUdp(TunnelRule rule, UdpClient listener) {
        var peers = new Dictionary<string, Flow>();
        while (!Token.IsCancellationRequested) {
            var packet = await listener.ReceiveAsync(Token);
            if (packet.Buffer.Length > TunnelFrame.MaxDatagram) { Interlocked.Increment(ref dropped); continue; }
            var key = packet.RemoteEndPoint.ToString();
            if (!peers.TryGetValue(key, out var flow) || !flows.ContainsKey(flow.Id)) {
                foreach (var stale in peers.Where(pair => !flows.ContainsKey(pair.Value.Id)).Select(pair => pair.Key).ToArray()) peers.Remove(stale);
                flow = Create(Interlocked.Add(ref sequence, 2), rule);
                if (flow == null) { Interlocked.Increment(ref dropped); continue; }
                flow.ListenerUdp = listener; flow.Peer = packet.RemoteEndPoint; peers[key] = flow;
                var created = flow;
                Work(() => Guard(created, async () => {
                    await Send(new(TunnelFrame.Open, created.Id, rule.Id, []));
                    await created.Opened.Task.WaitAsync(TimeSpan.FromSeconds(10), created.Token);
                    await Pump(created);
                }));
            }
            flow.LastActivity = Environment.TickCount64;
            if (!flow.QueueOutgoing(packet.Buffer)) Interlocked.Increment(ref dropped);
        }
    }
    private async Task Pump(Flow flow) {
        if (flow.Rule.Udp) await Task.WhenAll(UdpRead(flow), UdpWrite(flow));
        else await Task.WhenAll(TcpRead(flow), TcpWrite(flow));
    }
    private async Task TcpRead(Flow flow) {
        try {
            var buffer = new byte[TunnelFrame.Chunk]; var stream = flow.Tcp.GetStream();
            while (!flow.Token.IsCancellationRequested) {
                var count = await stream.ReadAsync(buffer, flow.Token);
                if (count == 0) { await Send(new(TunnelFrame.Fin, flow.Id, 0, [])); flow.ReadDone.TrySetResult(); return; }
                await flow.TakeCredit(count);
                await Send(new(TunnelFrame.Data, flow.Id, 0, buffer[..count])); Interlocked.Add(ref sent, count);
            }
        } catch { flow.Cancel(); throw; }
    }
    private async Task TcpWrite(Flow flow) {
        try {
            while (!flow.Token.IsCancellationRequested) {
                var frame = await flow.ReadTcp();
                if (frame.Type == TunnelFrame.Fin) {
                    flow.Tcp.Client.Shutdown(SocketShutdown.Send);
                    await flow.ReadDone.Task.WaitAsync(flow.Token); return;
                }
                await flow.Tcp.GetStream().WriteAsync(frame.Payload, flow.Token);
                flow.Consumed(frame.Payload.Length);
                await Send(new(TunnelFrame.Window, flow.Id, frame.Payload.Length, []));
            }
        } catch { flow.Cancel(); throw; }
    }
    private async Task UdpRead(Flow flow) {
        try {
            if (flow.TargetUdp != null) {
                while (!flow.Token.IsCancellationRequested) {
                    var packet = await flow.TargetUdp.ReceiveAsync(flow.Token);
                    if (packet.Buffer.Length > TunnelFrame.MaxDatagram) { Interlocked.Increment(ref dropped); continue; }
                    flow.LastActivity = Environment.TickCount64;
                    await Send(new(TunnelFrame.Datagram, flow.Id, 0, packet.Buffer)); Interlocked.Add(ref sent, packet.Buffer.Length);
                }
            } else await foreach (var packet in flow.Outgoing.Reader.ReadAllAsync(flow.Token)) {
                Interlocked.Add(ref flow.OutgoingBytes, -packet.Bytes.Length);
                if (Environment.TickCount64 - packet.At > 2000) { Interlocked.Increment(ref dropped); continue; }
                await Send(new(TunnelFrame.Datagram, flow.Id, 0, packet.Bytes)); Interlocked.Add(ref sent, packet.Bytes.Length);
            }
        } catch { flow.Cancel(); throw; }
    }
    private async Task UdpWrite(Flow flow) {
        try {
            await foreach (var packet in flow.Input.Reader.ReadAllAsync(flow.Token)) {
                Interlocked.Add(ref flow.DatagramBytes, -packet.Payload.Length);
                flow.LastActivity = Environment.TickCount64;
                if (flow.TargetUdp != null) await flow.TargetUdp.SendAsync(packet.Payload, flow.Token);
                else await flow.ListenerUdp.SendAsync(packet.Payload, flow.Peer, flow.Token);
            }
        } catch { flow.Cancel(); throw; }
    }
    private sealed class Flow(int id, TunnelRule rule, CancellationToken parent) : IDisposable
    {
        public int Id = id;
        public TunnelRule Rule = rule;
        private static (CancellationTokenSource Source, CancellationToken Token) Linked(CancellationToken parentToken) {
            var source = CancellationTokenSource.CreateLinkedTokenSource(parentToken); return (source, source.Token);
        }
        private readonly (CancellationTokenSource Source, CancellationToken Token) cancellation = Linked(parent);
        public CancellationToken Token => cancellation.Token;
        public TcpClient Tcp;
        public UdpClient TargetUdp, ListenerUdp;
        public IPEndPoint Peer;
        public long LastActivity = Environment.TickCount64;
        public readonly TaskCompletionSource Opened = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public readonly TaskCompletionSource ReadDone = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public readonly Channel<TunnelFrame> Input = Channel.CreateBounded<TunnelFrame>(new BoundedChannelOptions(16) { SingleWriter = true });
        public readonly Channel<(long At, byte[] Bytes)> Outgoing = Channel.CreateBounded<(long, byte[])>(16);
        public int OutgoingBytes, DatagramBytes;
        private int disposed;
        public bool QueueDatagram(TunnelFrame frame) {
            if (Interlocked.Add(ref DatagramBytes, frame.Payload.Length) <= TunnelFrame.Credit && Input.Writer.TryWrite(frame)) return true;
            Interlocked.Add(ref DatagramBytes, -frame.Payload.Length); return false;
        }
        public bool QueueOutgoing(byte[] bytes) {
            if (Interlocked.Add(ref OutgoingBytes, bytes.Length) <= TunnelFrame.Credit && Outgoing.Writer.TryWrite((Environment.TickCount64, bytes))) return true;
            Interlocked.Add(ref OutgoingBytes, -bytes.Length); return false;
        }
        private readonly object gate = new();
        private readonly SemaphoreSlim creditChanged = new(0);
        private readonly SemaphoreSlim dataChanged = new(0);
        private readonly byte[] incoming = new byte[rule.Udp ? 0 : TunnelFrame.Credit];
        private int head, count;
        private bool fin;
        private int credit = TunnelFrame.Credit, outstanding;
        public bool Receive(byte[] bytes) {
            lock (gate) {
                if (fin || outstanding + bytes.Length > TunnelFrame.Credit) return false;
                var tail = (head + count) % incoming.Length;
                var first = Math.Min(bytes.Length, incoming.Length - tail);
                bytes.AsSpan(0, first).CopyTo(incoming.AsSpan(tail));
                bytes.AsSpan(first).CopyTo(incoming);
                var wasEmpty = count == 0; count += bytes.Length; outstanding += bytes.Length;
                if (wasEmpty) dataChanged.Release();
                return true;
            }
        }
        public bool ReceiveFin() {
            lock (gate) { if (fin) return false; fin = true; dataChanged.Release(); return true; }
        }
        public async Task<TunnelFrame> ReadTcp() {
            while (true) {
                lock (gate) {
                    if (count > 0) {
                        var size = Math.Min(count, TunnelFrame.Chunk); var bytes = new byte[size];
                        var first = Math.Min(size, incoming.Length - head);
                        incoming.AsSpan(head, first).CopyTo(bytes); incoming.AsSpan(0, size - first).CopyTo(bytes.AsSpan(first));
                        head = (head + size) % incoming.Length; count -= size;
                        return new(TunnelFrame.Data, Id, 0, bytes);
                    }
                    if (fin) return new(TunnelFrame.Fin, Id, 0, []);
                }
                await dataChanged.WaitAsync(Token);
            }
        }
        public void Consumed(int size) { lock (gate) outstanding -= size; }
        public bool Grant(int size) {
            lock (gate) { if (size <= 0 || size > TunnelFrame.Credit || credit + size > TunnelFrame.Credit) return false; credit += size; }
            creditChanged.Release(); return true;
        }
        public async Task TakeCredit(int size) {
            while (true) {
                lock (gate) { if (credit >= size) { credit -= size; return; } }
                await creditChanged.WaitAsync(Token);
            }
        }
        public void Cancel() {
            try { cancellation.Source.Cancel(); } catch (ObjectDisposedException) { }
            Tcp?.Dispose(); TargetUdp?.Dispose();
        }
        public void Dispose() {
            Cancel();
            if (Interlocked.Exchange(ref disposed, 1) != 0) return;
            Input.Writer.TryComplete(); Outgoing.Writer.TryComplete();
            while (Input.Reader.TryRead(out _)) { }
            while (Outgoing.Reader.TryRead(out _)) { }
            cancellation.Source.Dispose();
        }
    }
}
