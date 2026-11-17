# FocusLock

> **Earned leisure instead of brute-force guilt trips.** Cross-platform boundary enforcement across Android, Windows, and Chrome that turns deep work into unlocked recreation.

[![FocusLock Demo](assets/focuslock-focus.png)](demo/focuslock-android-demo.mp4)

*Click the preview card above to watch the Android demo video.*

---

## Why I Built This

Most app blockers treat you like a toddler. You set an arbitrary block schedule, get frustrated the first time you legitimately need to look something up, override the timer with a master PIN, and abandon the tool by Wednesday.

FocusLock replaces punitive blocking with an **earned leisure economy**. You lock in productive focus intervals (deep coding, writing, research), and the system credits you with guilt-free recreational minutes that you can spend however you choose. When your leisure pool hits zero, boundaries snap shut across your devices.

## Standout Features

- **Multi-Device Barrier:** Unified policy enforcement across Android (native accessibility overlay), Windows desktop, and Chrome browser extensions.
- **Earned Leisure Economy:** Focus minutes convert dynamically into leisure credits based on configurable difficulty ratios.
- **Tamper-Resistant Guard:** Multi-window protection and protected background services prevent accidental or impulsive uninstalls during active focus sprints.
- **Zero Cloud Leakage:** All usage telemetry and focus timers operate locally with encrypted state sync across paired local devices.

## Visual Walkthrough & Demo

| Interface Overview | Android Native Demo |
| :--- | :--- |
| ![Interface](assets/focuslock-focus.png) | ![Boundary Overlay](assets/focuslock-boundaries.png) |

Watch the recorded Android interaction: **[demo/focuslock-android-demo.mp4](demo/focuslock-android-demo.mp4)**.

## Setup & Building

### Android Application
```bash
# Build Android debug APK
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Chrome Extension
1. Open `chrome://extensions/`
2. Enable **Developer mode**
3. Click **Load unpacked** and select the `extension/` directory.

### Windows Agent
```bash
cd desktop
dotnet build -c Release
dotnet run --project FocusLock.Desktop
```
