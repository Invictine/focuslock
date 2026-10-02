#!/usr/bin/env node
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const GB = 1000 ** 3;
export const DEFAULTS = Object.freeze({
  dau: 1000, devices: [2, 3], daysPerMonth: 30, pullHours: 4, uploadHours: 4,
  targetsPerDay: 20, historyDays: 30, archiveMonths: 12, bucketJsonBytes: 341,
  rowOverheadMultiplier: 1.25, deviceUsageCustomIndexes: 3, builtinIndexes: 2,
  lookupIndexBytes: 60, catalogRowBytes: 150, catalogCustomIndexes: 1,
  changedBucketFraction: 0.25, noopUploadFraction: 0, androidDevicesPerUser: 1, cleanupPageSize: 100,
  archiveBaseBytes: 160, archiveTargetBytes: 180, archiveDayBytes: 48,
  archiveTargetsPerDeviceMonth: 20, archiveCustomIndexes: 1, configBytesPerUser: 10_000,
  callsCap: 1_000_000, storageCapBytes: 0.5 * GB, ioCapBytes: GB,
});

function nonNegative(value, name) {
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0) throw new Error(`${name} must be non-negative`);
  return n;
}
function positive(value, name) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) throw new Error(`${name} must be positive`);
  return n;
}

function validate(input) {
  const o = { ...DEFAULTS, ...input };
  o.devices = Array.isArray(o.devices) ? o.devices : [o.devices];
  for (const k of ["dau", "daysPerMonth", "pullHours", "uploadHours", "targetsPerDay", "historyDays", "archiveMonths", "bucketJsonBytes", "rowOverheadMultiplier", "callsCap", "storageCapBytes", "ioCapBytes"]) o[k] = positive(o[k], k);
  for (const k of ["deviceUsageCustomIndexes", "builtinIndexes", "lookupIndexBytes", "catalogRowBytes", "catalogCustomIndexes", "androidDevicesPerUser", "cleanupPageSize", "archiveBaseBytes", "archiveTargetBytes", "archiveDayBytes", "archiveTargetsPerDeviceMonth", "archiveCustomIndexes", "configBytesPerUser"]) o[k] = nonNegative(o[k], k);
  o.changedBucketFraction = nonNegative(o.changedBucketFraction, "changedBucketFraction");
  if (o.changedBucketFraction > 1) throw new Error("changedBucketFraction must be between 0 and 1");
  o.noopUploadFraction = nonNegative(o.noopUploadFraction, "noopUploadFraction");
  if (o.noopUploadFraction > 1 || o.changedBucketFraction + o.noopUploadFraction > 1) throw new Error("changedBucketFraction plus noopUploadFraction must be between 0 and 1");
  if (!o.devices.length) throw new Error("devices must include at least one scenario");
  o.devices = o.devices.map((n) => positive(n, "devices"));
  for (const k of ["dau", "daysPerMonth", "targetsPerDay", "historyDays", "archiveMonths", "deviceUsageCustomIndexes", "builtinIndexes", "catalogCustomIndexes", "androidDevicesPerUser", "cleanupPageSize", "archiveCustomIndexes", "archiveTargetsPerDeviceMonth"]) if (!Number.isInteger(o[k])) throw new Error(`${k} must be an integer`);
  if (o.devices.some((n) => !Number.isInteger(n))) throw new Error("devices must be an integer");
  if (o.devices.some((n) => o.androidDevicesPerUser > n)) throw new Error("androidDevicesPerUser cannot exceed a devices scenario");
  return o;
}

