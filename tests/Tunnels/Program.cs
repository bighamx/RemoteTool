using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using RemoteTool.WebApi.Services.Tunnels;

var gateway = new TunnelGateway();
var builder = WebApplication.CreateBuilder(); builder.WebHost.UseUrls("http://127.0.0.1:0"); builder.Logging.ClearProviders();
await using var app = builder.Build(); app.UseWebSockets();
app.Map("/tunnel", async c => { using var ws = await c.WebSockets.AcceptWebSocketAsync(); await gateway.Attach(ws, c.RequestAborted, () => true); });
await app.StartAsync();
var url = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>().Addresses.Single().Replace("http://", "ws://") + "/tunnel";
using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(35)); var ct = timeout.Token;
static void Check(bool value, string message) { if (!value) throw new Exception(message); Console.WriteLine("PASS: " + message); }
static int Port(bool udp = false) {
    if (udp) { using var socket = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0)); return ((IPEndPoint)socket.Client.LocalEndPoint).Port; }
    var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop(); return port;
}
static async Task Send(ClientWebSocket ws, byte type, int id, int aux, byte[] payload, CancellationToken ct) =>
    await ws.SendAsync(new TunnelFrame(type, id, aux, payload).Encode(), WebSocketMessageType.Binary, true, ct);
static async Task<TunnelFrame> Read(ClientWebSocket ws, CancellationToken ct) {
    while (true) { var message = await TunnelGateway.Read(ws, ct); if (message.Type == WebSocketMessageType.Binary) return TunnelFrame.Decode(message.Bytes);
        using var json = JsonDocument.Parse(message.Bytes); if (json.RootElement.GetProperty("type").GetString() == "error") throw new Exception(json.RootElement.GetProperty("message").GetString()); }
}
var tcpEcho = new TcpListener(IPAddress.Loopback, 0); tcpEcho.Start();
var tcpEchoPort = ((IPEndPoint)tcpEcho.LocalEndpoint).Port;
var echoTask = Task.Run(async () => { using var target = await tcpEcho.AcceptTcpClientAsync(ct); var stream = target.GetStream(); var buffer = new byte[16384];
    while (true) { var n = await stream.ReadAsync(buffer, ct); if (n == 0) { target.Client.Shutdown(SocketShutdown.Send); break; } await stream.WriteAsync(buffer.AsMemory(0,n), ct); } });
