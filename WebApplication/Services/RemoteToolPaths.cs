namespace RemoteTool.WebApi.Services;

/// <summary>
/// Canonical on-disk locations for this product. The server was renamed ChuckieHelper → RemoteTool,
/// so the data directory follows the product name: every component derives its paths from here
/// instead of hard-coding the folder, and <see cref="RemoteToolDataMigration"/> relocates the
/// historical location once on first start.
/// </summary>
public static class RemoteToolPaths
{
    /// <summary>Data directory name under ProgramData / LocalAppData / temp.</summary>
    public const string ProductFolder = "RemoteTool";

    /// <summary>
    /// Directory names this product used before the rename, newest first. Only used to find data
    /// that still needs migrating; never read or written at runtime.
    /// </summary>
    public static readonly string[] LegacyProductFolders = { "ChuckieHelper" };

    public static string ProgramData => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), ProductFolder);

    /// <summary>Product data directory under an arbitrary base (used by tests and the migration).</summary>
    public static string Root(string baseFolder) => Path.Combine(baseFolder, ProductFolder);

    public static string LocalData => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), ProductFolder);

    public static string TempData => Path.Combine(Path.GetTempPath(), ProductFolder);

    public static string CodexBridge => Path.Combine(ProgramData, "codex-bridge");

    /// <summary>Bridge state journal (runs/providers/model selections) for the current protocol.</summary>
    public static string CodexState => Path.Combine(CodexBridge, "state-v10");

    public static string CodexAttachments => Path.Combine(ProgramData, "codex-attachments");

    /// <summary>Attachment store for the given agent ("hermes" or "codex").</summary>
    public static string Attachments(string agent) => Path.Combine(ProgramData, agent + "-attachments");

    public static string HermesCompactions => Path.Combine(ProgramData, "hermes-compactions");

    public static string HermesKeyFile => Path.Combine(ProgramData, "hermes", "api-key.env");

    // Hermes itself remains a separate, user-owned installation.
    public static string HermesHome => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "hermes");

    public static string Sensors => Path.Combine(ProgramData, "sensors");

    public static string RunRegistry => Path.Combine(ProgramData, "run-registry.json");

    public static string DesktopAgentLog => Path.Combine(ProgramData, "logs", "desktop-agent-startup.log");

    /// <summary>Root that a pre-rename release would have used under the given base folder.</summary>
    public static string LegacyRoot(string baseFolder, string legacyName) => Path.Combine(baseFolder, legacyName);
}
