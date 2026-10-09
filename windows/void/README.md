# Void

Void is a minimal Windows focus launcher. It starts as a small persistent control on your desktop; choose when to open the homepage or begin a focus block.

## Install

Download [Void-Setup-1.2.0.exe](https://github.com/Invictine/Void/releases/download/v1.2.0/Void-Setup-1.2.0.exe) from the [GitHub releases](https://github.com/Invictine/Void/releases/latest) and run it on Windows x64. The installer includes the .NET runtime, installs for your Windows account without administrator access, creates a Start Menu shortcut, and offers a desktop shortcut. Uninstall through Windows **Installed apps**. Your settings, recent presets, and focus history are kept when you uninstall.

Close Void before upgrading or uninstalling. **Ctrl+Alt+Shift+F12** immediately exits Void and restores the taskbar, even during a locked block.

## Desktop launcher

Opening Void shows a small **VOID** control near the bottom-right of the desktop without taking focus, hiding the taskbar, or changing your monitors. Drag its grip to move it. Click **VOID** to open the homepage, or the arrow for up to five recently used presets and **Quit Void**. Each preset shows its app and duration; its tooltip includes the saved **Ask other apps to close** choice. Clicking a preset starts a new block with those saved choices.

Recent presets are recorded only after a successful app launch and survive restarts. Removing an app or changing its launch target or arguments removes its old presets from the menu. Existing history does not include the full launch settings, so presets start accumulating from your next successful block.

Use **Back to desktop** on the homepage or after a completed block to return to the persistent launcher and restore the desktop. Escape from the homepage does the same. During a timed block the desktop launcher is hidden; the return and emergency shortcuts remain available.

## Focus blocks

Choose an app, enter **1–1440 minutes**, or choose a **15 / 25 / 50 minute** preset. **Ask other apps to close** can be changed for each block. Void starts the timer only after the chosen app launches successfully.

While your work app is active, Void stays behind it. Press **Ctrl+Alt+Space**, use **Alt+Tab**, or launch Void again to return to the session screen. It shows remaining time, progress, **Return to [app]**, and **Exit Focus**. Returning to the app never resets the timer.

When the timer ends, Void brings the completion screen forward once and plays an optional sound. Start another block, take a timed break, or return to the desktop launcher. Breaks use your configured duration and can end early. Normal focus exit stays locked until the block ends; **Back to desktop** appears when the timer ends. You can also hold Enter or the mouse button on **Exit Focus** for three seconds, then type `EXIT` to return to the desktop launcher. Escape cancels the exit prompt. The emergency shortcut remains available throughout.

Void asks other eligible app windows to close normally only when you enable that option. It never force-terminates them. Handle any save prompts normally; apps can refuse to close.

## Settings and apps

Open **Settings & apps** from the launcher to:

- Add, edit, remove, and reorder your work apps.
- Browse for an executable or choose an installed Windows app, including native ChatGPT.
- Set launch arguments, the launcher heading, default focus duration, and break duration.
- Enable the app guard, default close requests, completion sounds, automatic Windows startup, or primary-monitor mode.

Apply each app edit, then save settings. App and focus preference changes take effect immediately. Primary-monitor mode changes take effect when you next enter focus from the desktop launcher. The initial app choices include Chemistry (native ChatGPT), Physics (Chrome), Coding (VS Code), Editing (Premiere), and Writing (Notepad). Availability depends on what is installed; use the app manager to repair or replace a missing target.

The guard returns to Void when another app becomes foreground. Every window belonging to the chosen executable is allowed, including other Chrome profiles. Essential Windows interfaces and save dialogs remain usable. Windows notifications may still interrupt focus; Windows Do Not Disturb can silence them.

## History and local storage

The launcher shows today's completed blocks and focus minutes. **History** lists the latest 500 sessions, including blocks ended with the emergency shortcut, and exports them as CSV. Totals are grouped by the local date when a block started. Breaks are not counted as focus time.

Settings are in `%LOCALAPPDATA%\Void\config.json`; history is in `%LOCALAPPDATA%\Void\history.json`; recent presets are in `%LOCALAPPDATA%\Void\recent-presets.json`. They survive updates and uninstall. On the first launch, Void imports its adjacent `config.json` template into the user profile; later launches use the saved user settings. Existing user settings take precedence. Invalid files are reported and retained. App management can explicitly replace invalid settings when saved.

Sessions are not resumed after restarting Void. A forced process termination can lose the current history record. The running timer uses monotonic elapsed time, so changing the Windows clock does not shorten a block.

## FocusLock integration

Void is also the Windows Frog launcher inside FocusLock. The same source is mirrored at `windows/void/` in `Invictine/focuslock`; shared launcher improvements must ship in both repositories (see `AGENTS.md`). Standalone Void continues to use its own launcher, presets, settings, timers, history, and recovery without a FocusLock account.

FocusLock builds a separately named `FocusLock.Void.exe` and starts it with `--focuslock`. This mode receives versioned session snapshots over redirected standard input and emits user actions over redirected standard output. It shows the current Frog task, approved app/site shortcuts, the host's timer, and task completion controls. FocusLock owns all timing, work credit, Frog completion, and blocking. Finishing a timer does not automatically tick off the task, and closing the launcher does not clear FocusLock's boundaries.

Integrated mode shows a small draggable black countdown during the host's five-minute daily grace. At expiry it uses the same primary-monitor and taskbar recovery helpers as standalone Void, then shows a plain black task entry followed by essential app and website choices. Only the chosen shortcuts appear during focus. Escape or the emergency shortcut closes the surface and restores the display layout; FocusLock's blocking policy remains active. The focus screen remains reachable through Alt+Tab.

The daily interaction deadline and Frog selection/completion are FocusLock host features, so standalone Void has no daily account/task gate. Its existing black launcher, primary-monitor setting, presets, timers, and history remain available. Shared presentation, protocol and recovery changes are mirrored in both repositories; integrated mode never runs the standalone foreground guard or awards work credits itself.

The helper closes and restores the desktop when the host disconnects or stops sending snapshots. Its return and emergency shortcuts are best effort; conflicts with an already-running standalone Void are shown on screen, and display takeover is skipped when recovery shortcuts cannot be reserved. App shortcuts use observed Windows executable paths, saved mappings, or an explicitly located executable matching the approved app identity. Website shortcuts open HTTPS addresses; FocusLock's existing browser rules remain in effect.

For the integrated build, run `npm run void:build` from FocusLock's `desktop/` directory. For the standalone build, use the commands below. From the FocusLock root, check source parity with `node scripts/sync-void.mjs --void-root <standalone-checkout>` before committing either shared copy.

## Build the installer

Run from this folder on Windows:

```powershell
.\build-installer.ps1
```

The script locates a .NET SDK and Inno Setup compiler. When absent, it downloads a local .NET 8 SDK and a signed Inno Setup compiler into the ignored `work\tools` folder. The resulting self-contained Windows x64 installer is written to `outputs`. Use `-SkipToolBootstrap` to require preinstalled build tools.

For a development build and storage/timer checks:

```powershell
dotnet build -c Release
dotnet run --project tests\StorageTests.csproj
```

If the SDK was bootstrapped locally, use `.\work\tools\dotnet-sdk\dotnet.exe` in place of `dotnet`. Installer defaults use environment-variable paths and do not contain a hardcoded Windows username. Settings and session data are never included in the installer.

## Recovery and current limits

By default, Void switches Windows to the primary monitor when a focus block starts and restores the previous active display layout when you return to the desktop launcher or quit. This follows the primary desktop monitor rather than assuming the built-in panel. In a cloned layout, the first target in Windows display-path priority order is retained. Disable **Use only the primary monitor** in settings to leave additional monitors active. The switch is temporary and does not overwrite the saved Windows display configuration. If Windows cannot apply the requested layout, Void reports the error and continues without the switch. If a monitor is unplugged during a block, recovery asks Windows for a usable layout. The fullscreen Void surface itself covers the primary monitor. Taskbar hiding/restoration enumerates Windows taskbars. A separate recovery process restores taskbars and the saved display layout after the main app exits, including forced termination of that app. Display recovery snapshots are stored locally until restoration succeeds. Killing the entire process tree can also kill the recovery process.

An installer built locally is unsigned unless you add your own signing process.
