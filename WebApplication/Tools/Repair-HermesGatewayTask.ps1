#Requires -RunAsAdministrator
[CmdletBinding()]
param([string]$TaskName = 'Hermes_Gateway')
$ErrorActionPreference = 'Stop'

# Change only the login mode; preserve the task action, user, triggers and settings.
# Do not stop a gateway or interrupt any active conversation.
$original = Export-ScheduledTask -TaskName $TaskName
[xml]$definition = $original
$principal = $definition.Task.Principals.Principal
if (-not $principal.UserId) { throw 'The gateway task has no user identity.' }
if ($principal.UserId -in @('SYSTEM', 'S-1-5-18', 'LOCAL SERVICE', 'NETWORK SERVICE')) {
    throw 'The gateway task must belong to the Hermes desktop user.'
}
$backupDirectory = Join-Path $env:ProgramData 'RemoteTool\hermes-task-backups'
New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
$backup = Join-Path $backupDirectory ('gateway-' + (Get-Date -Format 'yyyyMMdd-HHmmss-ffff') + '.xml')
[IO.File]::WriteAllText($backup, $original)
$principal.LogonType = 'InteractiveToken'
Register-ScheduledTask -TaskName $TaskName -Xml $definition.OuterXml -Force | Out-Null
[xml]$verified = Export-ScheduledTask -TaskName $TaskName
if ($verified.Task.Principals.Principal.LogonType -ne 'InteractiveToken') {
    throw "Task verification failed. Original definition: $backup"
}
Write-Output "Updated $TaskName to InteractiveToken. Backup: $backup"
Write-Output 'The running gateway was left in place. Once conversations are idle, stop the old gateway and start Hermes from the desktop user session.'
