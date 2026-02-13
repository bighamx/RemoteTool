using ChuckieHelper.WebApi.Jobs;
using ChuckieHelper.WebApi.Services;
using ChuckieHelper.WebApi.Services.RemoteControl;
using Hangfire;
using Hangfire.Console;
using Hangfire.Storage.SQLite;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;
using System.Text;

namespace ChuckieHelper.WebApi.Extensions
{
    public static class ServiceCollectionExtensions
    {
        public static IServiceCollection AddRemoteControlServices(this IServiceCollection services)
        {
            services.AddSingleton<AuthService>();
            services.AddSingleton<SystemService>();
            services.AddSingleton<TerminalService>();
            
            if (System.Runtime.InteropServices.RuntimeInformation.IsOSPlatform(System.Runtime.InteropServices.OSPlatform.Windows))
            {
                services.AddSingleton<ISystemControlService, SystemService>();
                services.AddSingleton<IDockerService, DockerService>();
            }
            else
            {
                services.AddSingleton<ISystemControlService, LinuxSystemService>();
                services.AddSingleton<IDockerService, LinuxDockerService>();
            }
            services.AddSingleton<FileService>();

            return services;
        }

        public static IServiceCollection AddJwtAuthentication(this IServiceCollection services, IConfiguration configuration)
        {
            var jwtSecret = configuration["Auth:JwtSecret"] 
                ?? throw new InvalidOperationException("Auth:JwtSecret 配置项缺失，请在 appsettings.json 中配置");
            var key = Encoding.UTF8.GetBytes(jwtSecret);

            services.AddAuthentication(options =>
            {
                options.DefaultAuthenticateScheme = JwtBearerDefaults.AuthenticationScheme;
                options.DefaultChallengeScheme = JwtBearerDefaults.AuthenticationScheme;
            })
            .AddJwtBearer(options =>
            {
                options.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidateIssuer = true,
                    ValidIssuer = "RemoteControl",
                    ValidateAudience = true,
                    ValidAudience = "RemoteControl",
                    ValidateLifetime = true,
                    ValidateIssuerSigningKey = true,
                    IssuerSigningKey = new SymmetricSecurityKey(key)
                };

                options.Events = new JwtBearerEvents
                {
                    OnMessageReceived = context =>
                    {
                        var accessToken = context.Request.Query["access_token"];
                        if (string.IsNullOrEmpty(accessToken))
                        {
                            if (context.Request.Cookies.TryGetValue("access_token", out var cookieToken))
                            {
                                accessToken = cookieToken;
                            }
                        }
                        if (!string.IsNullOrEmpty(accessToken))
                        {
                            context.Token = accessToken;
                        }
                        return Task.CompletedTask;
                    },
                    OnChallenge = context =>
                    {
                        if (!context.Response.HasStarted && (context.Request.Path.StartsWithSegments("/api") == false || context.Request.Path.StartsWithSegments("/hangfire")))
                        {
                            context.HandleResponse();
                            context.Response.Redirect("/Account/Login?ReturnUrl=" + System.Net.WebUtility.UrlEncode(context.Request.Path + context.Request.QueryString));
                        }
                        return Task.CompletedTask;
                    }
                };
            });

            return services;
        }

        public static IServiceCollection AddHangfireServices(this IServiceCollection services, IConfiguration configuration)
        {
            services.AddHangfire(config => config
                .SetDataCompatibilityLevel(CompatibilityLevel.Version_180)
                .UseSimpleAssemblyNameTypeSerializer()
                .UseRecommendedSerializerSettings()
                .UseConsole()
                .UseSQLiteStorage("hangfire.db"));

            services.AddHangfireServer();
            services.AddExampleTask();
            services.AddQBittorrentTask();
            services.AddDdnsTask();

            return services;
        }
    }
}