using var udpEcho = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
var udpEchoPort = ((IPEndPoint)udpEcho.Client.LocalEndPoint).Port;
var udpTask = Task.Run(async () => { try { while (!ct.IsCancellationRequested) { var p = await udpEcho.ReceiveAsync(ct); await udpEcho.SendAsync(p.Buffer, p.RemoteEndPoint, ct); } } catch (Exception) { } });
var tcpPort = Port(); var udpPort = Port(true);
var rules = new[] {
    new TunnelRule(1,"out-tcp","tcp","phone_to_pc","127.0.0.1",Port(),"127.0.0.1",tcpEchoPort),
    new TunnelRule(2,"in-tcp","tcp","pc_to_phone","127.0.0.1",tcpPort,"127.0.0.1",8080),
    new TunnelRule(3,"out-udp","udp","phone_to_pc","127.0.0.1",Port(true),"127.0.0.1",udpEchoPort),
    new TunnelRule(4,"in-udp","udp","pc_to_phone","127.0.0.1",udpPort,"127.0.0.1",8080)
};
var clientId = "tunnel-test-" + Guid.NewGuid().ToString("N"); var runId = Guid.NewGuid().ToString();
async Task<ClientWebSocket> Connect(string run) {
    var ws = new ClientWebSocket(); await ws.ConnectAsync(new Uri(url), ct);
    await ws.SendAsync(JsonSerializer.SerializeToUtf8Bytes(new { type="configure", version=1, client_id=clientId, run_id=run, name="test", rules }, TunnelGateway.Json), WebSocketMessageType.Text, true, ct);
    return ws;
}
using var client = await Connect(runId);
var hello = await TunnelGateway.Read(client, ct); Check(Encoding.UTF8.GetString(hello.Bytes).Contains("ready"), "WS configuration acknowledged");
var payload = new byte[1024 * 1024]; Random.Shared.NextBytes(payload);
await Send(client, TunnelFrame.Open, 1, 1, [], ct);
Check((await Read(client,ct)).Type == TunnelFrame.Opened, "TCP target connected");
using var echoed = new MemoryStream(); var offset = 0; var credit = TunnelFrame.Credit;
async Task Consume(TunnelFrame frame) {
    if (frame.Type == TunnelFrame.Window) credit += frame.Aux;
    else if (frame.Type == TunnelFrame.Data) { echoed.Write(frame.Payload); await Send(client,TunnelFrame.Window,1,frame.Payload.Length,[],ct); }
    else if (frame.Type == TunnelFrame.Error) throw new Exception(Encoding.UTF8.GetString(frame.Payload));
}
while (offset < payload.Length) {
    var count = Math.Min(TunnelFrame.Chunk,payload.Length-offset);
    while (credit < count) await Consume(await Read(client,ct));
    await Send(client,TunnelFrame.Data,1,0,payload[offset..(offset+count)],ct); offset+=count; credit-=count;
}
await Send(client,TunnelFrame.Fin,1,0,[],ct);
while (true) { var frame=await Read(client,ct); if (frame.Type == TunnelFrame.Fin) break; await Consume(frame); }
Check(echoed.ToArray().SequenceEqual(payload), "phone-to-PC TCP 1 MiB and flow control/half-close");
await echoTask; tcpEcho.Stop();
using var local = new TcpClient(); await local.ConnectAsync(IPAddress.Loopback,tcpPort,ct);
var opened = await Read(client,ct); Check(opened.Type==TunnelFrame.Open && opened.Aux==2 && opened.Id%2==0,"PC-to-phone TCP listener opens correct rule");
await Send(client,TunnelFrame.Opened,opened.Id,0,[],ct);
var reverse = payload[..20000]; var localStream=local.GetStream(); await localStream.WriteAsync(reverse,ct); local.Client.Shutdown(SocketShutdown.Send);
var reverseRead=Task.Run(async()=>{using var data=new MemoryStream();await localStream.CopyToAsync(data,ct);return data.ToArray();});
while(true) { var frame=await Read(client,ct);
    if(frame.Type==TunnelFrame.Data){await Send(client,TunnelFrame.Data,frame.Id,0,frame.Payload,ct);await Send(client,TunnelFrame.Window,frame.Id,frame.Payload.Length,[],ct);}
    if(frame.Type==TunnelFrame.Fin){await Send(client,TunnelFrame.Fin,frame.Id,0,[],ct);break;}
}
Check((await reverseRead).SequenceEqual(reverse),"PC-to-phone TCP response survives input half-close");
await Send(client,TunnelFrame.Open,3,3,[],ct);
TunnelFrame udpOpened; do { udpOpened=await Read(client,ct); } while(udpOpened.Id!=3);
Check(udpOpened.Type==TunnelFrame.Opened,"UDP target connected");
foreach(var size in new[]{0,1000,60000}) {
    var bytes=payload[..size]; await Send(client,TunnelFrame.Datagram,3,0,bytes,ct);
    TunnelFrame result; do { result=await Read(client,ct); } while(result.Type!=TunnelFrame.Datagram || result.Id!=3);
    Check(result.Payload.SequenceEqual(bytes),$"phone-to-PC UDP {size} bytes");
}
using var localUdp=new UdpClient(new IPEndPoint(IPAddress.Loopback,0));
var datagram=payload[..1000]; await localUdp.SendAsync(datagram,new IPEndPoint(IPAddress.Loopback,udpPort),ct);
TunnelFrame peerOpen; do { peerOpen=await Read(client,ct); } while(peerOpen.Type!=TunnelFrame.Open);
Check(peerOpen.Aux==4 && peerOpen.Id%2==0,"PC-to-phone UDP peer isolated by rule");
await Send(client,TunnelFrame.Opened,peerOpen.Id,0,[],ct);
TunnelFrame peerData; do { peerData=await Read(client,ct); } while(peerData.Type!=TunnelFrame.Datagram || peerData.Id!=peerOpen.Id);
await Send(client,TunnelFrame.Datagram,peerOpen.Id,0,peerData.Payload,ct);
Check((await localUdp.ReceiveAsync(ct)).Buffer.SequenceEqual(datagram),"PC-to-phone UDP reply routed back to origin");
await gateway.Stop(clientId); client.Abort();
using var stopped=await Connect(runId); var stopMessage=await TunnelGateway.Read(stopped,ct);
Check(Encoding.UTF8.GetString(stopMessage.Bytes).Contains("stopped"),"server stop blocks automatic reconnect of the same run"); stopped.Abort();
using var restarted=await Connect(Guid.NewGuid().ToString()); var restartMessage=await TunnelGateway.Read(restarted,ct);
Check(Encoding.UTF8.GetString(restartMessage.Bytes).Contains("ready"),"explicit new run can rebind listeners after stop");
await gateway.Stop(clientId); restarted.Abort();
udpEcho.Dispose(); await app.StopAsync();
Console.WriteLine("Tunnel integration passed: both directions, TCP/UDP, 1 MiB stream, half-close, stop/restart.");
