import { useCallback, useEffect } from "react";
import { ConvexHttpClient } from "convex/browser";
import { getFunctionName, type FunctionReference, type FunctionArgs, type FunctionReturnType } from "convex/server";
import { useFocusAuth } from "./auth";
import { enqueueMutation, pendingMutations, replayMutations, type MutationReplayResult } from "./offlineQueue";

const flights = new Map<string, Promise<Map<string, MutationReplayResult>>>();

export function accountClient(account: string, token: string) {
  const payload = token.split('.')[1];
  const claims = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
  if (claims.sub !== account) throw new Error('Account changed; saved changes remain with their original account.');
  const client = new ConvexHttpClient(import.meta.env.VITE_CONVEX_URL);
  client.setAuth(token); // This request stays bound to this account even if the UI signs out.
  return client;
}

export async function flushMutations(account: string, token: string) {
  const existing = flights.get(account);
  if (existing) return existing;
  const task = (async () => {
    const client = accountClient(account, token);
    return replayMutations(account, (item) => client.mutation(item.path as any, item.args));
  })();
  flights.set(account, task);
  try { return await task; } finally { flights.delete(account); }
}

export function useDurableMutation<F extends FunctionReference<"mutation">>(reference: F) {
  const auth = useFocusAuth();
  const path = getFunctionName(reference);
  return useCallback(async (args: FunctionArgs<F>): Promise<FunctionReturnType<F>> => {
    const account = auth.user?.id;
    if (!account) throw new Error('Sign in before saving shared data.');
    const id = crypto.randomUUID();
    enqueueMutation(account, { id, path, args });
    try {
      const token = await auth.getSyncToken();
      if (!token) throw new Error('Sign in again to sync.');
      const results = await flushMutations(account, token);
      const result = results.get(id);
      if (!result) throw new Error('This change is waiting for sync.');
      if (!result.ok) throw result.error;
      return result.value as FunctionReturnType<F>;
    } catch (error) {
      const detail = error instanceof Error ? error.message : 'The server did not confirm this change.';
      throw new Error(`${detail} Saved on this device for retry when your account is connected.`);
    }
  }, [auth.user?.id, auth.getSyncToken, path]);
}

export function useMutationReplay() {
  const auth = useFocusAuth();
  useEffect(() => {
    const account = auth.user?.id;
    if (!account) return;
    const replay = async () => {
      try {
        if (!pendingMutations(account).length) return;
        const token = await auth.getSyncToken();
        if (token) await flushMutations(account, token);
      } catch { /* Never clear unreadable or unacknowledged data. */ }
    };
    void replay();
    const timer = window.setInterval(replay, 4 * 60 * 60_000);
    window.addEventListener('online', replay);
    return () => { window.clearInterval(timer); window.removeEventListener('online', replay); };
  }, [auth.user?.id, auth.getSyncToken]);
}
