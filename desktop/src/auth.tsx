import { useClerk, useUser } from "@clerk/clerk-react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type { ConvexReactClient } from "convex/react";
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

export type FocusUser = { name: string; email: string; imageUrl?: string };
type FocusAuthValue = {
  user: FocusUser | null;
  loading: boolean;
  error: string | null;
  signInInBrowser: () => Promise<void>;
  signOut: () => Promise<void>;
};

const FocusAuthContext = createContext<FocusAuthValue>({
  user: null,
  loading: true,
  error: null,
  signInInBrowser: async () => undefined,
  signOut: async () => undefined,
});

export function useFocusAuth() { return useContext(FocusAuthContext); }

export function DesktopBrowserAuthProvider({ client, children }: { client: ConvexReactClient; children: React.ReactNode }) {
  const [user, setUser] = useState<FocusUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const state = await invoke<{ signedIn: boolean; profile?: { name: string; email: string; imageUrl?: string } }>("get_browser_auth_state");
      if (state.signedIn && state.profile) {
        setUser(state.profile);
        client.setAuth(() => invoke<string | null>("get_browser_auth_token"));
      } else {
        setUser(null);
        client.clearAuth();
      }
      setError(null);
    } catch (reason) {
      client.clearAuth();
      setUser(null);
      setError(String(reason));
    } finally {
      setLoading(false);
    }
  }, [client]);

  useEffect(() => {
    refresh();
    let unlisten: undefined | (() => void);
    listen("browser-auth-complete", () => refresh()).then((stop) => { unlisten = stop; });
    return () => unlisten?.();
  }, [refresh]);

  const value = useMemo<FocusAuthValue>(() => ({
    user,
    loading,
    error,
    signInInBrowser: async () => {
      setError(null);
      await invoke("start_browser_sign_in");
    },
    signOut: async () => {
      await invoke("sign_out_browser_auth");
      client.clearAuth();
      setUser(null);
    },
  }), [client, error, loading, user]);

  return <FocusAuthContext.Provider value={value}>{children}</FocusAuthContext.Provider>;
}

export function ClerkWebAuthProvider({ children }: { children: React.ReactNode }) {
  const { user, isLoaded } = useUser();
  const clerk = useClerk();
  const value = useMemo<FocusAuthValue>(() => ({
    user: user ? {
      name: user.fullName || user.firstName || "FocusLock user",
      email: user.primaryEmailAddress?.emailAddress || "",
      imageUrl: user.imageUrl,
    } : null,
    loading: !isLoaded,
    error: null,
    signInInBrowser: async () => undefined,
    signOut: async () => { await clerk.signOut(); },
  }), [clerk, isLoaded, user]);
  return <FocusAuthContext.Provider value={value}>{children}</FocusAuthContext.Provider>;
}
