# Silent per-machine install, a live service pipe, then a clean uninstall.
$ErrorActionPreference = "Stop"

$Desktop = Split-Path -Parent $PSScriptRoot
Set-Location $Desktop
$installDir = Join-Path ${env:ProgramFiles} "VeritasVPN"
$failed = $false

function Invoke-Bounded([string]$File, [string[]]$ArgumentList, [int]$Seconds) {
    $proc = Start-Process -FilePath $File -ArgumentList $ArgumentList -WindowStyle Hidden -PassThru
    if (-not $proc.WaitForExit($Seconds * 1000)) {
        taskkill /F /T /PID $proc.Id | Out-Null
        throw "deadline waiting for $File"
    }
    return $proc.ExitCode
}

function Invoke-Uninstall([int]$Seconds) {
    $un = Join-Path $installDir "uninstall.exe"
    if (-not (Test-Path $un)) { return 0 }
    # The starter copies itself to Temp and returns. The copy does the removal,
    # including this directory, so do not pass _?=.
    $proc = Start-Process -FilePath $un -ArgumentList "/S" -WindowStyle Hidden -PassThru
    if (-not $proc.WaitForExit(20000)) {
        taskkill /F /T /PID $proc.Id | Out-Null
        throw "deadline waiting for uninstall"
    }
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $svc = Get-Service -Name VeritasVPN -ErrorAction SilentlyContinue
        if (-not $svc -and -not (Test-Path $installDir)) { return 0 }
        Start-Sleep -Milliseconds 500
    }
    if ((Get-Service -Name VeritasVPN -ErrorAction SilentlyContinue) -or (Test-Path $installDir)) {
        throw "uninstall left the app behind"
    }
    return 0
}

function Remove-Installed {
    try { Invoke-Uninstall 90 | Out-Null } catch { }
    foreach ($image in @("veritasvpn-desktop.exe", "wstunnel.exe")) {
        $kill = Start-Process -FilePath "taskkill" -ArgumentList @("/F", "/IM", $image) -WindowStyle Hidden -PassThru
        if (-not $kill.WaitForExit(8000)) { taskkill /F /PID $kill.Id | Out-Null }
    }
    $svc = Join-Path $Desktop "src-tauri\target\release\veritas-service.exe"
    if (Test-Path $svc) {
        foreach ($cmd in @("repair", "stop", "uninstall")) {
            $proc = Start-Process -FilePath $svc -ArgumentList $cmd -WindowStyle Hidden -PassThru
            if (-not $proc.WaitForExit(20000)) { taskkill /F /PID $proc.Id | Out-Null }
        }
    }
}

try {
    $installerDir = Join-Path $Desktop "src-tauri\target\release\bundle\nsis"
    $installer = Get-ChildItem -Path $installerDir -Filter "*.exe" | Select-Object -First 1
    if (-not $installer) { throw "NSIS installer was not produced" }
    $code = Invoke-Bounded $installer.FullName @("/S") 180
    $hook = Join-Path $env:TEMP "veritas-install-hook.txt"
    if (Test-Path $hook) { Get-Content $hook | ForEach-Object { Write-Host "INSTALL_HOOK $_" } }
    if ($code -ne 0) { throw "installer exit $code" }
    Write-Host "INSTALL_SILENT"

    $running = $false
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        $svc = Get-Service -Name VeritasVPN -ErrorAction SilentlyContinue
        if ($svc -and $svc.Status -eq "Running") { $running = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $running) { throw "service did not reach Running" }
    Write-Host "INSTALL_SERVICE_RUNNING"

    $ui = Join-Path $installDir "veritasvpn-desktop.exe"
    if (-not (Test-Path $ui)) { throw "installed app is missing" }
    $marker = Join-Path $env:TEMP "veritasvpn-pipe-check.txt"
    if (Test-Path $marker) { Remove-Item $marker -Force }
    $code = Invoke-Bounded $ui @("--veritas-pipe-check") 20
    $text = ""
    if (Test-Path $marker) { $text = (Get-Content -Raw $marker).Trim() }
    if ($code -ne 0 -or $text -ne "PIPE_OK") { throw "pipe check failed" }
    Write-Host "PIPE_OK"

    if (-not (Test-Path (Join-Path $installDir "uninstall.exe"))) { throw "uninstaller is missing" }
    $code = Invoke-Uninstall 120
    if ($code -ne 0) { throw "uninstaller exit $code" }
    Write-Host "UNINSTALL_SILENT"

    $dllPath = Join-Path $Desktop "src-tauri\target\wireguard-nt\dll.path"
    $env:VERITAS_WIREGUARD_DLL = (Get-Content -Raw $dllPath).Trim()
    $probe = Join-Path $Desktop "src-tauri\target\debug\veritas-service.exe"
    if (-not (Test-Path $probe)) {
        $probe = Join-Path $Desktop "src-tauri\target\release\veritas-service.exe"
    }
    $log = Join-Path $env:TEMP "veritas-leftover.txt"
    $proc = Start-Process -FilePath $probe -ArgumentList "leftover" -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError (Join-Path $env:TEMP "veritas-leftover.err")
    if (-not $proc.WaitForExit(20000)) {
        taskkill /F /PID $proc.Id | Out-Null
        throw "deadline waiting for leftover"
    }
    $lines = @(Get-Content $log)
    $lines | ForEach-Object { Write-Host $_ }
    foreach ($need in @("LEFTOVER_SERVICE 0", "LEFTOVER_FILTERS 0", "LEFTOVER_ADAPTER 0")) {
        if ($lines -notcontains $need) { throw "missing $need" }
    }
    if (Test-Path $installDir) { throw "install directory remains" }
    Write-Host "INSTALL_GONE_FILES"
    $query = Start-Process -FilePath "sc.exe" -ArgumentList @("query", "VeritasVPN") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $env:TEMP "veritas-sc.txt") -RedirectStandardError (Join-Path $env:TEMP "veritas-sc.err")
    if (-not $query.WaitForExit(15000)) {
        taskkill /F /PID $query.Id | Out-Null
        throw "deadline waiting for sc"
    }
    if ($query.ExitCode -eq 0) { throw "service still exists" }
    Write-Host "INSTALL_GONE_SERVICE"
} catch {
    Write-Host $_.Exception.Message
    $failed = $true
} finally {
    Remove-Installed
}

if ($failed) { exit 1 }
