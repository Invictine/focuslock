# FocusLock visual system

The Android app is the reference for content and navigation. All clients use the same fixed dark theme: T3 Code-inspired charcoal surfaces, Android's muted rose emphasis, quiet separators, and native sans-serif text. Wallpaper and OS appearance do not change the brand palette.

## Source of truth

Edit `theme.json`, then run `npm run theme:generate` from the repository root. It generates the Windows and extension CSS variables and Android `FocusLockPalette`. `npm run theme:check` detects drift. Commit generated outputs so each platform also builds independently.

Use semantic colors in screens: `surfaceContainer` for panels, `onSurface` for main text, `onSurfaceVariant` for supporting text, and paired `primary` / `onPrimary` colors for primary actions. Error and success colors communicate state, with accompanying text or icons.

## Structure and behavior

- Keep Focus, Boundaries, Settings, and Account in that order. Chrome-specific scheduling remains reachable inside Focus.
- Use a compact sidebar for wide Windows/browser windows, and native bottom navigation on Android. Preserve destinations when the window narrows.
- Focus prioritizes leisure balance and focus actions, screen time, today's totals, and recent work. Chrome reports the capabilities and usage available to its extension.
- Use 20px/dp major panels, 12px/dp controls, restrained borders, and tabular numerals. Adapt spacing to pointer or touch use; Android targets remain at least 48dp.
- Pair controls with visible focus, hover, pressed, selected, and disabled states. Respect reduced motion. Keep status, loading, sign-in, and blocking screens in the same palette.
- Do not substitute fixture data for real tracking, authentication, or synchronization. UI previews using fixtures are verification only.
