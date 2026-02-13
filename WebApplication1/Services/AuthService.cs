using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.Extensions.Configuration;
using Microsoft.IdentityModel.Tokens;

namespace ChuckieHelper.WebApi.Services;

public class AuthService
{
    private readonly IConfiguration _configuration;
    private readonly string _jwtSecret;
    private readonly JwtSecurityTokenHandler _tokenHandler = new();

    public AuthService(IConfiguration configuration)
    {
        _configuration = configuration;
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

    public string GenerateToken(string username)
    {
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_jwtSecret));
        var credentials = new SigningCredentials(key, SecurityAlgorithms.HmacSha256);

        var claims = new[]
        {
            new Claim(ClaimTypes.Name, username),
            new Claim(ClaimTypes.Role, "Admin"),
            new Claim(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString())
        };

        var token = new JwtSecurityToken(
            issuer: "RemoteControl",
            audience: "RemoteControl",
            claims: claims,
            expires: DateTime.UtcNow.AddHours(24),
            signingCredentials: credentials
        );

        return _tokenHandler.WriteToken(token);
    }

    public bool ValidateToken(string token)
    {
        try
        {
            var key = Encoding.UTF8.GetBytes(_jwtSecret);

            _tokenHandler.ValidateToken(token, new TokenValidationParameters
            {
                ValidateIssuer = false,
                ValidateAudience = false,
                ValidateLifetime = true,
                ValidateIssuerSigningKey = true,
                IssuerSigningKey = new SymmetricSecurityKey(key),
                ClockSkew = TimeSpan.Zero
            }, out SecurityToken validatedToken);

            return validatedToken is JwtSecurityToken jwtToken && jwtToken.ValidTo > DateTime.UtcNow;
        }
        catch
        {
            return false;
        }
    }

    public ClaimsPrincipal? GetPrincipalFromToken(string token)
    {
        try
        {
            var key = Encoding.UTF8.GetBytes(_jwtSecret);

            var principal = _tokenHandler.ValidateToken(token, new TokenValidationParameters
            {
                ValidateIssuer = false,
                ValidateAudience = false,
                ValidateLifetime = true,
                ValidateIssuerSigningKey = true,
                IssuerSigningKey = new SymmetricSecurityKey(key),
                ClockSkew = TimeSpan.Zero
            }, out SecurityToken validatedToken);

            return principal;
        }
        catch
        {
            return null;
        }
    }
}
