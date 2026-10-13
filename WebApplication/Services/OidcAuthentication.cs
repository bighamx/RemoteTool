using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.OpenIdConnect;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;

namespace RemoteTool.WebApi.Services;

public static class OidcAuthentication
{
    public const string ExternalCookie = "remotetool-oidc-external";
    public const string SchemePrefix = "remotetool-provider-";
    public const string CallbackPath = "/access/callback";
    public static string Scheme(string id) => SchemePrefix + id;
}

/// <summary>One standard OpenID Connect handler per configured provider, all sharing /access/callback.</summary>
public sealed class OidcOptionsSetup(OidcProviderStore store, IHttpClientFactory clients) : IConfigureNamedOptions<OpenIdConnectOptions>
{
    public void Configure(OpenIdConnectOptions options) { }

    public void Configure(string? name, OpenIdConnectOptions options)
    {
        if (name is null || !name.StartsWith(OidcAuthentication.SchemePrefix, StringComparison.Ordinal)) return;
        var provider = store.Find(name[OidcAuthentication.SchemePrefix.Length..]);
        if (provider is not { Enabled: true }) throw new InvalidOperationException("OIDC provider is unavailable");
        options.Authority = provider.Issuer;
        if (provider.Discovery)
        {
            if (provider.DiscoveryUrl.Length > 0) options.MetadataAddress = provider.DiscoveryUrl;
        }
        else
        {
            var client = clients.CreateClient("oidc-metadata");
            client.Timeout = TimeSpan.FromSeconds(15);
            options.ConfigurationManager = new ConfigurationManager<OpenIdConnectConfiguration>(
                provider.CertificateUrl, new OidcManualConfiguration(provider),
                new HttpDocumentRetriever(client) { RequireHttps = true });
        }
        options.ClientId = provider.ClientId;
        options.ClientSecret = provider.ClientSecret;
        options.CallbackPath = OidcAuthentication.CallbackPath;
        options.SignInScheme = OidcAuthentication.ExternalCookie;
        options.ResponseType = OpenIdConnectResponseType.Code;
        options.UsePkce = true;
        options.RequireHttpsMetadata = true;
        options.SaveTokens = false;
        options.GetClaimsFromUserInfoEndpoint = provider.UseUserInfo;
        options.MapInboundClaims = false;
        options.SkipUnrecognizedRequests = true;
        options.Scope.Clear();
        foreach (var scope in provider.Scopes) options.Scope.Add(scope);
        options.ClaimActions.MapUniqueJsonKey("email", "email");
        options.Events = new OpenIdConnectEvents
        {
            OnTicketReceived = context =>
            {
                var current = store.Find(provider.Id);
                var email = context.Principal?.FindFirstValue("email") ?? context.Principal?.FindFirstValue(ClaimTypes.Email);
                if (current is not { Enabled: true } || string.IsNullOrWhiteSpace(email) ||
                    !current.AllowedEmails.Contains(email, StringComparer.OrdinalIgnoreCase))
                {
                    // RemoteAuthenticationHandler does not treat Fail() from
                    // TicketReceived as a stop signal before SignInAsync.
                    context.HandleResponse();
                    context.Response.Redirect("/Account/Login?oidcError=denied");
                    return Task.CompletedTask;
                }
                ((ClaimsIdentity)context.Principal!.Identity!).AddClaim(new Claim("remotetool:oidc-provider", provider.Id));
                return Task.CompletedTask;
            },
            OnRemoteFailure = context =>
            {
                context.HandleResponse();
                context.Response.Redirect("/Account/Login?oidcError=failed");
                return Task.CompletedTask;
            }
        };
    }
}

/// <summary>Register and remove named handlers without recycling IIS or interrupting running jobs.</summary>
public sealed class OidcSchemeRegistry(OidcProviderStore store,
    IAuthenticationSchemeProvider schemes, IOptionsMonitorCache<OpenIdConnectOptions> cache)
{
    private readonly object _gate = new();

    public void Sync()
    {
        lock (_gate)
        {
            // AddOpenIdConnect registers its required services through this bootstrap
            // scheme. It must not remain a request handler: every request would try to
            // initialize its deliberately empty options before reaching real providers.
            schemes.RemoveScheme("oidc-bootstrap");
            cache.TryRemove("oidc-bootstrap");
            var enabled = store.List().Where(p => p.Enabled).ToDictionary(p => OidcAuthentication.Scheme(p.Id));
            var existing = schemes.GetAllSchemesAsync().GetAwaiter().GetResult()
                .Where(s => s.Name.StartsWith(OidcAuthentication.SchemePrefix, StringComparison.Ordinal)).ToArray();
            foreach (var item in existing)
            {
                schemes.RemoveScheme(item.Name);
                cache.TryRemove(item.Name);
            }
            foreach (var (name, provider) in enabled)
                schemes.AddScheme(new AuthenticationScheme(name, provider.Name, typeof(OpenIdConnectHandler)));
        }
    }
}
