import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const config = JSON.parse(
  readFileSync(resolve(root, "desktop/src-tauri/tauri.conf.json"), "utf8"),
) as { app?: { windows?: Array<Record<string, unknown>> } };
const mainSource = readFileSync(
  resolve(root, "desktop/src-tauri/src/main.rs"),
  "utf8",
);

describe("desktop startup ordering", () => {
  it("defers Tauri's automatically created main window until runtime setup is ready", () => {
    // Tauri auto-creates configured windows before user setup runs, so startup must defer that window.
    expect(config.app?.windows?.[0]).toMatchObject({ create: false });
  });

  it("builds the configured main window after registering every startup runtime", () => {
    const setup = mainSource.match(/\.setup\(\|app\| \{([\s\S]*?)\n\s*\}\)/)?.[1];
    expect(setup).toBeTruthy();

    const requiredRegistrations = [
      "app.manage(auth::BrowserAuthRuntime::load",
      "app.manage(blocker::BlockerRuntime::new())",
      "app.manage(browser_warning::BrowserRepairRuntime::default())",
      "app.manage(runtime)",
    ];
    const builders = [
      "blocker::build_blocker_window",
      "browser_warning::build_browser_warning_window",
      "tauri::WebviewWindowBuilder::from_config",
    ];

    for (const registration of requiredRegistrations) {
      expect(setup).toContain(registration);
    }
    for (const builder of builders) {
      expect(setup).toContain(builder);
    }

    const lastRegistration = Math.max(
      ...requiredRegistrations.map((text) => setup.indexOf(text)),
    );
    const firstBuilder = Math.min(
      ...builders.map((text) => setup.indexOf(text)),
    );
    expect(lastRegistration).toBeLessThan(firstBuilder);
  });

  it("starts tracking only after runtime registration and startup helpers are created", () => {
    const setup = mainSource.match(/\.setup\(\|app\| \{([\s\S]*?)\n\s*\}\)/)?.[1];
    expect(setup).toBeTruthy();
    const start = setup!.indexOf("app.state::<TrackerRuntime>().start");
    expect(start).toBeGreaterThan(-1);

    for (const text of [
      "app.manage(auth::BrowserAuthRuntime::load",
      "app.manage(blocker::BlockerRuntime::new())",
      "app.manage(browser_warning::BrowserRepairRuntime::default())",
      "app.manage(runtime)",
      "blocker::build_blocker_window",
      "browser_warning::build_browser_warning_window",
    ]) {
      expect(setup!.indexOf(text)).toBeLessThan(start);
    }
    expect(mainSource).toContain("tauri::WebviewWindowBuilder::from_config");
  });
});
