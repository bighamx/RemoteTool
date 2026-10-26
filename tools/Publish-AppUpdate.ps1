param(
    [string]$ApkPath,
    [string]$PreviousApkPath,
    [switch]$IncludeCompanion,
    [string]$CompanionApkPath,
    [string]$PreviousCompanionApkPath,
    [string]$NotesPath,
    [string]$OutputDirectory,
    [string]$AndroidSdk,
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
function Get-ApkSha256([string]$LiteralPath) {
    $stream = [IO.File]::OpenRead($LiteralPath)
    $digest = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $digest.Dispose(); $stream.Dispose() }
}
$taskRepo = Split-Path -Parent $PSScriptRoot
if (-not $ApkPath) {
    $apkDirectory = Join-Path $taskRepo 'app\mobile\build\outputs\apk\full\release'
    $metadataPath = Join-Path $apkDirectory 'output-metadata.json'
    if (-not (Test-Path -LiteralPath $metadataPath)) { throw 'Build :mobile:assembleFullRelease first, or pass -ApkPath.' }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (@($metadata.elements).Count -ne 1) { throw 'Expected one full Release APK; pass -ApkPath explicitly.' }
    $ApkPath = Join-Path $apkDirectory $metadata.elements[0].outputFile
}
$apkSource = (Resolve-Path -LiteralPath $ApkPath).Path
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) {
    $sdkProperty = Get-Content -LiteralPath (Join-Path $taskRepo 'app\local.properties') -Encoding UTF8 |
        Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    if ($sdkProperty) { $AndroidSdk = ($sdkProperty -replace '^sdk.dir=', '').Replace('\:', ':').Replace('\\', '\') }
}
if (-not $AndroidSdk) { throw 'Pass -AndroidSdk or configure ANDROID_HOME.' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Where-Object { (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe')) -and (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat')) } |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android build-tools with aapt and apksigner are required.' }
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
function Read-VerifiedApk([string]$Path, [string]$PackageId) {
    $source = (Resolve-Path -LiteralPath $Path).Path
    $badging = (& $aapt dump badging $source) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read APK metadata.' }
    if ($Publish -and $badging -match '(?m)^application-debuggable') { throw 'Publish a Release APK, not a debuggable APK.' }
    $identity = [regex]::Match($badging, "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
    if (-not $identity.Success -or $identity.Groups[1].Value -ne $PackageId) { throw "Wrong APK application ID; expected $PackageId." }
    $name = $identity.Groups[3].Value
    if ($name -notmatch '^[a-zA-Z0-9.+_-]{1,80}$') { throw 'Unsafe APK version name.' }
    $verification = (& $apksigner verify --print-certs $source) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $signer = [regex]::Match($verification, 'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)').Groups[1].Value.ToLowerInvariant()
    if (-not $signer) { throw 'APK signer certificate missing.' }
    [pscustomobject]@{ Path=$source; PackageId=$PackageId; VersionCode=[long]$identity.Groups[2].Value; VersionName=$name; Signer=$signer; Size=(Get-Item -LiteralPath $source).Length; Sha256=(Get-ApkSha256 $source) }
}
$companion = $null
if ($IncludeCompanion -or $CompanionApkPath) {
    if (-not $CompanionApkPath) {
        $phoneDirectory = Join-Path $taskRepo 'app\mobile\build\outputs\apk\phone\release'
        $phoneMetadataPath = Join-Path $phoneDirectory 'output-metadata.json'
        if (-not (Test-Path -LiteralPath $phoneMetadataPath)) { throw 'Build :mobile:assemblePhoneRelease first.' }
        $phoneMetadata = Get-Content -LiteralPath $phoneMetadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if (@($phoneMetadata.elements).Count -ne 1) { throw 'Expected one companion Release APK.' }
        $CompanionApkPath = Join-Path $phoneDirectory $phoneMetadata.elements[0].outputFile
    }
    $companion = Read-VerifiedApk $CompanionApkPath 'com.chuckiehelper.phonecontrol'
    if ($PreviousCompanionApkPath) {
        $previousPhone = Read-VerifiedApk $PreviousCompanionApkPath 'com.chuckiehelper.phonecontrol'
        if ($companion.Signer -ne $previousPhone.Signer -or $companion.VersionCode -le $previousPhone.VersionCode) { throw 'Companion signer must match and version must increase.' }
    }
}
$badging = (& $aapt dump badging $apkSource) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Unable to read APK metadata.' }
if ($Publish -and $badging -match '(?m)^application-debuggable') { throw 'Publish the optimized Release APK, not a debuggable APK.' }
$identity = [regex]::Match($badging, "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
if (-not $identity.Success -or $identity.Groups[1].Value -ne 'com.chuckiehelper.mobile') { throw 'Wrong APK application ID.' }
$versionCode = [long]$identity.Groups[2].Value
$versionName = $identity.Groups[3].Value
if ($versionName -notmatch '^[a-zA-Z0-9.+_-]{1,80}$') { throw 'Version name is not a safe release asset name.' }
$verification = (& $apksigner verify --print-certs $apkSource) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$certificate = [regex]::Match($verification, 'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)').Groups[1].Value.ToLowerInvariant()
if (-not $certificate) { throw 'APK has no verified signing certificate.' }
if ($companion -and $companion.Signer -ne $certificate) { throw 'Main and companion signing certificates must match.' }
if ($PreviousApkPath) {
    $previousSource = (Resolve-Path -LiteralPath $PreviousApkPath).Path
    $oldVerification = (& $apksigner verify --print-certs $previousSource) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Previous APK signature verification failed.' }
    $oldCertificate = [regex]::Match($oldVerification, 'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)').Groups[1].Value.ToLowerInvariant()
    if ($oldCertificate -ne $certificate) { throw 'New and previous APK signatures differ; do not uninstall to update.' }
    $oldBadging = (& $aapt dump badging $previousSource) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read previous APK version.' }
    $oldCode = [long][regex]::Match($oldBadging, "versionCode='(\d+)'").Groups[1].Value
    if ($versionCode -le $oldCode) { throw 'versionCode must be greater than the previous APK.' }
}
$notes = if ($NotesPath) { [IO.File]::ReadAllText((Resolve-Path -LiteralPath $NotesPath).Path, [Text.Encoding]::UTF8) } else { "RemoteTool Android v$versionName" }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $taskRepo "artifacts\app-updates\$versionCode" }
$destination = [IO.Path]::GetFullPath($OutputDirectory)
$apkName = "RemoteTool-$versionName.apk"
$targetApk = Join-Path $destination $apkName
$manifestPath = Join-Path $destination 'remotetool-update.json'
# Installed versions through build 43 request the legacy filename.
$legacyManifestPath = Join-Path $destination 'chuckiehelper-update.json'
$notesTarget = Join-Path $destination 'release-notes.txt'
$manifest = [ordered]@{
    schemaVersion = 1
    packageId = 'com.chuckiehelper.mobile'
    versionCode = $versionCode
    versionName = $versionName
    apkAsset = $apkName
    size = (Get-Item -LiteralPath $apkSource).Length
    sha256 = Get-ApkSha256 $apkSource
    signerSha256 = $certificate
    notes = $notes
}
New-Item -ItemType Directory -Path $destination -Force | Out-Null
if ($apkSource -ne $targetApk) { Copy-Item -LiteralPath $apkSource -Destination $targetApk -Force }
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5), $utf8)
Copy-Item -LiteralPath $manifestPath -Destination $legacyManifestPath -Force
[IO.File]::WriteAllText($notesTarget, $notes, $utf8)
if ((Get-ApkSha256 $targetApk) -ne $manifest.sha256) { throw 'Copied APK hash mismatch.' }
$releaseAssets = @($targetApk, $manifestPath, $legacyManifestPath)
if ($companion) {
    $phoneName = "RemoteTool-PhoneControl-$($companion.VersionName).apk"
    $phoneTarget = Join-Path $destination $phoneName
    $phoneManifestPath = Join-Path $destination 'remotetool-phone-update.json'
    if ($companion.Path -ne $phoneTarget) { Copy-Item -LiteralPath $companion.Path -Destination $phoneTarget }
    $phoneManifest = [ordered]@{ schemaVersion=1; packageId=$companion.PackageId; versionCode=$companion.VersionCode; versionName=$companion.VersionName; apkAsset=$phoneName; size=$companion.Size; sha256=$companion.Sha256; signerSha256=$companion.Signer; notes=$notes }
    [IO.File]::WriteAllText($phoneManifestPath, ($phoneManifest | ConvertTo-Json -Depth 5), $utf8)
    if ((Get-ApkSha256 $phoneTarget) -ne $companion.Sha256) { throw 'Copied companion APK hash mismatch.' }
    $releaseAssets += @($phoneTarget, $phoneManifestPath)
}
if ($Publish) {
    $repository = 'bighamx/RemoteTool'
    $repositoryId = 368781353
    $releasesRaw = & gh api "repositories/$repositoryId/releases?per_page=30"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read current releases; refusing to publish without version verification.' }
    foreach ($release in ($releasesRaw -join "`n" | ConvertFrom-Json)) {
        if ($release.draft -or $release.prerelease) { continue }
        $asset = $release.assets | Where-Object { $_.name -in @('remotetool-update.json', 'chuckiehelper-update.json') } | Select-Object -First 1
        if ($asset) {
            if (-not ($asset.browser_download_url.StartsWith("https://github.com/$repository/releases/download/") -or
                $asset.browser_download_url.StartsWith('https://github.com/bighamx/chuckieTool/releases/download/'))) { throw 'Unexpected manifest URL.' }
            $published = Invoke-RestMethod -Uri $asset.browser_download_url
            if ($versionCode -le [long]$published.versionCode) { throw 'versionCode must exceed every published stable Android version.' }
        }
        if ($companion) {
            $phoneAsset = $release.assets | Where-Object { $_.name -eq 'remotetool-phone-update.json' } | Select-Object -First 1
            if ($phoneAsset) {
                if (-not $phoneAsset.browser_download_url.StartsWith("https://github.com/$repository/releases/download/")) { throw 'Unexpected companion manifest URL.' }
                $publishedPhone = Invoke-RestMethod -Uri $phoneAsset.browser_download_url
                if ($publishedPhone.packageId -ne $companion.PackageId -or $companion.VersionCode -le [long]$publishedPhone.versionCode) { throw 'Companion version must exceed published versions of the same package.' }
                if ($publishedPhone.signerSha256 -and $publishedPhone.signerSha256 -ne $companion.Signer) { throw 'Companion published signing certificate mismatch.' }
            }
        }
    }
    $releaseCommit = & git -C $taskRepo rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve release commit.' }
    $pendingChanges = & git -C $taskRepo status --porcelain
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify source worktree state.' }
    if ($pendingChanges) { throw 'Commit all source changes before publishing.' }
    & gh release create "android-v$versionName-build$versionCode" @releaseAssets --repo $repository --target $releaseCommit --title "RemoteTool Android v$versionName" --notes-file $notesTarget
    if ($LASTEXITCODE -ne 0) { throw 'GitHub release creation failed.' }
} else { Write-Output 'Prepared locally. Add -Publish to publish these assets as an Android GitHub release.' }
Write-Output "APK: $targetApk"
Write-Output "Manifest: $manifestPath"
Write-Output "Version: $versionName ($versionCode)"
Write-Output "SHA256: $($manifest.sha256)"
if ($companion) { Write-Output "Companion: $phoneTarget ($($companion.VersionName), build $($companion.VersionCode))" }
