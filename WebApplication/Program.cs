using ChuckieHelper.WebApi.Jobs;
using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Services.RemoteControl;
using RemoteTool.WebApi.Extensions;
using Hangfire;
using Hangfire.Console;
using Hangfire.Storage.SQLite;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authentication.OpenIdConnect;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using System.Text;
using System.IO.Compression;
using Microsoft.AspNetCore.ResponseCompression;


namespace RemoteTool.WebApi
{
    public class Program
    {
        public static async Task Main(string[] args)
        {
            if (args.Length == 2 && args[0] == "--server-updater") {
                if (!OperatingSystem.IsWindows() && !OperatingSystem.IsLinux()) throw new PlatformNotSupportedException("Server updates currently require Windows IIS or Linux systemd.");
                if (!Path.IsPathFullyQualified(args[1])) throw new ArgumentException("Expected the installed updater configuration path.");
                await new Services.Updates.Worker(Path.GetFullPath(args[1])).Run();
                return;
            }
            if (args.Length == 2 && args[0] == "--file-git-worker") {
                await FileGitService.Worker(args[1]);
                return;
            }
            // 产品改名 ChuckieHelper → RemoteTool：首次启动把旧数据目录搬到新目录（幂等，见迁移类）。
            // 三个入口（Web/桌面代理/Codex 桥）都可能在 Web 进程之前先起来，各自调用一次即可。
            Services.RemoteToolDataMigration.Migrate();

            if (args.Length == 2 && args[0] == "--codex-bridge") {
                await Services.Codex.CodexAgent.RunAsync(args[1]);
                return;
            }
            // 桌面代理模式：当以 --desktop-agent 参数启动时，
            // 不启动 Web 服务器，而是运行命名管道服务器处理桌面操作。
            // 此模式由 IIS 进程在检测到 Session 0 时自动启动。
            if (args.Contains("--desktop-agent"))
            {
                AgentStartupLogger.Log("Program", "检测到 --desktop-agent 参数，进入桌面代理模式");
                AgentStartupLogger.Log("Program", $"日志文件: {AgentStartupLogger.LogFilePath}");
                AgentStartupLogger.Log("Program", $"进程 ID: {Environment.ProcessId}");
                AgentStartupLogger.Log("Program", $"用户: {Environment.UserDomainName}\\{Environment.UserName}");
                AgentStartupLogger.Log("Program", $"启动参数: {string.Join(' ', args)}");


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
                    AgentStartupLogger.Log("Program", "桌面代理收到取消信号，准备停止");
                }
                catch (Exception ex)
                {
                    AgentStartupLogger.LogException("Program", "桌面代理模式运行异常", ex);
                    throw;
                }
                finally
                {
                    AgentStartupLogger.Log("Program", "桌面代理模式退出");
                    cts.Dispose();
                }
                return;
            }

