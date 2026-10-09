namespace ChuckieHelper.WebApi.Services.Codex;

internal static class CodexOfficialRouting
{
    // A failed workspace switch must not leave Codex routed to a third-party provider.
    // Keep the custom tables dormant on disk; removing the selector restores built-in OpenAI.
    public static async Task Restore(string executable, string home, CancellationToken cancellationToken = default)
    {
        var path = Path.Combine(home, "config.toml");
        if (!File.Exists(path)) return;
        var original = await File.ReadAllTextAsync(path, cancellationToken);
        var lines = original.Split('\n');
        for (var index = 0; index < lines.Length; index++) {
            var line = lines[index];
            var trimmed = line.Trim();
            var next = trimmed.Length > 14 ? trimmed[14] : '\0';
            var isSelector = trimmed.StartsWith("model_provider", StringComparison.Ordinal)
                && (next == '\0' || next == '=' || char.IsWhiteSpace(next));
            if (isSelector) lines[index] = "";
        }
        var text = string.Join('\n', lines);
        text = text.Replace("\r\n\n", "\r\n").Replace("\n\n\n", "\n");
        if (text == original) return;
        var backup = Path.Combine(home, "backups", "chuckie-helper", DateTime.UtcNow.ToString("yyyyMMdd-HHmmss") + "-" + Guid.NewGuid().ToString("N")[..6]);
        Directory.CreateDirectory(backup);
        File.Copy(path, Path.Combine(backup, "config.toml"));
        await File.WriteAllTextAsync(path, text, cancellationToken);
        var catalog = Path.Combine(home, "model_catalog.json");
        if (File.Exists(catalog)) File.Delete(catalog);
        if (string.IsNullOrWhiteSpace(executable) || !File.Exists(executable)) return;
        await using var verify = new CodexRpc(executable, home, new Dictionary<string, string>(), _ => Task.CompletedTask);
        await verify.Initialize(cancellationToken);
    }
}
