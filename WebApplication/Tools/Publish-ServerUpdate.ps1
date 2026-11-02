#Requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$PublishDirectory,[Parameter(Mandatory)][string]$OutputDirectory,
      [Parameter(Mandatory)][string]$Version,[Parameter(Mandatory)][long]$Sequence,[Parameter(Mandatory)][string]$Commit)
$ErrorActionPreference='Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$' -or $Sequence -le 0 -or $Commit -notmatch '^[a-f0-9]{40}$') { throw 'Invalid release identity' }
$stage=Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) ('stage-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force|Out-Null
Get-ChildItem -LiteralPath $PublishDirectory -Force | Where-Object { $_.Name -notlike 'appsettings*' -and $_.Name -notin @('web.config','.env','.git','shared','updates') } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $stage -Recurse }
$archive=Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) 'RemoteTool-server-win-x64.zip'
if(Test-Path $archive){throw 'Release archive already exists'}
[IO.Compression.ZipFile]::CreateFromDirectory($stage,$archive)
$manifest=[ordered]@{schemaVersion=1;version=$Version;releaseSequence=$Sequence;commit=$Commit;channel='stable';minimumUpdaterVersion='1.0.0';protocolMin=1;protocolMax=1;rollbackSupported=$true;publishedAt=[DateTimeOffset]::UtcNow.ToString('O');releaseNotes="RemoteTool 服务端 $Version";packages=@{'win-x64-iis'=@{url="https://github.com/bighamx/RemoteTool/releases/download/server-v$Version/RemoteTool-server-win-x64.zip";sha256=(Get-FileHash $archive).Hash.ToLowerInvariant();size=(Get-Item $archive).Length}}}
$bytes=[Text.Encoding]::UTF8.GetBytes(($manifest|ConvertTo-Json -Depth 8 -Compress))
[IO.File]::WriteAllBytes((Join-Path $OutputDirectory 'server-update.json'),$bytes)
Write-Output "Prepared service release $Version, sequence $Sequence (SHA256)"
