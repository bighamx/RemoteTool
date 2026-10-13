using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Services;

public sealed record AccessSettings(bool Enabled, string Team, string Audience, string[] AllowedEmails);

public sealed class AccessSettingsStore
{
    private readonly object _gate = new();
    private readonly string _path = Path.Combine(RemoteToolPaths.ProgramData, "oidc", "cloudflare-access.json");
    private readonly IConfiguration _configuration;
    private AccessSettings? _current;

    public AccessSettingsStore(IConfiguration configuration)
    {
        _configuration = configuration;
        IdentityDataDirectory.Ensure(Path.GetDirectoryName(_path)!);
        IdentityDataDirectory.EnsureFile(_path);
    }

    public AccessSettings Read()
    {
        lock (_gate)
        {
            if (_current is not null) return _current;
            if (File.Exists(_path))
                return _current = JsonSerializer.Deserialize<AccessSettings>(File.ReadAllText(_path))
                    ?? new AccessSettings(false, "", "", []);
            // Existing private appsettings deployments keep working until edited in the UI.
            var team = _configuration["CloudflareAccess:Team"]?.Trim() ?? "";
            var audience = _configuration["CloudflareAccess:Audience"]?.Trim() ?? "";
            var email = _configuration["CloudflareAccess:Email"]?.Trim() ?? "";
            return _current = new AccessSettings(team.Length > 0 && audience.Length > 0 && email.Length > 0,
                team, audience, email.Length > 0 ? [email] : []);
        }
    }

    public AccessSettings Save(AccessSettings input)
    {
        var team = (input.Team ?? "").Trim().ToLowerInvariant();
        var audience = (input.Audience ?? "").Trim();
        var emails = (input.AllowedEmails ?? []).Select(x => x?.Trim().ToLowerInvariant() ?? "")
            .Where(x => x.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        if (input.Enabled && (!Regex.IsMatch(team, @"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$") ||
            audience.Length is < 1 or > 128 || emails.Length is < 1 or > 50 ||
            emails.Any(x => x.Length > 254 || !Regex.IsMatch(x, @"^[^\s@]+@[^\s@]+\.[^\s@]+$"))))
            throw new ArgumentException("请填写 Team 名称、应用 AUD 和 1–50 个有效的允许邮箱");
        var result = new AccessSettings(input.Enabled, team, audience, emails);
        lock (_gate)
        {
            IdentityDataDirectory.Ensure(Path.GetDirectoryName(_path)!);
            var temporary = _path + ".tmp-" + Guid.NewGuid().ToString("N");
            try
            {
                File.WriteAllText(temporary, JsonSerializer.Serialize(result), new UTF8Encoding(false));
                if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                File.Move(temporary, _path, true);
                IdentityDataDirectory.EnsureFile(_path);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
            return _current = result;
        }
    }
}
