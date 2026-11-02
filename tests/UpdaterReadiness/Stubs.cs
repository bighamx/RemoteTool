using System.Text.Json;
namespace RemoteTool.WebApi.Services {
    public static class RemoteToolPaths {
        public static string ProgramData = "";
        public static string RunRegistry => Path.Combine(ProgramData, "runs.json");
        public static string CodexState => Path.Combine(ProgramData, "native");
    }
    public static class ServerBuild { public static string Version => "test"; }
    public sealed class HermesProfiles { public string Root => Path.Combine(RemoteToolPaths.ProgramData, "no-hermes"); }
    public sealed class HermesProfileScope : IDisposable { public static HermesProfileScope Enter(string profile) => new(); public void Dispose() {} }
    public sealed class HermesManagement { public Task<JsonElement> Invoke(string action, object? input, CancellationToken ct) => throw new Exception("Unexpected Hermes status probe"); }
    public sealed class HermesBridge(Func<HttpResponseMessage> response) {
        public Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, object? body, object? extra, CancellationToken ct) => Task.FromResult(response());
    }
    public sealed class CodexBridge(Func<HttpResponseMessage> response) {
        public int Probes;
        public Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, object? body, CancellationToken ct) { Probes++; return Task.FromResult(response()); }
    }
}
namespace RemoteTool.WebApi.Services.Updates {
    public sealed class UpdaterBootstrap {
        public string Message => "test";
        public Task Ensure(CancellationToken ct) => Task.CompletedTask;
        public Task Start(string jobId, CancellationToken ct) => Task.CompletedTask;
        public Task Resume(CancellationToken ct) => Task.CompletedTask;
    }
}
namespace Hangfire {
    public sealed class JobStorage {
        public static JobStorage Current { get; } = new();
        public JobStorage GetMonitoringApi() => this;
        public long ProcessingCount() => 0;
    }
}
