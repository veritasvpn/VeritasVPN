# Download the pinned wstunnel 10.6.2 Windows build. The exe is not committed.
$ErrorActionPreference = "Stop"

$Version = "10.6.2"
$ArchiveUrl = "https://github.com/erebe/wstunnel/releases/download/v$Version/wstunnel_${Version}_windows_amd64.tar.gz"
$ArchiveSha256 = "3a88e9533845fdd377c79f5aa61f9eb7cedbfc26639533e7b944e317408f1c3b"
$ExeSha256 = "e41b00423da049fd1395b456e422d3c806c9c8c4108eb4ea3e988cbeec493db9"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir = Join-Path (Split-Path -Parent $Root) "src-tauri\target\wstunnel"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Archive = Join-Path $OutDir "wstunnel_$Version`_windows_amd64.tar.gz"
if (-not (Test-Path $Archive)) {
    Invoke-WebRequest -Uri $ArchiveUrl -OutFile $Archive -UseBasicParsing
}
$archiveHash = (Get-FileHash -Algorithm SHA256 -Path $Archive).Hash.ToLowerInvariant()
if ($archiveHash -ne $ArchiveSha256) { throw "wstunnel archive hash mismatch" }

$Extract = Join-Path $OutDir "extract"
if (Test-Path $Extract) { Remove-Item -Recurse -Force $Extract }
New-Item -ItemType Directory -Force -Path $Extract | Out-Null
tar -xzf $Archive -C $Extract
$Exe = Join-Path $Extract "wstunnel.exe"
if (-not (Test-Path $Exe)) { throw "wstunnel.exe missing from the archive" }
$exeHash = (Get-FileHash -Algorithm SHA256 -Path $Exe).Hash.ToLowerInvariant()
if ($exeHash -ne $ExeSha256) { throw "wstunnel.exe hash mismatch" }
Set-Content -Path (Join-Path $OutDir "wstunnel.path") -Value $Exe -NoNewline
Write-Output $Exe
