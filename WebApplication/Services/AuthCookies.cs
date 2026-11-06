namespace RemoteTool.WebApi.Services;

/// <summary>
/// 登录态 Cookie 的名字按协议区分。浏览器有个规则：非安全来源写不进同名同域同路径的
/// Secure Cookie（"leave secure cookies alone"）。同一个主机既用 https 又用 http 访问时，
/// 先经 https 登录留下的 access_token 是 Secure 的，随后 http 登录想写同名 Cookie 会被浏览器丢弃，
/// 于是登录成功却立刻被踢回登录页（https 仍正常）。分开命名让两种协议各自持有自己的登录态。
/// </summary>
public static class AuthCookies
{
    public const string Secure = "access_token";
    public const string Insecure = "access_token_http";

    public static string Name(bool https) => https ? Secure : Insecure;

    /// <summary>按当前协议优先读取，其次读另一份（同主机两种协议并存时都能用）。</summary>
    public static string Read(HttpRequest request)
    {
        var preferred = Name(request.IsHttps);
        if (request.Cookies.TryGetValue(preferred, out var token) && !string.IsNullOrEmpty(token)) return token;
        var fallback = Name(!request.IsHttps);
        return request.Cookies.TryGetValue(fallback, out var other) ? other : null;
    }

    public static void Write(HttpResponse response, bool https, string token, DateTimeOffset expires)
    {
        response.Cookies.Append(Name(https), token, new CookieOptions
        {
            HttpOnly = true,
            Secure = https,
            SameSite = SameSiteMode.Strict,
            Path = "/",
            Expires = expires
        });
    }

    public static void Delete(HttpResponse response, bool https)
    {
        response.Cookies.Delete(Secure);
        response.Cookies.Delete(Insecure);
        response.Cookies.Delete(Name(https), new CookieOptions { Path = "/", Secure = https, SameSite = SameSiteMode.Strict });
    }
}
