using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.Extensions.Configuration;
using Microsoft.IdentityModel.Tokens;

namespace RemoteTool.WebApi.Services;

public class AuthService
{
    private readonly IConfiguration _configuration;
    private readonly OidcProviderStore _oidc;
    private readonly AccessSettingsStore _access;
    private readonly string _jwtSecret;
    private readonly JwtSecurityTokenHandler _tokenHandler = new();

    public AuthService(IConfiguration configuration, OidcProviderStore oidc, AccessSettingsStore access)
    {
        _configuration = configuration;
        _oidc = oidc;
        _access = access;
        _jwtSecret = configuration["Auth:JwtSecret"] 
            ?? throw new InvalidOperationException("Auth:JwtSecret 配置项缺失，请在 appsettings.json 中配置");
    }

    public bool ValidateCredentials(string username, string password)
    {
        var configUsername = _configuration["Auth:Username"] ?? "admin";
        var configPassword = _configuration["Auth:Password"] 
            ?? throw new InvalidOperationException("Auth:Password 配置项缺失，请在 appsettings.json 中配置");

        return username == configUsername && password == configPassword;
    }

    public string GenerateToken(string username, string? source = null, string? provider = null)
    {
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_jwtSecret));
        var credentials = new SigningCredentials(key, SecurityAlgorithms.HmacSha256);

        var claims = new List<Claim>
        {
            new Claim(ClaimTypes.Name, username),
            new Claim(ClaimTypes.Role, "Admin"),
            new Claim(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString())
        };
        if (source is not null) claims.Add(new Claim("remotetool:auth-source", source));
        if (provider is not null) claims.Add(new Claim("remotetool:auth-provider", provider));

        var token = new JwtSecurityToken(
            issuer: "RemoteControl",
            audience: "RemoteControl",
            claims: claims,
            expires: DateTime.UtcNow.AddHours(24),
            signingCredentials: credentials
        );

        return _tokenHandler.WriteToken(token);
    }

    public bool ValidateToken(string token) => GetPrincipalFromToken(token) is not null;

    public ClaimsPrincipal? GetPrincipalFromToken(string token)
    {
        try
        {
            var principal = _tokenHandler.ValidateToken(token, ValidationParameters(), out _);
            return ValidateIdentity(principal) ? principal : null;
        }
        catch
        {
            return null;
        }
    }

    public bool ValidateIdentity(ClaimsPrincipal principal)
    {
        try
        {
            var source = principal.FindFirstValue("remotetool:auth-source");
            var providerId = principal.FindFirstValue("remotetool:auth-provider");
            var name = principal.FindFirstValue(ClaimTypes.Name);
            if (source is null) return true; // Existing password tokens remain compatible.
            if (source == "oidc")
            {
                var provider = providerId is null ? null : _oidc.Find(providerId);
                return provider is { Enabled: true } && name is not null &&
                    provider.AllowedEmails.Contains(name, StringComparer.OrdinalIgnoreCase);
            }
            if (source == "cloudflare")
            {
                var settings = _access.Read();
                return settings.Enabled && providerId is not null &&
                    settings.AllowedEmails.Contains(providerId, StringComparer.OrdinalIgnoreCase);
            }
            return false;
        }
        catch (Exception error) when (error is System.Security.Cryptography.CryptographicException or
            InvalidDataException or IOException or UnauthorizedAccessException or System.Text.Json.JsonException)
        {
            return false;
        }
    }

    private TokenValidationParameters ValidationParameters() => new()
    {
        ValidateIssuer = true, ValidIssuer = "RemoteControl",
        ValidateAudience = true, ValidAudience = "RemoteControl",
        ValidateLifetime = true, ValidateIssuerSigningKey = true,
        IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_jwtSecret)),
        ClockSkew = TimeSpan.Zero
    };
}
