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

function Assert-ParityWindow($proc) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class VeritasWindow {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
}
"@
    $hwnd = $proc.MainWindowHandle
    $rect = New-Object VeritasWindow+RECT
    [VeritasWindow]::SetForegroundWindow($hwnd) | Out-Null
    Start-Sleep -Milliseconds 300
    if (-not [VeritasWindow]::GetWindowRect($hwnd, [ref]$rect)) {
        throw "could not read the VeritasVPN window"
    }
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -lt 380 -or $width -gt 900 -or $height -lt 700) {
        throw "window size $width x $height is outside the phone-shaped range"
    }
    Add-Type -AssemblyName System.Drawing
    $bmp = New-Object System.Drawing.Bitmap $width, $height
    $graphics = [System.Drawing.Graphics]::FromImage($bmp)
    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bmp.Size)
    $cyan = 0
    for ($y = 0; $y -lt $height; $y += 6) {
        for ($x = 0; $x -lt $width; $x += 6) {
            $pixel = $bmp.GetPixel($x, $y)
            if ([Math]::Abs($pixel.R - 9) -lt 40 -and [Math]::Abs($pixel.G - 199) -lt 40 -and [Math]::Abs($pixel.B - 245) -lt 40) {
                $cyan++
            }
        }
    }
    $graphics.Dispose()
    $bmp.Dispose()
    Write-Host "PARITY_WINDOW width=$width height=$height"
    Write-Host "PARITY_CYAN samples=$cyan"
    if ($cyan -lt 8) {
        throw "the home screen did not paint the Android cyan token"
    }
}

$env:VERITAS_PARITY = "home"
$proc = Start-Process -FilePath $exe -PassThru
$deadline = (Get-Date).AddSeconds(60)
$opened = $false
try {
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            throw "VeritasVPN exited before showing a window (code $($proc.ExitCode))"
        }
        $proc.Refresh()
        $title = $proc.MainWindowTitle
        if ($title -eq "VeritasVPN" -and -not $opened) {
            Write-Host "WINDOW_OPEN title=$title pid=$($proc.Id)"
            $opened = $true
        }
        if ($title -eq "VeritasVPN parity home") {
            Write-Host "PARITY_HOME title=$title"
            Assert-ParityWindow $proc
            exit 0
        }
        Start-Sleep -Milliseconds 500
    }
    $proc.Refresh()
    throw "VeritasVPN parity home did not appear. MainWindowTitle='$($proc.MainWindowTitle)'"
} finally {
    if (-not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
}
