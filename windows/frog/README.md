# FocusLock Frog desktop surface

Independent FocusLock-only launcher: black background, essential app icons, and Open FocusLock. Task selection, timer controls, and completion stay in the FocusLock desktop app. The host remains the policy owner.

Source lives entirely here; it is not part of the Void sync/backport workflow. Standalone Void is unchanged. Protocol and recovery support were forked from the existing integration so future Frog work can stay independent.

The compatibility executable name remains `FocusLock.Void.exe` so existing host packaging and process recovery continue to work. Build with `scripts/build-focuslock-void.ps1`.
