using Hangfire.Dashboard;
using ChuckieHelper.WebApi.Services;

namespace ChuckieHelper.WebApi.Filters;

public class HangfireAuthorizationFilter : IDashboardAuthorizationFilter
{
    public bool Authorize(DashboardContext context)
    {
        var httpContext = context.GetHttpContext();

        // 1. Check if user is authenticated via standard mechanism (e.g. Header)
        // Note: HttpContext.User might not be populated if the middleware pipeline order is tricky or for certain paths
        // But let's check it first
        if (httpContext.User.Identity?.IsAuthenticated == true)
        {
            return true;
        }

        // 2. Check for Cookie manually (since we are handling the cookie logic ourselves for browser apps)
        if (httpContext.Request.Cookies.TryGetValue("access_token", out var token))
        {
            var authService = httpContext.RequestServices.GetService<AuthService>();
            if (authService != null && authService.ValidateToken(token))
            {
                return true;
            }
        }

        return false;
    }
}