/** Model only: FIT/FAIL compares this deterministic estimate with caps. */
export function calculateCapacity(input = {}) {
  const o = validate(input);
  const results = o.devices.map((devicesPerUser) => {
    const { dau, daysPerMonth } = o;
    const pulls = dau * devicesPerUser * daysPerMonth * (24 / o.pullHours);
    const uploads = dau * devicesPerUser * daysPerMonth * (24 / o.uploadHours);
    // Android getSnapshot bundles today's usage and the target catalog into each pull.
    const androidSnapshots = dau * o.androidDevicesPerUser * daysPerMonth * (24 / o.pullHours);
    const usageRows = dau * devicesPerUser * o.targetsPerDay * o.historyDays;
    const monthlyNewUsageRows = dau * devicesPerUser * o.targetsPerDay * daysPerMonth;
    const deviceDoc = o.bucketJsonBytes * o.rowOverheadMultiplier;
    const deviceIndexes = o.deviceUsageCustomIndexes + o.builtinIndexes;
    const catalogRows = dau * devicesPerUser * o.targetsPerDay;
    const catalogDoc = o.catalogRowBytes * o.rowOverheadMultiplier;
    const catalogIndexes = o.catalogCustomIndexes + o.builtinIndexes;
    const archiveJson = o.archiveBaseBytes + devicesPerUser * o.archiveTargetsPerDeviceMonth * o.archiveTargetBytes + daysPerMonth * o.archiveDayBytes;
    const archiveDoc = archiveJson * o.rowOverheadMultiplier;
    const archiveIndexes = o.archiveCustomIndexes + o.builtinIndexes;
    const archiveDocs = dau * o.archiveMonths;

    const storageBytes = usageRows * deviceDoc * (1 + deviceIndexes) +
      catalogRows * catalogDoc * (1 + catalogIndexes) +
      archiveDocs * archiveDoc * (1 + archiveIndexes) + dau * o.configBytesPerUser;
    // Recent rows churn within the 30-day window; steady-state retained usage
    // does not grow from that churn. Only the new compact month archives add
    // persistent bytes under this simplified target-count assumption.
    const monthlyStorageGrowthBytes = dau * archiveDoc * (1 + archiveIndexes);

    const bucketRead = deviceDoc + o.lookupIndexBytes;
    // Client dirty filtering limits normal uploads to changed buckets. A
    // separate fraction models unchanged snapshots retransmitted on retry.
    const uploadReadBytes = uploads * o.targetsPerDay *
      (o.changedBucketFraction + o.noopUploadFraction) * bucketRead;
    const changedBuckets = uploads * o.targetsPerDay * o.changedBucketFraction;
    const uploadWriteBytes = changedBuckets * (deviceDoc + deviceIndexes * o.lookupIndexBytes);
    const todayReadBytes = androidSnapshots * devicesPerUser * o.targetsPerDay * bucketRead;
    const catalogReadBytes = androidSnapshots * devicesPerUser * o.targetsPerDay * (catalogDoc + o.lookupIndexBytes);
    // Per changed usage bucket: catalog row lookup (~150 B) and two row-sized writes.
    const catalogDeltaBytes = changedBuckets * (o.catalogRowBytes + 2 * o.lookupIndexBytes);

    // One global daily retention worker deletes expired rows in bounded pages and folds them into month archives.
    const expiredPerDay = dau * devicesPerUser * o.targetsPerDay;
    const cleanupCalls = Math.ceil(expiredPerDay / o.cleanupPageSize) * daysPerMonth;
    const expiredPerMonth = expiredPerDay * daysPerMonth;
    const retentionReadBytes = expiredPerMonth * bucketRead;
    const retentionDeleteBytes = expiredPerMonth * (deviceDoc + deviceIndexes * o.lookupIndexBytes);
    const archiveUpdates = dau * daysPerMonth;
    const archiveMaintenanceBytes = archiveUpdates * (archiveDoc + o.lookupIndexBytes + archiveDoc + archiveIndexes * o.lookupIndexBytes);

    const functionCalls = pulls + uploads + cleanupCalls;
    const databaseIoBytes = uploadReadBytes + uploadWriteBytes + todayReadBytes + catalogReadBytes + catalogDeltaBytes + retentionReadBytes + retentionDeleteBytes + archiveMaintenanceBytes;
    const checks = {
      functionCalls: { used: functionCalls, cap: o.callsCap, unit: "calls/month" },
      storageBytes: { used: storageBytes, cap: o.storageCapBytes, unit: "bytes" },
      databaseIoBytes: { used: databaseIoBytes, cap: o.ioCapBytes, unit: "bytes/month" },
    };
    for (const check of Object.values(checks)) check.status = check.used <= check.cap ? "FIT" : "FAIL";
    return {
      devicesPerUser,
      status: Object.values(checks).every((c) => c.status === "FIT") ? "FIT" : "FAIL",
      checks,
      calls: { pulls, uploads, cleanupCalls },
      storage: { usageRows, monthlyNewUsageRows, catalogRows, archiveDocs, archiveDocJsonBytes: archiveJson, bytes: storageBytes, monthlyGrowthBytes: monthlyStorageGrowthBytes },
      databaseIo: { uploadReadBytes, uploadWriteBytes, todayReadBytes, catalogReadBytes, catalogDeltaBytes, retentionReadBytes, retentionDeleteBytes, archiveMaintenanceBytes, bytes: databaseIoBytes },
    };
  });
  return { assumptions: o, results };
}

