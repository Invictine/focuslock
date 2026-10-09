# Shared Void launcher

Use Luna subagents for well-scoped work they can reliably complete. Preserve unrelated changes. Inspect Git status/root before editing; run appropriate checks, review the diff, commit task changes, and push the intended branch to its existing upstream after a coherent milestone. Never force-push, create a nested repository, or commit credentials, user data, caches, or generated outputs.

## Required feature backports

This Windows launcher is shipped both by the standalone `Invictine/Void` repository and inside `Invictine/focuslock` at `windows/void/`.

**Every shared launcher feature or fix added to FocusLock Void must be backported to standalone Void in the same task before reporting completion.** Standalone improvements must also be imported into FocusLock's shared copy. Implement applicable behavior in both user flows; copying unreachable code does not count as a feature backport. Host-specific Frog policy, task/account sync, and credit integration stays in FocusLock's desktop host; document why a host-only change has no standalone equivalent.

- Inspect both working trees. Reconcile unrelated/concurrent edits manually before mirroring; never overwrite them.
- From the FocusLock root, use `node scripts/sync-void.mjs --void-root <standalone-checkout> --direction to-standalone --write` for a reviewed backport, or `--direction from-standalone --write` for a reviewed standalone import.
- Run the same command without `--write` to verify shared source, tests, packaging, and documentation match. Intentional removed files must be reconciled manually; the tool never deletes files.
- Build/test standalone Void and the FocusLock helper, plus affected desktop tests. Native changes require checking approved app launching, timing, task completion, and recovery/disconnection.
- Commit and push the task in both repositories. Report both commits, and explicitly report any backport or live-validation limitation.

Standalone uses `Void.exe`; FocusLock builds this project with `AssemblyName=FocusLock.Void` and launches `--focuslock` using a versioned stdin/stdout protocol. Integrated mode delegates all blocking, timing, Frog state, and credit to the host. Do not run a competing foreground guard, auto-tick a task when its timer ends, or clear host policy on emergency exit. Preserve standalone settings, history, launcher presets, and recovery behavior.
