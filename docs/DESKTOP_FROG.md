# Desktop Eat the Frog

Eat the Frog is a FocusLock desktop policy. Its state is device-local and is **off by default**, including on legacy installs that contain an older enabled flag without completed setup. Every Windows device must complete the setup flow and use the explicit **Enable Eat the Frog** action once before the daily routine can auto-arm. Strict Mode does not bypass this opt-in.

## Essential access

Setup records the apps and website domains required for the user's work. The native Windows catalog supplies running applications; an executable may also be entered manually. Website entries are normalized to domains and open in Chrome. Unknown apps and sites remain unavailable while the Frog lock is active. Boundary and permanent-block targets always take precedence over Frog essentials, and an essential selection cannot weaken either policy.

The selected daily task may use only the saved essential app and domain set. Changing the task cannot expand that set. Task completion requires explicit completion plus the configured tracked focus time; timer expiry alone never completes the task.

## Emergency disable

The native desktop emergency shortcut is `Ctrl+Shift+Alt+F12`. It immediately disables and disarms the desktop Frog routine, stops its launcher session, and persists an emergency-disabled marker. The marker survives restart and daily rollover. It remains disabled until the user manually re-enables Frog from desktop Settings after setup. Emergency disable does not delete the selected task, erase tracked focus, remove boundaries or permanent blocks, or mark the task complete.

The shortcut is implemented by the FocusLock desktop host because it must control native enforcement and the launcher. The shared Void launcher remains a presentation and tool-launch layer; it has no standalone Frog policy or emergency equivalent. Standalone Void keeps its own settings and history while receiving shared launcher UI fixes through the normal parity workflow.

## Strict Mode and recovery

Strict Mode may prevent weakening an active Frog commitment, such as turning an enabled routine off through ordinary Settings or lowering its required focus. It does not create the initial opt-in, change the emergency shortcut, or decide which apps and websites are boundaries. Emergency recovery is a native escape from the Frog routine and leaves the stronger boundary and permanent-block policies intact.

Void provides the focused task launcher, progress display, approved-tool opening, and disconnect/recovery surface. FocusLock owns the Frog state, timing, access verdicts, boundary precedence, credits, and completion.

## Windows acceptance checklist

Build and fixture tests cover serialization, setup validation, policy composition, and launcher state. They do not prove native Windows enforcement. Real acceptance should be performed on an installed signed build with a signed-in account:

1. Complete desktop setup on a fresh device and confirm Frog remains off until the explicit enable action.
2. Configure essential apps and Chrome domains; verify an unlisted app/site is routed to the Frog launcher while an essential target remains reachable.
3. Start a real task, open approved tools, observe progress, tick the task off explicitly, and verify the required focus threshold releases the lock.
4. Trigger `Ctrl+Shift+Alt+F12`, restart FocusLock, and confirm Frog remains disabled while existing boundaries and permanent blocks still enforce.
5. Manually re-enable Frog in Settings and verify the next daily cycle can arm after the normal interaction/grace timing.
6. Disconnect or stop the launcher and verify the documented recovery behavior without treating a fixture or build-only result as native acceptance.