const fmt = (n) => `${(n / GB).toFixed(3)} GB (${Math.round(n).toLocaleString("en-US")} B)`;
function render(report) {
  const o = report.assumptions;
  const lines = [
    "Convex monthly workload budget estimate (model only; not production certification)",
    `Assumptions: ${o.dau} DAU, ${o.daysPerMonth} days, pull/upload every ${o.pullHours}/${o.uploadHours} h, ${o.targetsPerDay} targets/device/day, ${o.historyDays} detailed days, ${o.archiveMonths} compact archive months.`,
    `Rows: ${o.bucketJsonBytes} B mean JSON row sample × ${o.rowOverheadMultiplier} row factor; deviceUsage ${o.deviceUsageCustomIndexes} custom + ${o.builtinIndexes} built-in indexes; ${o.lookupIndexBytes} B/index entry; dirty ${o.changedBucketFraction} + retransmit ${o.noopUploadFraction} fractions; ${o.cleanupPageSize} rows/retention page.`,
  ];
  for (const s of report.results) {
    lines.push(`\n${s.status} — ${s.devicesPerUser} devices/user`);
    lines.push(`  Function calls: ${Math.round(s.checks.functionCalls.used).toLocaleString("en-US")} / ${s.checks.functionCalls.cap.toLocaleString("en-US")} (${s.checks.functionCalls.status}); ${Math.round(s.calls.pulls).toLocaleString("en-US")} pulls, ${Math.round(s.calls.uploads).toLocaleString("en-US")} uploads, ${Math.round(s.calls.cleanupCalls).toLocaleString("en-US")} cleanup pages.`);
    lines.push(`  Stored DB: ${fmt(s.checks.storageBytes.used)} / ${fmt(s.checks.storageBytes.cap)} (${s.checks.storageBytes.status}); monthly growth ${fmt(s.storage.monthlyGrowthBytes)}`);
    lines.push(`  DB I/O: ${fmt(s.checks.databaseIoBytes.used)} / ${fmt(s.checks.databaseIoBytes.cap)} (${s.checks.databaseIoBytes.status})`);
    lines.push(`  I/O upload read/write ${fmt(s.databaseIo.uploadReadBytes)} / ${fmt(s.databaseIo.uploadWriteBytes)}; bundled today/catalog ${fmt(s.databaseIo.todayReadBytes)} / ${fmt(s.databaseIo.catalogReadBytes)}; catalog deltas ${fmt(s.databaseIo.catalogDeltaBytes)}; retention read/delete ${fmt(s.databaseIo.retentionReadBytes)} / ${fmt(s.databaseIo.retentionDeleteBytes)}; archive updates ${fmt(s.databaseIo.archiveMaintenanceBytes)}.`);
  }
  lines.push("\nModel result only. Framework overhead, unmodeled endpoints, retries, cache behavior, migration backfill, and live deployment usage are excluded. Compare with live Convex metrics before making capacity commitments.");
  return lines.join("\n");
}

function parseArgs(argv) {
  const options = {};
  let json = false;
  for (const arg of argv) {
    if (arg === "--json") { json = true; continue; }
    if (arg === "--help" || arg === "-h") return { help: true };
    const m = /^--([a-z-]+)=(.+)$/.exec(arg);
    if (!m) throw new Error(`Unknown argument: ${arg}`);
    const key = m[1].replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    options[key] = key === "devices" ? m[2].split(",").map(Number) : Number(m[2]);
  }
  return { options, json };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const parsed = parseArgs(process.argv.slice(2));
    if (parsed.help) console.log("Usage: node scripts/estimate-convex-capacity.mjs [--dau=1000] [--devices=2,3] [--pull-hours=4] [--upload-hours=4] [--targets-per-day=20] [--history-days=30] [--archive-months=12] [--row-overhead-multiplier=1.25] [--noop-upload-fraction=0] [--json]");
    else {
      const report = calculateCapacity(parsed.options);
      console.log(parsed.json ? JSON.stringify(report, null, 2) : render(report));
    }
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
