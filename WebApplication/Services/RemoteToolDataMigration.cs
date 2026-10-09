using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>
/// One-time relocation of the on-disk data directory when the product was renamed
/// ChuckieHelper → RemoteTool. Runs on the first start of a renamed build (web process or any of
/// its child processes). Each data root has its own completion marker; incomplete moves retry.
///
/// Only the data directory moves. The web installation (bin, appsettings, web.config) keeps its
/// path: the IIS site, the keepalive scheduled task and appsettings point at it, so moving it would
/// silently break those. Stop old bridge/desktop processes before upgrading so they do not
/// continue writing to the old layout while files are being relocated.
/// </summary>
public static class RemoteToolDataMigration
{
    /// <summary>Written into the destination root once the move has completed.</summary>
    private const string MarkerFileName = "data-migration.done";

    /// <summary>Files whose content embeds absolute data paths and must be rewritten after a move.</summary>
    private static readonly string[] PathRewritingFiles =
    {
        "run-registry.json",
        Path.Combine("codex-bridge", "connection.json"),
        Path.Combine("codex-bridge", "model-selections.json"),
        Path.Combine("codex-bridge", "runs.json"),
        Path.Combine("codex-bridge", "title-model.json"),
        Path.Combine("codex-bridge", "title-sessions.json"),
        Path.Combine("codex-bridge", "title-sessions-hermes.json"),
        Path.Combine("codex-bridge", "state-v10", "runs.json"),
        Path.Combine("codex-bridge", "state-v10", "providers.json"),
        Path.Combine("codex-bridge", "state-v10", "model-selections.json"),
    };

    public static void Migrate()
        => Migrate(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            Path.GetTempPath());

    /// <summary>Roots are parameters so the relocation can be exercised against fixtures.</summary>
    public static void Migrate(string commonDataRoot, string localDataRoot, string tempRoot)
    {
        try
        {
            // Each root has its own marker: IIS and the desktop user have different LocalAppData.
            foreach (var baseRoot in new[] { commonDataRoot, localDataRoot, tempRoot }.Distinct())
                MigrateRoot(baseRoot);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            // Migration is best effort: a failure must not stop the service from starting.
            // The marker is only written after a successful move, so the next start retries.
        }
    }

    private static void MigrateRoot(string baseRoot)
    {
        var destination = RemoteToolPaths.Root(baseRoot);
        var marker = Path.Combine(destination, MarkerFileName);
        var sources = RemoteToolPaths.LegacyProductFolders.Select(name => Path.Combine(baseRoot, name)).ToArray();
        if (File.Exists(marker) && !sources.Any(Directory.Exists)) return;
        try
        {
            Directory.CreateDirectory(destination);
            if ((File.GetAttributes(destination) & FileAttributes.ReparsePoint) != 0) return;
            // Serializes startup migration across web/bridge/desktop processes.
            using var migrationLock = new FileStream(Path.Combine(destination, ".data-migration.lock"),
                FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
            var complete = true;
            foreach (var source in sources) complete &= MoveDirectory(source, destination);
            complete &= RewriteStoredPaths(destination);
            if (complete)
                File.WriteAllText(marker, RemoteToolPaths.ProductFolder, Encoding.UTF8);
            else if (File.Exists(marker)) File.Delete(marker);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            // Another process is migrating, or access is unavailable. Leave data for a later retry.
            Console.Error.WriteLine($"RemoteTool data migration pending ({destination}): {error.GetType().Name}");
        }
    }

    /// <summary>
    /// Moves <paramref name="source"/> into <paramref name="destination"/>, deleting the source when
    /// everything moved. A pre-existing destination is merged into recursively (directories are
    /// descended into, existing files win), never overwritten, so no data is lost if both layouts exist.
    /// </summary>
    private static bool MoveDirectory(string source, string destination)
    {
        if (!Directory.Exists(source)) return true;
        if (string.Equals(Path.GetFullPath(source), Path.GetFullPath(destination), StringComparison.OrdinalIgnoreCase)) return true;
        if ((File.GetAttributes(source) & FileAttributes.ReparsePoint) != 0) return false;

        Directory.CreateDirectory(destination);
        var complete = true;
        foreach (var entry in Directory.EnumerateFileSystemEntries(source))
        {
            var target = Path.Combine(destination, Path.GetFileName(entry));
            try
            {
                if ((File.GetAttributes(entry) & FileAttributes.ReparsePoint) != 0) { complete = false; continue; }
                if (Directory.Exists(target) && (File.GetAttributes(target) & FileAttributes.ReparsePoint) != 0) { complete = false; continue; }
                if (Directory.Exists(entry))
                {
                    if (Directory.Exists(target)) complete &= MoveDirectory(entry, target); // merge nested directories
                    else Directory.Move(entry, target);
                }
                else if (File.Exists(entry))
                {
                    if (!File.Exists(target)) File.Move(entry, target);
                    else complete = false; // Preserve both copies; a conflict is not a completed migration.
                }
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                complete = false;
                // Leave the entry in place (and the source directory behind) rather than risk data loss.
            }
        }

        try { if (!Directory.EnumerateFileSystemEntries(source).Any()) Directory.Delete(source); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { complete = false; }
        return complete;
    }

    /// <summary>
    /// Repoints absolute paths recorded inside the moved data files, so e.g. the bridge connection
    /// and the resume journal point at the new directory. Only a small whitelist is touched; logs
    /// and binary fixtures are deliberately left alone.
    /// </summary>
    private static bool RewriteStoredPaths(string root)
    {
        if (!Directory.Exists(root)) return true;
        var complete = true;
        foreach (var legacyName in RemoteToolPaths.LegacyProductFolders)
        {
            var oldRoot = Path.Combine(Path.GetDirectoryName(root)!, legacyName).Replace('\\', '/');
            foreach (var relative in PathRewritingFiles)
            {
                var file = Path.Combine(root, relative);
                if (!File.Exists(file)) continue;
                try
                {
                    var text = File.ReadAllText(file, Encoding.UTF8);
                    var json = JsonNode.Parse(text);
                    if (json == null || !RewriteNode(json, oldRoot, root)) continue;
                    var temporary = file + ".migration.tmp";
                    File.WriteAllText(temporary, json.ToJsonString(), Encoding.UTF8);
                    File.Move(temporary, file, overwrite: true);
                }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { complete = false; }
            }
        }
        return complete;
    }

    private static bool RewriteNode(JsonNode node, string oldRoot, string newRoot)
    {
        var changed = false;
        string Rewrite(string value)
        {
            var normalized = value.Replace('\\', '/');
            if (!normalized.Equals(oldRoot, StringComparison.OrdinalIgnoreCase)
                && !normalized.StartsWith(oldRoot + "/", StringComparison.OrdinalIgnoreCase)) return value;
            changed = true;
            return newRoot + normalized[oldRoot.Length..].Replace('/', Path.DirectorySeparatorChar);
        }
        if (node is JsonObject obj)
            foreach (var pair in obj.ToArray())
            {
                if (pair.Value is JsonValue value && value.TryGetValue<string>(out var text)) obj[pair.Key] = Rewrite(text);
                else if (pair.Value != null) changed |= RewriteNode(pair.Value, oldRoot, newRoot);
            }
        else if (node is JsonArray array)
            for (var i = 0; i < array.Count; i++)
            {
                if (array[i] is JsonValue value && value.TryGetValue<string>(out var text)) array[i] = Rewrite(text);
                else if (array[i] != null) changed |= RewriteNode(array[i]!, oldRoot, newRoot);
            }
        return changed;
    }
}
