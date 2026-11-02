<#
.SYNOPSIS
  Publish RemoteTool.WebApi for linux-x64 and deploy it to a Linux host over SSH.

.DESCRIPTION
  One-shot idempotent deployment: publish -> config -> tar -> scp -> swap -> systemd -> health check.
  Intended for the Ubuntu test VM (or any systemd host with a non-root user).

  What it handles:
    * self-contained linux-x64 publish (no dotnet needed to run the main app)
    * REMOTETOOL_DATA_DIR so data never lands in /usr/share (root-only)
    * appsettings.json derived from the Windows config (auth password/JWT preserved),
      with Codex/Hermes paths rewritten for Linux
    * systemd unit + restart on failure
    * previous release kept as <RemotePath>.prev for rollback

  Note: the Codex bridge spawns `dotnet WebApplication.dll`, so the host still needs
  the ASP.NET Core 8 runtime (apt install aspnetcore-runtime-8.0). The script warns
  when it is missing.

.EXAMPLE
  ./WebApplication/Tools/Deploy-Linux.ps1
  ./WebApplication/Tools/Deploy-Linux.ps1 -HostName ubuntu-vm -Port 8890
  ./WebApplication/Tools/Deploy-Linux.ps1 -HostName ubuntu-vm -SourceConfig D:\GIT\RemoteTool\WebApplication\bin\Debug\net8.0\appsettings.json
#>
[CmdletBinding()]
param(
    [string]$HostName       = 'ubuntu-vm',
    [string]$RemoteUser     = '',
    [int]   $Port           = 8890,
    [string]$RemotePath     = '/home/chuckie/remotetool',
    [string]$DataPath       = '/home/chuckie/remotetool-data',
    [string]$ServiceName    = 'remotetool',
    [string]$Address        = '',
    [string]$SourceConfig   = '',
    [string]$CodexExecutable= '/usr/bin/codex',
    [string]$CodexHome      = '/home/chuckie/.codex',
    [string]$ProxyUrl       = '',
    [switch]$SkipPull,
    [switch]$SkipPublish
)

