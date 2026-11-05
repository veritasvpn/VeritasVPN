# Download the official WireGuardNT build used by the Windows service.
# The DLL is not committed. It is loaded unmodified through its public API.
$ErrorActionPreference = "Stop"

$ZipUrl = "https://download.wireguard.com/wireguard-nt/wireguard-nt-1.1.zip"
$ZipSha256 = "dceb30a9bc4be48cce0f74160fc88a585a2c2627366e8f846fc6658f9038dace"
$DllSha256 = "b1b85e072c45d81358be29d94c599dc76652f912be8c0f0a41e2d5d89a6461d3"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir = Join-Path (Split-Path -Parent $Root) "src-tauri\target\wireguard-nt"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$ZipPath = Join-Path $OutDir "wireguard-nt-1.1.zip"

if (-not (Test-Path $ZipPath)) {
    Invoke-WebRequest -Uri $ZipUrl -OutFile $ZipPath -UseBasicParsing
}
$zipHash = (Get-FileHash -Algorithm SHA256 -Path $ZipPath).Hash.ToLowerInvariant()
if ($zipHash -ne $ZipSha256) { throw "wireguard-nt zip hash mismatch" }

$Extract = Join-Path $OutDir "extract"
if (Test-Path $Extract) { Remove-Item -Recurse -Force $Extract }
Expand-Archive -Path $ZipPath -DestinationPath $Extract -Force
$Dll = Join-Path $Extract "wireguard-nt\bin\amd64\wireguard.dll"
$dllHash = (Get-FileHash -Algorithm SHA256 -Path $Dll).Hash.ToLowerInvariant()
if ($dllHash -ne $DllSha256) { throw "wireguard.dll hash mismatch" }
Set-Content -Path (Join-Path $OutDir "dll.path") -Value $Dll -NoNewline
Write-Output $Dll