            var builder = WebApplication.CreateBuilder(args);
            var sharedConfig = Environment.GetEnvironmentVariable("REMOTETOOL_SHARED_CONFIG");
            if (!string.IsNullOrWhiteSpace(sharedConfig)) {
                builder.Configuration.AddJsonFile(Path.Combine(sharedConfig, "appsettings.json"), optional: false, reloadOnChange: true);
                builder.Configuration.AddJsonFile(Path.Combine(sharedConfig, $"appsettings.{builder.Environment.EnvironmentName}.json"), optional: true, reloadOnChange: true);
                builder.Configuration.AddJsonFile(Path.Combine(sharedConfig, "hosting.json"), optional: true, reloadOnChange: true);
            }
            builder.Services.AddSingleton<Services.Updates.UpdaterBootstrap>();
            builder.Services.AddSingleton<Services.Updates.ServerUpdateService>();
            builder.Services.AddHostedService(provider => provider.GetRequiredService<Services.Updates.ServerUpdateService>());
            builder.Services.AddSingleton<Services.Storage.ManagedStorageService>(services=>new(
                services.GetRequiredService<RunRegistry>(),services.GetRequiredService<IConfiguration>(),services.GetRequiredService<HermesProfiles>(),
                (agent,profile,id)=>{
                    System.Text.Json.Nodes.JsonObject state;
                    if(agent=="codex")state=services.GetRequiredService<CodexSessionActivity>().Read(id);
                    else {using var scope=HermesProfileScope.Enter(profile);state=services.GetRequiredService<HermesSessionActivity>().Read(id);}
                    var running=state["running"] is System.Text.Json.Nodes.JsonValue value&&value.TryGetValue<bool>(out var flag)&&flag;
                    if(!running){
                        // 服务端观察到会话已停：清掉两分钟前登记却再没下文的 run，避免永久锁住附件。
                        var registry=services.GetRequiredService<RunRegistry>();
                        var key=agent=="hermes"&&profile!="default"?"hermes@"+profile:agent;
                        var entry=registry.QueryEntry(key,id);
                        if(entry is not null&&DateTimeOffset.UtcNow.ToUnixTimeSeconds()-entry.Value.UpdatedAt>120)registry.Clear(key,id,entry.Value.RunId);
                    }
                    return running;
                }));
            builder.Services.AddSingleton<Services.PhoneControl.PhoneGateway>();
            builder.Services.AddHostedService<Services.PhoneControl.PhoneSessionReaper>();
            builder.Services.AddSingleton<Services.PhoneControl.PhoneSessionIntegration>();

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
            builder.Services.AddRequestDecompression();
            builder.Services.AddResponseCompression(options => {
                options.EnableForHttps = true;
                options.Providers.Add<BrotliCompressionProvider>();
                options.Providers.Add<GzipCompressionProvider>();
                // SSE must stay unbuffered; binary media and range responses use their original encoding.
                // .NET 8 serves .js as text/javascript; without it every script crossed the tunnel uncompressed.
                options.MimeTypes = new[] { "application/json", "application/problem+json", "text/plain", "text/css", "application/javascript", "text/javascript", "text/html", "image/svg+xml", "application/manifest+json" };
            });
            // Brotli quality 4 (Optimal) costs a few ms per response but is ~20% smaller than Fastest over the tunnel.
            builder.Services.Configure<BrotliCompressionProviderOptions>(options => options.Level = CompressionLevel.Optimal);
            builder.Services.Configure<GzipCompressionProviderOptions>(options => options.Level = CompressionLevel.Fastest);

            // 注册编码提供程序以支持 GBK (需 NuGet 安装 System.Text.Encoding.CodePages)
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);


