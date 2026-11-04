# Start the unsigned desktop build and confirm the VeritasVPN window is visible.
# The tunnel is still a stub. The screen itself is the real React UI.
$ErrorActionPreference = "Stop"

$desktop = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $desktop "src-tauri\target\release\veritasvpn-desktop.exe"
if (-not (Test-Path $exe)) {
    throw "desktop exe not found at $exe"
}

$installerDir = Join-Path $desktop "src-tauri\target\release\bundle\nsis"
$installer = Get-ChildItem -Path $installerDir -Filter "*.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $installer) {
    throw "NSIS installer was not produced"
}
Write-Host "NSIS $($installer.Name) $([math]::Round($installer.Length / 1MB, 1)) MiB"

$proc = Start-Process -FilePath $exe -PassThru
$deadline = (Get-Date).AddSeconds(45)
try {
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            throw "VeritasVPN exited before showing a window (code $($proc.ExitCode))"
        }
        $proc.Refresh()
        $title = $proc.MainWindowTitle
        if ($title -eq "VeritasVPN") {
            Write-Host "WINDOW_OPEN title=$title pid=$($proc.Id)"
            exit 0
        }
        Start-Sleep -Milliseconds 500
    }
    $proc.Refresh()
    throw "VeritasVPN window did not appear. MainWindowTitle='$($proc.MainWindowTitle)'"
} finally {
    if (-not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
}
