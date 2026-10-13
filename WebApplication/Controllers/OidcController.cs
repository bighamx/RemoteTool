using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services;

namespace RemoteTool.WebApi.Controllers;

[ApiController, Route("api/auth/oidc")]
public sealed class OidcController(OidcProviderStore store, OidcSchemeRegistry registry,
    AccessSettingsStore accessSettings, AuthService auth) : Controller
{
    [HttpGet("providers"), AllowAnonymous]
    public IActionResult PublicProviders() => Ok(store.List().Where(p => p.Enabled)
        .Select(p => new { p.Id, p.Name }));

    [HttpGet("start/{id}"), AllowAnonymous]
    public IActionResult Start(string id, [FromQuery] string? returnUrl)
    {
        var provider = store.Find(id);
        if (provider is not { Enabled: true }) return NotFound();
        var destination = Url.IsLocalUrl(returnUrl) ? returnUrl! : "/";
        var callback = Url.Action(nameof(Complete), "Oidc", new { returnUrl = destination })!;
        return Challenge(new AuthenticationProperties { RedirectUri = callback }, OidcAuthentication.Scheme(id));
    }

    [HttpGet("complete"), AllowAnonymous]
    public async Task<IActionResult> Complete([FromQuery] string? returnUrl)
    {
        Response.Headers.CacheControl = "no-store";
        var result = await HttpContext.AuthenticateAsync(OidcAuthentication.ExternalCookie);
        var email = result.Principal?.FindFirstValue("email") ?? result.Principal?.FindFirstValue(ClaimTypes.Email);
        var id = result.Principal?.FindFirstValue("remotetool:oidc-provider");
        var provider = id is null ? null : store.Find(id);
        await HttpContext.SignOutAsync(OidcAuthentication.ExternalCookie);
        if (!result.Succeeded || provider is not { Enabled: true } || string.IsNullOrWhiteSpace(email) ||
            !provider.AllowedEmails.Contains(email, StringComparer.OrdinalIgnoreCase))
            return Redirect("/Account/Login?oidcError=failed");

        var token = auth.GenerateToken(email, "oidc", provider.Id);
        Response.Cookies.Append("access_token", token, new CookieOptions
        {
            HttpOnly = true, Secure = Request.IsHttps, SameSite = SameSiteMode.Strict,
            Path = "/", Expires = DateTimeOffset.UtcNow.AddHours(24)
        });
        ViewData["Email"] = email;
        ViewData["ReturnUrl"] = Url.IsLocalUrl(returnUrl) ? returnUrl : "/";
        return new ViewResult { ViewName = "~/Views/Account/OidcComplete.cshtml", ViewData = ViewData };
    }

    [HttpGet("settings"), Authorize(Roles = "Admin")]
    public IActionResult List() => Ok(store.List().Select(p => new
    {
        p.Id, p.Name, p.Issuer, p.ClientId, p.AllowedEmails, p.Enabled, p.Scopes, p.UseUserInfo,
        p.Discovery, p.DiscoveryUrl, p.AuthorizationUrl, p.TokenUrl, p.CertificateUrl, p.UserInfoUrl,
        HasSecret = !string.IsNullOrEmpty(p.ClientSecret)
    }));

    [HttpPost("settings"), Authorize(Roles = "Admin")]
    public IActionResult Save([FromBody] OidcProviderInput input)
    {
        try
        {
            var provider = store.Save(input);
            registry.Sync();
            return Ok(new { provider.Id, provider.Name });
        }
        catch (ArgumentException error) { return BadRequest(new { message = error.Message }); }
        catch (KeyNotFoundException) { return NotFound(new { message = "OIDC 提供方不存在" }); }
    }

    [HttpPost("settings/{id}/delete"), Authorize(Roles = "Admin")]
    public IActionResult Delete(string id)
    {
        try { store.Delete(id); registry.Sync(); return Ok(new { deleted = true }); }
        catch (KeyNotFoundException) { return NotFound(new { message = "OIDC 提供方不存在" }); }
    }

    [HttpGet("access-settings"), Authorize(Roles = "Admin")]
    public IActionResult GetAccessSettings() => Ok(accessSettings.Read());

    [HttpPut("access-settings"), Authorize(Roles = "Admin")]
    public IActionResult SaveAccessSettings([FromBody] AccessSettings input)
    {
        try { return Ok(accessSettings.Save(input)); }
        catch (ArgumentException error) { return BadRequest(new { message = error.Message }); }
    }
}
