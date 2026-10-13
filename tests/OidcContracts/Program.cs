using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authentication.OpenIdConnect;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Logging;
using RemoteTool.WebApi.Extensions;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Controllers;

var root = Path.Combine(Path.GetTempPath(), "remotetool-oidc-contracts-" + Guid.NewGuid().ToString("N"));
Environment.SetEnvironmentVariable("REMOTETOOL_DATA_DIR", root);
try
{
    var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
    {
        ["Auth:JwtSecret"] = "oidc-contract-test-secret-at-least-32-bytes",
        ["Auth:Username"] = "admin",
        ["Auth:Password"] = "contract-test-password"
    }).Build();
    var services = new ServiceCollection();
    services.AddLogging();
    services.AddHttpClient();
    services.AddSingleton<IConfiguration>(config);
    services.AddSingleton<OidcProviderStore>();
    services.AddSingleton<OidcSchemeRegistry>();
    services.AddSingleton<IConfigureOptions<OpenIdConnectOptions>, OidcOptionsSetup>();
    services.AddAuthentication().AddCookie(OidcAuthentication.ExternalCookie)
        .AddOpenIdConnect("oidc-bootstrap", options => options.SkipUnrecognizedRequests = true);
    using var container = services.BuildServiceProvider();
    var store = container.GetRequiredService<OidcProviderStore>();
    var first = store.Save(new OidcProviderInput(null, "First provider", "https://first-issuer.example/", "client-one", "private-one", ["Alice@Example.com"], true));
    var second = store.Save(new OidcProviderInput(null, "Another provider", "https://id.example.net/", "client-two", "private-two", ["bob@example.net"], true,
        ["openid", "email", "custom"], true));
    var customDiscovery = store.Save(new OidcProviderInput(null, "Custom discovery", "https://special.example/", "client-discovery", "private-discovery", ["test@example.com"], true,
        DiscoveryUrl: "https://special.example/metadata/openid"));
    var manual = store.Save(new OidcProviderInput(null, "Manual metadata", "https://manual.example/", "client-manual", "private-manual", ["test@example.com"], true,
        Discovery: false, AuthorizationUrl: "https://manual.example/login", TokenUrl: "https://manual.example/exchange",
        CertificateUrl: "https://manual.example/keys", UserInfoUrl: "https://manual.example/user"));
    var file = File.ReadAllText(Path.Combine(root, "oidc", "providers.json"));
    Check(!file.Contains("private-one") && !file.Contains("private-two"), "client secrets must be encrypted on disk");
    if (OperatingSystem.IsWindows())
    {
        var directoryAcl = new DirectoryInfo(Path.Combine(root, "oidc")).GetAccessControl();
        var usersSid = new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null);
        Check(directoryAcl.AreAccessRulesProtected &&
            !directoryAcl.GetAccessRules(true, true, typeof(SecurityIdentifier)).Cast<FileSystemAccessRule>()
                .Any(rule => rule.IdentityReference == usersSid && rule.AccessControlType == AccessControlType.Allow),
            "local Users must not be allowed to rewrite the email allowlist");
    }
    Check(first.AllowedEmails.SequenceEqual(["alice@example.com"]), "email allowlist should normalize casing");
    Check(store.Find(first.Id)!.ClientSecret == "private-one", "client secret must survive decryption");
    Check(new OidcProviderStore(config).Find(second.Id)!.ClientSecret == "private-two", "client secret must survive process restart");
    var access = new AccessSettingsStore(config);
    var auth = new AuthService(config, store, access);
    var oidcToken = auth.GenerateToken("alice@example.com", "oidc", first.Id);
    Check(auth.ValidateToken(oidcToken), "allowed OIDC identity should authenticate");
    Check(auth.ValidateToken(auth.GenerateToken("local-admin")), "password login should remain available");

    var registry = container.GetRequiredService<OidcSchemeRegistry>();
    registry.Sync();
    var schemes = container.GetRequiredService<IAuthenticationSchemeProvider>();
    var options = container.GetRequiredService<IOptionsMonitor<OpenIdConnectOptions>>();
    foreach (var p in new[] { first, second })
    {
        Check(await schemes.GetSchemeAsync(OidcAuthentication.Scheme(p.Id)) is not null, "enabled provider should register a scheme");
        var value = options.Get(OidcAuthentication.Scheme(p.Id));
        Check(value.CallbackPath == "/access/callback", "all providers share the requested callback path");
        Check(value.UsePkce && value.ResponseType == "code" && value.SkipUnrecognizedRequests,
            "authorization code, PKCE and shared callback routing are required");
        Check(value.ClientId == p.ClientId && value.Authority == p.Issuer && value.SignInScheme == OidcAuthentication.ExternalCookie,
            "named options must not leak settings across providers");
        Check(value.GetClaimsFromUserInfoEndpoint == p.UseUserInfo && value.Scope.Order().SequenceEqual(p.Scopes.Order()),
            "provider-specific scopes and UserInfo setting must stay isolated");
        Check(value.RequireHttpsMetadata && !value.SaveTokens, "metadata must use HTTPS and provider tokens must not persist in local cookies");
    }
    Check(options.Get(OidcAuthentication.Scheme(customDiscovery.Id)).MetadataAddress == "https://special.example/metadata/openid",
        "a nonstandard Discovery URL should override the issuer's default well-known path");
    Check(options.Get(OidcAuthentication.Scheme(manual.Id)).ConfigurationManager is ConfigurationManager<OpenIdConnectConfiguration>,
        "manual endpoints should use a refreshing configuration manager rather than Discovery");
    using (var manualRsa = RSA.Create(2048))
    {
        var key = manualRsa.ExportParameters(false);
        var jwks = JsonSerializer.Serialize(new { keys = new[] { new { kty = "RSA", kid = "manual-key", use = "sig",
            n = Base64UrlEncoder.Encode(key.Modulus!), e = Base64UrlEncoder.Encode(key.Exponent!) } } });
        var metadata = await new OidcManualConfiguration(manual).GetConfigurationAsync(manual.CertificateUrl,
            new StaticDocumentRetriever(jwks), default);
        Check(metadata.Issuer == manual.Issuer && metadata.AuthorizationEndpoint == manual.AuthorizationUrl &&
            metadata.TokenEndpoint == manual.TokenUrl && metadata.UserInfoEndpoint == manual.UserInfoUrl &&
            metadata.SigningKeys.Count == 1,
            "manual mode must supply configured endpoints and usable JWKS signing keys");
        var request = new CertificateRequest("CN=oidc-contract", manualRsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddMinutes(5));
        var pem = await new OidcManualConfiguration(manual).GetConfigurationAsync(manual.CertificateUrl,
            new StaticDocumentRetriever(certificate.ExportCertificatePem()), default);
        Check(pem.SigningKeys.Single() is X509SecurityKey, "manual Certificate URL should accept PEM certificates");
        try
        {
            await new OidcManualConfiguration(manual).GetConfigurationAsync(manual.CertificateUrl,
                new StaticDocumentRetriever("{\"keys\":[]}"), default);
            throw new Exception("empty JWKS was accepted");
        }
        catch (InvalidDataException) { }
    }
    var firstState = options.Get(OidcAuthentication.Scheme(first.Id)).StateDataFormat.Protect(new AuthenticationProperties());
    Check(options.Get(OidcAuthentication.Scheme(first.Id)).StateDataFormat.Unprotect(firstState) is not null,
        "the initiating provider must recognize its callback state");
    Check(options.Get(OidcAuthentication.Scheme(second.Id)).StateDataFormat.Unprotect(firstState) is null,
        "another provider must skip a callback it did not initiate");
    store.Save(new OidcProviderInput(first.Id, first.Name, first.Issuer, first.ClientId, null, first.AllowedEmails, false));
    registry.Sync();
    Check(await schemes.GetSchemeAsync(OidcAuthentication.Scheme(first.Id)) is null, "disabled provider should be removed immediately");
    Check(!auth.ValidateToken(oidcToken), "disabling a provider must revoke its issued local tokens");
    Check(await schemes.GetSchemeAsync(OidcAuthentication.Scheme(second.Id)) is not null, "other provider must remain active");
    store.Delete(second.Id);
    registry.Sync();
    Check(await schemes.GetSchemeAsync(OidcAuthentication.Scheme(second.Id)) is null, "deleted provider should be removed immediately");
    ExpectInvalid(() => store.Save(new OidcProviderInput(null, "HTTP", "http://127.0.0.1/", "client", "secret", ["test@example.com"], true)));
    ExpectInvalid(() => store.Save(new OidcProviderInput(null, "Any", "https://issuer.example/", "client", "secret", [], true)));
    ExpectInvalid(() => store.Save(new OidcProviderInput(null, "Missing keys", "https://issuer.example/", "client", "secret", ["test@example.com"], true,
        Discovery: false, AuthorizationUrl: "https://issuer.example/auth", TokenUrl: "https://issuer.example/token")));
    ExpectInvalid(() => store.Save(new OidcProviderInput(null, "HTTP key", "https://issuer.example/", "client", "secret", ["test@example.com"], true,
        Discovery: false, AuthorizationUrl: "https://issuer.example/auth", TokenUrl: "https://issuer.example/token", CertificateUrl: "http://issuer.example/keys")));
    Check(!access.Read().Enabled, "Cloudflare Access must be opt-in");
    var savedAccess = access.Save(new AccessSettings(true, "My-Team", "audience-tag", ["ALICE@example.com", "bob@example.com"]));
    Check(savedAccess.AllowedEmails.SequenceEqual(["alice@example.com", "bob@example.com"]), "Access email allowlist must normalize case");
    Check(new AccessSettingsStore(config).Read().Enabled, "Access settings must survive restart");
    var accessToken = auth.GenerateToken("admin", "cloudflare", "alice@example.com");
    Check(auth.ValidateToken(accessToken), "allowed Access identity should authenticate");
    access.Save(new AccessSettings(true, "my-team", "audience-tag", ["bob@example.com"]));
    Check(!auth.ValidateToken(accessToken), "removing an Access email must revoke its issued local tokens");
    using (var rsa = RSA.Create(2048))
    {
        var publicKey = rsa.ExportParameters(false);
        string b64(byte[] bytes) => Base64UrlEncoder.Encode(bytes);
        var jwks = JsonSerializer.Serialize(new { keys = new[] { new { kty = "RSA", kid = "test-key", alg = "RS256", use = "sig", n = b64(publicKey.Modulus!), e = b64(publicKey.Exponent!) } } });
        var identity = new CloudflareAccessIdentity(access, new StaticHttpFactory(jwks));
        string assertion(string email, string audience) => new JwtSecurityTokenHandler().WriteToken(new JwtSecurityToken(
            issuer: "https://my-team.cloudflareaccess.com", audience: audience,
            claims: [new Claim("email", email)], expires: DateTime.UtcNow.AddMinutes(5),
            signingCredentials: new SigningCredentials(new RsaSecurityKey(rsa) { KeyId = "test-key" }, SecurityAlgorithms.RsaSha256)));
        Check(await identity.ValidatedEmailAsync(assertion("bob@example.com", "audience-tag"), default) == "bob@example.com",
            "Access must accept a signed token for an allowed email");
        Check(await identity.ValidatedEmailAsync(assertion("alice@example.com", "audience-tag"), default) is null,
            "Access must reject an email removed from its allowlist");
        Check(await identity.ValidatedEmailAsync(assertion("bob@example.com", "other-audience"), default) is null,
            "Access must reject a token for another application audience");
    }
    ExpectInvalid(() => access.Save(new AccessSettings(true, "https://invalid.cloudflareaccess.com", "aud", ["alice@example.com"])));
    var mock = store.Save(new OidcProviderInput(null, "Mock issuer", "https://mock-issuer.example/", "mock-client", "mock-secret", ["test@example.com"], true));
    var mockTwo = store.Save(new OidcProviderInput(null, "Second mock", "https://second-issuer.example/", "second-client", "second-secret", ["test@example.com"], true));
    using var signingRsa = RSA.Create(2048);
    var signingKey = new RsaSecurityKey(signingRsa) { KeyId = "mock-signing-key" };
    var publicSigningKey = new RsaSecurityKey(signingRsa.ExportParameters(false)) { KeyId = "mock-signing-key" };
    string? callbackNonce = null;
    var tokenEmail = "test@example.com";
    var tokenRequestBody = "";
    var manualMetadataRequests = new List<string>();
    string issueToken() => new JwtSecurityTokenHandler().WriteToken(new JwtSecurityToken(
        issuer: mock.Issuer, audience: mock.ClientId,
        claims: [new Claim("sub", "mock-user"), new Claim("nonce", callbackNonce!), new Claim("email", tokenEmail),
            new Claim(JwtRegisteredClaimNames.Iat, DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(), ClaimValueTypes.Integer64)],
        notBefore: DateTime.UtcNow.AddMinutes(-1), expires: DateTime.UtcNow.AddMinutes(5),
        signingCredentials: new SigningCredentials(signingKey, SecurityAlgorithms.RsaSha256)));
    var web = WebApplication.CreateBuilder(new WebApplicationOptions
    {
        EnvironmentName = "Development", ContentRootPath = Path.GetFullPath("WebApplication")
    });
    web.Logging.ClearProviders();
    web.WebHost.UseUrls("http://127.0.0.1:0");
    web.Services.AddSingleton(store);
    web.Services.AddSingleton(access);
    web.Services.AddSingleton(auth);
    web.Services.AddHttpClient();
    web.Services.AddHttpClient("oidc-metadata").ConfigurePrimaryHttpMessageHandler(() => new TokenHandler(request =>
    {
        manualMetadataRequests.Add(request.RequestUri!.ToString());
        var publicKey = signingRsa.ExportParameters(false);
        var jwks = JsonSerializer.Serialize(new { keys = new[] { new { kty = "RSA", kid = "manual-test", use = "sig",
            n = Base64UrlEncoder.Encode(publicKey.Modulus!), e = Base64UrlEncoder.Encode(publicKey.Exponent!) } } });
        return Task.FromResult(new HttpResponseMessage(System.Net.HttpStatusCode.OK)
        { Content = new StringContent(jwks, Encoding.UTF8, "application/json") });
    }));
    web.Services.AddSingleton<OidcSchemeRegistry>();
    web.Services.AddSingleton<IConfigureOptions<OpenIdConnectOptions>, OidcOptionsSetup>();
    web.Services.AddJwtAuthentication(config);
    web.Services.AddAuthentication().AddCookie(OidcAuthentication.ExternalCookie)
        .AddOpenIdConnect("oidc-bootstrap", options => options.SkipUnrecognizedRequests = true);
    web.Services.AddAuthorization();
    web.Services.AddControllersWithViews().AddRazorRuntimeCompilation().AddApplicationPart(typeof(OidcController).Assembly);
    web.Services.PostConfigure<OpenIdConnectOptions>(OidcAuthentication.Scheme(mock.Id), options =>
    {
        var configuration = new OpenIdConnectConfiguration
        {
            Issuer = mock.Issuer,
            AuthorizationEndpoint = "https://mock-issuer.example/authorize",
            TokenEndpoint = "https://mock-issuer.example/token"
        };
        configuration.SigningKeys.Add(publicSigningKey);
        options.ConfigurationManager = new StaticConfigurationManager<OpenIdConnectConfiguration>(configuration);
        options.Backchannel = new HttpClient(new TokenHandler(async request =>
        {
            tokenRequestBody = await request.Content!.ReadAsStringAsync();
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK)
            {
                Content = new StringContent(JsonSerializer.Serialize(new { access_token = "mock-access-token", token_type = "Bearer", expires_in = 3600, id_token = issueToken() }),
                    Encoding.UTF8, "application/json")
            };
        }));
    });
    web.Services.PostConfigure<OpenIdConnectOptions>(OidcAuthentication.Scheme(mockTwo.Id), options =>
        options.ConfigurationManager = new StaticConfigurationManager<OpenIdConnectConfiguration>(new OpenIdConnectConfiguration
        {
            Issuer = mockTwo.Issuer,
            AuthorizationEndpoint = "https://second-issuer.example/authorize",
            TokenEndpoint = "https://second-issuer.example/token"
        }));
    await using (var server = web.Build())
    {
        server.Services.GetRequiredService<OidcSchemeRegistry>().Sync();
        server.UseRouting();
        server.UseAuthentication();
        server.UseAuthorization();
        server.MapControllers();
        server.MapControllerRoute("default", "{controller=Home}/{action=Index}/{id?}");
        await server.StartAsync();
        using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false });
        var unauthorizedSettings = await client.GetAsync(server.Urls.Single() + "/api/auth/oidc/settings");
        Check(unauthorizedSettings.StatusCode == System.Net.HttpStatusCode.Unauthorized,
            "OIDC configuration must not be visible anonymously");
        var unauthorizedAccess = await client.GetAsync(server.Urls.Single() + "/api/auth/oidc/access-settings");
        Check(unauthorizedAccess.StatusCode == System.Net.HttpStatusCode.Unauthorized,
            "Cloudflare Access configuration must not be visible anonymously");
        using var adminClient = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false });
        adminClient.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", auth.GenerateToken("admin"));
        var adminSettings = await adminClient.GetStringAsync(server.Urls.Single() + "/api/auth/oidc/settings");
        Check(adminSettings.Contains(mock.Name) && !adminSettings.Contains("mock-secret"),
            "administrator may list providers without receiving client secrets");
        var settingsPage = await adminClient.GetAsync(server.Urls.Single() + "/settings/auth");
        var settingsHtml = await settingsPage.Content.ReadAsStringAsync();
        Check(settingsPage.StatusCode == System.Net.HttpStatusCode.OK &&
            settingsHtml.Contains("Cloudflare Access") && settingsHtml.Contains("v-on:submit.prevent=\"saveProvider\"") &&
            settingsHtml.Contains("form && form.id===item.id") && settingsHtml.Contains("Certificate / JWKS URL") &&
            settingsHtml.Contains("role=\"tablist\"") && settingsHtml.Contains("identity-panel-oidc") &&
            settingsHtml.Contains("identity-panel-access"),
            "administrator should be able to open the combined identity settings page");
        var loginPage = await client.GetAsync(server.Urls.Single() + "/Account/Login");
        Check(loginPage.StatusCode == System.Net.HttpStatusCode.OK &&
            (await loginPage.Content.ReadAsStringAsync()).Contains("oidc-logins"),
            "the login page should render the OIDC provider entry point");
        var publicProviders = await client.GetStringAsync(server.Urls.Single() + "/api/auth/oidc/providers");
        Check(publicProviders.Contains(mock.Name) && publicProviders.Contains(mockTwo.Name) &&
            !publicProviders.Contains("mock-secret") && !publicProviders.Contains("second-secret"),
            "public login list must show only provider names and identifiers, never client secrets");
        var passwordLogin = await client.PostAsync(server.Urls.Single() + "/api/auth/login",
            new StringContent("{\"username\":\"admin\",\"password\":\"contract-test-password\"}", Encoding.UTF8, "application/json"));
        Check(passwordLogin.StatusCode == System.Net.HttpStatusCode.OK &&
            passwordLogin.Headers.TryGetValues("Set-Cookie", out var passwordCookies) &&
            passwordCookies.Any(cookie => cookie.StartsWith("access_token=", StringComparison.Ordinal)),
            "existing password login must remain usable");
        var response = await client.GetAsync(server.Urls.Single() + "/api/auth/oidc/start/" + mock.Id);
        Check(response.StatusCode == System.Net.HttpStatusCode.Redirect, "OIDC login should issue a redirect");
        var redirect = response.Headers.Location!;
        var parameters = System.Web.HttpUtility.ParseQueryString(redirect.Query);
        Check(redirect.Host == "mock-issuer.example" && parameters["client_id"] == "mock-client",
            "redirect must target the selected provider");
        Check(parameters["redirect_uri"] == server.Urls.Single() + "/access/callback",
            "OIDC redirect_uri must use the requested callback path");
        Check(parameters["response_type"] == "code" && parameters["code_challenge_method"] == "S256" &&
            !string.IsNullOrEmpty(parameters["code_challenge"]) && !string.IsNullOrEmpty(parameters["state"]),
            "authorization request must include state and PKCE S256");
        var manualStart = await client.GetAsync(server.Urls.Single() + "/api/auth/oidc/start/" + manual.Id);
        Check(manualStart.StatusCode == System.Net.HttpStatusCode.Redirect &&
            manualStart.Headers.Location?.Host == "manual.example" &&
            manualStart.Headers.Location?.AbsolutePath == "/login" &&
            manualMetadataRequests.SequenceEqual([manual.CertificateUrl]),
            "manual provider must load only its configured key URL and use its explicit Auth URL");
        var secondResponse = await client.GetAsync(server.Urls.Single() + "/api/auth/oidc/start/" + mockTwo.Id);
        var secondRedirect = secondResponse.Headers.Location!;
        var secondState = System.Web.HttpUtility.ParseQueryString(secondRedirect.Query)["state"];
        var cookies = string.Join("; ", secondResponse.Headers.GetValues("Set-Cookie").Select(value => value.Split(';')[0]));
        using var callback = new HttpRequestMessage(HttpMethod.Get,
            server.Urls.Single() + "/access/callback?error=access_denied&state=" + Uri.EscapeDataString(secondState!));
        callback.Headers.Add("Cookie", cookies);
        var callbackResponse = await client.SendAsync(callback);
        Check(callbackResponse.StatusCode == System.Net.HttpStatusCode.Redirect &&
            callbackResponse.Headers.Location?.ToString() == "/Account/Login?oidcError=failed",
            "shared callback must skip the first provider and handle the second provider state");
        async Task<HttpResponseMessage> CompleteMockLogin(string email)
        {
            tokenEmail = email;
            var start = await client.GetAsync(server.Urls.Single() + "/api/auth/oidc/start/" + mock.Id);
            var query = System.Web.HttpUtility.ParseQueryString(start.Headers.Location!.Query);
            callbackNonce = query["nonce"];
            var challengeCookies = string.Join("; ", start.Headers.GetValues("Set-Cookie").Select(value => value.Split(';')[0]));
            using var request = new HttpRequestMessage(HttpMethod.Get,
                server.Urls.Single() + "/access/callback?code=accepted&state=" + Uri.EscapeDataString(query["state"]!));
            request.Headers.Add("Cookie", challengeCookies);
            return await client.SendAsync(request);
        }
        var acceptedCallback = await CompleteMockLogin("test@example.com");
        Check(acceptedCallback.StatusCode == System.Net.HttpStatusCode.Redirect &&
            acceptedCallback.Headers.Location?.ToString().StartsWith("/api/auth/oidc/complete", StringComparison.Ordinal) == true,
            "signed ID token from allowed email should finish OIDC callback");
        Check(tokenRequestBody.Contains("code_verifier="), "token redemption must send the PKCE verifier");
        var externalCookie = string.Join("; ", acceptedCallback.Headers.GetValues("Set-Cookie")
            .Where(value => value.StartsWith(".AspNetCore." + OidcAuthentication.ExternalCookie, StringComparison.Ordinal))
            .Select(value => value.Split(';')[0]));
        using var completeRequest = new HttpRequestMessage(HttpMethod.Get,
            new Uri(new Uri(server.Urls.Single()), acceptedCallback.Headers.Location));
        completeRequest.Headers.Add("Cookie", externalCookie);
        var completed = await client.SendAsync(completeRequest);
        Check(completed.StatusCode == System.Net.HttpStatusCode.OK, "allowed identity should finish local login");
        var localCookie = completed.Headers.GetValues("Set-Cookie").First(value => value.StartsWith("access_token=", StringComparison.Ordinal));
        var localToken = Uri.UnescapeDataString(localCookie.Split(';')[0]["access_token=".Length..]);
        Check(auth.ValidateToken(localToken) && auth.GetPrincipalFromToken(localToken)?.FindFirstValue(ClaimTypes.Name) == "test@example.com",
            "local session must be bound to the allowed OIDC email");
        using var oidcClient = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false });
        oidcClient.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", localToken);
        Check((await oidcClient.GetAsync(server.Urls.Single() + "/api/auth/oidc/settings")).StatusCode == System.Net.HttpStatusCode.OK,
            "allowed OIDC user should receive the existing administrator role");
        store.Save(new OidcProviderInput(mock.Id, mock.Name, mock.Issuer, mock.ClientId, null, ["someone-else@example.com"], true));
        server.Services.GetRequiredService<OidcSchemeRegistry>().Sync();
        Check((await oidcClient.GetAsync(server.Urls.Single() + "/api/auth/oidc/settings")).StatusCode == System.Net.HttpStatusCode.Unauthorized,
            "removing an OIDC email must revoke its already-issued API token");
        var deniedCallback = await CompleteMockLogin("other@example.com");
        var deniedHasExternalCookie = deniedCallback.Headers.TryGetValues("Set-Cookie", out var deniedCookies) &&
            deniedCookies.Any(cookie => cookie.StartsWith(".AspNetCore." + OidcAuthentication.ExternalCookie, StringComparison.Ordinal));
        Check(deniedCallback.StatusCode == System.Net.HttpStatusCode.Redirect &&
            deniedCallback.Headers.Location?.ToString() == "/Account/Login?oidcError=denied" &&
            !deniedHasExternalCookie,
            "a signed ID token from a non-allowlisted email must not log in");
        var newInput = new OidcProviderInput(null, "Added through UI API", "https://added.example/", "added-client", "added-secret", ["added@example.com"], true);
        var saved = await adminClient.PostAsync(server.Urls.Single() + "/api/auth/oidc/settings",
            new StringContent(JsonSerializer.Serialize(newInput), Encoding.UTF8, "application/json"));
        Check(saved.StatusCode == System.Net.HttpStatusCode.OK &&
            (await client.GetStringAsync(server.Urls.Single() + "/api/auth/oidc/providers")).Contains("Added through UI API"),
            "administrator-created provider must become a login choice without restart");
        var addedId = JsonDocument.Parse(await saved.Content.ReadAsStringAsync()).RootElement.GetProperty("id").GetString();
        var removed = await adminClient.PostAsync(server.Urls.Single() + "/api/auth/oidc/settings/" + addedId + "/delete",
            new StringContent("{}", Encoding.UTF8, "application/json"));
        Check(removed.StatusCode == System.Net.HttpStatusCode.OK &&
            !(await client.GetStringAsync(server.Urls.Single() + "/api/auth/oidc/providers")).Contains("Added through UI API"),
            "administrator should delete a provider with POST, without relying on DELETE");
        using var accessUpdate = new HttpRequestMessage(HttpMethod.Put, server.Urls.Single() + "/api/auth/oidc/access-settings")
        {
            Content = new StringContent(JsonSerializer.Serialize(new AccessSettings(true, "new-team", "new-audience", ["new@example.com"])),
                Encoding.UTF8, "application/json")
        };
        var updatedAccess = await adminClient.SendAsync(accessUpdate);
        Check(updatedAccess.StatusCode == System.Net.HttpStatusCode.OK &&
            access.Read().Team == "new-team" && access.Read().AllowedEmails.SequenceEqual(["new@example.com"]),
            "administrator-updated Access configuration must take effect without restart");
        await server.StopAsync();
    }
    Console.WriteLine("OIDC contracts: provider isolation, callback, PKCE, encryption, allowlist and dynamic removal passed");
}
finally
{
    Environment.SetEnvironmentVariable("REMOTETOOL_DATA_DIR", null);
    if (Directory.Exists(root)) Directory.Delete(root, true);
}

static void Check(bool value, string message) { if (!value) throw new Exception(message); }
static void ExpectInvalid(Action action)
{
    try { action(); throw new Exception("Invalid provider was accepted"); }
    catch (ArgumentException) { }
}

sealed class StaticHttpFactory(string jwks) : IHttpClientFactory
{
    public HttpClient CreateClient(string name) => new(new JwksHandler(jwks));
    private sealed class JwksHandler(string body) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(new HttpResponseMessage(System.Net.HttpStatusCode.OK)
            { Content = new StringContent(body, Encoding.UTF8, "application/json") });
    }
}

sealed class TokenHandler(Func<HttpRequestMessage, Task<HttpResponseMessage>> respond) : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => respond(request);
}

sealed class StaticDocumentRetriever(string body) : IDocumentRetriever
{
    public Task<string> GetDocumentAsync(string address, CancellationToken cancel) => Task.FromResult(body);
}
