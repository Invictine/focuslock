; Always gate removal, including silent uninstall and installer /UPDATE calls.
; The probe runs before single-instance handling, window creation, or recovery.
!macro NSIS_HOOK_PREUNINSTALL
  Push $R8
  ClearErrors
  ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --focuslock-check-uninstall' $R8
  ${If} ${Errors}
    StrCpy $R8 11
  ${EndIf}
  ${If} $R8 <> 0
    ${If} $R8 = 10
      MessageBox MB_OK|MB_ICONEXCLAMATION "FocusLock cannot be uninstalled while Strict Mode is active. Wait until your commitment ends or use your configured guardian approval." /SD IDOK
    ${Else}
      MessageBox MB_OK|MB_ICONSTOP "FocusLock could not verify Strict Mode. Open FocusLock and try again after its uninstall protection is available." /SD IDOK
    ${EndIf}
    Pop $R8
    SetErrorLevel 1
    Quit
  ${EndIf}
  ; Stop this installation's recovery before Tauri removes its executable.
  ; The Strict Mode probe above must succeed before cleanup can run.
  !ifdef BUNDLEID
    ClearErrors
    ${If} $UpdateMode = 1
      ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "$INSTDIR\installer\cleanup.ps1" -InstallDir "$INSTDIR" -Upgrade' $R8
    ${Else}
      ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "$INSTDIR\installer\cleanup.ps1" -InstallDir "$INSTDIR"' $R8
    ${EndIf}
    ${If} ${Errors}
      StrCpy $R8 1
    ${EndIf}
    ${If} $R8 <> 0
      MessageBox MB_OK|MB_ICONSTOP "FocusLock could not stop its background recovery. No application files were removed. Try uninstalling again." /SD IDOK
      Pop $R8
      SetErrorLevel 1
      Quit
    ${EndIf}
  !endif
  Pop $R8
!macroend

; Reconcile shortcuts after every install/update path. Tauri's generated
; section skips shortcut creation during updates and only creates a desktop
; link for silent/passive installs, which leaves normal installs and updates
; without a discoverable launch path when links were deleted.
!macro NSIS_HOOK_POSTINSTALL
  ${If} $NoShortcutMode <> 1
    Push $UpdateMode
    StrCpy $UpdateMode 0
    Call CreateOrUpdateStartMenuShortcut
    Call CreateOrUpdateDesktopShortcut
    Pop $UpdateMode
  ${EndIf}
!macroend
