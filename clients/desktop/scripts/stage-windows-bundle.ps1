# Place the release service, pinned wireguard.dll, and pinned wstunnel.exe
# where the Windows installer picks them up. The binaries are not committed.
$ErrorActionPreference = "Stop"

$Desktop = Split-Path -Parent $PSScriptRoot
Set-Location $Desktop

function Invoke-Bounded([string]$File, [string[]]$ArgumentList, [int]$Seconds) {
    $proc = Start-Process -FilePath $File -ArgumentList $ArgumentList -WorkingDirectory $Desktop -NoNewWindow -PassThru
    if (-not $proc.WaitForExit($Seconds * 1000)) {
        taskkill /F /T /PID $proc.Id | Out-Null
        throw "deadline waiting for $File"
    }
    if ($proc.ExitCode -ne 0) {
        throw "$File exited $($proc.ExitCode)"
    }
}

# These already ran under pwsh in the compile step. Run them again in this
# host so Get-FileHash stays available. Windows PowerShell launched from
# Start-Process does not see that cmdlet on the runner.
& (Join-Path $PSScriptRoot "bundle-wireguard-nt.ps1") | Out-Null
& (Join-Path $PSScriptRoot "bundle-wstunnel-windows.ps1") | Out-Null
Invoke-Bounded "cargo" @(
    "build", "--manifest-path", "src-tauri/Cargo.toml", "--locked",
    "-p", "veritas-service", "--release"
) 900

$dest = Join-Path $Desktop "src-tauri\target\windows-bundle"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item (Join-Path $Desktop "src-tauri\target\release\veritas-service.exe") (Join-Path $dest "veritas-service.exe") -Force
$dll = (Get-Content -Raw (Join-Path $Desktop "src-tauri\target\wireguard-nt\dll.path")).Trim()
$helper = (Get-Content -Raw (Join-Path $Desktop "src-tauri\target\wstunnel\wstunnel.path")).Trim()
Copy-Item $dll (Join-Path $dest "wireguard.dll") -Force
Copy-Item $helper (Join-Path $dest "wstunnel.exe") -Force
Write-Host "BUNDLE_STAGED"
