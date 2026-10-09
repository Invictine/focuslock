import { useClerk, useUser } from "@clerk/clerk-react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type { ConvexReactClient } from "convex/react";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

export type FocusUser = { id: string; name: string; email: string; imageUrl?: string };
type NativeAuthState = { signedIn: boolean; profile?: FocusUser };
type BrowserAuthCompletion = { Ok: NativeAuthState } | { Err: string };
type FocusAuthValue = {
  user: FocusUser | null;
  loading: boolean;
  error: string | null;
  signInInBrowser: () => Promise<void>;
  signOut: () => Promise<void>;
  getSyncToken: () => Promise<string | null>;
};

const FocusAuthContext = createContext<FocusAuthValue>({
  user: null,
  loading: true,
  error: null,
  signInInBrowser: async () => undefined,
  signOut: async () => undefined,
  getSyncToken: async () => null,
});

export function useFocusAuth() { return useContext(FocusAuthContext); }

/** A stable, non-throwing token callback shared by Convex and explicit sync requests. */
export function createSafeBrowserTokenFetcher(options: {
  getToken: () => Promise<string | null>;
  getAuthState: () => Promise<NativeAuthState>;
  getGeneration: () => number;
  onRevoked: () => void;
  onTransientError: (message: string, state?: NativeAuthState) => void;
}): () => Promise<string | null> {
  let pending: { generation: number; promise: Promise<string | null> } | undefined;
  return () => {
    const generation = options.getGeneration();
    if (pending?.generation === generation) return pending.promise;
    const promise = (async () => {
      let token: string | null = null;
      let tokenError: unknown;
      try {
        token = await options.getToken();
      } catch (reason) {
        tokenError = reason;
      }
      if (options.getGeneration() !== generation) return null;
      if (token) return token;

      try {
        const state = await options.getAuthState();
        if (options.getGeneration() !== generation) return null;
        if (state.signedIn && state.profile) {
          options.onTransientError(
            `FocusLock could not refresh your sync token (${safeErrorDetail(tokenError ?? "no token returned")}). Your account is still signed in; check your connection or sign-in configuration and try syncing again.`,
            state,
          );
        } else {
          options.onRevoked();
        }
      } catch (stateError) {
        if (options.getGeneration() !== generation) return null;
        const detail = tokenError ?? stateError;
        options.onTransientError(
          `FocusLock could not verify your account or refresh its sync token (${safeErrorDetail(detail)}). Your current session is being kept; check your connection and retry.`,
        );
      }
      return null;
    })();
    const flight = { generation, promise };
    pending = flight;
    void promise.finally(() => {
      if (pending === flight) pending = undefined;
    });
    return promise;
  };
}

function safeErrorDetail(reason: unknown) {
  return String(reason || "unknown error")
    .replace(/\bBearer\s+\S+/gi, "Bearer [redacted]")
    .replace(/([?&](?:code|token|state)=)[^&\s]+/gi, "$1[redacted]")
    .slice(0, 240);
}

function authError(reason: unknown, action: string) {
  const detail = safeErrorDetail(reason);
  return `${action} ${detail}. Check your connection and try again.`;
}

function isCompletion(value: unknown): value is BrowserAuthCompletion {
  return !!value && typeof value === "object" && ("Ok" in value || "Err" in value);
}

