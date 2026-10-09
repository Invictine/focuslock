import { afterEach, describe, expect, it, vi } from "vitest";
import { invoke } from "../desktop/node_modules/@tauri-apps/api/core";
import { persistStrictUninstallGuard } from "../desktop/src/strictUninstall";

vi.mock("../desktop/node_modules/@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

describe("desktop Strict Mode uninstall bridge", () => {
  it("keeps web previews free of native side effects", async () => {
    vi.stubGlobal("window", {});
    await persistStrictUninstallGuard("a", { strictMode: true, strictEndsAt: 500 });
    expect(invoke).not.toHaveBeenCalled();
  });

  it("sends the commitment and exact guardian approval identity to native storage", async () => {
    vi.stubGlobal("window", { __TAURI_INTERNALS__: {} });
    await persistStrictUninstallGuard("a", {
      strictMode: false, strictEndsAt: 0,
      strictSessionId: "session", strictApprovedSessionId: "session", strictApprovedEndsAt: 500,
    });
    expect(invoke).toHaveBeenCalledWith("sync_strict_uninstall_guard", {
      accountId: "a", enabled: false, endsAt: null, sessionId: "session",
      approvedSessionId: "session", approvedEndsAt: 500,
    });
  });

  it("propagates persistence failure so a commitment does not report protection saved", async () => {
    vi.stubGlobal("window", { __TAURI_INTERNALS__: {} });
    vi.mocked(invoke).mockRejectedValueOnce(new Error("disk unavailable"));
    await expect(persistStrictUninstallGuard("a", { strictMode: true, strictEndsAt: 500 }))
      .rejects.toThrow("disk unavailable");
  });
});
