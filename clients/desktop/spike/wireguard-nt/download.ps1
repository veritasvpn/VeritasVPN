# Download the official WireGuardNT prebuilt and check its hashes.
# The DLL is not committed. The spike uses it unmodified, through the public API only.
$ErrorActionPreference = "Stop"

$ZipUrl = "https://download.wireguard.com/wireguard-nt/wireguard-nt-1.1.zip"
$ZipSha256 = "dceb30a9bc4be48cce0f74160fc88a585a2c2627366e8f846fc6658f9038dace"
$DllSha256 = "b1b85e072c45d81358be29d94c599dc76652f912be8c0f0a41e2d5d89a6461d3"
$DllInsideZip = "wireguard-nt/bin/amd64/wireguard.dll"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir = Join-Path $Root "build"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$ZipPath = Join-Path $OutDir "wireguard-nt-1.1.zip"
Write-Host "Downloading $ZipUrl"
Invoke-WebRequest -Uri $ZipUrl -OutFile $ZipPath -UseBasicParsing

$zipHash = (Get-FileHash -Algorithm SHA256 -Path $ZipPath).Hash.ToLowerInvariant()
if ($zipHash -ne $ZipSha256) {
    throw "wireguard-nt zip SHA-256 mismatch: got $zipHash"
}

$Extract = Join-Path $OutDir "extracted"
if (Test-Path $Extract) { Remove-Item -Recurse -Force $Extract }
Expand-Archive -Path $ZipPath -DestinationPath $Extract -Force

$DllPath = Join-Path $Extract ($DllInsideZip -replace "/", "\")
if (-not (Test-Path $DllPath)) {
    throw "amd64 wireguard.dll missing from the official zip"
}

$dllHash = (Get-FileHash -Algorithm SHA256 -Path $DllPath).Hash.ToLowerInvariant()
if ($dllHash -ne $DllSha256) {
    throw "wireguard.dll SHA-256 mismatch: got $dllHash"
}

Write-Host "SPIKE_DLL $DllPath"
Write-Host "SPIKE_DLL_SHA256 $dllHash"
