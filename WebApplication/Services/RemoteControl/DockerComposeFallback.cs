using System.Text.Json;
using RemoteTool.WebApi.Models.RemoteControl;

namespace RemoteTool.WebApi.Services.RemoteControl;

internal static class DockerComposeFallback
{
    public static List<ComposeProject> Parse(string output)
    {
        var groups = new Dictionary<string, List<(ComposeContainerInfo Container, string Config)>>(StringComparer.Ordinal);
        foreach (var line in output.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            using var doc = JsonDocument.Parse(line);
            var item = doc.RootElement;
            var labels = Value(item, "Labels");
            var project = Label(labels, "com.docker.compose.project");
            if (string.IsNullOrWhiteSpace(project)) continue;
            var container = new ComposeContainerInfo {
                Id = Value(item, "ID"), Names = Value(item, "Names"),
                Image = Value(item, "Image"), Status = Value(item, "Status"),
                State = Value(item, "State")
            };
            if (!groups.TryGetValue(project, out var rows)) groups[project] = rows = [];
            rows.Add((container, Label(labels, "com.docker.compose.project.config_files")));
        }
        return groups.Select(group => new ComposeProject {
            Name = group.Key,
            Status = group.Value.All(row => row.Container.State == "running") ? "running" : "mixed",
            ContainerCount = group.Value.Count,
            ConfigFiles = group.Value.Select(row => row.Config).FirstOrDefault(value => value.Length > 0) ?? "",
            Containers = group.Value.Select(row => row.Container).ToList()
        }).OrderBy(project => project.Name, StringComparer.Ordinal).ToList();
    }

    private static string Value(JsonElement item, string key) => item.TryGetProperty(key, out var value)
        ? value.ToString() : "";

    private static string Label(string labels, string key)
    {
        var marker = key + "=";
        var start = labels.IndexOf(marker, StringComparison.Ordinal);
        if (start < 0 || start > 0 && labels[start - 1] != ',') return "";
        start += marker.Length;
        var end = key == "com.docker.compose.project.config_files"
            ? labels.IndexOf(",com.docker.compose.", start, StringComparison.Ordinal)
            : labels.IndexOf(',', start);
        return labels[start..(end < 0 ? labels.Length : end)];
    }
}
