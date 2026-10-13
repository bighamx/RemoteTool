using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Services;

public sealed record OidcProvider(
    string Id, string Name, string Issuer, string ClientId, string ClientSecret,
    string[] AllowedEmails, bool Enabled, string[] Scopes, bool UseUserInfo,
    bool Discovery, string DiscoveryUrl, string AuthorizationUrl, string TokenUrl,
    string CertificateUrl, string UserInfoUrl);

public sealed record OidcProviderInput(
    string? Id, string? Name, string? Issuer, string? ClientId, string? ClientSecret,
    string[]? AllowedEmails, bool Enabled, string[]? Scopes = null, bool UseUserInfo = false,
    bool Discovery = true, string? DiscoveryUrl = null, string? AuthorizationUrl = null,
    string? TokenUrl = null, string? CertificateUrl = null, string? UserInfoUrl = null);

/// <summary>OIDC client registrations are private runtime data, not source-controlled appsettings.</summary>
public sealed class OidcProviderStore
{
    private sealed record StoredProvider(string Id, string Name, string Issuer, string ClientId,
        string ProtectedSecret, string[] AllowedEmails, bool Enabled, string[]? Scopes = null, bool UseUserInfo = false,
        bool Discovery = true, string? DiscoveryUrl = null, string? AuthorizationUrl = null,
        string? TokenUrl = null, string? CertificateUrl = null, string? UserInfoUrl = null);
    private readonly object _gate = new();
    private readonly string _path;
    private readonly byte[] _key;
    private List<StoredProvider> _providers;

    public OidcProviderStore(IConfiguration configuration)
        : this(Path.Combine(RemoteToolPaths.ProgramData, "oidc", "providers.json"),
            configuration["Auth:JwtSecret"] ?? throw new InvalidOperationException("Auth:JwtSecret is required")) { }

    internal OidcProviderStore(string path, string jwtSecret)
    {
        _path = path;
        IdentityDataDirectory.Ensure(Path.GetDirectoryName(path)!);
        IdentityDataDirectory.EnsureFile(path);
        _key = SHA256.HashData(Encoding.UTF8.GetBytes("RemoteTool OIDC secret v1\0" + jwtSecret));
        _providers = File.Exists(path)
            ? JsonSerializer.Deserialize<List<StoredProvider>>(File.ReadAllText(path)) ?? [] : [];
    }

    public IReadOnlyList<OidcProvider> List()
    {
        lock (_gate) return _providers.Select(Open).ToArray();
    }

    public OidcProvider? Find(string id)
    {
        lock (_gate)
        {
            var row = _providers.FirstOrDefault(p => p.Id == id);
            return row is null ? null : Open(row);
        }
    }

