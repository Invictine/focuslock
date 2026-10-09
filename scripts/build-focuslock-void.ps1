param([switch]$Force, [string]$DotNetPath = $env:FOCUSLOCK_DOTNET)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$voidSource = Join-Path $projectRoot 'windows\void'
$voidOutput = Join-Path $projectRoot 'desktop\src-tauri\resources\void'
$voidProject = Join-Path $voidSource 'Void.csproj'
$voidBinary = Join-Path $voidOutput 'FocusLock.Void.exe'
$sourceFiles = Get-ChildItem -LiteralPath $voidSource -File -Recurse | Where-Object {
    $_.FullName -notmatch '[\\/](bin|obj|work|outputs|portable)[\\/]' -and $_.Extension -in '.cs', '.xaml', '.csproj', '.json'
}
$newestSource = ($sourceFiles | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc
if (-not $Force -and (Test-Path -LiteralPath $voidBinary) -and (Get-Item -LiteralPath $voidBinary).LastWriteTimeUtc -ge $newestSource) {
    Write-Output 'FocusLock Void helper is up to date.'
    exit 0
}
if (-not $DotNetPath) {
    $dotnetCommand = Get-Command dotnet -ErrorAction SilentlyContinue
    if ($dotnetCommand) { $DotNetPath = $dotnetCommand.Source }
}
if (-not $DotNetPath -or -not (Test-Path -LiteralPath $DotNetPath)) {
    throw 'The .NET 8 SDK is needed to build the Windows launcher. Install it, then run npm run void:build again.'
}
$availableSdks = & $DotNetPath --list-sdks
if ($LASTEXITCODE -ne 0 -or -not $availableSdks) {
    throw 'No .NET SDK is available. Install .NET 8 SDK or set FOCUSLOCK_DOTNET to an SDK dotnet.exe.'
}
New-Item -ItemType Directory -Path $voidOutput -Force | Out-Null
& $DotNetPath publish $voidProject -c Release -r win-x64 --self-contained true '-p:AssemblyName=FocusLock.Void' '-p:PublishSingleFile=true' '-p:IncludeNativeLibrariesForSelfExtract=true' -o $voidOutput
if ($LASTEXITCODE -ne 0) { throw 'FocusLock Void publish failed.' }
if (-not (Test-Path -LiteralPath $voidBinary)) { throw 'Publish did not produce FocusLock.Void.exe.' }
Write-Output 'Built the bundled FocusLock Void Windows launcher.'
