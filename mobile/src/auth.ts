import { jwtDecode } from 'jwt-decode';

// Convex verifies signature/audience. This extra check prevents a client kept
// alive during an account switch from submitting the previous account's queue.
export function tokenBelongsTo(token: string | null, ownerId: string): boolean {
  if (!token) return false;
  try {
    return jwtDecode<{ sub?: string }>(token).sub === ownerId;
  } catch {
    return false;
  }
}

export function errorMessage(error: unknown): string {
  return (error instanceof Error ? error.message : String(error))
    .replace(/\bBearer\s+\S+/gi, 'Bearer [redacted]')
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[redacted]')
    .slice(0, 240);
}
