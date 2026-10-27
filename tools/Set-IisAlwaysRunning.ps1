param(
    [string]$SiteName = 'Helper',
    [string]$ExpectedRoot = 'E:\GIT\RemoteTool\WebApplication\bin\Debug\net8.0'
)

$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an Administrator PowerShell window.'
}

$appcmd = Join-Path $env:windir 'System32\inetsrv\appcmd.exe'
function Invoke-AppCmd {
    param([string[]]$Arguments)
    $result = & $appcmd @Arguments
    if ($LASTEXITCODE -ne 0) { throw ($result -join [Environment]::NewLine) }
    $result
}

$application = "$SiteName/"
$physicalPath = (Invoke-AppCmd -Arguments @('list', 'vdir', $application, '/text:physicalPath') | Out-String).Trim()
$physicalPath = [Environment]::ExpandEnvironmentVariables($physicalPath)
if ([IO.Path]::GetFullPath($physicalPath).TrimEnd('\') -ine [IO.Path]::GetFullPath($ExpectedRoot).TrimEnd('\')) {
    throw "Site directory does not match expected RemoteTool deployment: $physicalPath"
}
$pool = (Invoke-AppCmd -Arguments @('list', 'app', $application, '/text:applicationPool') | Out-String).Trim()
if (-not $pool) { throw 'Could not determine application pool.' }

$backup = 'RemoteTool-AlwaysRunning-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
Invoke-AppCmd -Arguments @('add', 'backup', $backup)
Write-Host "IIS configuration backup: $backup"
Invoke-AppCmd -Arguments @('set', 'apppool', $pool, '/processModel.idleTimeout:00:00:00', '/startMode:AlwaysRunning')
Invoke-AppCmd -Arguments @('set', 'app', $application, '/preloadEnabled:true')

Write-Host 'Effective configuration:'
Invoke-AppCmd -Arguments @('list', 'apppool', $pool, '/text:processModel.idleTimeout')
Invoke-AppCmd -Arguments @('list', 'apppool', $pool, '/text:startMode')
Invoke-AppCmd -Arguments @('list', 'app', $application, '/text:preloadEnabled')
Write-Host 'Expected values: 00:00:00, AlwaysRunning, true.'
Write-Host 'Application Initialization must be installed for preload to warm the application after a restart.'
Write-Host 'No explicit application-pool restart was requested.'
