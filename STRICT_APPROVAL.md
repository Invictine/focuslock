# Strict Mode email approval configuration

The approval flow is intentionally server mediated. Configure these Convex environment variables before enabling email approval:

- `RESEND_API_KEY`: Resend API key, stored as a Convex secret.
- `RESEND_FROM_EMAIL`: verified sender address, for example `FocusLock <noreply@example.com>`.
- `STRICT_APPROVAL_REVIEW_URL_BASE`: the public HTTPS origin serving `convex/http.ts` (without a trailing slash).

The requester must be authenticated and must have configured one guardian before starting Strict Mode. The guardian email is immutable while the active strict commitment is in progress. A request is bound to `strictSessionId` and the exact `strictEndsAt`; tokens are 256-bit random values, stored only as SHA-256 hashes, expire after 30 minutes, and can be consumed once. `GET /strict-approval?token=...` only renders a form because email scanners prefetch GET links. `POST` performs the atomic approval.

Email provider failures are persisted as `failed` and returned as `sent: false`; the system never reports a message as sent unless Resend returns a successful response. Approval writes `strictApprovedSessionId`, `strictApprovedEndsAt`, and `strictApprovedAt` on `userPrefs`. It does not alter permanent blocks or directly expose a public approve mutation.

The review origin is normally `https://<deployment>.convex.site`, not the `.convex.cloud` API address. Deploy the updated Convex functions before running the new clients. Keep the email credentials on the server; never put them in Android BuildConfig, desktop Vite variables, or extension storage. No real email is sent as part of the automated tests.

References: [Convex HTTP actions](https://docs.convex.dev/functions/http-actions), [Convex runtimes](https://docs.convex.dev/functions/runtimes), [Resend idempotency keys](https://resend.com/changelog/idempotency-keys).
