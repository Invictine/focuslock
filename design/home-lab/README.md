# Five Android homepage studies

Open **gallery.html** in any modern browser. It contains the five designs, fonts,
icons, styles, and demo interactions in one file and works offline.

Alternatively, from the repository root:

```powershell
node design/home-lab/serve.mjs
```

Open http://127.0.0.1:4175. The server binds only to the local machine and serves
only this folder. Press Ctrl+C to stop it.

Choose Halo, Daybook, Signal, Mosaic, or Flow. **Compare all five** puts their
complete scrollable homepages together. Preview controls cover narrow screens,
larger text, a normal day, loading, no data, missing permissions, task errors,
disconnected TickTick, and leisure ahead of focus. Every phone has the same
sample-day dataset; local demo edits belong to that concept. Reset restores all.

Try Start focus, Log work, task checkboxes, Eat the Frog, history expansion,
the focus-goal setting, and the navigation destinations. These are local
interaction demonstrations; this folder has no live app connection, account
authentication, device permissions, enforcement, sync, or writes to TickTick.

The Android app has not been changed. See **DESIGN.md** for the design reasoning,
function mapping, and a Compose conversion contract.

## Rebuild or verify

```powershell
node design/home-lab/bundle.mjs
node design/home-lab/verify.mjs
node design/home-lab/verify-offline.mjs
```

The bundler uses the checkout's existing extension esbuild dependency. Browser
verification uses the Playwright runtime already available on this workstation.
The fixture dates intentionally use 30 September 2026 for a repeatable comparison.

Fonts are DM Sans and Space Grotesk under the SIL Open Font License. Icons are
from Lucide. Their licenses are included in **assets/**. **fetch-assets.mjs**
refreshes the local assets; it is not needed to view the gallery.
