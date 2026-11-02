#Requires -RunAsAdministrator
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$appcmd=Join-Path $env:windir 'System32\inetsrv\appcmd.exe'
function Iis { $result=& $appcmd @args; if($LASTEXITCODE){throw 'IIS operation failed'}; return $result }
$updates=Join-Path $env:ProgramData 'RemoteTool\updates'
$config=Get-Content (Join-Path $updates 'installation.json') -Raw|ConvertFrom-Json
$migration=Get-Content (Join-Path $updates 'migration.json') -Raw|ConvertFrom-Json
$base=([uri]$config.healthUrl).GetLeftPart([UriPartial]::Authority)
$headers=@{'X-RemoteTool-Updater'=$config.workerToken}
if((Iis list vdir "$($config.site)/" /text:physicalPath).Trim() -ne $migration.oldDirectory){throw 'IIS path changed after preparation; recheck migration manually'}
# Requires the new checking API on the current deployment. This deliberate prerequisite lets
# the running process count requests and native tasks before its first migration.
$health=Invoke-RestMethod $config.healthUrl
if($health.id -ne $config.deviceId){throw 'Device identity mismatch'}
$id=[guid]::NewGuid().ToString('N')
$job=@{id=$id;phase='migrating';message='首次迁移等待空闲';sequence=0;version='initial'}
[IO.File]::WriteAllText((Join-Path $updates 'job.json'),($job|ConvertTo-Json))
$backup='RemoteTool-before-updater-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
$appcmd=Join-Path $env:windir 'System32\inetsrv\appcmd.exe'
& $appcmd add backup $backup|Out-Null
if($LASTEXITCODE){throw 'Failed to back up IIS configuration'}
$switched=$false
try {
    $ready=Invoke-RestMethod "$base/api/server-update/readiness?jobId=$id" -Method Post -Headers $headers -TimeoutSec 60
    if(-not $ready.ready){throw ('Tasks/connections are not idle: '+($ready.blockers -join '; '))}
    Iis stop apppool "/apppool.name:$($config.pool)"|Out-Null
    Iis set vdir "$($config.site)/" "/physicalPath:$($migration.initialDirectory)"|Out-Null
    $switched=$true
    Iis start apppool "/apppool.name:$($config.pool)"|Out-Null
    $healthy=$false
    for($i=0;$i -lt 45;$i++){
        try {$value=Invoke-RestMethod $config.healthUrl -TimeoutSec 3;if($value.id -eq $config.deviceId -and $value.processId -ne $health.processId){$healthy=$true;break}}catch{}
        Start-Sleep -Seconds 2
    }
    if(-not $healthy){throw 'Initial release health/identity verification failed'}
    $job.phase='completed';$job.message='首次迁移完成'
    [IO.File]::WriteAllText((Join-Path $updates 'job.json'),($job|ConvertTo-Json))
    Write-Output 'IIS migrated to the installed release; device identity verified. Update mode remains notification-only.'
}catch{
    if($switched){Iis set vdir "$($config.site)/" "/physicalPath:$($migration.oldDirectory)"|Out-Null;try{Iis start apppool "/apppool.name:$($config.pool)"|Out-Null}catch{Iis recycle apppool "/apppool.name:$($config.pool)"|Out-Null}}
    $job.phase='failed';$job.message=$_.Exception.Message
    [IO.File]::WriteAllText((Join-Path $updates 'job.json'),($job|ConvertTo-Json))
    throw
}finally{try{Invoke-RestMethod "$base/api/server-update/release-drain" -Method Post -Headers $headers -TimeoutSec 5|Out-Null}catch{}}
