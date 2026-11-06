using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexToolEvent {
    private static readonly Regex PowerShellCommand = new(
        """^\s*(?:"([^"]+)"|'([^']+)'|(\S+))\s+(?:(?:-(?:NoLogo|NoProfile|NonInteractive)\s+)|(?:-ExecutionPolicy\s+\S+\s+))*-(?:Command|c)\s+([\s\S]+)$""",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    // Remove display-only launcher overhead before spending the preview budget.
    // WindowsApps runtime paths can consume almost the entire budget by themselves.
    internal static string Preview(string source) {
        var text = source ?? "";
        var match = PowerShellCommand.Match(text);
        if (match.Success) {
            var executable = Enumerable.Range(1, 3).Select(i => match.Groups[i].Value).First(value => value.Length > 0);
            var name = executable.Replace('\\', '/').Split('/').Last();
            if (name.Equals("pwsh", StringComparison.OrdinalIgnoreCase) || name.Equals("pwsh.exe", StringComparison.OrdinalIgnoreCase)) {
                var command = match.Groups[4].Value.Trim();
                if (command.Length >= 2 && command[0] is '\'' or '"' && command[^1] == command[0]) command = command[1..^1];
                text = "…/pwsh " + command;
            }
        }
        text = Regex.Replace(text, @"\s+", " ").Trim();
        return text.Length > 200 ? text[..199] + "…" : text;
    }

    internal static (string Key, object Value)[] Fields(JsonNode item, string name, string preview) => new (string, object)[] {
        ("tool", name), ("item_id", item.S("id")), ("call_id", item.S("callId")),
        ("preview", preview), ("error", item.S("status") is "failed" or "declined" ||
            item["exitCode"] != null && item.L("exitCode") != 0)
    };

    internal static string Status(JsonNode item, JsonNode turn) {
        if (item["exitCode"] != null && item.L("exitCode") != 0) return "failed";
        return item.S("status") switch {
            "failed" => "failed",
            "declined" or "interrupted" => "cancelled",
            "completed" => "completed",
            _ when item.B("completed") => "completed",
            _ => turn.S("status") == "inProgress" ? "running" : "unknown"
        };
    }
}
