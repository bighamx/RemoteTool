using ChuckieHelper.WebApi.Jobs;
using ChuckieHelper.WebApi.Services;
using ChuckieHelper.WebApi.Services.RemoteControl;
using ChuckieHelper.WebApi.Extensions;
using Hangfire;
using Hangfire.Console; 
using Hangfire.Storage.SQLite;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.IdentityModel.Tokens;
using System.Text;


namespace ChuckieHelper.WebApi
{
    public class Program
    {
        public static async Task Main(string[] args)
        {
            // 桌面代理模式：当以 --desktop-agent 参数启动时，
            // 不启动 Web 服务器，而是运行命名管道服务器处理桌面操作。
            // 此模式由 IIS 进程在检测到 Session 0 时自动启动。
            if (args.Contains("--desktop-agent"))
            {
                Console.WriteLine("[DesktopAgent] 以桌面代理模式启动");
                Console.WriteLine($"[DesktopAgent] 进程 ID: {Environment.ProcessId}");
                Console.WriteLine($"[DesktopAgent] 用户: {Environment.UserName}");


                var cts = new CancellationTokenSource();
                Console.CancelKeyPress += (sender, e) =>
                {
                    e.Cancel = true;
                    cts.Cancel();
                };
                try
                {
                    await DesktopAgent.RunServerAsync(cts.Token);
                }
                catch (OperationCanceledException)
                {
                    Console.WriteLine("[DesktopAgent] 代理已停止");
                }
                finally
                {
                    cts.Dispose();
                }
                return;
            }

            var builder = WebApplication.CreateBuilder(args);

            // 上传文件大小限制（默认 500MB，可在 appsettings 中通过 FileUpload:MaxSizeMB 覆盖）
            var maxUploadMb = builder.Configuration.GetValue("FileUpload:MaxSizeMB", 500);
            var maxUploadBytes = (long)maxUploadMb * 1024 * 1024;

            builder.WebHost.ConfigureKestrel(options =>
            {
                options.Limits.MaxRequestBodySize = maxUploadBytes;
            });
            builder.Services.Configure<Microsoft.AspNetCore.Http.Features.FormOptions>(options =>
            {
                options.MultipartBodyLengthLimit = maxUploadBytes;
            });

            // Add services to the container.
            builder.Services.AddControllersWithViews().AddRazorRuntimeCompilation();

            // 注册编码提供程序以支持 GBK (需 NuGet 安装 System.Text.Encoding.CodePages)
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);


            // Remote Control Services
            builder.Services.AddRemoteControlServices();

            // Configure JWT Authentication
            builder.Services.AddJwtAuthentication(builder.Configuration);

            // Hangfire Services (Client, Server, Tasks)
            builder.Services.AddHangfireServices(builder.Configuration);

            // 配置转发头，使在 Cloudflare 等反向代理后能正确识别 HTTPS 与原始 Host
            builder.Services.Configure<ForwardedHeadersOptions>(options =>
            {
                options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto | ForwardedHeaders.XForwardedHost;
                options.KnownNetworks.Clear();
                options.KnownProxies.Clear();
            });

            var app = builder.Build();

            // 必须在管道最前使用，以便后续中间件看到正确的 Scheme/Host
            app.UseForwardedHeaders();

            // 若在 Session 0（IIS）且配置了管理员凭据，创建“登录时以最高权限运行桌面代理”的计划任务（密码可来自配置或环境变量 REMOTECONTROL_ELEVATEDAGENT_PASSWORD）
            if (InteractiveProcessLauncher.IsRunningInSession0)
            {
                var elevatedUser = app.Configuration["RemoteControl:ElevatedAgent:UserName"]?.Trim();
                var elevatedPass = app.Configuration["RemoteControl:ElevatedAgent:Password"]
                    ?? Environment.GetEnvironmentVariable("REMOTECONTROL_ELEVATEDAGENT_PASSWORD");
                var elevatedDomain = app.Configuration["RemoteControl:ElevatedAgent:Domain"]?.Trim();
                if (!string.IsNullOrEmpty(elevatedUser) && !string.IsNullOrEmpty(elevatedPass))
                {
                    InteractiveProcessLauncher.CreateElevatedAgentLogonTask(elevatedUser, elevatedPass, string.IsNullOrEmpty(elevatedDomain) ? null : elevatedDomain);
                }
            }

