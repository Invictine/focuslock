import { useEffect, useState } from "react";

/** Refresh at expiry without keeping a per-second render timer alive. */
export function useStrictActive(enabled: boolean, endsAt?: number): boolean {
  const [clock, setClock] = useState(0);
  useEffect(() => {
    if (!enabled || !endsAt) return;
    const remaining = endsAt - Date.now();
    if (remaining <= 0) return;
    // Browser timers overflow beyond ~24.8 days; re-arm for longer commitments.
    const timer = window.setTimeout(() => setClock((value) => value + 1), Math.min(remaining + 1, 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [enabled, endsAt, clock]);
  return enabled && (!endsAt || endsAt > Date.now());
}
