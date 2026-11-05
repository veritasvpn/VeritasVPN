; Per-machine install hooks. One elevation, from the NSIS per-machine mode.
; Signing is not done here. Cloud signing belongs in bundle.windows.signCommand
; once a real certificate exists. Do not point that at a stand-in.

!macro NSIS_HOOK_PREINSTALL
  ${If} ${IsNativeARM64}
    ${IfNot} ${Silent}
      MessageBox MB_ICONSTOP "VeritasVPN for Windows on ARM is not available yet."
    ${EndIf}
    Abort
  ${EndIf}
  ReadRegStr $0 HKLM "SOFTWARE\Microsoft\Windows NT\CurrentVersion" "CurrentBuildNumber"
  IntCmp $0 19045 veritas_build_ok veritas_build_ok veritas_build_old
  veritas_build_old:
    ${IfNot} ${Silent}
      MessageBox MB_ICONSTOP "VeritasVPN needs Windows 10 version 22H2 or Windows 11."
    ${EndIf}
    Abort
  veritas_build_ok:
  ExecWait 'taskkill /F /IM veritasvpn-desktop.exe' $0
  ExecWait 'taskkill /F /IM wstunnel.exe' $0
  IfFileExists "$INSTDIR\service\veritas-service.exe" 0 veritas_pre_done
    ExecWait '"$INSTDIR\service\veritas-service.exe" stop' $0
  veritas_pre_done:
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ExecWait '"$INSTDIR\service\veritas-service.exe" install --client "$INSTDIR\${MAINBINARYNAME}.exe" --dll "$INSTDIR\service\wireguard.dll" --helper "$INSTDIR\service\wstunnel.exe"' $0
  ${If} $0 <> 0
    ${IfNot} ${Silent}
      MessageBox MB_ICONSTOP "VeritasVPN could not finish installing. Try again."
    ${EndIf}
    Abort
  ${EndIf}
  ExecWait '"$INSTDIR\service\veritas-service.exe" start' $0
  ${If} $0 <> 0
    ${IfNot} ${Silent}
      MessageBox MB_ICONSTOP "VeritasVPN could not finish installing. Try again."
    ${EndIf}
    Abort
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ExecWait 'taskkill /F /IM veritasvpn-desktop.exe' $0
  ExecWait 'taskkill /F /IM wstunnel.exe' $0
  IfFileExists "$INSTDIR\service\veritas-service.exe" 0 veritas_un_done
    ExecWait '"$INSTDIR\service\veritas-service.exe" uninstall' $0
  veritas_un_done:
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ReadEnvStr $0 PROGRAMDATA
  ${If} $0 != ""
    RMDir /r "$0\VeritasVPN"
  ${EndIf}
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "VeritasVPN"
  ExecWait 'taskkill /F /IM wstunnel.exe' $0
!macroend
