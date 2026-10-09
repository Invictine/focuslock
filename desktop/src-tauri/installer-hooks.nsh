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
  Pop $R8
!macroend
