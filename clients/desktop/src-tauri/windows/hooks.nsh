; Per-machine install hooks. One elevation, from the NSIS per-machine mode.
; Signing is not done here. Cloud signing belongs in bundle.windows.signCommand
; once a real certificate exists. Do not point that at a stand-in.

; After taskkill, wait until the image is really gone. Tauri's installer
; runs CheckIfAppIsRunning immediately after this hook. If the process is
; still exiting, Restart Manager lists it, "Click OK to kill it" runs
; RmShutdown, that call fails on an already-dead PID, and the installer
; aborts with "Failed to kill VeritasVPN. Please close it first then try
; again". 30 tries of 500 ms is 15 seconds. A process that is already
; gone returns immediately so a user-launched app that was closed first
; is unchanged.
!macro WaitUntilImageGone image tag attempts
  StrCpy $R8 0
  veritas_wait_${tag}:
    nsExec::ExecToStack 'cmd /c tasklist /NH /FI "IMAGENAME eq ${image}" | find /I "${image}"'
    Pop $R7
    Pop $R6
    ; find exits 0 when the image is still listed, 1 (or anything else) when it is gone.
    ${If} $R7 != 0
      Goto veritas_waited_${tag}
    ${EndIf}
    IntOp $R8 $R8 + 1
    ${If} $R8 >= ${attempts}
      Goto veritas_waited_${tag}
    ${EndIf}
    Sleep 500
    Goto veritas_wait_${tag}
  veritas_waited_${tag}:
!macroend

!macro StopDesktopAndHelper
  ExecWait 'taskkill /F /IM veritasvpn-desktop.exe' $0
  ExecWait 'taskkill /F /IM wstunnel.exe' $0
  !insertmacro WaitUntilImageGone "veritasvpn-desktop.exe" desktop 30
  !insertmacro WaitUntilImageGone "wstunnel.exe" helper 10
!macroend

!macro NSIS_HOOK_PREINSTALL
  FileOpen $9 "$TEMP\veritas-install-hook.txt" w
  FileWrite $9 "preinstall$\n"
  ${If} ${IsNativeARM64}
    FileWrite $9 "arm$\n"
    FileClose $9
    ${IfNot} ${Silent}
      MessageBox MB_ICONSTOP "VeritasVPN for Windows on ARM is not available yet."
    ${EndIf}
    Abort
  ${EndIf}
  ReadRegStr $0 HKLM "SOFTWARE\Microsoft\Windows NT\CurrentVersion" "CurrentBuildNumber"
  FileWrite $9 "build=$0$\n"
  FileClose $9
  ${If} $0 < 19045
    ${IfNot} ${Silent}
      MessageBox MB_ICONSTOP "VeritasVPN needs Windows 10 version 22H2 or Windows 11."
    ${EndIf}
    Abort
  ${EndIf}
  !insertmacro StopDesktopAndHelper
  IfFileExists "$INSTDIR\service\veritas-service.exe" 0 veritas_pre_done
    ExecWait '"$INSTDIR\service\veritas-service.exe" stop' $0
    ; Disconnect cleanly before the files are replaced. A service older than
    ; this hook has no reset command; it prints its usage and changes nothing,
    ; and the new service's reset below does the work.
    ExecWait '"$INSTDIR\service\veritas-service.exe" reset' $0
  veritas_pre_done:
  ; Confirm the window finished exiting after stop/reset as well, in case a
  ; brief restart raced with the first wait, then tell the hook log.
  !insertmacro WaitUntilImageGone "veritasvpn-desktop.exe" desktop2 10
  FileOpen $9 "$TEMP\veritas-install-hook.txt" a
  FileSeek $9 0 END
  ${If} $R7 != 0
    FileWrite $9 "desktop_gone$\n"
  ${Else}
    FileWrite $9 "desktop_still_running$\n"
  ${EndIf}
  FileClose $9
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; A reinstall or upgrade while connected must come up disconnected. Without
  ; this the new service found the old session on disk, resumed the tunnel,
  ; and the new window showed Not connected with every route still in it.
  ; Best effort: a failure here must not stop the install.
  ExecWait '"$INSTDIR\service\veritas-service.exe" reset' $0
  FileOpen $9 "$TEMP\veritas-install-hook.txt" a
  FileSeek $9 0 END
  FileWrite $9 "reset=$0$\n"
  FileClose $9
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
  !insertmacro StopDesktopAndHelper
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