            // Configure the HTTP request pipeline.
            if (!app.Environment.IsDevelopment())
            {
                app.UseExceptionHandler("/Home/Error");
            }
            app.UseStaticFiles();

            app.UseRouting();

            app.UseWebSockets();
            app.UseAuthentication();
            app.UseAuthorization();


            // Custom Middleware to intercept Hangfire 401 and redirect to Login
            app.Use(async (context, next) =>
            {
                await next();

                if (context.Response.StatusCode == 401 && context.Request.Path.StartsWithSegments("/hangfire"))
                {
                    context.Response.Redirect("/Account/Login?ReturnUrl=" + System.Net.WebUtility.UrlEncode(context.Request.Path + context.Request.QueryString));
                }
            });

            // Hangfire Dashboard
            app.UseHangfireDashboard("/hangfire-inner", new DashboardOptions
            {
                DashboardTitle = "Hangfire 任务",
                AppPath = null, // Set to null to hide the "Back to Site" link
                Authorization = new[] { new ChuckieHelper.WebApi.Filters.HangfireAuthorizationFilter() }
            });

            // 按实例名称注册 Hangfire 定时任务：Office 仅 DDNS，Home 注册全部
            var instanceName = (app.Configuration["InstanceName"] ?? "Home").Trim();
            if (string.Equals(instanceName, "Office", StringComparison.OrdinalIgnoreCase))
            {
                app.UseHangfireDdnsTask();
            }
            else
            {
                app.UseHangfireExampleTask();
                app.UseHangfireQBittorrentTask();
                app.UseHangfireDdnsTask();
            }

            app.MapControllerRoute(
                name: "default",
                pattern: "{controller=Home}/{action=Index}/{id?}");

            // WebSocket endpoint for terminal
            app.Map("/ws/terminal", async context =>
            {
                if (!context.WebSockets.IsWebSocketRequest)
                {
                    context.Response.StatusCode = 400;
                    return;
                }

                // 从查询参数获取终端类型；Linux 仅支持 shell，Windows 支持 shell/cmd/powershell
                var typeParam = context.Request.Query["type"].ToString().ToLower();
                var terminalType = typeParam switch
                {
                    "cmd" => TerminalType.Cmd,
                    "powershell" => TerminalType.PowerShell,
                    _ => TerminalType.Shell
                };

                var terminalService = context.RequestServices.GetRequiredService<TerminalService>();
                var webSocket = await context.WebSockets.AcceptWebSocketAsync();
                await terminalService.HandleWebSocketAsync(webSocket, terminalType);
            });

            // WebSocket 端点：远程控制键鼠，建立后持续接收 JSON 命令，避免每个操作一次 HTTP 请求
            app.Map("/ws/input", async context =>
            {
                if (!context.WebSockets.IsWebSocketRequest)
                {
                    context.Response.StatusCode = 400;
                    return;
                }

                var token = context.Request.Query["access_token"].ToString();
                var authService = context.RequestServices.GetRequiredService<AuthService>();
                if (string.IsNullOrEmpty(token) || !authService.ValidateToken(token))
                {
                    context.Response.StatusCode = 401;
                    return;
                }

                var systemService = context.RequestServices.GetRequiredService<SystemService>();
                var webSocket = await context.WebSockets.AcceptWebSocketAsync();
                await InputWebSocketHandler.RunAsync(webSocket, systemService, context.RequestAborted);
            });

            app.Run();
        }
    }
}
