using System.Security.Cryptography.X509Certificates;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;

namespace RemoteTool.WebApi.Services;

/// <summary>Build verified OIDC metadata from explicitly configured endpoints and rotating public keys.</summary>
public sealed class OidcManualConfiguration(OidcProvider provider) : IConfigurationRetriever<OpenIdConnectConfiguration>
{
    public async Task<OpenIdConnectConfiguration> GetConfigurationAsync(
        string address, IDocumentRetriever retriever, CancellationToken cancellationToken)
    {
        var document = await retriever.GetDocumentAsync(provider.CertificateUrl, cancellationToken);
        var configuration = new OpenIdConnectConfiguration
        {
            Issuer = provider.Issuer,
            AuthorizationEndpoint = provider.AuthorizationUrl,
            TokenEndpoint = provider.TokenUrl,
            UserInfoEndpoint = provider.UserInfoUrl,
            JwksUri = provider.CertificateUrl
        };
        if (document.TrimStart().StartsWith('{'))
        {
            var set = new JsonWebKeySet(document);
            configuration.JsonWebKeySet = set;
            foreach (var key in set.GetSigningKeys().Where(IsPublicSigningKey))
                configuration.SigningKeys.Add(key);
        }
        else if (document.Contains("-----BEGIN CERTIFICATE-----", StringComparison.Ordinal))
        {
            configuration.SigningKeys.Add(new X509SecurityKey(X509Certificate2.CreateFromPem(document)));
        }
        if (configuration.SigningKeys.Count == 0)
            throw new InvalidDataException("证书/JWKS 地址没有可用于验证 ID Token 的非对称公钥");
        return configuration;
    }

    private static bool IsPublicSigningKey(SecurityKey key) => key is RsaSecurityKey or ECDsaSecurityKey or X509SecurityKey ||
        key is JsonWebKey { Kty: "RSA" or "EC" or "OKP" };
}
