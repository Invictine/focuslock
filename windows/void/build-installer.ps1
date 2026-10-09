[CmdletBinding()]
param(
    [string]$Version = '1.3.0',
    [string]$Configuration = 'Release',
    [switch]$SkipToolBootstrap
)

$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
$toolsRoot = Join-Path $repoRoot 'work\tools'
$sdkRoot = Join-Path $toolsRoot 'dotnet-sdk'
$publishDir = Join-Path $repoRoot 'work\publish'
$outputsDir = Join-Path $repoRoot 'outputs'
$issPath = Join-Path $repoRoot 'packaging\Void.iss'

function Find-DotNetSdk {
    $commands = @(Get-Command dotnet.exe -ErrorAction SilentlyContinue)
    foreach ($command in $commands) {
        $sdks = & $command.Source --list-sdks 2>$null
        if ($LASTEXITCODE -eq 0 -and $sdks) { return $command.Source }
    }
    $localDotnet = Join-Path $sdkRoot 'dotnet.exe'
    if (Test-Path $localDotnet) {
        $sdks = & $localDotnet --list-sdks 2>$null
        if ($LASTEXITCODE -eq 0 -and $sdks) { return $localDotnet }
    }
    return $null
}

function Find-Iscc {
    $commands = @(Get-Command ISCC.exe -ErrorAction SilentlyContinue)
    if ($commands.Count -gt 0) { return $commands[0].Source }
    $paths = @(
        (Join-Path $env:ProgramFiles 'Inno Setup 7\ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 7\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $toolsRoot 'innosetup\ISCC.exe')
    )
    return $paths | Where-Object { Test-Path $_ } | Select-Object -First 1
}

function Ensure-DotNetSdk {
    $dotnet = Find-DotNetSdk
    if ($dotnet) { return $dotnet }
    if ($SkipToolBootstrap) { throw 'A .NET 8 SDK is required; none was found and tool bootstrap was skipped.' }

    New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
    $installer = Join-Path $toolsRoot 'dotnet-install.ps1'
    Invoke-WebRequest -Uri 'https://dot.net/v1/dotnet-install.ps1' -OutFile $installer
    & $installer -Channel 8.0 -Architecture x64 -InstallDir $sdkRoot -NoPath
    if ($LASTEXITCODE -ne 0) { throw '.NET SDK bootstrap failed.' }
    $dotnet = Find-DotNetSdk
    if (-not $dotnet) { throw 'The .NET SDK bootstrap completed without a usable dotnet SDK.' }
    return $dotnet
}

function Ensure-Iscc {
    $iscc = Find-Iscc
    if ($iscc) { return $iscc }
    if ($SkipToolBootstrap) { throw 'Inno Setup ISCC.exe was not found and tool bootstrap was skipped.' }

    New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
    $download = Join-Path $toolsRoot 'innosetup-installer.exe'
    $extractDir = Join-Path $toolsRoot 'innosetup'
    # Pin the official immutable x64 release; verify its Authenticode signature before running it.
    Invoke-WebRequest -Uri 'https://github.com/jrsoftware/issrc/releases/download/is-7_1_0/innosetup-7.1.0-x64.exe' -OutFile $download
    $signature = Get-AuthenticodeSignature -FilePath $download
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Pyrsys B.V.*') {
        throw 'The downloaded Inno Setup compiler installer did not have the expected valid Pyrsys B.V. signature.'
    }
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
    $extract = Start-Process -FilePath $download -ArgumentList "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CURRENTUSER /DIR=`"$extractDir`"" -WindowStyle Hidden -Wait -PassThru
    if ($extract.ExitCode -ne 0) { throw "Inno Setup extraction failed with exit code $($extract.ExitCode)." }
    $iscc = Find-Iscc
    if (-not $iscc) { throw 'Inno Setup was installed, but ISCC.exe could not be found.' }
    return $iscc
}

if (-not (Test-Path $issPath)) { throw "Installer definition not found: $issPath" }
$dotnet = Ensure-DotNetSdk
$iscc = Ensure-Iscc

New-Item -ItemType Directory -Force -Path $publishDir, $outputsDir | Out-Null
$expectedPublishDir = [System.IO.Path]::GetFullPath((Join-Path $repoRoot 'work\publish')).TrimEnd('\')
$resolvedPublishDir = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $publishDir).Path).TrimEnd('\')
$publishDirectory = Get-Item -LiteralPath $resolvedPublishDir -Force
if (-not [string]::Equals($resolvedPublishDir, $expectedPublishDir, [System.StringComparison]::OrdinalIgnoreCase) -or
    [string]::Equals($resolvedPublishDir, [System.IO.Path]::GetFullPath($repoRoot).TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase) -or
    (($publishDirectory.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)) {
    throw "Refusing to clear publish directory outside expected workspace path: $resolvedPublishDir"
}
Remove-Item -LiteralPath $resolvedPublishDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $publishDir | Out-Null

& $dotnet publish (Join-Path $repoRoot 'Void.csproj') -c $Configuration -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:Version=$Version -o $publishDir
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed with exit code $LASTEXITCODE." }
if (-not (Test-Path (Join-Path $publishDir 'Void.exe'))) { throw 'Publish did not produce Void.exe.' }
Copy-Item -LiteralPath (Join-Path $repoRoot 'packaging\default-config.json') -Destination (Join-Path $publishDir 'config.json') -Force

& $iscc "/DMyAppVersion=$Version" $issPath
if ($LASTEXITCODE -ne 0) { throw "Inno Setup compilation failed with exit code $LASTEXITCODE." }

$artifact = Join-Path $outputsDir "Void-Setup-$Version.exe"
if (-not (Test-Path $artifact)) { throw "Installer artifact was not created: $artifact" }
Write-Host "Installer created: $artifact"
Write-Host "Published app: $publishDir"