            // Remote Control Services
            builder.Services.AddRemoteControlServices();
            builder.Services.AddSingleton<DeviceIdentity>();
            builder.Services.AddSingleton<Services.Cloudflare.CloudflareAccounts>();
            builder.Services.AddSingleton<Services.Tunnels.TunnelGateway>();
            builder.Services.AddSingleton<HermesProfiles>();
            builder.Services.AddSingleton<HermesAdminFiles>();
            builder.Services.AddSingleton<HermesGatewayAdmin>();
            builder.Services.AddSingleton<HermesPatches>();
            builder.Services.AddTransient<HermesProfileFilter>();
            builder.Services.AddSingleton<HermesBridge>();
            builder.Services.AddSingleton<HermesTitleService>();
            builder.Services.AddSingleton<RunRegistry>();
            builder.Services.AddSingleton<Services.Codex.CodexInstaller>();
            builder.Services.AddSingleton<Services.HermesInstaller>();
builder.Services.AddSingleton<HermesManagement>();
builder.Services.AddSingleton<HermesSessionActivity>();
builder.Services.AddSingleton<CodexSessionActivity>();
builder.Services.AddSingleton<HermesCompaction>();
            builder.Services.AddSingleton<HermesAttachments>();
            builder.Services.AddSingleton<Services.RemoteControl.FileBookmarkStore>();
            builder.Services.AddSingleton<HermesNativeAttachments>();
            builder.Services.AddSingleton<CodexBridge>();
            builder.Services.AddSingleton<ITitleModelGateway>(services => services.GetRequiredService<CodexBridge>());
            builder.Services.AddKeyedSingleton("codex", new HermesAttachments("codex"));
            builder.Services.AddHttpClient("codex", client => client.Timeout = Timeout.InfiniteTimeSpan)
                .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false });
            builder.Services.AddHostedService<HermesAttachmentCleanup>();
            builder.Services.AddSingleton<Services.Scheduled.ScheduledMessageStore>();
            builder.Services.AddHostedService<Services.Scheduled.ScheduledMessageDispatcher>();
            builder.Services.AddHttpClient("hermes", client => client.Timeout = Timeout.InfiniteTimeSpan)
                .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false });
            builder.Services.AddSingleton<PerformanceMonitor>();
            builder.Services.AddSingleton<HardwareSensorMonitor>();
            builder.Services.AddHostedService(services => services.GetRequiredService<HardwareSensorMonitor>());
            builder.Services.AddHostedService(services => services.GetRequiredService<PerformanceMonitor>());

            // Configure JWT Authentication
            builder.Services.AddJwtAuthentication(builder.Configuration);
            builder.Services.AddSingleton<OidcProviderStore>();
            builder.Services.AddSingleton<OidcSchemeRegistry>();
            builder.Services.AddSingleton<IConfigureOptions<OpenIdConnectOptions>, OidcOptionsSetup>();
            builder.Services.AddAuthentication()
                .AddCookie(OidcAuthentication.ExternalCookie, options => {
                    options.Cookie.HttpOnly = true;
                    options.Cookie.SameSite = SameSiteMode.Lax;
                    options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
                    options.ExpireTimeSpan = TimeSpan.FromMinutes(5);
                })
                // Registers the standard OIDC handler services. Named provider schemes are
                // added at runtime by OidcSchemeRegistry; this scheme is never challenged.
                .AddOpenIdConnect("oidc-bootstrap", options => options.SkipUnrecognizedRequests = true);
            builder.Services.AddHttpClient();
            builder.Services.AddSingleton<AccessSettingsStore>();
            builder.Services.AddSingleton<CloudflareAccessIdentity>();

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
            if (OperatingSystem.IsWindows()) {
                try {
                    Services.Codex.CodexAttachmentAccess.Ensure(Services.RemoteToolPaths.CodexAttachments,
                        Services.RemoteControl.InteractiveProcessLauncher.GetInteractiveUserSid());
                } catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException) {
                    app.Logger.LogWarning(error, "Unable to refresh interactive Codex attachment access");
                }
            }
            app.Services.GetRequiredService<OidcSchemeRegistry>().Sync();
            // The native task page no longer mounts Hangfire Dashboard. Existing
            // recurring-job registrations and API actions still use the static
            // Hangfire facade, so bind it to the DI storage before registering jobs.
            JobStorage.Current = app.Services.GetRequiredService<JobStorage>();

            // 必须在管道最前使用，以便后续中间件看到正确的 Scheme/Host
            app.UseForwardedHeaders();
            app.UseRequestDecompression();
            app.UseResponseCompression();
            app.Use(async (context, next) => {
                if (context.Request.Path.StartsWithSegments("/api"))
                    context.Response.Headers["X-RemoteTool-Request-Compression"] = "gzip";
                await next();
            });

            // Cloudflare Access SSO is opt-in and requires a valid signed assertion for
            // the configured application and email. Keep the normal password login for
            // local access and for deployments without Access.
            app.Use(async (context, next) =>
            {
                var access = context.RequestServices.GetRequiredService<CloudflareAccessIdentity>();
                var assertion = context.Request.Headers["Cf-Access-Jwt-Assertion"].ToString();
                if (await access.ValidatedEmailAsync(assertion, context.RequestAborted) is { } email)
                {
                    var auth = context.RequestServices.GetRequiredService<AuthService>();
                    var token = auth.GenerateToken(context.RequestServices.GetRequiredService<IConfiguration>()["Auth:Username"] ?? "admin", "cloudflare", email);
                    context.Request.Headers.Authorization = "Bearer " + token;
                    if (AuthCookies.Read(context.Request) is not { Length: > 0 } existingToken || !auth.ValidateToken(existingToken))
                        AuthCookies.Write(context.Response, context.Request.IsHttps, token, DateTimeOffset.UtcNow.AddHours(24));
                }
                await next();
            });

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
            ServerBuild.AssetVersion = ServerBuild.ComputeAssetVersion(app.Environment.WebRootPath);
            app.UseStaticFiles(new StaticFileOptions {
                // Versioned URLs (?v=) never change, so repeat visits over a slow tunnel skip the round trip entirely.
                // Unversioned files revalidate with their ETag so a deployment is never hidden behind a stale copy.
                OnPrepareResponse = context => {
                    var headers = context.Context.Response.Headers;
                    var path = context.Context.Request.Path.Value ?? "";
                    if (context.Context.Request.Query.ContainsKey("v")) headers.CacheControl = "public, max-age=31536000, immutable";
                    else if (path.StartsWith("/icons/", StringComparison.OrdinalIgnoreCase) || path.StartsWith("/mobile/", StringComparison.OrdinalIgnoreCase)) headers.CacheControl = "public, max-age=86400";
                    else headers.CacheControl = "no-cache";
                }
            });
            app.MapGet("/mobile", () => Results.Redirect("/"));

            app.UseRouting();

            app.UseWebSockets();
            app.UseAuthentication();
            app.UseAuthorization();
            app.Use(async (context, next) => {
                if ((!context.Request.Path.StartsWithSegments("/api") && !context.Request.Path.StartsWithSegments("/ws")) || context.Request.Path.StartsWithSegments("/api/server-update") ||
                    context.Request.Path.StartsWithSegments("/api/device/identity") || context.Request.Path.StartsWithSegments("/api/device/update-health")) { await next(); return; }
                var updates = context.RequestServices.GetRequiredService<Services.Updates.ServerUpdateService>();
                var requestStamp = DateTime.UtcNow.Ticks;
                if (!updates.Enter(requestStamp)) { context.Response.StatusCode = 503; await context.Response.WriteAsJsonAsync(new { message = "服务端正在更新，请稍后重连", code = "server_updating" }); return; }
                try { await next(); } finally { updates.Leave(requestStamp); }
            });


            // Custom Middleware to intercept Hangfire 401 and redirect to Login
            app.Use(async (context, next) =>
            {
                await next();

                if (context.Response.StatusCode == 401 && context.Request.Path.StartsWithSegments("/hangfire"))
                {
                    context.Response.Redirect("/Account/Login?ReturnUrl=" + System.Net.WebUtility.UrlEncode(context.Request.Path + context.Request.QueryString));
                }
            });

            // The /hangfire page uses authenticated native-jobs APIs.


            // 按实例名称注册 Hangfire 定时任务：Office 仅 DDNS，Home 注册全部
            var instanceName = (app.Configuration["InstanceName"] ?? "Home").Trim();
            if (string.Equals(instanceName, "Office", StringComparison.OrdinalIgnoreCase))
            {

            }
            else
            {
                app.UseHangfireQBittorrentMoveTask();

            }
            app.UseHangfireExampleTask();
            app.UseHangfireDdnsTask();
            app.UseHangfireQBittorrentMaintenceTask();
            app.UseHangfireEmbyTask();

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


                var token = AuthCookies.Read(context.Request) ?? context.Request.Query["access_token"].ToString();
                var authService = context.RequestServices.GetRequiredService<AuthService>();
                if (string.IsNullOrEmpty(token) || !authService.ValidateToken(token))
                {
                    context.Response.StatusCode = 401;
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

                var token = AuthCookies.Read(context.Request) ?? context.Request.Query["access_token"].ToString();
                var authService = context.RequestServices.GetRequiredService<AuthService>();
                if (string.IsNullOrEmpty(token) || !authService.ValidateToken(token))
                {
                    context.Response.StatusCode = 401;
                    return;
                }

                var systemService = context.RequestServices.GetRequiredService<ISystemControlService>();
                if (!RemoteDesktopPlatform.Supported)
                {
                    context.Response.StatusCode = 501;
                    await context.Response.WriteAsJsonAsync(RemoteDesktopPlatform.Rejection());
                    return;
                }
                context.Response.Headers["X-Chuckie-Input-Ack"] = "1";
                var webSocket = await context.WebSockets.AcceptWebSocketAsync();
                await InputWebSocketHandler.RunAsync(webSocket, systemService, context.RequestAborted);
            });

            app.Map("/ws/tunnel", async context => {
                if (!context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode = 400; return; }
                var token = AuthCookies.Read(context.Request) ?? context.Request.Headers.Authorization.ToString().Replace("Bearer ", "", StringComparison.Ordinal);
                var auth = context.RequestServices.GetRequiredService<AuthService>();
                if (!auth.ValidateToken(token)) { context.Response.StatusCode = 401; return; }
                var origin = context.Request.Headers.Origin.ToString();
                if (origin.Length > 0 && (!Uri.TryCreate(origin, UriKind.Absolute, out var uri) ||
                    !string.Equals(uri.Authority, context.Request.Host.Value, StringComparison.OrdinalIgnoreCase))) { context.Response.StatusCode = 403; return; }
                using var socket = await context.WebSockets.AcceptWebSocketAsync();
                await context.RequestServices.GetRequiredService<Services.Tunnels.TunnelGateway>().Attach(socket, context.RequestAborted, () => auth.ValidateToken(token));
            });
            app.Map("/ws/phone-agent", async context => {
                if (!context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode = 400; return; }
                var token = AuthCookies.Read(context.Request) ?? context.Request.Headers.Authorization.ToString().Replace("Bearer ", "", StringComparison.Ordinal);
                if (!context.RequestServices.GetRequiredService<AuthService>().ValidateToken(token)) { context.Response.StatusCode = 401; return; }
                using var socket = await context.WebSockets.AcceptWebSocketAsync();
                await context.RequestServices.GetRequiredService<Services.PhoneControl.PhoneGateway>().Attach(socket, context.RequestAborted);
            });
            app.Run();
        }
    }
}
