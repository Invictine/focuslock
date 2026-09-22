export const MAX_STRICT_HOURS = 30 * 24;
export const STRICT_HOUR_OPTIONS = [1, 2, 3, 4, 6, 8, 12, 24, 48, 72, 120, 168, 336, 720];

export function strictEndError(end: number, now: number, currentEnd = 0): string | null {
  if (!Number.isFinite(end) || end <= now) return "Choose an end time in the future.";
  if (end > now + MAX_STRICT_HOURS * 3_600_000) return "A commitment can last up to 30 days.";
  if (currentEnd > now && end <= currentEnd) return "Choose an end time later than your current commitment.";
  return null;
}

export function strictDurationLabel(hours: number): string {
  return hours >= 24 && hours % 24 === 0
    ? `${hours / 24} day${hours === 24 ? "" : "s"}`
    : `${hours} hour${hours === 1 ? "" : "s"}`;
}
