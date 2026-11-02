#Requires -Version 7.0
#Requires -RunAsAdministrator
[CmdletBinding()]
param([string]$RepositoryRoot=(Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath($RepositoryRoot)
$root=Join-Path $repo ('.tmp-mobile-build/iis-updater-test-'+[guid]::NewGuid().ToString('N'))
$runtime=Join-Path $root 'site';$outside=Join-Path $root 'outside';$project=Join-Path $root 'project'
$name='RemoteToolUpdaterTest-'+[guid]::NewGuid().ToString('N')
$dotnet=(Get-Command dotnet -CommandType Application).Source
$appcmd=Join-Path $env:windir 'System32/inetsrv/appcmd.exe'
$poolCreated=$false;$siteCreated=$false
function Iis { $value=& $appcmd @args; if($LASTEXITCODE){throw 'Fixture IIS operation failed'};return $value }
New-Item -ItemType Directory -Path $runtime,$outside,$project -Force|Out-Null
try {
    $launcher=[Security.SecurityElement]::Escape((Join-Path $repo 'WebApplication/Services/Updates/UpdaterProcess.cs'))
    $checks=[Security.SecurityElement]::Escape((Join-Path $repo 'tests/ServerUpdates/UpdaterProcessChecks.cs'))
    "<Project Sdk=`"Microsoft.NET.Sdk.Web`"><PropertyGroup><TargetFramework>net8.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable><UseAppHost>false</UseAppHost></PropertyGroup><ItemGroup><Compile Include=`"$launcher`" Link=`"UpdaterProcess.cs`"/><Compile Include=`"$checks`" Link=`"UpdaterProcessChecks.cs`"/></ItemGroup></Project>"|Set-Content (Join-Path $project 'Fixture.csproj')
    @'
using System.Reflection;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Updates;
if(await UpdaterProcessChecks.Handle(args))return;
var builder=WebApplication.CreateBuilder(args);var app=builder.Build();
JsonObject Identity()=>(JsonObject)typeof(UpdaterProcessChecks).GetMethod("Identity",BindingFlags.Static|BindingFlags.NonPublic)!.Invoke(null,null)!;
app.MapGet("/identity",()=>new {pid=Environment.ProcessId,identity=Identity()});
app.MapPost("/launch",()=>{
 var root=Environment.GetEnvironmentVariable("FIXTURE_ROOT")!;
 File.WriteAllText(Path.Combine(root,"parent.json"),Identity().ToJsonString());
 var child=UpdaterProcess.StartCommand(Environment.GetEnvironmentVariable("DOTNET_HOST_PATH")!,new[]{Path.Combine(root,"outside","Fixture.dll"),"--process-child",root},root);
 File.WriteAllText(Path.Combine(root,"launch.json"),System.Text.Json.JsonSerializer.Serialize(child));return Results.Ok(child);
});
await app.RunAsync();
'@|Set-Content (Join-Path $project 'Program.cs')
    & $dotnet build (Join-Path $project 'Fixture.csproj') -c Release -o $runtime -v:q *> (Join-Path $root 'build.log')
    if($LASTEXITCODE){throw 'Fixture build failed; see build.log'}
    Get-ChildItem -LiteralPath $runtime -File|Where-Object {$_.Extension -eq '.dll' -or $_.Name -in @('Fixture.deps.json','Fixture.runtimeconfig.json')}|ForEach-Object{Copy-Item -LiteralPath $_.FullName -Destination $outside}
    $rootXml=[Security.SecurityElement]::Escape($root);$dotnetXml=[Security.SecurityElement]::Escape($dotnet)
    "<configuration><system.webServer><handlers><add name=`"aspNetCore`" path=`"*`" verb=`"*`" modules=`"AspNetCoreModuleV2`" resourceType=`"Unspecified`"/></handlers><aspNetCore processPath=`"$dotnetXml`" arguments=`"./Fixture.dll`" hostingModel=`"inprocess`" stdoutLogEnabled=`"true`" stdoutLogFile=`"./fixture-stdout`"><environmentVariables><environmentVariable name=`"DOTNET_HOST_PATH`" value=`"$dotnetXml`"/><environmentVariable name=`"FIXTURE_ROOT`" value=`"$rootXml`"/><environmentVariable name=`"REMOTETOOL_PROCESS_TEST`" value=`"inherited-from-iis`"/></environmentVariables></aspNetCore></system.webServer></configuration>"|Set-Content (Join-Path $runtime 'web.config')
    $tcp=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$tcp.Start();$port=$tcp.LocalEndpoint.Port;$tcp.Stop()
    Iis add apppool "/name:$name" '/managedRuntimeVersion:' '/processModel.identityType:LocalSystem'|Out-Null;$poolCreated=$true
    Iis add site "/name:$name" "/physicalPath:$runtime" "/bindings:http/127.0.0.1:${port}:"|Out-Null;$siteCreated=$true
    Iis set app "$name/" "/applicationPool:$name"|Out-Null
    $parent=$null
    for($attempt=0;$attempt -lt 20;$attempt++) {
        try {$parent=Invoke-RestMethod "http://127.0.0.1:$port/identity" -TimeoutSec 30;break}
        catch {if($attempt -eq 19){throw};Start-Sleep -Milliseconds 200}
    }
    $null=Invoke-RestMethod "http://127.0.0.1:$port/launch" -Method Post -TimeoutSec 15
    $deadline=(Get-Date).AddSeconds(10)
    while(-not(Test-Path (Join-Path $root 'child.json')) -and (Get-Date) -lt $deadline){Start-Sleep -Milliseconds 100}
    $before=Get-Content (Join-Path $root 'parent.json') -Raw|ConvertFrom-Json
    $after=Get-Content (Join-Path $root 'child.json') -Raw|ConvertFrom-Json
    if(($before|ConvertTo-Json -Compress) -ne ($after|ConvertTo-Json -Compress)){throw 'IIS token/privilege/environment inheritance mismatch'}
    Iis stop apppool "/apppool.name:$name"|Out-Null
    $parentProcess=Get-Process -Id $parent.pid -ErrorAction SilentlyContinue
    if($parentProcess -and -not $parentProcess.WaitForExit(15000)){throw 'Fixture IIS parent did not exit'}
    Start-Sleep -Milliseconds 200;$heartbeat=Get-Content (Join-Path $root 'heartbeat') -Raw;Start-Sleep -Milliseconds 300
    if((Get-Content (Join-Path $root 'heartbeat') -Raw) -eq $heartbeat){throw 'Updater died when its IIS pool stopped'}
    @{passed=$true;sid=$before.user;admin=$before.admin;privilegesRetained=$true;parentStopped=$true;childSurvived=$true}|ConvertTo-Json|Set-Content (Join-Path $root 'result.json')
    Write-Output "Real IIS: SID $($before.user), admin=$($before.admin); token privileges/environment inherited; stopped parent pool, outside DLL process survived"
} finally {
    Set-Content (Join-Path $root 'finish') ''
    if($siteCreated){& $appcmd delete site "/site.name:$name"|Out-Null}
    if($poolCreated){& $appcmd delete apppool "/apppool.name:$name"|Out-Null}
    Set-Content (Join-Path $root 'cleanup-complete') ''
    Write-Output "IIS test fixture: $root"
}
