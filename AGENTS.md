# Project work

Use Luna subagents for well-scoped tasks they can complete reliably. Keep planning, difficult reasoning, and final integration with the primary agent. Preserve unrelated changes.

Inspect the actual Git root and initial status before editing. Reuse this repository, preserve history/remotes/visibility, and never create a nested repository. After a coherent feature or fix, run appropriate checks, review the diff, commit only task changes, and push the intended branch to its existing upstream. Never force-push or commit credentials, local data, caches, or generated output. Report any validation or push limitation.

## FocusLock Void and standalone Void

`windows/void/` contains the shared Windows launcher source, mirrored from the standalone `Invictine/Void` repository. FocusLock packages that source as `FocusLock.Void.exe`; standalone Void builds the same project as `Void.exe`.

**Every feature or fix added to FocusLock Void's shared launcher must also be backported to standalone Void in the same task, before reporting completion.** Do not leave two implementations drifting. Improvements applicable to standalone use must be reachable in standalone use; an unused copy of a class is not a feature backport. FocusLock-specific task, policy, account, and credit integration remains in the desktop host. If a change genuinely only concerns that host, document why it has no standalone equivalent.

1. Locate the standalone Void checkout and inspect its Git status. Do not overwrite unrelated work.
2. Update shared source and both relevant user flows. Reconcile concurrent changes manually before copying.
3. Use `node scripts/sync-void.mjs --void-root <standalone-checkout> --direction to-standalone --write` to backport reviewed source, or `--direction from-standalone --write` to import reviewed standalone improvements. The tool does not delete files; reconcile intentional removals manually.
4. Run the command without `--write` to require byte-for-byte parity of shared source, tests, packaging, and documentation.
5. Build/test both the standalone launcher and FocusLock's bundled launcher, plus desktop tests affected by the integration. Validate startup, approved-tool launching, timer/progress, task completion, and disconnect/recovery for native behavior changes.
6. Commit and push the completed task in both repositories. Report both commits and any blocked backport or native validation. Never claim completion while silently skipping the standalone backport.

The launcher is a presentation/tool-launch layer. FocusLock owns Frog state and blocking, including permanent blocks and browser boundaries. Never introduce a competing Void foreground guard in integrated mode, double-count work, auto-complete a task when a timer expires, or clear Frog policy on emergency exit. Standalone behavior, settings, and user history must remain available.
