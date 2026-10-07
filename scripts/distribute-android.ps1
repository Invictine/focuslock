[CmdletBinding()]
param(
    [string]$ConfigPath,
    [switch]$ValidateOnly,
    [string]$Notes,
    [ValidateRange(2, 2100000000)]
    [int]$VersionCode
)

$ErrorActionPreference = 'Stop'
if (-not $ConfigPath) { $ConfigPath = Join-Path $PSScriptRoot '..\firebase-distribution.local.json' }
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
$packageName = 'com.focuslock.app'
$statePath = Join-Path $repoRoot 'artifacts\firebase-distribution-state.json'

function Stop-WithError([string]$Message) {
    throw $Message
}

function Get-SafeOutput([object[]]$Lines) {
    foreach ($line in $Lines) {
        $safe = [string]$line
        $safe = $safe -replace '(?i)(access[_ -]?token|refresh[_ -]?token|authorization|password|secret)(\s*[:=]\s*)([^\s,;]+)', '$1$2[redacted]'
        $safe = $safe -replace '(?i)(Bearer\s+)[A-Za-z0-9._~+/-]+=*', '$1[redacted]'
        if ($safe.Trim()) { $safe }
    }
}

function Invoke-Native([string]$Executable, [string[]]$Arguments, [string]$Label, [switch]$JsonOutput) {
    $previousPreference = $ErrorActionPreference
    $stderrPath = Join-Path ([IO.Path]::GetTempPath()) ("focuslock-cli-" + [guid]::NewGuid().ToString('N') + '.log')
    $stderrLines = @()
    try {
        $ErrorActionPreference = 'Continue'
        if ($JsonOutput) {
            # Firebase's progress spinner uses stderr even with --json. Keep it
            # separate so only the machine-readable stdout reaches the parser.
            $lines = @(& $Executable @Arguments 2> $stderrPath | ForEach-Object { [string]$_ })
        } else {
            $lines = @(& $Executable @Arguments 2>&1 | ForEach-Object { [string]$_ })
        }
        $code = $LASTEXITCODE
        if (Test-Path -LiteralPath $stderrPath) { $stderrLines = @(Get-Content -LiteralPath $stderrPath) }
    } finally {
        $ErrorActionPreference = $previousPreference
        Remove-Item -LiteralPath $stderrPath -Force -ErrorAction SilentlyContinue
    }
    if ($code -ne 0) {
        $details = @(Get-SafeOutput (@($lines) + @($stderrLines))) -join [Environment]::NewLine
        throw "$Label failed (exit $code). $details"
    }
    return ,$lines
}