$ErrorActionPreference = 'Stop'
foreach ($pathValue in @($RemotePath,$DataPath,$CodexHome)) {
    if (!$pathValue.StartsWith('/') -or $pathValue.Contains('\') -or $pathValue.Contains("'") -or $pathValue.Contains("`r") -or $pathValue.Contains("`n") -or $pathValue -match '/\.\.?(/|$)') { throw 'Remote paths must be absolute POSIX paths without shell delimiters or traversal' }
}
if ($RemotePath.TrimEnd('/') -in @('','/home','/usr','/var','/etc','/opt','/tmp')) { throw 'RemotePath must identify a dedicated application directory' }
$repo   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$staging = Join-Path $repo '.tmp-linux-publish'
$archive = Join-Path $repo '.tmp-linux-publish.tar.gz'
$target  = if ($RemoteUser) { "$RemoteUser@$HostName" } else { $HostName }

function Step($message) { Write-Host "== $message" -ForegroundColor Cyan }
function RunRemote([string]$command) {
    $output = & ssh -o BatchMode=yes -o ConnectTimeout=10 $target $command 2>&1
    if ($LASTEXITCODE -ne 0) {
        $output | ForEach-Object { Write-Host "  | $_" -ForegroundColor DarkYellow }
        throw "ssh failed (exit $LASTEXITCODE)"
    }
    $output
}

if (-not $SkipPull) {
    Step 'git pull'
    Push-Location $repo
    try { & git pull --ff-only; if ($LASTEXITCODE -ne 0) { throw 'git pull failed' } } finally { Pop-Location }
}

if (-not $SkipPublish) {
    Step 'publish linux-x64 (self-contained)'
    if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
    Push-Location $repo
    try {
        & dotnet publish 'WebApplication/WebApplication.csproj' -c Release -r linux-x64 --self-contained true `
            -p:PublishTrimmed=false -o $staging -v q --nologo
        if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed' }
    } finally { Pop-Location }
}

Step 'compose appsettings.json'
if (-not $SourceConfig) {
    $SourceConfig = Join-Path $repo 'WebApplication\bin\Debug\net8.0\appsettings.json'
}
if (-not (Test-Path $SourceConfig)) {
    throw "appsettings source not found: $SourceConfig (pass -SourceConfig with the live server config)"
}
$cfg = Get-Content $SourceConfig -Raw -Encoding UTF8 | ConvertFrom-Json
# Keep auth credentials; rewrite everything that is Windows-specific.
if (!$CodexHome.StartsWith('/') -or $CodexHome.Contains('\')) { throw 'CodexHome must be an absolute POSIX path' }
$remoteParent = $CodexHome.TrimEnd('/')
$remoteParent = $remoteParent.Substring(0,$remoteParent.LastIndexOf('/'))
if (!$remoteParent) { $remoteParent = '/' }
$cfg | Add-Member -Force -NotePropertyName Codex -NotePropertyValue ([pscustomobject]@{
    Executable       = $CodexExecutable
    Home             = $CodexHome
    WorkingDirectory = $remoteParent
})
$cfg | Add-Member -Force -NotePropertyName Hermes -NotePropertyValue ([pscustomobject]@{
    BaseUrl       = 'http://127.0.0.1:8642/'
    HomeDirectory = "$DataPath/hermes-home"
    KeyFile       = "$DataPath/hermes/api-key.env"
})
if (-not $cfg.PSObject.Properties['FileUpload']) {
    $cfg | Add-Member -NotePropertyName FileUpload -NotePropertyValue ([pscustomobject]@{ MaxSizeMB = 500 })
}
$cfg.PSObject.Properties.Remove('RemoteControl')
$cfg.PSObject.Properties.Remove('QbSettings')
$cfg.PSObject.Properties.Remove('EmbySettings')
$cfg.PSObject.Properties.Remove('CloudflareSettings')
$json = $cfg | ConvertTo-Json -Depth 20
[IO.File]::WriteAllText((Join-Path $staging 'appsettings.json'), $json, (New-Object Text.UTF8Encoding($false)))

Step 'package'
if (Test-Path $archive) { Remove-Item $archive -Force }
# A relative archive name works with both Windows BSD tar and GNU tar.
Push-Location $repo
try {
    & tar -czf (Split-Path -Leaf $archive) -C $staging .
    if ($LASTEXITCODE -ne 0) { throw 'tar failed' }
} finally { Pop-Location }
$sizeMb = [math]::Round((Get-Item $archive).Length / 1MB, 1)
Write-Host "   $archive ($sizeMb MB)"

Step 'upload'
$remoteUpload = "$DataPath/.deployment-$([Guid]::NewGuid().ToString('N'))"
RunRemote "mkdir -p '$remoteUpload'; chmod 700 '$remoteUpload'"
& scp -q -o BatchMode=yes $archive "${target}:$remoteUpload/package.tar.gz"
if ($LASTEXITCODE -ne 0) { throw 'scp failed' }

Step 'verify host prerequisites'
$hasDotnet = ((& ssh -o BatchMode=yes $target 'command -v dotnet >/dev/null && echo yes || echo no') -join '').Trim()
if ($hasDotnet -ne 'yes') {
    Write-Warning 'dotnet runtime missing on host; the Codex bridge will fail. Install: sudo apt-get install -y aspnetcore-runtime-8.0'
}

Step 'install and restart service'
$activeGuard = @"
import json,pathlib
root=pathlib.Path('$DataPath')
for journal in (root/'codex-bridge').glob('state-*/runs.json'):
    runs=json.loads(journal.read_text())
    if any(r.get('status') in ['started','submitting','queued','running'] for r in runs.values()):
        raise SystemExit('Active Codex tasks exist; finish them before deployment. No process was stopped.')
"@
$guardEncoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($activeGuard))
RunRemote "python3 -c `"import base64;exec(base64.b64decode('$guardEncoded'))`""
$previousRelease = "$RemotePath.prev-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$preserveSettings = if ($PSBoundParameters.ContainsKey('SourceConfig')) { '0' } else { '1' }
$proxySetup = ''
if ($ProxyUrl) {
    $proxy = [Uri]$ProxyUrl
    if (!$proxy.IsAbsoluteUri -or $proxy.Scheme -notin @('http','https') -or $ProxyUrl.Contains("`n") -or $ProxyUrl.Contains("`r")) { throw 'ProxyUrl must be an HTTP(S) proxy URL' }
    $proxyText = "HTTP_PROXY=$($proxy.AbsoluteUri)`nHTTPS_PROXY=$($proxy.AbsoluteUri)`nALL_PROXY=$($proxy.AbsoluteUri)`nNO_PROXY=localhost,127.0.0.1,::1`n"
    $encodedProxy = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($proxyText))
    $proxySetup = "printf '%s' '$encodedProxy' | base64 -d > '$DataPath/service-proxy.env'; chmod 600 '$DataPath/service-proxy.env'"
}
$remote = @"
set -e
mkdir -p '$DataPath' '$RemotePath'
$proxySetup
if systemctl is-active --quiet '$ServiceName'; then sudo systemctl stop '$ServiceName'; fi
if [ -d '$RemotePath' ]; then mv '$RemotePath' '$previousRelease'; fi
mkdir -p '$RemotePath'
tar xzf '$remoteUpload/package.tar.gz' -C '$RemotePath'
if [ '$preserveSettings' = 1 ] && [ -f '$previousRelease/appsettings.json' ]; then cp -p '$previousRelease/appsettings.json' '$RemotePath/appsettings.json'; fi
python3 - <<'PRESERVE_WORKSPACES'
from pathlib import Path
import shutil
old=Path('$previousRelease');new=Path('$RemotePath')
for item in old.iterdir():
    if item.is_file() and not item.is_symlink() and item.name.endswith(('.db','.sqlite','.db-wal','.db-shm','.sqlite-wal','.sqlite-shm')):
        destination=new/item.name
        if not destination.exists():
            shutil.copy2(item,destination)
for folder in old.iterdir():
    if chr(92) in folder.name and folder.is_dir():
        target=new/folder.name
        if not target.exists() and not target.is_symlink():
            target.symlink_to(folder.resolve(),target_is_directory=True)
PRESERVE_WORKSPACES
chmod +x '$RemotePath/WebApplication'
sudo tee /etc/systemd/system/$ServiceName.service >/dev/null <<UNIT
[Unit]
Description=RemoteTool Web Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=`$(id -un)
WorkingDirectory=$RemotePath
Environment=ASPNETCORE_URLS=http://0.0.0.0:$Port
Environment=ASPNETCORE_ENVIRONMENT=Production
Environment=REMOTETOOL_DATA_DIR=$DataPath
Environment=PATH=/usr/local/bin:/usr/bin:/bin
Environment=DOTNET_HOST_PATH=/usr/bin/dotnet
EnvironmentFile=-$DataPath/service-proxy.env
ExecStart=$RemotePath/WebApplication
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable '$ServiceName' >/dev/null 2>&1 || true
sudo systemctl restart '$ServiceName'
rm -f '$remoteUpload/package.tar.gz'
rmdir '$remoteUpload'
for i in `$(seq 1 30); do
  if systemctl is-active --quiet '$ServiceName'; then break; fi
  sleep 1
done
systemctl is-active '$ServiceName'
"@
# The here-string inherits this file's CRLF; bash chokes on "do\r".
RunRemote ($remote -replace "`r`n", "`n")

Step 'health check'
if (-not $Address) {
    # ssh alias 通常不在 DNS 里，取远端第一个 IPv4。
    $Address = (((RunRemote "hostname -I") -join ' ').Trim() -split '\s+')[0]
}
$url = "http://${Address}:$Port"
for ($i = 0; $i -lt 30; $i++) {
    try {
        $code = (Invoke-WebRequest -Uri "$url/Account/Login" -TimeoutSec 5 -MaximumRedirection 0 -SkipHttpErrorCheck).StatusCode
        if ($code -eq 200) { break }
    } catch { Start-Sleep -Seconds 2 }
}
if ($code -ne 200) { throw "health check failed: $url/Account/Login -> $code" }
Write-Host "OK  $url  (login page 200)" -ForegroundColor Green
Write-Host "    logs:   ssh $target 'sudo journalctl -u $ServiceName -f'"
Write-Host "    rollback release preserved: $previousRelease (stop service, preserve the current directory, restore this release, then start service)"
