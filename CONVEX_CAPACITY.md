# Convex capacity estimate

Checked against Convex's official limits on 2026-09-30. The Free deployment limits are 1,000,000 function calls/month, 0.5 GB total database storage, and 1 GB/month database I/O. Storage includes rows and indexes; Convex describes each index as another copy of its table. Database I/O counts document and index bytes transferred between functions and the database. [Convex limits](https://docs.convex.dev/production/state/limits)

The calculator at [`scripts/estimate-convex-capacity.mjs`](scripts/estimate-convex-capacity.mjs) turns explicit workload assumptions into monthly budgets. Run the defaults with:

```sh
node scripts/estimate-convex-capacity.mjs
```

It prints separate **FIT** or **FAIL** results for calls, storage, and I/O, then a scenario result. Those labels describe only the supplied model; they do not certify production capacity.

## Model inputs

Defaults use 1,000 daily active users, 30 days/month, 2 and 3 devices/user, a full-state pull and upload every four hours, 20 target buckets/device/day, 30 days of detailed usage, and 12 months of compact archives. The four-hour Android pull bundles today's usage and known-target catalog reads. One Android device/user is assumed. Desktop reactive query frequency, sign-in/config traffic, retries, and usage outside these endpoints are excluded.

The input row size defaults to 341 bytes, the mean JSON serialization size from one validated snapshot of 318 `deviceUsage` rows. This is a small sample of serialized row data, not a measurement of Convex storage or database I/O. The default 1.25 row factor is an adjustable overhead estimate; set `--row-overhead-multiplier=3` for a conservative stress case. The model counts the current three custom `deviceUsage` indexes and two built-in indexes as full row-size copies for storage, which is conservative; actual billing for this application's rows has not been measured. Index entries use a configurable 60-byte estimate for I/O. Convex documents that document writes create one entry for each index, including the built-in `by_id` and `by_creation_time` indexes; changing an indexed field can add another stale-entry write. [Convex log-stream usage fields](https://docs.convex.dev/production/integrations/log-streams)

All clients filter dirty counters before upload, so default upload reads are based on the assumed 25% dirty target fraction. `--changed-bucket-fraction` adjusts that fraction. Unchanged retransmits have their own `--noop-upload-fraction` input, defaulting to zero; it adds exact lookup reads but no writes. The model includes the catalog row lookup and two row-sized write estimates for every changed bucket. It models retention as a daily global cleanup paged at 100 expired rows, retaining 30 detailed days (the server cutoff includes a one-day timezone buffer) before folding expired data into monthly per-user archives. Each archive estimate includes daily totals and per-target/device monthly counters. For I/O, it assumes one archive document lookup and update per active user/day; actual batching can change this cost. Archive document sizes, 150-byte catalog rows, and the 10,000-byte per-user configuration estimate are assumptions, not measured Convex sizes. `--archive-months=12` means twelve months of already stored archive history in the estimate, not an archive retention cap. If old month documents remain indefinitely, the monthly growth line estimates the added bytes for each new archive month; if old months are pruned, net growth will be lower. The model assumes the catalog target count stays constant, so newly discovered targets add more storage than shown.

## Default result

At 1,000 DAU, two devices/user stays under the modeled call cap at 732,000 calls/month, but estimates 3.64 GB stored and 10.07 GB/month database I/O after dirty-client filtering. Three devices/user estimates 1.098 million calls, 5.40 GB stored, and 15.05 GB/month I/O. Both scenarios fail the modeled Free storage and I/O budgets; the three-device case also exceeds the call cap. The conclusion is that the current model does not support a claim that this workload fits Free; lower targets or DAU reduce the estimate, but a 1,000-DAU commitment needs measured production usage.

A smaller example, `node scripts/estimate-convex-capacity.mjs --dau=100 --devices=1 --targets-per-day=5`, returns FIT for the modeled resources: 36,150 calls, 71 MB stored, and 157 MB/month I/O. This is a scenario comparison, not a promise that a live deployment will fit. Actual usage depends on target activity, row/index sizes, query behavior, client state, and background work.

The key design pressure is sustained database I/O as well as calls. At the defaults, four-hour uploads perform exact reads only for dirty buckets, Android snapshots scan today's bucket rows and materialized catalog entries, and the retention worker reads/deletes expired detail while updating archive documents. A materialized catalog avoids rescanning full raw history for target discovery. Device display names and static device profiles are separate from heartbeat state so heartbeats do not invalidate usage summaries. These changes do not make the 1,000-DAU estimate fit Free by themselves.

## Implementation and live measurement status

The configured development deployment's functions and schema were prepared successfully on 2026-09-30. A pre-retention database snapshot is [archived here](build/convex-before-storage-20260930.zip) with SHA-256 `68AB41564C106D9AB8ED83F7CF4DC4916956F16DD313996A3F71B809C5AC5D38`. ZIP integrity was verified. This records a recovery point; it does not stand in for a live acceptance test.

Live read-only capacity queries could not be completed: the deployment was disabled by its quota, and an inline row-size query was denied. The 341-byte sample is therefore JSON serialization evidence only. No actual storage, I/O, or quota usage measurements are claimed here.

## Work before making a capacity claim

1. Verify the implemented materialized catalog, exact lookups, and delta-write I/O in a live deployment.
2. Verify the implemented 30-day detail compaction and catalog migration live; monthly archives remain indefinitely and still consume increasing storage.
3. Move summary and statistics work to local aggregation where server data is not required for cross-device correctness.
4. Measure representative pulls, uploads, catalog reads, retention pages, and archive updates against live Convex usage metrics. Convex exposes per-function database read/write I/O and written index-row counts in log streams. [Usage metrics](https://docs.convex.dev/production/integrations/log-streams)

The Free-plan numbers can change. Recheck the official [limits page](https://docs.convex.dev/production/state/limits) before planning around them; usage limits are also available as deployment guardrails, with monthly windows using UTC calendar months. [Usage limits](https://docs.convex.dev/production/usage-limits)