export function DesktopBrowserAuthProvider({ client, children }: { client: ConvexReactClient; children: React.ReactNode }) {
  const [user, setUser] = useState<FocusUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const userRef = useRef(user);
  const clientRef = useRef(client);
  const authIdentityRef = useRef<string | null>(null);
  const tokenFetcherRef = useRef<() => Promise<string | null>>();
  const generationRef = useRef(0);
  userRef.current = user;
  clientRef.current = client;

  const tokenFetcher = useMemo(() => createSafeBrowserTokenFetcher({
    getToken: () => invoke<string | null>("get_browser_auth_token"),
    getAuthState: () => invoke<NativeAuthState>("get_browser_auth_state"),
    getGeneration: () => generationRef.current,
    onRevoked: () => {
      generationRef.current += 1;
      authIdentityRef.current = null;
      userRef.current = null;
      setUser(null);
      setError("Your FocusLock sign-in has expired. Sign in again to resume account sync.");
      clientRef.current.clearAuth();
    },
    onTransientError: (message, state) => {
      if (state?.signedIn && state.profile && !userRef.current) {
        userRef.current = state.profile;
        setUser(state.profile);
      }
      setError(message);
    },
  }), []);
  tokenFetcherRef.current = tokenFetcher;

  const applyAuthState = useCallback((state: NativeAuthState, forceNewSession = false) => {
    const nextIdentity = state.signedIn && state.profile ? state.profile.id : null;
    if (forceNewSession || authIdentityRef.current !== nextIdentity) generationRef.current += 1;
    if (state.signedIn && state.profile) {
      userRef.current = state.profile;
      setUser(state.profile);
      setError(null);
      if (forceNewSession || authIdentityRef.current !== state.profile.id) {
        authIdentityRef.current = state.profile.id;
        clientRef.current.setAuth(() => tokenFetcherRef.current!());
      }
    } else {
      userRef.current = null;
      setUser(null);
      setError(null);
      authIdentityRef.current = null;
      clientRef.current.clearAuth();
    }
  }, []);

  const refresh = useCallback(async () => {
    const generation = generationRef.current;
    try {
      const state = await invoke<NativeAuthState>("get_browser_auth_state");
      if (generation === generationRef.current) applyAuthState(state);
    } catch (reason) {
      // A local-state query failure may be an outage. Keep the existing session and user.
      if (generation === generationRef.current) {
        setError(authError(reason, "Could not check your FocusLock sign-in."));
      }
    } finally {
      setLoading(false);
    }
  }, [applyAuthState]);

  useEffect(() => {
    let active = true;
    let unlisten: (() => void) | undefined;
    void refresh();
    listen<BrowserAuthCompletion>("browser-auth-complete", (event) => {
      if (!active) return;
      const payload: unknown = event.payload;
      if (isCompletion(payload) && "Err" in payload) {
        generationRef.current += 1;
        setError(`Browser sign-in did not complete: ${payload.Err}`);
        setLoading(false);
      } else if (isCompletion(payload) && "Ok" in payload) {
        applyAuthState(payload.Ok, true);
        setLoading(false);
      } else {
        void refresh();
      }
    }).then((stop) => {
      if (active) unlisten = stop;
      else stop();
    }).catch((reason) => {
      if (active) {
        setError(authError(reason, "Could not listen for browser sign-in results."));
        setLoading(false);
      }
    });
    return () => {
      active = false;
      generationRef.current += 1;
      unlisten?.();
    };
  }, [applyAuthState, refresh]);

  const signInInBrowser = useCallback(async () => {
    setError(null);
    try {
      await invoke("start_browser_sign_in");
    } catch (reason) {
      setError(authError(reason, "Could not start browser sign-in."));
    }
  }, []);

  const signOut = useCallback(async () => {
    try {
      await invoke("sign_out_browser_auth");
      generationRef.current += 1;
      authIdentityRef.current = null;
      userRef.current = null;
      setUser(null);
      setError(null);
      clientRef.current.clearAuth();
    } catch (reason) {
      setError(`Could not sign out of FocusLock. ${safeErrorDetail(reason)}`);
    }
  }, []);

  const value = useMemo<FocusAuthValue>(() => ({
    user,
    loading,
    error,
    getSyncToken: tokenFetcher,
    signInInBrowser,
    signOut,
  }), [error, loading, signInInBrowser, signOut, tokenFetcher, user]);

  return <FocusAuthContext.Provider value={value}>
    {children}
    {user && error && <div role="alert" aria-live="polite" style={{
      position: "fixed", bottom: 16, left: "50%", transform: "translateX(-50%)",
      zIndex: 10000, maxWidth: "min(560px, calc(100vw - 32px))", padding: "10px 14px",
      borderRadius: 8, background: "#422b1f", color: "#fff1df", fontSize: 13,
      boxShadow: "0 4px 18px #0005",
    }}>{error}</div>}
  </FocusAuthContext.Provider>;
}

export function ClerkWebAuthProvider({ children }: { children: React.ReactNode }) {
  const { user, isLoaded } = useUser();
  const clerk = useClerk();
  const value = useMemo<FocusAuthValue>(() => ({
    user: user ? {
      id: user.id,
      name: user.fullName || user.firstName || "FocusLock user",
      email: user.primaryEmailAddress?.emailAddress || "",
      imageUrl: user.imageUrl,
    } : null,
    loading: !isLoaded,
    error: null,
    getSyncToken: async () => (await clerk.session?.getToken({ template: "convex" })) ?? null,
    signInInBrowser: async () => undefined,
    signOut: async () => { await clerk.signOut(); },
  }), [clerk, isLoaded, user]);
  return <FocusAuthContext.Provider value={value}>{children}</FocusAuthContext.Provider>;
}