    public OidcProvider Save(OidcProviderInput input)
    {
        var name = input.Name?.Trim() ?? "";
        var issuer = input.Issuer?.Trim().TrimEnd('/') ?? "";
        var clientId = input.ClientId?.Trim() ?? "";
        var emails = (input.AllowedEmails ?? []).Select(x => x?.Trim().ToLowerInvariant() ?? "")
            .Where(x => x.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        var scopes = (input.Scopes ?? ["openid", "profile", "email"]).Select(x => x?.Trim() ?? "")
            .Where(x => x.Length > 0).Distinct(StringComparer.Ordinal).ToArray();
        var discoveryUrl = input.DiscoveryUrl?.Trim() ?? "";
        var authorizationUrl = input.AuthorizationUrl?.Trim() ?? "";
        var tokenUrl = input.TokenUrl?.Trim() ?? "";
        var certificateUrl = input.CertificateUrl?.Trim() ?? "";
        var userInfoUrl = input.UserInfoUrl?.Trim() ?? "";
        static bool ValidEndpoint(string value) => Uri.TryCreate(value, UriKind.Absolute, out var endpoint) &&
            endpoint.Scheme == Uri.UriSchemeHttps && endpoint.UserInfo.Length == 0 &&
            endpoint.Fragment.Length == 0 && value.Length <= 2048;
        if (name.Length is < 1 or > 80 || clientId.Length is < 1 or > 200 ||
            !Uri.TryCreate(issuer, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps ||
            uri.UserInfo.Length > 0 || uri.Query.Length > 0 || uri.Fragment.Length > 0 ||
            emails.Length is < 1 or > 50 || emails.Any(x => x.Length > 254 ||
                !Regex.IsMatch(x, @"^[^\s@]+@[^\s@]+\.[^\s@]+$")) ||
            scopes.Length is < 1 or > 20 || !scopes.Contains("openid") ||
            scopes.Any(x => x.Length > 80 || !Regex.IsMatch(x, @"^[a-zA-Z0-9._:/-]+$")) ||
            (discoveryUrl.Length > 0 && !ValidEndpoint(discoveryUrl)) ||
            new[] { authorizationUrl, tokenUrl, certificateUrl, userInfoUrl }
                .Any(value => value.Length > 0 && !ValidEndpoint(value)) ||
            (!input.Discovery && (authorizationUrl.Length == 0 || tokenUrl.Length == 0 ||
                certificateUrl.Length == 0 || input.UseUserInfo && userInfoUrl.Length == 0)))
            throw new ArgumentException("请填写名称、HTTPS Issuer、Client ID、允许邮箱，以及当前模式所需的 HTTPS 端点");

        lock (_gate)
        {
            var id = string.IsNullOrEmpty(input.Id) ? Guid.NewGuid().ToString("N")[..16] : input.Id;
            var previous = _providers.FirstOrDefault(p => p.Id == id);
            if (input.Id is { Length: > 0 } && previous is null) throw new KeyNotFoundException("OIDC 提供方不存在");
            if (previous is null && _providers.Count >= 20) throw new ArgumentException("最多配置 20 个 OIDC 提供方");
            var secret = string.IsNullOrEmpty(input.ClientSecret) ? previous?.ProtectedSecret
                : Protect(input.ClientSecret);
            if (string.IsNullOrEmpty(secret)) throw new ArgumentException("新提供方必须填写 Client Secret");
            var row = new StoredProvider(id, name, issuer + "/", clientId, secret, emails, input.Enabled, scopes, input.UseUserInfo,
                input.Discovery, discoveryUrl, authorizationUrl, tokenUrl, certificateUrl, userInfoUrl);
            var updated = _providers.Where(p => p.Id != id).Append(row).ToList();
            Persist(updated);
            _providers = updated;
            return Open(row);
        }
    }

    public void Delete(string id)
    {
        lock (_gate)
        {
            if (!_providers.Any(p => p.Id == id)) throw new KeyNotFoundException("OIDC 提供方不存在");
            var updated = _providers.Where(p => p.Id != id).ToList();
            Persist(updated);
            _providers = updated;
        }
    }

    private OidcProvider Open(StoredProvider p) => new(p.Id, p.Name, p.Issuer, p.ClientId,
        Unprotect(p.ProtectedSecret), p.AllowedEmails.ToArray(), p.Enabled,
        (p.Scopes ?? ["openid", "profile", "email"]).ToArray(), p.UseUserInfo,
        p.Discovery, p.DiscoveryUrl ?? "", p.AuthorizationUrl ?? "", p.TokenUrl ?? "",
        p.CertificateUrl ?? "", p.UserInfoUrl ?? "");

    private string Protect(string value)
    {
        var nonce = RandomNumberGenerator.GetBytes(12);
        var ciphertext = new byte[Encoding.UTF8.GetByteCount(value)];
        var tag = new byte[16];
        using (var aes = new AesGcm(_key, 16)) aes.Encrypt(nonce, Encoding.UTF8.GetBytes(value), ciphertext, tag);
        return "v1:" + Convert.ToBase64String(nonce.Concat(tag).Concat(ciphertext).ToArray());
    }

    private string Unprotect(string value)
    {
        if (!value.StartsWith("v1:", StringComparison.Ordinal)) throw new InvalidDataException("OIDC secret format is invalid");
        var bytes = Convert.FromBase64String(value[3..]);
        if (bytes.Length < 28) throw new InvalidDataException("OIDC secret is incomplete");
        var plaintext = new byte[bytes.Length - 28];
        using (var aes = new AesGcm(_key, 16)) aes.Decrypt(bytes.AsSpan(0, 12), bytes.AsSpan(28), bytes.AsSpan(12, 16), plaintext);
        return Encoding.UTF8.GetString(plaintext);
    }

    private void Persist(List<StoredProvider> rows)
    {
        var directory = Path.GetDirectoryName(_path)!;
        IdentityDataDirectory.Ensure(directory);
        var temporary = _path + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(rows), new UTF8Encoding(false));
            if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            File.Move(temporary, _path, true);
            IdentityDataDirectory.EnsureFile(_path);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
}
