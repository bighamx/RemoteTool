using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using ChuckieHelper.WebApi.Services;

namespace ChuckieHelper.WebApi.Controllers;

[ApiController]
[Route("api/[controller]")]
public class AuthController : ControllerBase
{
    private readonly AuthService _authService;

    public AuthController(AuthService authService)
    {
        _authService = authService;
    }

    [HttpPost("login")]
    public IActionResult Login([FromBody] LoginRequest request)
    {
        if (string.IsNullOrEmpty(request.Username) || string.IsNullOrEmpty(request.Password))
        {
            return BadRequest(new { message = "Username and password are required" });
        }

        if (_authService.ValidateCredentials(request.Username, request.Password))
        {
            var token = _authService.GenerateToken(request.Username);
            Response.Cookies.Append("access_token", token, new CookieOptions
            {
                HttpOnly = true,
                Secure = Request.IsHttps,
                SameSite = SameSiteMode.Strict,
                Path = "/",
                Expires = DateTimeOffset.UtcNow.AddHours(24)
            });
            return Ok(new { token, username = request.Username });
        }

        return Unauthorized(new { message = "Invalid username or password" });
    }

    [HttpGet("validate")]
    public IActionResult Validate([FromQuery] string token)
    {
        if (string.IsNullOrEmpty(token))
        {
            return BadRequest(new { valid = false, message = "Token is required" });
        }

        var isValid = _authService.ValidateToken(token);
        return Ok(new { valid = isValid });
    }

    [HttpGet("me")]
    [Authorize]
    public IActionResult GetCurrentUser()
    {
        var username = User.Identity?.Name ?? "Unknown";
        return Ok(new { username, authenticated = true });
    }
}

public class LoginRequest
{
    public string Username { get; set; } = "";
    public string Password { get; set; } = "";
}
