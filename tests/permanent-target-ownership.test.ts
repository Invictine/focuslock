import { describe, expect, it } from "vitest";
import {
  claimPermanentTargets,
  discoverPermanentTargets,
  permanentTargetsOwnedByAccount,
} from "../desktop/src/permanentSync";

class MemoryStorage {
  private readonly values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

describe("Windows permanent target ownership", () => {
  it("lets the first verified account adopt legacy targets and prevents cross-account upload", () => {
    const storage = new MemoryStorage();
    const deviceId = "windows-device";

    const discovered = discoverPermanentTargets(storage, deviceId, ["discord.exe", "steam.exe"]);
    expect(discovered?.unclaimedTargets).toEqual(["discord.exe", "steam.exe"]);

    const ownedByAlice = claimPermanentTargets(storage, deviceId, ["discord.exe", "steam.exe"], "alice");
    expect(ownedByAlice?.unclaimedTargets).toEqual([]);
    expect(permanentTargetsOwnedByAccount(ownedByAlice!, ["discord.exe", "steam.exe"], "alice"))
      .toEqual(["discord.exe", "steam.exe"]);
    expect(permanentTargetsOwnedByAccount(ownedByAlice!, ["discord.exe", "steam.exe"], "bob"))
      .toEqual([]);

    // A later discovery after an account switch must preserve Alice's claim.
    const rediscovered = discoverPermanentTargets(storage, deviceId, ["discord.exe", "steam.exe"]);
    expect(rediscovered?.unclaimedTargets).toEqual([]);
    expect(permanentTargetsOwnedByAccount(rediscovered!, ["discord.exe", "steam.exe"], "bob"))
      .toEqual([]);
  });

  it("keeps ownership isolated per device and supports an explicit second account claim", () => {
    const storage = new MemoryStorage();
    const pcOne = claimPermanentTargets(storage, "pc-one", ["editor.exe"], "alice");
    const pcTwo = discoverPermanentTargets(storage, "pc-two", ["editor.exe"]);

    expect(permanentTargetsOwnedByAccount(pcOne!, ["editor.exe"], "bob")).toEqual([]);
    expect(pcTwo?.unclaimedTargets).toEqual(["editor.exe"]);

    const sharedCloudTarget = claimPermanentTargets(storage, "pc-one", ["editor.exe"], "bob");
    expect(permanentTargetsOwnedByAccount(sharedCloudTarget!, ["editor.exe"], "alice"))
      .toEqual(["editor.exe"]);
    expect(permanentTargetsOwnedByAccount(sharedCloudTarget!, ["editor.exe"], "bob"))
      .toEqual(["editor.exe"]);
  });
});
