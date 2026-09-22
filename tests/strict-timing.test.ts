import { describe, expect, it } from "vitest";
import { strictEndError, strictDurationLabel } from "../desktop/src/strictTiming";

describe("strict commitment timing", () => {
  const now = 1_800_000_000_000;
  it("supports 30 days and rejects invalid, expired, or overlong commitments", () => {
    expect(strictEndError(now + 30 * 86_400_000, now)).toBeNull();
    for (const end of [NaN, Infinity, now, now - 1, now + 30 * 86_400_000 + 1]) {
      expect(strictEndError(end, now)).toBeTruthy();
    }
  });
  it("only extends active commitments", () => {
    const current = now + 7 * 86_400_000;
    expect(strictEndError(current, now, current)).toBeTruthy();
    expect(strictEndError(current - 1, now, current)).toBeTruthy();
    expect(strictEndError(current + 1, now, current)).toBeNull();
  });
  it("labels days and hours without misleading units", () => {
    expect(strictDurationLabel(1)).toBe("1 hour");
    expect(strictDurationLabel(24)).toBe("1 day");
    expect(strictDurationLabel(168)).toBe("7 days");
  });
});
