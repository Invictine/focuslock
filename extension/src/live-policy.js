// Small dependency-injected Convex subscription controller.  Keeping Convex out
// of this module makes it usable by both the extension and deterministic tests.
export function createLivePolicyController({
  url, createClient, query, readIdentity, fetchToken, onPolicy, onError, onIdentityChange,
}) {
  let generation = 0;
  let identity = null;
  let client = null;
  let queryUnsubscribe = null;
  let connectionUnsubscribe = null;
  let subscribedArgs = null;
  let connected = false;
  let received = false;
  let active = false;
  let policyQueue = Promise.resolve();

  const sameIdentity = (a, b) => a?.userId === b?.userId && a?.session === b?.session;

  function report(error) {
    try { onError?.(error); } catch { /* diagnostics must never break the stream */ }
  }

  async function isCurrentIdentity(gen, expected) {
    if (gen !== generation || !sameIdentity(identity, expected)) return false;
    try {
      const live = await readIdentity();
      return gen === generation && sameIdentity(identity, expected) && sameIdentity(live, expected);
    } catch { return false; }
  }

  function closeClient() {
    const q = queryUnsubscribe;
    const c = connectionUnsubscribe;
    queryUnsubscribe = null;
    connectionUnsubscribe = null;
    subscribedArgs = null;
    active = false;
    try { q?.(); } catch (e) { report(e); }
    try { c?.(); } catch (e) { report(e); }
    try { Promise.resolve(client?.close?.()).catch(report); } catch (e) { report(e); }
    client = null;
    connected = false;
    received = false;
  }

  async function stop() {
    generation += 1;
    identity = null;
    closeClient();
    // Let already queued callbacks observe the new generation before callers
    // continue; they remain harmless even if this promise is not awaited.
    await policyQueue.catch(() => {});
  }

  async function ensure(args) {
    const requested = JSON.stringify(args);
    const observedGeneration = generation;
    let next;
    try { next = await readIdentity(); } catch (e) { report(e); return; }
    if (observedGeneration !== generation) return;

    if (!sameIdentity(identity, next)) {
      generation += 1;
      const mine = generation;
      closeClient();
      identity = next || null;
      if (onIdentityChange) {
        try { await onIdentityChange(identity); } catch (e) { report(e); identity = undefined; return; }
        if (mine !== generation) return;
      }
    }
    if (!identity) return;
    if (client && subscribedArgs === requested && active) return;
    if (client) { generation += 1; closeClient(); }
    const mine = generation;
    if (mine !== generation) return;

    const boundIdentity = identity;
    let nextClient;
    try {
      nextClient = createClient(url, { unsavedChangesWarning: false, expectAuth: true });
      nextClient.setAuth(async ({ forceRefreshToken } = {}) => {
        if (!(await isCurrentIdentity(mine, boundIdentity))) return null;
        try {
          const token = await fetchToken(boundIdentity, !!forceRefreshToken);
          return (await isCurrentIdentity(mine, boundIdentity)) ? token : null;
        } catch (e) { report(e); return null; }
      });
      client = nextClient;
      const onResult = (pulse) => {
        if (mine !== generation || !sameIdentity(identity, boundIdentity)) return;
        received = true;
        const isCurrent = async () => isCurrentIdentity(mine, boundIdentity);
        policyQueue = policyQueue.then(async () => {
          if (!(await isCurrent())) return;
          try { await onPolicy(pulse, { ...boundIdentity, args, isCurrent }); }
          catch (e) { report(e); }
        }).catch(report);
      };
      const onQueryError = (e) => { if (mine === generation) report(e); };
      queryUnsubscribe = nextClient.onUpdate(query, args, onResult, onQueryError);
      if (nextClient.subscribeToConnectionState) {
        connectionUnsubscribe = nextClient.subscribeToConnectionState((state) => {
          if (mine !== generation) return;
          connected = !!(state?.isWebSocketConnected ?? state?.isConnected ?? state?.connected ?? state === true);
        });
      }
      subscribedArgs = requested;
      active = true;
    } catch (e) {
      if (client === nextClient) closeClient();
      report(e);
    }
  }

  return {
    ensure,
    stop,
    status: () => ({ active, connected, received }),
  };
}
