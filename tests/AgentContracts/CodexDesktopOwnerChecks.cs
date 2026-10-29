using System.Text.Json.Nodes;
using System.Reflection;
using System.Text;
using RemoteTool.WebApi.Services.Codex;

internal static class CodexDesktopOwnerChecks
{
    public static async Task Run() {
        var id = Guid.NewGuid().ToString();
        foreach (var scenario in new[] { "ready", "recover", "slow-owner", "unknown", "other-error", "invalid-id", "unavailable", "retry-rejected", "probe-unknown", "retry-unknown" }) {
            var requests = 0; var opens = 0; var probes = 0; var deliveries = 0;
            var inputId = scenario == "invalid-id" ? id + "?prompt=untrusted" : id;
            async Task<JsonObject> Request() {
                await Task.CompletedTask;
                requests++;
                if (scenario == "unknown") throw new CodexError("uncertain", 504, "delivery_unknown");
                if (scenario == "retry-unknown" && requests > 1) throw new CodexError("uncertain retry", 504, "delivery_unknown");
                if (scenario == "other-error") throw new CodexError("busy", 409, "busy");
                if (scenario != "ready" && (requests == 1 || scenario == "retry-rejected"))
                    throw new CodexError("no-client-found", 409, "desktop_no_client");
                deliveries++; return new JsonObject { ["accepted"] = true };
            }
            Task Open(string thread) {
                if (thread != id || requests != 1) throw new Exception("Only the validated rejected thread may be loaded");
                opens++; return Task.CompletedTask;
            }
            Task<JsonObject> Discover(string thread) {
                probes++;
                if (opens != 1 || requests != 1 || thread != id) throw new Exception("Owner must be ready before retrying");
                if (scenario == "probe-unknown") throw new CodexError("discovery timeout", 504, "delivery_unknown");
                if (scenario == "unavailable" || (scenario == "slow-owner" && probes == 1))
                    throw new CodexError("no-client-found", 409, "desktop_no_client");
                return Task.FromResult(new JsonObject());
            }
            CodexError failure = null;
            try { await CodexDesktopSync.RecoverOwner(inputId, Request, Open, Discover); }
            catch (CodexError error) { failure = error; }
            var recoverable = scenario is "recover" or "slow-owner" or "unavailable" or "retry-rejected" or "probe-unknown" or "retry-unknown";
            var success = scenario is "ready" or "recover" or "slow-owner";
            if ((failure == null) != success || deliveries != (success ? 1 : 0) || opens != (recoverable ? 1 : 0) ||
                requests != (scenario is "recover" or "slow-owner" or "retry-rejected" or "retry-unknown" ? 2 : 1) ||
                probes != (scenario is "slow-owner" or "unavailable" ? 2 : recoverable ? 1 : 0))
                throw new Exception("Incorrect desktop owner recovery: " + scenario);
            if (scenario == "unknown" && failure?.Code != "delivery_unknown") throw new Exception("Uncertain delivery must remain uncertain");
            if (scenario == "retry-unknown" && failure?.Code != "delivery_unknown") throw new Exception("Uncertain retry must not be repeated");
            if (scenario == "probe-unknown" && (failure?.Code != "session_owner_unavailable" || failure.Delivery != "rejected"))
                throw new Exception("Discovery timeout is not uncertainty about the undelivered user message");
        }
        using var input = new MemoryStream();
        foreach (var frame in new[] {
            new JsonObject { ["type"] = "client-discovery-request", ["requestId"] = "discovery" },
            new JsonObject { ["type"] = "response", ["requestId"] = "operation", ["resultType"] = "success" }
        }) {
            var bytes = Encoding.UTF8.GetBytes(frame.ToJsonString());
            input.Write(BitConverter.GetBytes(bytes.Length)); input.Write(bytes);
        }
        input.Position = 0;
        using var stream = new DuplexFixture(input);
        var read = typeof(CodexDesktopSync).GetMethod("Read", BindingFlags.NonPublic | BindingFlags.Static)!;
        var response = await (Task<JsonObject>)read.Invoke(null, new object[] { stream, CancellationToken.None })!;
        var output = stream.Output.ToArray();
        var decline = JsonNode.Parse(Encoding.UTF8.GetString(output, 4, BitConverter.ToInt32(output)))!;
        if (response["requestId"]?.ToString() != "operation" || decline["type"]?.ToString() != "client-discovery-response" ||
            decline["requestId"]?.ToString() != "discovery" || decline["response"]?["canHandle"]?.GetValue<bool>() != false)
            throw new Exception("Transient clients must decline discovery and continue reading their operation");
        Console.WriteLine("Desktop owner recovery: single retry, readiness, validation and uncertain-delivery checks passed");
    }
    private sealed class DuplexFixture(MemoryStream input) : Stream
    {
        public MemoryStream Output { get; } = new();
        public override bool CanRead => true;
        public override bool CanWrite => true;
        public override bool CanSeek => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override int Read(byte[] buffer, int offset, int count) => input.Read(buffer, offset, count);
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default) => input.ReadAsync(buffer, ct);
        public override void Write(byte[] buffer, int offset, int count) => Output.Write(buffer, offset, count);
        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default) => Output.WriteAsync(buffer, ct);
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        protected override void Dispose(bool disposing) { if (disposing) Output.Dispose(); base.Dispose(disposing); }
    }
}
