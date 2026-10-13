using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Microsoft.IdentityModel.Tokens;

namespace RemoteTool.WebApi.Services;

// Optional single sign-on for a localhost origin published behind Cloudflare Access.
// Access's signed assertion is verified before it can become a RemoteTool session.
public sealed class CloudflareAccessIdentity(AccessSettingsStore settings, IHttpClientFactory clients)
{
    private readonly SemaphoreSlim _refreshLock = new(1, 1);
    private JsonWebKeySet? _keys;
    private DateTimeOffset _keysFetched;
    private string? _keysTeam;

    public bool Enabled => settings.Read().Enabled;

    public async Task<string?> ValidatedEmailAsync(string assertion, CancellationToken cancellationToken)
    {
        var config = settings.Read();
        if (!config.Enabled || string.IsNullOrWhiteSpace(assertion)) return null;
        try
        {
            await RefreshKeysAsync(config.Team, cancellationToken);
            var principal = new JwtSecurityTokenHandler().ValidateToken(assertion, new TokenValidationParameters
            {
                ValidateIssuer = true,
                ValidIssuer = $"https://{config.Team}.cloudflareaccess.com",
                ValidateAudience = true,
                ValidAudience = config.Audience,
                ValidateLifetime = true,
                RequireExpirationTime = true,
                ValidateIssuerSigningKey = true,
                RequireSignedTokens = true,
                ValidAlgorithms = [SecurityAlgorithms.RsaSha256],
                IssuerSigningKeys = _keys!.Keys
            }, out _);
            var email = principal.FindFirstValue(ClaimTypes.Email) ?? principal.FindFirstValue("email");
            return email is not null && config.AllowedEmails.Contains(email, StringComparer.OrdinalIgnoreCase) ? email : null;
        }
        catch (Exception error) when (error is SecurityTokenException or HttpRequestException or OperationCanceledException or InvalidOperationException or ArgumentException)
        {
            return null;
        }
    }

    private async Task RefreshKeysAsync(string team, CancellationToken cancellationToken)
    {
        if (_keys != null && _keysTeam == team && DateTimeOffset.UtcNow - _keysFetched < TimeSpan.FromHours(1)) return;
        await _refreshLock.WaitAsync(cancellationToken);
        try
        {
            if (_keys != null && _keysTeam == team && DateTimeOffset.UtcNow - _keysFetched < TimeSpan.FromHours(1)) return;
            using var response = await clients.CreateClient().GetAsync($"https://{team}.cloudflareaccess.com/cdn-cgi/access/certs", cancellationToken);
            response.EnsureSuccessStatusCode();
            _keys = new JsonWebKeySet(await response.Content.ReadAsStringAsync(cancellationToken));
            _keysTeam = team;
            _keysFetched = DateTimeOffset.UtcNow;
        }
        finally { _refreshLock.Release(); }
    }
}
