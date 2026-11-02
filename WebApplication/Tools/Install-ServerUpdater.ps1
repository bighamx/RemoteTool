#Requires -RunAsAdministrator
#Requires -Version 7.0
[CmdletBinding()]
param([string]$Site='RemoteTool', [Parameter(Mandatory)][string]$UpdaterExecutable,
      [ValidateSet('Auto','Source','Release')][string]$Mode='Auto', [string]$ProjectDirectory,
      [string]$InitialRelease, [string]$InstallRoot='C:\Program Files\RemoteTool',
      [string]$HealthBase='http://127.0.0.1:8888', [switch]$Activate)
$ErrorActionPreference='Stop'
$appcmd=Join-Path $env:windir 'System32\inetsrv\appcmd.exe'
function Iis { $result=& $appcmd @args; if($LASTEXITCODE){throw 'IIS configuration operation failed'}; return $result }
$uri=[uri]$HealthBase
if(-not $uri.IsLoopback -or $uri.Scheme -notin @('http','https')){throw 'HealthBase must be loopback'}
$pool=(Iis list app "$Site/" /text:applicationPool).Trim()
if(@(Iis list app "/apppool.name:$pool" /text:app.name).Count -ne 1){throw 'Use a dedicated application pool'}
if((Iis list apppool $pool /text:processModel.identityType).Trim() -ne 'LocalSystem'){throw 'This installer supports the existing LocalSystem RemoteTool pool'}
$old=[IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables((Iis list vdir "$Site/" /text:physicalPath).Trim()))
if(-not $ProjectDirectory){
    for($directory=Get-Item $old;$null -ne $directory;$directory=$directory.Parent){
        if(Test-Path (Join-Path $directory.FullName 'WebApplication.csproj')){$ProjectDirectory=$directory.FullName;break}
    }
}
if($Mode -eq 'Auto'){$Mode=if($ProjectDirectory){'Source'}else{'Release'}}
if($Mode -eq 'Source' -and -not(Test-Path (Join-Path $ProjectDirectory 'WebApplication.csproj'))){throw 'Source mode requires WebApplication.csproj'}
if($Mode -eq 'Release' -and (-not $InitialRelease -or -not(Test-Path (Join-Path $InitialRelease 'WebApplication.dll')))){throw 'Release mode requires a complete published InitialRelease directory'}
if(-not(Test-Path $UpdaterExecutable)){throw 'Updater executable not found'}
$root=[IO.Path]::GetFullPath($InstallRoot)
$updates=Join-Path $env:ProgramData 'RemoteTool\updates'
if(Test-Path $root){throw 'Use a fresh installation directory'}
if(Test-Path (Join-Path $updates 'installation.json')){throw 'Updater already configured'}
$identity=Invoke-RestMethod "$HealthBase/api/device/identity"
New-Item -ItemType Directory -Path $root,$updates -Force|Out-Null
foreach($directory in @($root,$updates)){
    & icacls.exe $directory /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F'|Out-Null
    if($LASTEXITCODE){throw 'Failed to secure updater directory'}
}
$shared=Join-Path $root 'shared';$releases=Join-Path $root 'releases';$updater=Join-Path $root 'updater'
New-Item -ItemType Directory -Path $shared,$releases,$updater -Force|Out-Null
Copy-Item -LiteralPath $UpdaterExecutable -Destination (Join-Path $updater 'ServerUpdater.exe')
$settings=@{site=$Site;pool=$pool;releases=$releases;shared=$shared;healthUrl="$HealthBase/api/device/update-health";deviceId=$identity.id;
    workerToken=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));mode=$Mode.ToLowerInvariant();projectDirectory=$ProjectDirectory;runtimeDirectory=$old}
[IO.File]::WriteAllText((Join-Path $updates 'installation.json'),($settings|ConvertTo-Json))
$action=New-ScheduledTaskAction -Execute (Join-Path $updater 'ServerUpdater.exe') -Argument ('"'+(Join-Path $updates 'installation.json')+'"')
$trigger=New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(5) -RepetitionInterval (New-TimeSpan -Minutes 5)
$taskSettings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
if($Mode -eq 'Source'){
    $principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Highest
}else{$principal=New-ScheduledTaskPrincipal -UserId SYSTEM -LogonType ServiceAccount -RunLevel Highest}
Register-ScheduledTask -TaskName RemoteTool_ServerUpdate -Action $action -Trigger $trigger -Settings $taskSettings -Principal $principal -Force|Out-Null
if($Mode -eq 'Source'){
    Write-Output "Source rebuild enabled: $ProjectDirectory -> $old. IIS path and Git files were not changed."
    return
}
$baseline=Join-Path $releases 'initial';New-Item -ItemType Directory -Path $baseline|Out-Null
Copy-Item -Path (Join-Path $InitialRelease '*') -Destination $baseline -Recurse
Get-ChildItem -LiteralPath $old -Filter 'appsettings*.json'|ForEach-Object{Copy-Item -LiteralPath $_.FullName -Destination $shared}
# Preserve the existing Hangfire database by absolute path, without copying a live SQLite file.
$settingsFile=Join-Path $shared 'appsettings.json';$application=Get-Content $settingsFile -Raw|ConvertFrom-Json
if(-not $application.Hangfire){$application|Add-Member NoteProperty Hangfire ([pscustomobject]@{})}
$database=if($application.Hangfire.Database){[string]$application.Hangfire.Database}else{'hangfire.db'}
if(-not [IO.Path]::IsPathFullyQualified($database)){
    $candidate=Join-Path $old $database
    if(-not(Test-Path $candidate)){throw 'Set Hangfire:Database to the existing absolute database path before Release migration'}
    $database=[IO.Path]::GetFullPath($candidate)
}
$application.Hangfire|Add-Member -Force NoteProperty Database $database
[IO.File]::WriteAllText($settingsFile,($application|ConvertTo-Json -Depth 30))
[xml]$web=Get-Content (Join-Path $old 'web.config')
$envs=$web.configuration.location.'system.webServer'.aspNetCore.environmentVariables
if(-not $envs){$envs=$web.CreateElement('environmentVariables');$web.configuration.location.'system.webServer'.aspNetCore.AppendChild($envs)|Out-Null}
$entry=$web.CreateElement('environmentVariable');$entry.SetAttribute('name','REMOTETOOL_SHARED_CONFIG');$entry.SetAttribute('value',$shared);$envs.AppendChild($entry)|Out-Null
$web.Save((Join-Path $shared 'web.config'));Copy-Item (Join-Path $shared 'web.config') (Join-Path $baseline 'web.config') -Force
@{site=$Site;oldDirectory=$old;initialDirectory=$baseline;healthUrl=$settings.healthUrl;deviceId=$identity.id}|ConvertTo-Json|Set-Content (Join-Path $updates 'migration.json')
Write-Output "Release installation prepared: $baseline. IIS still points to $old."
if($Activate){& (Join-Path $PSScriptRoot 'Activate-ServerUpdater.ps1')}
