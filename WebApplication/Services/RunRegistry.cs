using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>
/// 会话级活跃任务注册表：手机/平板等多端共享「哪个会话当前有 run 在执行」。
/// run_id 只由发起设备本地记录时，其它设备打开同一会话看不到实时进度；
/// 这里在转发 run 提交时登记，任何设备可查询并挂载同一事件流。
/// 持久化到 ProgramData（重启恢复）；查询时由调用方校验终态并清除。
/// </summary>
public sealed class RunRegistry
{
    private readonly string path = RemoteToolPaths.RunRegistry;
    private readonly object gate = new();
    private readonly Dictionary<string, JsonObject> entries; // key = $"{agent}:{sessionId}"

    public RunRegistry()
    {
        entries = Load();
    }

    private Dictionary<string, JsonObject> Load()
    {
        try
        {
            if (File.Exists(path))
            {
                var doc = JsonNode.Parse(File.ReadAllText(path)) as JsonObject;
                if (doc != null)
                    return doc.ToDictionary(e => e.Key, e => e.Value as JsonObject ?? new JsonObject());
            }
        }
        catch (Exception) when (true) { /* 损坏即丢弃，注册表只是提示性质 */ }
        return new Dictionary<string, JsonObject>();
    }

    private void Persist()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var doc = new JsonObject();
        foreach (var e in entries) doc[e.Key] = e.Value.DeepClone();
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, doc.ToJsonString());
        File.Move(tmp, path, true);
    }

    private static string Key(string agent, string sessionId) => agent + ":" + sessionId;

    public void Register(string agent, string sessionId, string runId)
    {
        lock (gate)
        {
            entries[Key(agent, sessionId)] = new JsonObject
            {
                ["run_id"] = runId,
                ["updated_at"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            };
            Persist();
        }
    }

    /// <summary>返回该会话登记的活跃 run_id，无则 null。</summary>
    public string? Query(string agent, string sessionId)
    {
        lock (gate)
            return entries.TryGetValue(Key(agent, sessionId), out var e) ? e["run_id"]?.GetValue<string>() : null;
    }

    /// <summary>任务到达终态后清除登记。仅当登记的 run_id 与传入一致才删（避免清掉后来新提交的 run）。</summary>
    public void Clear(string agent, string sessionId, string runId)
    {
        lock (gate)
        {
            var k = Key(agent, sessionId);
            if (entries.TryGetValue(k, out var e) && e["run_id"]?.GetValue<string>() == runId)
            {
                entries.Remove(k);
                Persist();
            }
        }
    }
}
