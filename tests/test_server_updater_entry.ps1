#Requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RuntimeDirectory)
$ErrorActionPreference='Stop'
$runtime=[IO.Path]::GetFullPath($RuntimeDirectory)
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('remotetool-dll-entry-'+[guid]::NewGuid().ToString('N'))
$outside=Join-Path $temporary 'outside-website'
$updates=Join-Path $temporary 'updates'
New-Item -ItemType Directory -Path $outside,$updates -Force|Out-Null
$process=$null
try {
    Get-ChildItem -LiteralPath $runtime -Filter '*.dll' -File | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $outside}
    $platform=Join-Path $runtime 'runtimes'
    if(Test-Path $platform){Get-ChildItem -LiteralPath $platform -Filter '*.dll' -File -Recurse | ForEach-Object {
        $target=Join-Path $outside ([IO.Path]::GetRelativePath($runtime,$_.FullName))
        New-Item -ItemType Directory -Path (Split-Path $target) -Force|Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target
    }}
    foreach($name in @('WebApplication.deps.json','WebApplication.runtimeconfig.json')) {Copy-Item -LiteralPath (Join-Path $runtime $name) -Destination $outside}
    $configuration=Join-Path $updates 'installation.json'
    @{site='fixture';pool='fixture';releases=$temporary;shared=$temporary;healthUrl='http://127.0.0.1:1';deviceId='fixture';workerToken='fixture'}|ConvertTo-Json|Set-Content -LiteralPath $configuration
    $start=[Diagnostics.ProcessStartInfo]::new((Get-Command dotnet -CommandType Application).Source)
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.WorkingDirectory=$outside
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.Environment['REMOTETOOL_DATA_DIR']=Join-Path $temporary 'must-not-start-web'
    foreach($argument in @((Join-Path $outside 'WebApplication.dll'),'--server-updater',$configuration)) {$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($start)
    $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
    if(-not $process.WaitForExit(60000)){throw 'DLL updater mode did not exit without a job'}
    if($process.ExitCode -ne 0){throw ('DLL updater startup failed: '+$stderr.GetAwaiter().GetResult())}
    if(-not(Test-Path (Join-Path $updates 'worker.lock'))){throw 'Updater entry did not acquire its own lock'}
    if(Test-Path (Join-Path $temporary 'must-not-start-web')){throw 'Updater mode started Web/data-migration initialization'}
    if(Get-ChildItem -LiteralPath $outside -Filter '*.exe'){throw 'Updater mode should not require an EXE'}
    Write-Output 'DLL updater entry: outside copy started and exited successfully, without Web services or EXE'
} finally {
    if($process){if(-not $process.HasExited){$process.Kill($true);$process.WaitForExit()};$process.Dispose()}
    $resolved=[IO.Path]::GetFullPath($temporary)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe fixture cleanup target'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
