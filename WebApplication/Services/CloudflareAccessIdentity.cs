using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Microsoft.IdentityModel.Tokens;

namespace RemoteTool.WebApi.Services;

// Optional single sign-on for a localhost origin published behind Cloudflare Access.
// Access's signed assertion is verified before it can become a RemoteTool session.
public sealed class CloudflareAccessIdentity(IConfiguration configuration, IHttpClientFactory clients)
{
    private readonly string? _team = configuration["CloudflareAccess:Team"]?.Trim().TrimEnd('/');
    private readonly string? _audience = configuration["CloudflareAccess:Audience"]?.Trim();
    private readonly string? _email = configuration["CloudflareAccess:Email"]?.Trim();
    private readonly SemaphoreSlim _refreshLock = new(1, 1);
    private JsonWebKeySet? _keys;
    private DateTimeOffset _keysFetched;

    public bool Enabled => !string.IsNullOrEmpty(_team) && !string.IsNullOrEmpty(_audience) && !string.IsNullOrEmpty(_email);

    public async Task<bool> AcceptAsync(string assertion, CancellationToken cancellationToken)
    {
        if (!Enabled || string.IsNullOrWhiteSpace(assertion)) return false;
        try
        {
            await RefreshKeysAsync(cancellationToken);
            var principal = new JwtSecurityTokenHandler().ValidateToken(assertion, new TokenValidationParameters
            {
                ValidateIssuer = true,
                ValidIssuer = $"https://{_team}.cloudflareaccess.com",
                ValidateAudience = true,
                ValidAudience = _audience,
                ValidateLifetime = true,
                RequireExpirationTime = true,
                ValidateIssuerSigningKey = true,
                RequireSignedTokens = true,
                ValidAlgorithms = [SecurityAlgorithms.RsaSha256],
                IssuerSigningKeys = _keys!.Keys
            }, out _);
            var email = principal.FindFirstValue(ClaimTypes.Email) ?? principal.FindFirstValue("email");
            return string.Equals(email, _email, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception error) when (error is SecurityTokenException or HttpRequestException or OperationCanceledException or InvalidOperationException or ArgumentException)
        {
            return false;
        }
    }

    private async Task RefreshKeysAsync(CancellationToken cancellationToken)
    {
        if (_keys != null && DateTimeOffset.UtcNow - _keysFetched < TimeSpan.FromHours(1)) return;
        await _refreshLock.WaitAsync(cancellationToken);
        try
        {
            if (_keys != null && DateTimeOffset.UtcNow - _keysFetched < TimeSpan.FromHours(1)) return;
            using var response = await clients.CreateClient().GetAsync($"https://{_team}.cloudflareaccess.com/cdn-cgi/access/certs", cancellationToken);
            response.EnsureSuccessStatusCode();
            _keys = new JsonWebKeySet(await response.Content.ReadAsStringAsync(cancellationToken));
            _keysFetched = DateTimeOffset.UtcNow;
        }
        finally { _refreshLock.Release(); }
    }
}
