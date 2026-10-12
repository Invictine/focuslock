import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { useEffect, useRef, useState } from "react";
import { applyNativeFrogPolicy, emergencyDisable, setNativeFrogReady, type FrogState, type NativeFrogPolicy } from "./frog";
import { setNativeStrictSettingsLocked } from "./strictSettings";

export type DesktopFrogPolicy = NativeFrogPolicy & { locked: boolean; hotkeyAvailable: boolean; strictSettingsLocked?: boolean };
let queue: Promise<unknown> = Promise.resolve();
function serialize<T>(work: () => Promise<T>): Promise<T> {
  const result = queue.then(work, work);
  queue = result.catch(() => undefined);
  return result;
}

/** Only the explicit setup/settings action may opt this device in. */
export async function configureDesktopFrog(enabled: boolean, appIds: string[], domains: string[]) {
  return serialize(async () => {
    const policy = await invoke<DesktopFrogPolicy>("configure_frog_policy", { config: { enabled, appIds, domains } });
    applyNativeFrogPolicy(policy);
    setNativeFrogReady(true);
    return policy;
  });
}

/** Native policy wins over stale renderer storage, delayed IPC and restarts. */
export function useNativeFrogPolicy(frog: FrogState, blockedApps: string[], blockedDomains: string[]) {
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const live = useRef({ frog, blockedApps, blockedDomains });
  live.current = { frog, blockedApps, blockedDomains };
  useEffect(() => {
    if (!(window as any).__TAURI_INTERNALS__) return;
    let disposed = false;
    let busy = false;
    let lastPolicy = "";
    let lastLock = "";
    let recoveryGeneration = 0;
    let unlisten: (() => void) | undefined;
    const emergency = () => {
      recoveryGeneration += 1;
      emergencyDisable();
      lastPolicy = "";
      lastLock = "";
    };
    void listen("focuslock-frog-emergency", emergency).then((stop) => { if (disposed) stop(); else unlisten = stop; });
    const tick = async () => {
      if (disposed || busy) return;
      busy = true;
      try {
        await serialize(async () => {
          const generation = recoveryGeneration;
          const policy = await invoke<DesktopFrogPolicy>("get_frog_policy");
          setNativeStrictSettingsLocked(Boolean(policy.strictSettingsLocked));
          if (disposed || generation !== recoveryGeneration) return;
          const json = JSON.stringify(policy);
          if (json !== lastPolicy) {
            lastPolicy = json;
            applyNativeFrogPolicy(policy);
          }
          setNativeFrogReady(true);
          setReady(true);
          const { frog: current, blockedApps: apps, blockedDomains: sites } = live.current;
          const allowedApps = current.essentialAppIds.filter((id) => !apps.includes(id.toLowerCase()));
          const allowedDomains = current.essentialDomains.filter((host) => !sites.some((blocked) =>
            host === blocked || host.endsWith(`.${blocked}`) || blocked.endsWith(`.${host}`)));
          const lock = { locked: Boolean(policy.enabled && !policy.emergencyDisabled && current.enabled && current.locked), appIds: allowedApps, domains: allowedDomains };
          const lockJson = JSON.stringify(lock);
          if (lockJson !== lastLock) {
            await invoke("sync_frog_lock", lock);
            lastLock = lockJson;
          }
          setError(null);
        });
      } catch (reason) {
        if (!disposed) setError(String(reason));
      } finally { busy = false; }
    };
    void tick();
    const interval = window.setInterval(() => void tick(), 1000);
    return () => { disposed = true; window.clearInterval(interval); unlisten?.(); };
  }, []);
  return { ready, error };
}
