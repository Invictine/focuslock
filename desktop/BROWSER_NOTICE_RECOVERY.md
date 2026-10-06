# Browser repair notice recovery

Supported-browser repair notices are presentation-debounced for four seconds. The native monitor can detect a missing extension heartbeat before the extension's next two-second heartbeat arrives; the notice therefore remains hidden while more than 56 seconds of the original 60-second grace remains. The native close deadline is unchanged.

Once the notice becomes visible, it stays visible for that repair incident even if a close retry or settings reset restores the displayed countdown. A healthy sample clears the incident and hides the notice immediately, so a later outage starts with a fresh debounce. Unsupported browsers show a notice immediately because they cannot provide supported extension health.

This behavior belongs to FocusLock's native extension monitor and has no standalone Void equivalent.

## Transient browser menus

Some Chrome menu popups are separate visible HWNDs owned by the main browser window. The extension does not lease those transient HWNDs, so treating every visible browser-process window as a browser window can falsely report `extension_missing` while a menu is open. The monitor and bridge now discover only unowned interactive top-level windows, excluding child, tool and nonactivating windows. Fullscreen browser windows remain eligible when Windows removes their caption, and each real window still needs its own extension lease. Minimized-window handling remains at discovery, so existing pending close deadlines are preserved. This is also FocusLock native browser-monitor behavior and has no standalone Void equivalent.

The October 6, 2026 live regression reproduced the false warning with Chrome's menu open despite a fresh lease for its main window. After installing the correction, holding that same menu open for 20 seconds kept all 20 health samples connected and all 316 native-window visibility samples hidden. Native tests also create an actual unowned root and owned dialog to verify classification; exact per-window lease checks and the original close deadline remain covered by the browser bridge and guard tests.
