[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallDir,
    [switch] $Upgrade
)

$ErrorActionPreference = 'Stop'

function Get-FullPath([string] $Path) {
    return [System.IO.Path]::GetFullPath($Path)
}

function Remove-SafeFile([string] $Path) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        Remove-Item -LiteralPath $Path -Force
    }
}

try {
    $installPath = Get-FullPath $InstallDir
    $installInfo = Get-Item -LiteralPath $installPath -Force
    if (-not ($installInfo -is [System.IO.DirectoryInfo]) -or $null -eq $installInfo.Parent) {
        throw "InstallDir must be an existing non-root directory"
    }
    $desktopExe = Join-Path $installPath 'focuslock-desktop.exe'
    if (-not (Test-Path -LiteralPath $desktopExe -PathType Leaf)) {
        throw "Required installed executable was not found: $desktopExe"
    }
    $installPrefix = $installPath.TrimEnd('\') + '\'

    $dataDir = Join-Path $env:APPDATA 'com.focuslock.desktop'
    $ownerFile = Join-Path $dataDir 'recovery-owner.json'
    $readyFile = Join-Path $dataDir 'recovery-ready.json'
    Remove-SafeFile $ownerFile
    Remove-SafeFile $readyFile
    Get-ChildItem -LiteralPath $dataDir -Filter 'recovery-*.stop' -File -ErrorAction SilentlyContinue |
        ForEach-Object { Remove-SafeFile $_.FullName }

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($dataDir))
    } finally {
        $sha.Dispose()
    }
    $hex = -join ($digest | ForEach-Object { $_.ToString('x2') })
    $taskName = "FocusLock Recovery $($hex.Substring(0, 16))"
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($null -ne $task -and $task.Description -eq 'FocusLock desktop recovery') {
        Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction Stop
    }

    for ($pass = 0; $pass -lt 3; $pass++) {
        $processes = Get-CimInstance Win32_Process -ErrorAction Stop |
            Where-Object {
                ($_.Name -ieq 'focuslock-desktop.exe' -or $_.Name -ieq 'FocusLock.Void.exe') -and
                $_.ExecutablePath -and
                (Get-FullPath $_.ExecutablePath).StartsWith($installPrefix, [System.StringComparison]::OrdinalIgnoreCase)
            }
        foreach ($process in $processes) {
            Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 200
    }
    $remaining = Get-CimInstance Win32_Process -ErrorAction Stop |
        Where-Object {
            ($_.Name -ieq 'focuslock-desktop.exe' -or $_.Name -ieq 'FocusLock.Void.exe') -and
            $_.ExecutablePath -and
            (Get-FullPath $_.ExecutablePath).StartsWith($installPrefix, [System.StringComparison]::OrdinalIgnoreCase)
        }
    if ($remaining) { throw 'FocusLock processes remained after cleanup' }

    $startupLink = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup\FocusLock.lnk'
    if (Test-Path -LiteralPath $startupLink -PathType Leaf) {
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($startupLink)
        $target = [string]$shortcut.TargetPath
        $args = [string]$shortcut.Arguments
        if ([IO.Path]::GetFileName($target) -ieq 'focuslock-desktop.exe' -or
            $args -match '(?i)[\\/]focuslock\.vbs([\s"'']|$)') {
            Remove-SafeFile $startupLink
        }
    }

    if (-not $Upgrade) {
        $run = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser, [Microsoft.Win32.RegistryView]::Default)
        try {
            $runKey = $run.OpenSubKey('Software\Microsoft\Windows\CurrentVersion\Run', $true)
            if ($null -ne $runKey) {
                $value = [string]$runKey.GetValue('FocusLock', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
                if ($value -and $value -match '(?i)(^|[\s"''])' + [regex]::Escape($desktopExe) + '([\s"'']|$)') { $runKey.DeleteValue('FocusLock', $false) }
                $runKey.Dispose()
            }
        } finally { $run.Dispose() }

        $bases = @(
            'Software\Google\Chrome\NativeMessagingHosts',
            'Software\Microsoft\Edge\NativeMessagingHosts',
            'Software\BraveSoftware\Brave-Browser\NativeMessagingHosts',
            'Software\Vivaldi\NativeMessagingHosts',
            'Software\Opera Software\Opera Stable\NativeMessagingHosts',
            'Software\Opera Software\Opera GX Stable\NativeMessagingHosts'
        )
        $manifestPath = Get-FullPath (Join-Path $dataDir 'browser-native-host.json')
        $ownsManifest = $false
        if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
            $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
            $ownsManifest = $manifest.name -eq 'com.focuslock.browser' -and $manifest.path -and
                (Get-FullPath $manifest.path) -ieq $desktopExe
        }
        foreach ($view in @([Microsoft.Win32.RegistryView]::Registry32, [Microsoft.Win32.RegistryView]::Registry64)) {
            if (-not $ownsManifest) { continue }
            $root = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser, $view)
            try {
                foreach ($base in $bases) {
                    $key = $root.OpenSubKey("$base\com.focuslock.browser", $true)
                    if ($null -ne $key) {
                        $registered = [string]$key.GetValue('', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
                        $key.Dispose()
                        if ($registered -and (Get-FullPath $registered) -ieq $manifestPath) { $root.DeleteSubKey("$base\com.focuslock.browser", $false) }
                    }
                }
            } finally { $root.Dispose() }
        }
    }
    exit 0
} catch {
    Write-Error $_
    exit 1
}
