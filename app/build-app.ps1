param(
    [ValidateSet('full', 'phone', 'both')][string]$Edition = 'full',
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Release',
    [string[]]$GradleArguments = @()
)
$ErrorActionPreference = 'Stop'
$editions = if ($Edition -eq 'both') { @('full', 'phone') } else { @($Edition) }
$targets = @($editions | ForEach-Object {
    ':mobile:assemble' + $_.Substring(0, 1).ToUpperInvariant() + $_.Substring(1) + $Configuration
})
Push-Location -LiteralPath $PSScriptRoot
try {
    & .\gradlew.bat @targets @GradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Android build failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
