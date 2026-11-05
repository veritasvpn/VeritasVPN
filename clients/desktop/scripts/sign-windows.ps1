# Authenticode hook for a later cloud certificate (SSL.com eSigner or similar).
# Wire it by setting bundle.windows.signCommand in tauri.windows.conf.json.
# %1 is the file Tauri asks to sign. This script never creates a certificate
# and never reports a file as signed.
$ErrorActionPreference = "Stop"
Write-Host "SIGN_SKIPPED"
exit 0
