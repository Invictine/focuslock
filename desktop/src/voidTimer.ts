export type VoidClockSample = {
  sampledAt: number;
  wholeSeconds: number;
  remainderMs: number;
  suspended: boolean;
};

/** Samples the monotonic clock used by the launcher interval. Long gaps earn no time. */
export function sampleVoidClock(
  previousAt: number | null,
  now: number,
  remainderMs: number,
  remainingSeconds: number,
  maxSampleGapMs = 5_000,
): VoidClockSample {
  if (previousAt === null || !Number.isFinite(previousAt) || !Number.isFinite(now)) {
    return { sampledAt: now, wholeSeconds: 0, remainderMs: 0, suspended: false };
  }
  const elapsed = now - previousAt;
  if (elapsed < 0 || elapsed > maxSampleGapMs) {
    return { sampledAt: now, wholeSeconds: 0, remainderMs: 0, suspended: elapsed > maxSampleGapMs };
  }
  const total = Math.max(0, remainderMs) + elapsed;
  const availableSeconds = Math.max(0, Math.trunc(remainingSeconds));
  const wholeSeconds = Math.min(availableSeconds, Math.floor(total / 1_000));
  const reachedEnd = wholeSeconds >= availableSeconds;
  return {
    sampledAt: now,
    wholeSeconds,
    remainderMs: reachedEnd ? 0 : total - wholeSeconds * 1_000,
    suspended: false,
  };
}

/** A completion session can be persisted once even if task completion and timer expiry race. */
export function claimVoidCompletion(ledger: Set<string>, sessionId: string): boolean {
  if (!sessionId || ledger.has(sessionId)) return false;
  ledger.add(sessionId);
  return true;
}

export function canAcceptVoidStart(statusRunning: boolean, closedDuringStart: boolean): boolean {
  return statusRunning && !closedDuringStart;
}
