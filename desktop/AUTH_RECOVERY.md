# Desktop sign-in recovery

The Windows desktop uses Clerk's public-client OAuth flow with PKCE in the
system browser. The native host refreshes the saved account before requesting
a sync token. See [Clerk's public-client documentation](https://clerk.com/docs/guides/configure/auth-strategies/oauth/how-clerk-implements-oauth#public-clients-and-pkce).

A token endpoint response of HTTP 400 with the OAuth error `invalid_grant`
clears only the unusable saved authentication session and requests a fresh
browser sign-in. Network failures, unexpected responses, and client configuration
errors retain the local account and display an error. Provider response bodies
and credentials are never included in diagnostics.

Both Convex's token callback and direct sync requests handle failed exchanges
without an unhandled rejection. A failed browser sign-in remains visible instead
of being erased by an account-state refresh. Local boundaries, activity, and
queued work remain separate from the authentication session.

Regression checks: native `cargo test` and root
`npm test -- --run tests/desktop-auth.test.ts`, followed by desktop `npm run build`.
A new browser sign-in still requires normal user authentication and consent.

This change belongs to FocusLock's desktop account host. Standalone Void has no
Clerk/Convex account flow, and the shared launcher source is unaffected.
