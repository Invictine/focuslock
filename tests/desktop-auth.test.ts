import { describe, expect, it, vi } from "vitest";
import { createSafeBrowserTokenFetcher, type FocusUser } from "../desktop/src/auth";

const savedProfile: FocusUser = {
  id: "user-123",
  name: "Saved User",
  email: "saved@example.invalid",
};

describe("desktop browser auth token recovery", () => {
  it("returns a valid native token without checking or changing local auth state", async () => {
    const getAuthState = vi.fn();
    const onRevoked = vi.fn();
    const onTransientError = vi.fn();
    let generation = 0;
    const fetchToken = createSafeBrowserTokenFetcher({
      getToken: async () => "convex-token",
      getAuthState,
      getGeneration: () => generation,
      onRevoked,
      onTransientError,
    });

    await expect(fetchToken()).resolves.toBe("convex-token");
    expect(getAuthState).not.toHaveBeenCalled();
    expect(onRevoked).not.toHaveBeenCalled();
    expect(onTransientError).not.toHaveBeenCalled();
  });

  it("returns null and preserves a still-signed-in user when token exchange temporarily fails", async () => {
    const onRevoked = vi.fn();
    const onTransientError = vi.fn();
    let generation = 0;
    const fetchToken = createSafeBrowserTokenFetcher({
      getToken: async () => { throw new Error("network unavailable"); },
      getAuthState: async () => ({ signedIn: true, profile: savedProfile }),
      getGeneration: () => generation,
      onRevoked,
      onTransientError,
    });

    await expect(fetchToken()).resolves.toBeNull();
    expect(onRevoked).not.toHaveBeenCalled();
    expect(onTransientError).toHaveBeenCalledOnce();
    expect(onTransientError.mock.calls[0][0]).toMatch(/network unavailable.*still signed in/i);
    expect(onTransientError.mock.calls[0][1]).toEqual({ signedIn: true, profile: savedProfile });
  });

  it("reconciles a revoked session and never leaks a rejection to Convex", async () => {
    const onRevoked = vi.fn();
    const onTransientError = vi.fn();
    let generation = 0;
    const fetchToken = createSafeBrowserTokenFetcher({
      getToken: async () => null,
      getAuthState: async () => ({ signedIn: false }),
      getGeneration: () => generation,
      onRevoked,
      onTransientError,
    });

    await expect(fetchToken()).resolves.toBeNull();
    expect(onRevoked).toHaveBeenCalledOnce();
    expect(onTransientError).not.toHaveBeenCalled();
  });

  it("coalesces simultaneous token requests and retains user state when local verification also fails", async () => {
    let finishToken!: (token: string | null) => void;
    const getToken = vi.fn(() => new Promise<string | null>((resolve) => { finishToken = resolve; }));
    const onRevoked = vi.fn();
    const onTransientError = vi.fn();
    let generation = 0;
    const fetchToken = createSafeBrowserTokenFetcher({
      getToken,
      getAuthState: async () => { throw new Error("native bridge unavailable"); },
      getGeneration: () => generation,
      onRevoked,
      onTransientError,
    });

    const first = fetchToken();
    const second = fetchToken();
    expect(getToken).toHaveBeenCalledOnce();
    finishToken(null);
    await expect(Promise.all([first, second])).resolves.toEqual([null, null]);
    expect(onRevoked).not.toHaveBeenCalled();
    expect(onTransientError).toHaveBeenCalledOnce();
    expect(onTransientError.mock.calls[0][0]).toMatch(/session is being kept/i);
  });

  it("discards an old token flight after sign-out and keeps newer requests independent", async () => {
    let generation = 1;
    const resolvers: Array<(token: string | null) => void> = [];
    const getToken = vi.fn(() => new Promise<string | null>((resolve) => resolvers.push(resolve)));
    const onRevoked = vi.fn();
    const onTransientError = vi.fn();
    const fetchToken = createSafeBrowserTokenFetcher({
      getToken,
      getAuthState: async () => ({ signedIn: true, profile: savedProfile }),
      getGeneration: () => generation,
      onRevoked,
      onTransientError,
    });

    const oldRequest = fetchToken();
    generation += 1; // sign-out or account replacement invalidates this flight
    const newRequest = fetchToken();
    resolvers[0]("old-account-token");
    await expect(oldRequest).resolves.toBeNull();
    expect(onRevoked).not.toHaveBeenCalled();
    expect(onTransientError).not.toHaveBeenCalled();
    resolvers[1]("new-account-token");
    await expect(newRequest).resolves.toBe("new-account-token");
    expect(getToken).toHaveBeenCalledTimes(2);
  });
});
