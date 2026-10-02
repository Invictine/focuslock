import { describe, expect, it } from "vitest";
import { calculateCapacity } from "../scripts/estimate-convex-capacity.mjs";

describe("Convex capacity model", () => {
  it("uses deterministic 2-device and 3-device scenarios and exposes the call overage", () => {
    const report = calculateCapacity();
    expect(report.results.map((scenario) => scenario.devicesPerUser)).toEqual([2, 3]);
    expect(report.results[0].calls).toEqual({
      pulls: 360_000,
      uploads: 360_000,
      cleanupCalls: 12_000,
    });
    expect(report.results[0].checks.functionCalls).toMatchObject({ used: 732_000, status: "FIT" });
    expect(report.results[1].checks.functionCalls).toMatchObject({ used: 1_098_000, status: "FAIL" });
  });

  it("fits exactly at configured call, storage, and I/O boundaries, then fails above them", () => {
    const base = {
      dau: 1,
      devices: [1],
      daysPerMonth: 1,
      pullHours: 24,
      uploadHours: 24,
      targetsPerDay: 1,
      historyDays: 1,
      archiveMonths: 1,
      bucketJsonBytes: 10,
      rowOverheadMultiplier: 1,
      deviceUsageCustomIndexes: 0,
      builtinIndexes: 0,
      lookupIndexBytes: 0,
      catalogRowBytes: 0,
      catalogCustomIndexes: 0,
      changedBucketFraction: 1,
      androidDevicesPerUser: 1,
      cleanupPageSize: 100,
      archiveBaseBytes: 0,
      archiveTargetBytes: 0,
      archiveDayBytes: 0,
      archiveTargetsPerDeviceMonth: 0,
      archiveCustomIndexes: 0,
      configBytesPerUser: 0,
      callsCap: 3,
      storageCapBytes: 10,
      ioCapBytes: 50,
    };
    const exact = calculateCapacity(base).results[0];
    expect(exact.status).toBe("FIT");
    expect(exact.checks).toMatchObject({
      functionCalls: { used: 3, status: "FIT" },
      storageBytes: { used: 10, status: "FIT" },
      databaseIoBytes: { used: 50, status: "FIT" },
    });

    const over = calculateCapacity({ ...base, ioCapBytes: 49 }).results[0];
    expect(over.status).toBe("FAIL");
    expect(over.checks.databaseIoBytes.status).toBe("FAIL");
  });

  it("charges full all-time catalog scans and grows storage with retained history", () => {
    const common = {
      dau: 1,
      devices: [2],
      daysPerMonth: 30,
      pullHours: 4,
      uploadHours: 4,
      targetsPerDay: 20,
      bucketJsonBytes: 360,
      rowOverheadMultiplier: 1,
      deviceUsageCustomIndexes: 0,
      builtinIndexes: 0,
      lookupIndexBytes: 60,
      changedBucketFraction: 0,
      androidDevicesPerUser: 1,
      configBytesPerUser: 0,
    };
    const thirty = calculateCapacity({ ...common, historyDays: 30 }).results[0];
    const sixty = calculateCapacity({ ...common, historyDays: 60 }).results[0];
    expect(sixty.storage.usageRows).toBe(thirty.storage.usageRows * 2);
    expect(sixty.storage.bytes - thirty.storage.bytes).toBe(thirty.storage.usageRows * 360);
    expect(sixty.databaseIo.todayReadBytes).toBe(thirty.databaseIo.todayReadBytes);
    expect(sixty.storage.monthlyGrowthBytes).toBe(thirty.storage.monthlyGrowthBytes);
  });

  it("rejects invalid intervals and change fractions", () => {
    expect(() => calculateCapacity({ uploadHours: 0 })).toThrow("uploadHours must be positive");
    expect(() => calculateCapacity({ changedBucketFraction: 1.1 })).toThrow("between 0 and 1");
    expect(() => calculateCapacity({ changedBucketFraction: 0.8, noopUploadFraction: 0.3 }))
      .toThrow("plus noopUploadFraction");
  });

  it("stays finite for extreme workloads and models no-op upload writes as zero", () => {
    const idle = calculateCapacity({ devices: [1], dau: 1, targetsPerDay: 1, changedBucketFraction: 0 }).results[0];
    expect(idle.databaseIo.uploadWriteBytes).toBe(0);

    const extreme = calculateCapacity({ dau: 50_000, devices: [3], targetsPerDay: 500, historyDays: 365 }).results[0];
    expect(extreme.status).toBe("FAIL");
    expect(Number.isFinite(extreme.checks.storageBytes.used)).toBe(true);
    expect(Number.isFinite(extreme.checks.databaseIoBytes.used)).toBe(true);
  });

  it("reads dirty buckets by default and separates unchanged retry reads from writes", () => {
    const base = {
      dau: 1, devices: [1], daysPerMonth: 1, pullHours: 24, uploadHours: 24,
      targetsPerDay: 1, historyDays: 1, archiveMonths: 1, bucketJsonBytes: 10,
      rowOverheadMultiplier: 1, changedBucketFraction: 0.25, lookupIndexBytes: 60,
      androidDevicesPerUser: 0,
    };
    const dirtyOnly = calculateCapacity(base).results[0].databaseIo;
    const withRetries = calculateCapacity({ ...base, noopUploadFraction: 0.5 }).results[0].databaseIo;
    expect(dirtyOnly.uploadReadBytes).toBe(17.5);
    expect(withRetries.uploadReadBytes).toBe(52.5);
    expect(withRetries.uploadWriteBytes).toBe(dirtyOnly.uploadWriteBytes);
  });
});
