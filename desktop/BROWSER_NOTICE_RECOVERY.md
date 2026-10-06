# Browser repair notice recovery

Supported-browser repair notices are presentation-debounced for four seconds. The native monitor can detect a missing extension heartbeat before the extension's next two-second heartbeat arrives; the notice therefore remains hidden while more than 56 seconds of the original 60-second grace remains. The native close deadline is unchanged.

Once the notice becomes visible, it stays visible for that repair incident even if a close retry or settings reset restores the displayed countdown. A healthy sample clears the incident and hides the notice immediately, so a later outage starts with a fresh debounce. Unsupported browsers show a notice immediately because they cannot provide supported extension health.

This behavior belongs to FocusLock's native extension monitor and has no standalone Void equivalent.