function Resolve-AndroidSdk {
    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    $localProperties = Join-Path $repoRoot 'local.properties'
    if (-not $sdk -and (Test-Path -LiteralPath $localProperties)) {
        $line = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { $sdk = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Replace('\:', ':').Replace('\\', '\') }
    }
    if ($sdk -and (Test-Path -LiteralPath $sdk -PathType Container)) { return (Resolve-Path -LiteralPath $sdk).Path }
    return $null
}

function Get-AndroidBuildTools([string]$SdkPath) {
    $buildTools = Join-Path $SdkPath 'build-tools'
    if (-not (Test-Path -LiteralPath $buildTools -PathType Container)) { return $null }
    $candidates = Get-ChildItem -LiteralPath $buildTools -Directory | Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending
    foreach ($candidate in $candidates) {
        $signer = Join-Path $candidate.FullName 'apksigner.bat'
        $aapt = Join-Path $candidate.FullName 'aapt.exe'
        if (-not (Test-Path -LiteralPath $signer)) { $signer = Join-Path $candidate.FullName 'apksigner' }
        if ((Test-Path -LiteralPath $signer) -and (Test-Path -LiteralPath $aapt)) {
            return [pscustomobject]@{ Signer = $signer; Aapt = $aapt }
        }
    }
    return $null
}

function Validate-DistributionConfig([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Stop-WithError "Config file not found: $Path. Copy firebase-distribution.example.json and fill in your Firebase project, app, testers, and groups." }
    try { $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json } catch { Stop-WithError "Config file is not valid JSON: $($_.Exception.Message)" }

    if (-not ($config.projectId -is [string]) -or $config.projectId -notmatch '^[a-z][a-z0-9-]{4,28}[a-z0-9]$') { Stop-WithError 'Config projectId must be a Firebase project ID (6–30 lowercase letters, digits, or hyphens).' }
    if (-not ($config.appId -is [string]) -or $config.appId -notmatch '^1:[0-9]+:android:[0-9a-fA-F]+$') { Stop-WithError 'Config appId must be an Android Firebase App ID such as 1:123456789:android:abcdef0123456789.' }

    foreach ($field in @('testers', 'groups')) {
        $value = $config.$field
        if ($null -eq $value) {
            $value = @()
            $config | Add-Member -NotePropertyName $field -NotePropertyValue $value -Force
        }
        if ($value -isnot [System.Array] -and $value -isnot [System.Collections.IList]) { Stop-WithError "Config $field must be a JSON array of strings." }
        foreach ($item in $value) {
            if (-not ($item -is [string])) { Stop-WithError "Every $field entry must be a string." }
        }
        $config.$field = @($value | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Select-Object -Unique)
    }
    if ($config.testers.Count -eq 0 -and $config.groups.Count -eq 0) { Stop-WithError 'Add at least one tester email or tester group.' }
    foreach ($email in $config.testers) {
        if ($email.Length -gt 254 -or $email -notmatch '^[A-Za-z0-9.!#$%&''*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$') { Stop-WithError "Invalid tester email address in config: $email" }
    }
    foreach ($group in $config.groups) {
        if ($group -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$') { Stop-WithError "Invalid Firebase tester group alias in config: $group" }
    }
    return $config
}

Push-Location $repoRoot
try {
$config = Validate-DistributionConfig $ConfigPath
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) { Stop-WithError 'Gradle wrapper was not found in the project root.' }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Stop-WithError 'Java was not found on PATH. Install JDK 17 and open a new terminal.' }
$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaLines = @(& java -version 2>&1 | ForEach-Object { [string]$_ })
    $javaExitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = $previousPreference }
if ($javaExitCode -ne 0 -or -not (($javaLines -join "`n") -match 'version "17(?:\.|"|\+)')) { Stop-WithError 'JDK 17 is required for the Android Gradle build.' }

$sdkPath = Resolve-AndroidSdk
if (-not $sdkPath) { Stop-WithError 'Android SDK was not found. Set ANDROID_SDK_ROOT (or ANDROID_HOME) or configure local.properties.' }
$tools = Get-AndroidBuildTools $sdkPath
if (-not $tools) { Stop-WithError "Android SDK build-tools with apksigner and aapt were not found under $sdkPath." }
$firebase = Get-Command firebase -ErrorAction SilentlyContinue
if (-not $firebase) { Stop-WithError 'Firebase CLI was not found on PATH. Install it with npm install --global firebase-tools@15.32.1.' }

Write-Output "Distribution config is valid for project $($config.projectId); Firebase app $($config.appId)."
Write-Output "JDK 17, Android SDK build-tools, Gradle wrapper, and Firebase CLI are available."
if ($ValidateOnly) {
    Write-Output 'Validation only: no Firebase calls, build, or upload were performed.'
    exit 0
}

# Verify the selected Firebase app belongs to this project and is the Android package used by this build.
$appsRaw = Invoke-Native $firebase.Source @('apps:list', 'ANDROID', '--project', $config.projectId, '--non-interactive', '--json') 'Firebase app listing' -JsonOutput
$appsText = $appsRaw -join "`n"
try {
    $parsed = $appsText | ConvertFrom-Json
    if ($parsed.status -eq 'error') { Stop-WithError 'Firebase CLI could not list apps for the configured project.' }
    $appRows = if ($parsed.result) { @($parsed.result) } else { @($parsed) }
} catch { Stop-WithError 'Could not parse Firebase CLI apps:list JSON output.' }
$selected = $appRows | Where-Object { $_.appId -eq $config.appId -or $_.app_id -eq $config.appId } | Select-Object -First 1
if (-not $selected) { Stop-WithError 'Configured Firebase app ID was not found in the selected Firebase project. Check firebase apps:list.' }
$registeredPackage = $selected.packageName
if (-not $registeredPackage) { $registeredPackage = $selected.package_name }
if (-not $registeredPackage) { $registeredPackage = $selected.namespace }
if ($registeredPackage -ne $packageName) { Stop-WithError "Configured Firebase app package does not match expected $packageName." }

# The generated code is monotonic across repeated runs, including runs in the same second.
$stateDir = Split-Path -Parent $statePath
if (-not (Test-Path -LiteralPath $stateDir)) { New-Item -ItemType Directory -Path $stateDir -Force | Out-Null }
$epoch = [DateTime]::new(2020, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)
$calculated = [int64][Math]::Floor(([DateTime]::UtcNow - $epoch).TotalSeconds)
$last = 1
if (Test-Path -LiteralPath $statePath) {
    try { $saved = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json; if ($saved.lastVersionCode -is [long] -or $saved.lastVersionCode -is [int]) { $last = [int64]$saved.lastVersionCode } } catch { Stop-WithError 'Distribution state file is unreadable; preserve it and repair its JSON before continuing.' }
}
if ($VersionCode -gt 0) {
    if ($VersionCode -le $last) { Stop-WithError "VersionCode must exceed the last persisted code ($last)." }
    $nextCode = [int64]$VersionCode
} else {
    $nextCode = [Math]::Max($calculated, $last + 1)
}
if ($nextCode -gt 2100000000) { Stop-WithError 'Generated versionCode exceeds the Android update counter limit.' }
[IO.File]::WriteAllText($statePath, (@{ lastVersionCode = $nextCode } | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
Write-Output "Building debug APK with versionCode $nextCode."
Invoke-Native $gradleWrapper @('testDebugUnitTest', 'lintDebug', 'assembleDebug', "-PfocuslockVersionCode=$nextCode", '--no-daemon', '--console=plain') 'Android validation and debug build' | Out-Null

$apkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) { Stop-WithError 'Gradle completed but the expected debug APK was not produced.' }
Invoke-Native $tools.Signer @('verify', '--verbose', $apkPath) 'APK signature verification' | Out-Null
$badging = Invoke-Native $tools.Aapt @('dump', 'badging', $apkPath) 'APK metadata inspection'
$badgingText = $badging -join "`n"
if ($badgingText -notmatch "package: name='$([regex]::Escape($packageName))'" -or $badgingText -notmatch "versionCode='$nextCode'") { Stop-WithError 'APK metadata did not match the expected package and versionCode.' }

if (-not $PSBoundParameters.ContainsKey('Notes')) {
    $commit = 'unknown'
    $commitLines = @(& git -C $repoRoot rev-parse --short HEAD 2>$null)
    if ($LASTEXITCODE -eq 0 -and $commitLines.Count -gt 0) { $commit = [string]$commitLines[0] }
    $Notes = "FocusLock Android debug build $nextCode (source commit $commit)."
}
$notesPath = Join-Path ([IO.Path]::GetTempPath()) ("focuslock-release-notes-" + [guid]::NewGuid().ToString('N') + '.txt')
try {
    [IO.File]::WriteAllText($notesPath, $Notes, (New-Object Text.UTF8Encoding($false)))
    $uploadArgs = @('appdistribution:distribute', $apkPath, '--project', $config.projectId, '--app', $config.appId, '--non-interactive', '--release-notes-file', $notesPath)
    if ($config.testers.Count -gt 0) { $uploadArgs += @('--testers', ($config.testers -join ',')) }
    if ($config.groups.Count -gt 0) { $uploadArgs += @('--groups', ($config.groups -join ',')) }
    $uploadOutput = Invoke-Native $firebase.Source $uploadArgs 'Firebase App Distribution upload'
    Get-SafeOutput $uploadOutput | ForEach-Object { Write-Output $_ }
    Write-Output "Firebase App Distribution confirmed success for versionCode $nextCode."
} finally {
    Remove-Item -LiteralPath $notesPath -Force -ErrorAction SilentlyContinue
}
} catch {
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    exit 1
} finally {
    Pop-Location
}
