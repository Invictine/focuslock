import DeviceActivity
import ExpoModulesCore
import FamilyControls
import ManagedSettings
import SwiftUI
import UIKit

enum ScreenTimeCapabilityGate {
  private static let infoKey = "FocusLockScreenTimeEnabled"
  static var isEnabled: Bool {
    guard let value = Bundle.main.object(forInfoDictionaryKey: infoKey) as? NSNumber else { return true }
    return value.boolValue
  }

  static func unavailableError() -> NSError {
    NSError(
      domain: "FocusLockScreenTime",
      code: 40,
      userInfo: [NSLocalizedDescriptionKey: "Screen Time enforcement is disabled in this build. FocusLock can still run without iOS app blocking."]
    )
  }
}

private enum ScreenTimeStorage {
  static let appGroup = "group.com.focuslock.app.ios"
  static let selection = "focuslock.screenTime.selection.v1"
  static let shielding = "focuslock.screenTime.shielding.v1"
  static let rules = "focuslock.screenTime.rules.v1"
  static let suspended = "focuslock.screenTime.suspended.v1"
  static let strictUntil = "focuslock.screenTime.strictUntil.v1"
  static let accountNukeOwner = "focuslock.screenTime.accountNukeOwner.v1"
  static let accountNukeActive = "focuslock.screenTime.accountNukeActive.v1"
  static let accountNukeOwners = "focuslock.screenTime.accountNukeOwners.v2"
  static let creditEnabled = "focuslock.screenTime.credit.enabled.v1"
  static let creditReconfiguring = "focuslock.screenTime.credit.reconfiguring.v1"
  static let creditSelection = "focuslock.screenTime.credit.selection.v1"
  static let creditCurrentEpoch = "focuslock.screenTime.credit.currentEpoch.v1"
  static let creditOwnerCounters = "focuslock.screenTime.credit.ownerCounters.v1"
  static let creditEpochPrefix = "focuslock.screenTime.credit.epoch."
  static let creditLedgerPrefix = "focuslock.screenTime.credit.ledger."
  static let homeOnly = "focuslock.screenTime.homeOnly.v1"
  static let atHome = "focuslock.screenTime.atHome.v1"
  static let migrated = "focuslock.screenTime.sharedMigration.v1"
  static let manualStore = "focuslock.screenTime"
  static let accountNukeStore = "focuslock.account-nuke"
  static let creditStore = "focuslock.credit"
  static let rulePrefix = "focuslock.rule."
  static let activityPrefix = "focuslock.rule."
  static let creditActivityPrefix = "focuslock.credit."
  static let maximumRules = 12
  static let maximumMonitors = 20
}

private struct CreditEpoch: Codable {
  var id: String
  var ownerId: String
  var startedAt: Double
  var endsAt: Double
  var allowanceMinutes: Int
  var segmentBaseMinutes: Int
  var selectionData: Data
}

private struct CreditUsageLedger: Codable {
  var consumedMinutes: Int = 0
  var dailySeconds: [String: Int] = [:]
  var updatedAt: Double = 0
}

private struct StoredRule: Codable, Equatable {
  var id: String
  var title: String
  var kind: String
  var enabled: Bool
  var startMinute: Int?
  var endMinute: Int?
  var weekdays: [Int]?
  var limitMinutes: Int?
  var endsAt: Double?
  var requiredFocusMinutes: Int?
  var completedDate: String?
  var exemptDate: String?
  var limitReachedDate: String?
  var selectionData: Data?

  var selection: FamilyActivitySelection {
    guard let selectionData,
          let value = try? JSONDecoder().decode(FamilyActivitySelection.self, from: selectionData) else {
      return FamilyActivitySelection()
    }
    return value
  }

  func view(shielding: Bool) -> [String: Any] {
    var result: [String: Any] = [
      "id": id, "title": title, "kind": kind, "enabled": enabled,
      "selectionCount": selectionCount, "shielding": shielding,
    ]
    if let startMinute { result["startMinute"] = startMinute }
    if let endMinute { result["endMinute"] = endMinute }
    if let weekdays { result["weekdays"] = weekdays }
    if let limitMinutes { result["limitMinutes"] = limitMinutes }
    if let endsAt { result["endsAt"] = endsAt }
    if let requiredFocusMinutes { result["requiredFocusMinutes"] = requiredFocusMinutes }
    if let completedDate { result["completedDate"] = completedDate }
    if let exemptDate { result["exemptDate"] = exemptDate }
    return result
  }

  var selectionCount: Int {
    let value = selection
    return value.applicationTokens.count + value.categoryTokens.count + value.webDomainTokens.count
  }
}

private struct RuleInput: Codable {
  var id: String
  var title: String
  var kind: String
  var enabled: Bool
  var startMinute: Int?
  var endMinute: Int?
  var weekdays: [Int]?
  var limitMinutes: Int?
  var endsAt: Double?
  var requiredFocusMinutes: Int?
}

private struct ScreenTimeStatusValue {
  let authorization: String
  let selectionCount: Int
  let shielding: Bool
  let activeRuleCount: Int
  let suspended: Bool
  let strictUntil: Double
  let homeOnly: Bool
  let atHome: Bool?

  var dictionary: [String: Any] {
    [
      "available": true,
      "authorization": authorization,
      "selectionCount": selectionCount,
      "shielding": shielding,
      "activeRuleCount": activeRuleCount,
      "suspended": suspended,
      "strictUntil": strictUntil,
      "homeOnly": homeOnly,
      "atHome": atHome.map { $0 as Any } ?? NSNull(),
    ]
  }
}

public final class FocusLockScreenTimeModule: Module {
  private lazy var center = DeviceActivityCenter()
  private var defaults: UserDefaults {
    guard ScreenTimeCapabilityGate.isEnabled else { return .standard }
    return UserDefaults(suiteName: ScreenTimeStorage.appGroup) ?? .standard
  }
  private var didRestore = false
  @MainActor private var pickerInProgress = false

  private var manualStore: ManagedSettingsStore {
    ManagedSettingsStore(named: ManagedSettingsStore.Name(ScreenTimeStorage.manualStore))
  }

  private var accountNukeStore: ManagedSettingsStore {
    ManagedSettingsStore(named: ManagedSettingsStore.Name(ScreenTimeStorage.accountNukeStore))
  }

  private var creditStore: ManagedSettingsStore {
    ManagedSettingsStore(named: ManagedSettingsStore.Name(ScreenTimeStorage.creditStore))
  }

  public func definition() -> ModuleDefinition {
    Name("FocusLockScreenTime")

    OnCreate {
      guard ScreenTimeCapabilityGate.isEnabled else { return }
      Task { @MainActor in self.restoreOnceIfNeeded() }
    }
    OnAppEntersForeground {
      guard ScreenTimeCapabilityGate.isEnabled else { return }
      Task { @MainActor in self.evaluateRulesOnForeground() }
    }

    AsyncFunction("getStatus") { () async -> [String: Any] in
      await self.statusAsync()
    }
    AsyncFunction("requestAuthorization") { () async throws -> [String: Any] in
      try self.requireBuildCapability()
      return try await self.requestAuthorizationAsync()
    }
    AsyncFunction("presentPicker") { () async throws -> [String: Any] in
      try self.requireBuildCapability()
      return try await self.presentManualPickerAsync()
    }
    AsyncFunction("setShielding") { (enabled: Bool) async throws -> [String: Any] in
      if !enabled && !ScreenTimeCapabilityGate.isEnabled { return self.unavailableStatus() }
      try self.requireBuildCapability()
      return try await self.setManualShieldingAsync(enabled: enabled)
    }
    AsyncFunction("getRules") { () async -> [[String: Any]] in
      await self.getRulesAsync()
    }
    AsyncFunction("saveRule") { (input: [String: Any]) async throws -> [[String: Any]] in
      try self.requireBuildCapability()
      return try await self.saveRuleAsync(input)
    }
    AsyncFunction("pickRule") { (id: String) async throws -> [[String: Any]] in
      try self.requireBuildCapability()
      return try await self.pickRuleAsync(id)
    }
    AsyncFunction("deleteRule") { (id: String) async throws -> [[String: Any]] in
      try self.requireBuildCapability()
      return try await self.deleteRuleAsync(id)
    }
    AsyncFunction("suspendAll") { () async -> [String: Any] in
      if !ScreenTimeCapabilityGate.isEnabled { return self.unavailableStatus() }
      return await self.suspendAllAsync()
    }
    AsyncFunction("resumeAll") { () async throws -> [String: Any] in
      try self.requireBuildCapability()
      return try await self.resumeAllAsync()
    }
    AsyncFunction("setStrictUntil") { (timestampMs: Double) async throws -> [String: Any] in
      try self.requireBuildCapability()
      return try await self.setStrictUntilAsync(timestampMs)
    }
    AsyncFunction("setHomeOnly") { (enabled: Bool, atHome: Bool?) async throws -> [String: Any] in
      try self.requireBuildCapability()
      return try await self.setHomeOnlyAsync(enabled: enabled, atHome: atHome)
    }
    AsyncFunction("setAccountNuke") { (ownerId: String, active: Bool) async throws -> [String: Any] in
      try self.requireBuildCapability()
      return try await self.setAccountNukeAsync(ownerId: ownerId, active: active)
    }
    AsyncFunction("getCreditGate") { () async -> [String: Any] in
      await self.creditGateStatusAsync()
    }
    AsyncFunction("pickCreditApps") { () async throws -> [String: Any] in
      try self.requireBuildCapability()
      return try await self.pickCreditAppsAsync()
    }
    AsyncFunction("setCreditGateEnabled") { (enabled: Bool) async throws -> [String: Any] in
      try self.requireBuildCapability()
      return try await self.setCreditGateEnabledAsync(enabled)
    }
    AsyncFunction("configureCreditAllowance") { (ownerId: String, minutes: Int) async throws -> [String: Any] in
      try self.requireBuildCapability()
      return try await self.configureCreditAllowanceAsync(ownerId: ownerId, minutes: minutes)
    }
    AsyncFunction("getLeisureUsage") { (ownerId: String) async throws -> [[String: Any]] in
      try self.requireBuildCapability()
      return try await self.leisureUsageAsync(ownerId: ownerId)
    }
    AsyncFunction("updateTaskProgress") { (id: String, trackedMinutes: Int, checkedOff: Bool, focusMinutesToday: Int) async throws -> [[String: Any]] in
      try self.requireBuildCapability()
      return try await self.updateTaskProgressAsync(id, trackedMinutes: trackedMinutes, checkedOff: checkedOff, focusMinutesToday: focusMinutesToday)
    }

    View(FocusLockScreenTimeReportView.self) {
      ViewName("FocusLockScreenTimeReport")
    }
  }

  @MainActor
  private func statusAsync() -> [String: Any] {
    guard ScreenTimeCapabilityGate.isEnabled else { return unavailableStatus() }
    restoreOnceIfNeeded()
    let authorization = AuthorizationCenter.shared.authorizationStatus
    let manualShielding = defaults.bool(forKey: ScreenTimeStorage.shielding)
      && !isSuspended
      && authorization == .approved
      && hasAppliedPolicy(manualStore)
    let activeCount = authorization == .approved && !isSuspended
      ? loadRules().filter { isRuleActive($0) }.count
        + (hasAppliedPolicy(creditStore) ? 1 : 0)
        + (hasAppliedPolicy(accountNukeStore) ? 1 : 0) : 0
    return ScreenTimeStatusValue(
      authorization: authorizationName(authorization),
      selectionCount: count(loadManualSelection()),
      shielding: manualShielding,
      activeRuleCount: activeCount,
      suspended: isSuspended,
      strictUntil: strictUntil,
      homeOnly: homeOnly,
      atHome: atHome
    ).dictionary
  }

  private func unavailableStatus() -> [String: Any] {
    [
      "available": false,
      "authorization": "notDetermined",
      "selectionCount": 0,
      "shielding": false,
      "activeRuleCount": 0,
      "suspended": false,
      "strictUntil": 0,
      "homeOnly": false,
      "atHome": NSNull(),
    ]
  }

  private func requireBuildCapability() throws {
    guard ScreenTimeCapabilityGate.isEnabled else { throw ScreenTimeCapabilityGate.unavailableError() }
  }

  @MainActor
  private func requestAuthorizationAsync() async throws -> [String: Any] {
    restoreOnceIfNeeded()
    let auth = AuthorizationCenter.shared
    if auth.authorizationStatus == .notDetermined { try await auth.requestAuthorization(for: .individual) }
    guard auth.authorizationStatus == .approved else { throw authorizationError(auth.authorizationStatus) }
    if !isSuspended { try restorePolicies() }
    return statusAsync()
  }

  @MainActor
  private func presentManualPickerAsync() async throws -> [String: Any] {
    try requireEditable()
    guard !isManualShieldingEnabled else { throw ruleError(10, "Stop Screen Time shielding before changing the selected apps or websites.") }
    let saved = loadManualSelection()
    if let selection = try await presentPicker(initialSelection: saved) {
      try saveManualSelection(selection)
      restoreAccountNuke()
    }
    return statusAsync()
  }

  @MainActor
  private func setManualShieldingAsync(enabled: Bool) throws -> [String: Any] {
    restoreOnceIfNeeded()
    if enabled {
      guard !isSuspended else { throw ruleError(11, "Resume Screen Time rules before enabling manual shielding.") }
      guard AuthorizationCenter.shared.authorizationStatus == .approved else {
        throw authorizationError(AuthorizationCenter.shared.authorizationStatus)
      }
      let selection = loadManualSelection()
      guard count(selection) > 0 else { throw ruleError(12, "Choose at least one app, category, or website first.") }
      apply(selection, to: manualStore)
      if isHomeSuppressed { manualStore.clearAllSettings() }
      defaults.set(true, forKey: ScreenTimeStorage.shielding)
    } else {
      manualStore.clearAllSettings()
      defaults.set(false, forKey: ScreenTimeStorage.shielding)
    }
    return statusAsync()
  }

  @MainActor
  private func getRulesAsync() -> [[String: Any]] {
    guard ScreenTimeCapabilityGate.isEnabled else { return [] }
    restoreOnceIfNeeded()
    if AuthorizationCenter.shared.authorizationStatus == .approved && !isSuspended {
      try? restorePolicies() // Re-evaluate active schedules when the app returns.
    }
    return ruleViews()
  }

  @MainActor
  private func saveRuleAsync(_ raw: [String: Any]) throws -> [[String: Any]] {
    try requireEditable()
    let input = try decodeInput(raw)
    var rules = loadRules()
    let originalRules = rules
    let old = rules.first(where: { $0.id == input.id })
    if isStrictActive {
      guard let old, mayOnlyStrengthen(old: old, input: input) else {
        throw ruleError(13, "Strict Mode is active. Rule changes are locked until it ends.")
      }
    }
    if let old, old.kind == "permanent", old.enabled, !input.enabled {
      throw ruleError(14, "A permanent rule cannot be turned off or removed. Use emergency suspend if needed.")
    }
    if rules.count > ScreenTimeStorage.maximumRules || (old == nil && rules.count >= ScreenTimeStorage.maximumRules) {
      throw ruleError(15, "FocusLock supports up to 12 Screen Time rules.")
    }
    var next = StoredRule(
      id: input.id, title: input.title.trimmingCharacters(in: .whitespacesAndNewlines), kind: input.kind, enabled: input.enabled,
      startMinute: input.startMinute, endMinute: input.endMinute, weekdays: input.weekdays,
      limitMinutes: input.limitMinutes, endsAt: input.endsAt,
      requiredFocusMinutes: input.requiredFocusMinutes,
      completedDate: old?.completedDate, exemptDate: old?.exemptDate,
      limitReachedDate: old?.limitReachedDate,
      selectionData: old?.selectionData
    )
    try validateRule(next, old: old)
    if next.enabled && next.selectionCount == 0 { throw ruleError(16, "Pick at least one app, category, or website before enabling this rule.") }
    if next.kind == "timed", next.enabled {
      guard let endsAt = next.endsAt, endsAt >= Date().timeIntervalSince1970 * 1000 + 15 * 60_000 else {
        throw ruleError(17, "Timed rules must end at least 15 minutes from now.")
      }
    }
    if let index = rules.firstIndex(where: { $0.id == input.id }) { rules[index] = next }
    else { rules.append(next) }
    try saveRules(rules)
    do {
      try reconcile(next)
    } catch {
      try? saveRules(originalRules)
      if let old { try? reconcile(old) }
      else { stopMonitor(next.id); ruleStore(next.id).clearAllSettings() }
      throw error
    }
    restoreAccountNuke()
    return ruleViews()
  }

  @MainActor
  private func pickRuleAsync(_ id: String) async throws -> [[String: Any]] {
    try requireEditable()
    guard !isStrictActive else { throw ruleError(13, "Rule selections are locked during Strict Mode.") }
    var rules = loadRules()
    guard let index = rules.firstIndex(where: { $0.id == id }) else { throw ruleError(18, "Screen Time rule not found.") }
    guard !rules[index].enabled else { throw ruleError(19, "Turn the rule off before changing its selection.") }
    let saved = rules[index].selection
    if let selection = try await presentPicker(initialSelection: saved) {
      var encoded = rules[index]
      encoded.selectionData = try JSONEncoder().encode(selection)
      rules[index] = encoded
      try saveRules(rules)
      restoreAccountNuke()
    }
    return ruleViews()
  }

  @MainActor
  private func deleteRuleAsync(_ id: String) throws -> [[String: Any]] {
    try requireEditable()
    guard !isStrictActive else { throw ruleError(13, "Rules are locked during Strict Mode.") }
    var rules = loadRules()
    guard let old = rules.first(where: { $0.id == id }) else { return ruleViews() }
    guard !(old.kind == "permanent" && old.enabled) else {
      throw ruleError(14, "A permanent rule cannot be removed. Use emergency suspend if needed.")
    }
    stopMonitor(id)
    ruleStore(id).clearAllSettings()
    defaults.removeObject(forKey: limitReachedKey(id))
    try saveRules(rules.filter { $0.id != id })
    restoreAccountNuke()
    return ruleViews()
  }

  @MainActor
  private func suspendAllAsync() -> [String: Any] {
    defaults.set(true, forKey: ScreenTimeStorage.suspended)
    let ownMonitors = center.activities.filter {
      $0.rawValue.hasPrefix(ScreenTimeStorage.activityPrefix)
        || $0.rawValue.hasPrefix(ScreenTimeStorage.creditActivityPrefix)
    }
    center.stopMonitoring(ownMonitors)
    manualStore.clearAllSettings()
    for rule in loadRules() { ruleStore(rule.id).clearAllSettings() }
    accountNukeStore.clearAllSettings()
    creditStore.clearAllSettings()
    defaults.set(false, forKey: ScreenTimeStorage.creditReconfiguring)
    return statusAsync()
  }

  @MainActor
  private func resumeAllAsync() throws -> [String: Any] {
    guard AuthorizationCenter.shared.authorizationStatus == .approved else {
      throw authorizationError(AuthorizationCenter.shared.authorizationStatus)
    }
    if isAccountNukeActive && count(accountNukeSelection()) == 0 {
      throw ruleError(42, "Account Nuke is still requested, but no selected apps or websites remain. Choose targets before resuming Screen Time rules.")
    }
    defaults.set(false, forKey: ScreenTimeStorage.suspended)
    do {
      try restorePolicies()
    } catch {
      _ = suspendAllAsync()
      throw error
    }
    return statusAsync()
  }

  @MainActor
  private func setStrictUntilAsync(_ timestampMs: Double) throws -> [String: Any] {
    guard timestampMs.isFinite && timestampMs >= 0 else { throw ruleError(20, "Strict Mode end time must be a valid timestamp.") }
    if timestampMs > strictUntil { defaults.set(timestampMs, forKey: ScreenTimeStorage.strictUntil) }
    return statusAsync()
  }

  @MainActor
  private func setHomeOnlyAsync(enabled: Bool, atHome: Bool?) throws -> [String: Any] {
    defaults.set(enabled, forKey: ScreenTimeStorage.homeOnly)
    if let atHome { defaults.set(atHome, forKey: ScreenTimeStorage.atHome) }
    else { defaults.removeObject(forKey: ScreenTimeStorage.atHome) }
    if !isSuspended { try reevaluateHomeGatedPolicies() }
    return statusAsync()
  }

  @MainActor
  private func setAccountNukeAsync(ownerId rawOwnerId: String, active: Bool) throws -> [String: Any] {
    restoreOnceIfNeeded()
    let ownerId = rawOwnerId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !ownerId.isEmpty, ownerId.count <= 128 else {
      throw ruleError(41, "An account ID is required to manage this device's account Nuke policy.")
    }
    if active {
      guard !isSuspended else { throw ruleError(11, "Resume Screen Time rules before enabling account Nuke on this device.") }
      guard AuthorizationCenter.shared.authorizationStatus == .approved else {
        throw authorizationError(AuthorizationCenter.shared.authorizationStatus)
      }
      let selection = accountNukeSelection()
      guard count(selection) > 0 else {
        throw ruleError(42, "Choose at least one app, category, or website in Screen Time before enabling account Nuke.")
      }
      var owners = activeAccountNukeOwners()
      owners.insert(ownerId)
      defaults.set(owners.sorted(), forKey: ScreenTimeStorage.accountNukeOwners)
      apply(selection, to: accountNukeStore)
      return statusAsync()
    }

    var owners = activeAccountNukeOwners()
    guard owners.remove(ownerId) != nil else { return statusAsync() }
    defaults.set(owners.sorted(), forKey: ScreenTimeStorage.accountNukeOwners)
    if owners.isEmpty { accountNukeStore.clearAllSettings() }
    else { restoreAccountNuke() }
    return statusAsync()
  }

  @MainActor
  private func updateTaskProgressAsync(_ id: String, trackedMinutes: Int, checkedOff: Bool, focusMinutesToday: Int) throws -> [[String: Any]] {
    guard (0...100_000).contains(trackedMinutes), (0...100_000).contains(focusMinutesToday) else {
      throw ruleError(21, "Tracked focus minutes must be between 0 and 100,000.")
    }
    var rules = loadRules()
    guard let index = rules.firstIndex(where: { $0.id == id }), rules[index].kind == "task" else {
      throw ruleError(22, "Task rule not found.")
    }
    let today = Self.localDateKey(Date())
    var task = rules[index]
    if focusMinutesToday > 30 { task.exemptDate = today }
    if checkedOff, trackedMinutes >= (task.requiredFocusMinutes ?? Int.max) { task.completedDate = today }
    rules[index] = task
    try saveRules(rules)
    try reconcile(task)
    restoreAccountNuke()
    return ruleViews()
  }

  @MainActor
  private func creditGateStatusAsync() -> [String: Any] {
    guard ScreenTimeCapabilityGate.isEnabled else {
      return ["enabled": false, "selectionCount": 0, "allowanceMinutes": 0, "exhausted": false]
    }
    restoreOnceIfNeeded()
    let epoch = currentCreditEpoch()
    let ledger = epoch.map { creditLedger($0.id) }
    let exhausted = epoch.map {
      $0.allowanceMinutes == 0
        || (ledger?.consumedMinutes ?? 0) >= $0.allowanceMinutes
        || Date().timeIntervalSince1970 * 1000 >= $0.endsAt
    } ?? false
    var result: [String: Any] = [
      "enabled": defaults.bool(forKey: ScreenTimeStorage.creditEnabled),
      "selectionCount": count(loadCreditSelection()),
      "allowanceMinutes": epoch?.allowanceMinutes ?? 0,
      "exhausted": exhausted,
    ]
    if let epoch {
      result["ownerId"] = epoch.ownerId
      result["expiresAt"] = epoch.endsAt
    }
    return result
  }

  @MainActor
  private func pickCreditAppsAsync() async throws -> [String: Any] {
    try requireEditable()
    guard !defaults.bool(forKey: ScreenTimeStorage.creditEnabled) else {
      throw ruleError(44, "Turn off the credit gate before changing its selected apps or websites.")
    }
    guard !isStrictActive else { throw ruleError(13, "Credit app selections are locked during Strict Mode.") }
    if let selection = try await presentPicker(initialSelection: loadCreditSelection()) {
      defaults.set(try JSONEncoder().encode(selection), forKey: ScreenTimeStorage.creditSelection)
    }
    return creditGateStatusAsync()
  }

  @MainActor
  private func setCreditGateEnabledAsync(_ enabled: Bool) throws -> [String: Any] {
    restoreOnceIfNeeded()
    let wasEnabled = defaults.bool(forKey: ScreenTimeStorage.creditEnabled)
    if !enabled {
      guard !isStrictActive else { throw ruleError(45, "Strict Mode prevents disabling the credit gate. Use emergency suspend if needed.") }
      defaults.set(false, forKey: ScreenTimeStorage.creditEnabled)
      if let epoch = currentCreditEpoch() { stopCreditMonitor(epoch.id) }
      creditStore.clearAllSettings()
      restoreAccountNuke()
      return creditGateStatusAsync()
    }
    guard !isSuspended else { throw ruleError(11, "Resume Screen Time rules before enabling the credit gate.") }
    guard AuthorizationCenter.shared.authorizationStatus == .approved else {
      throw authorizationError(AuthorizationCenter.shared.authorizationStatus)
    }
    guard count(loadCreditSelection()) > 0 else {
      throw ruleError(46, "Choose at least one app, category, or website for credit spending first.")
    }
    guard let epoch = currentCreditEpoch() else {
      throw ruleError(47, "Sync a credit allowance before enabling the credit gate.")
    }
    guard count(creditSelection(epoch)) > 0,
          selectionsMatch(creditSelection(epoch), loadCreditSelection()) else {
      throw ruleError(52, "The saved credit allowance does not match the currently selected apps. Sync the allowance again before enabling it.")
    }
    defaults.set(true, forKey: ScreenTimeStorage.creditEnabled)
    do {
      try restoreCreditPolicy()
    } catch {
      defaults.set(wasEnabled, forKey: ScreenTimeStorage.creditEnabled)
      if let oldEpoch = currentCreditEpoch() { try? restoreCreditPolicy(for: oldEpoch) }
      throw error
    }
    restoreAccountNuke()
    return creditGateStatusAsync()
  }

  @MainActor
  private func configureCreditAllowanceAsync(ownerId rawOwnerId: String, minutes: Int) throws -> [String: Any] {
    restoreOnceIfNeeded()
    let ownerId = rawOwnerId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !ownerId.isEmpty, ownerId.count <= 128 else { throw ruleError(48, "An account ID is required to configure credit spending.") }
    guard (0...60).contains(minutes) else { throw ruleError(49, "Credit allowance must be between 0 and 60 minutes per sync.") }
    guard AuthorizationCenter.shared.authorizationStatus == .approved else {
      throw authorizationError(AuthorizationCenter.shared.authorizationStatus)
    }
    guard !isSuspended else { throw ruleError(11, "Resume Screen Time rules before configuring credit spending.") }
    let selection = loadCreditSelection()
    guard count(selection) > 0 else { throw ruleError(46, "Choose at least one app, category, or website for credit spending first.") }
    let oldData = defaults.data(forKey: ScreenTimeStorage.creditCurrentEpoch)
    let oldEpoch = currentCreditEpoch()
    if isStrictActive, let oldEpoch,
       (oldEpoch.ownerId != ownerId || oldEpoch.allowanceMinutes != minutes) {
      throw ruleError(50, "Strict Mode prevents changing the configured credit allowance.")
    }
    if let oldEpoch, oldEpoch.ownerId == ownerId, oldEpoch.allowanceMinutes == minutes,
       selectionsMatch(creditSelection(oldEpoch), selection),
       Date().timeIntervalSince1970 * 1000 < oldEpoch.endsAt,
       creditLedger(oldEpoch.id).consumedMinutes < minutes {
      return creditGateStatusAsync() // Keep the current threshold schedule and partial minute intact.
    }

    pruneCreditEpochs()
    let epochCount = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(ScreenTimeStorage.creditEpochPrefix) }.count
    guard epochCount < 1_000 else { throw ruleError(51, "The local credit usage history reached its safe limit. Sync and try again after older records expire.") }
    let now = Date().timeIntervalSince1970 * 1000
    let epoch = CreditEpoch(
      id: UUID().uuidString.lowercased(), ownerId: ownerId, startedAt: now, endsAt: now + 24 * 60 * 60 * 1000,
      allowanceMinutes: minutes, segmentBaseMinutes: 0, selectionData: try JSONEncoder().encode(selection)
    )
    if defaults.bool(forKey: ScreenTimeStorage.creditEnabled) {
      defaults.set(true, forKey: ScreenTimeStorage.creditReconfiguring)
    }
    if let oldEpoch { stopCreditMonitor(oldEpoch.id) }
    do {
      try saveCreditEpoch(epoch)
      defaults.set(try JSONEncoder().encode(epoch), forKey: ScreenTimeStorage.creditCurrentEpoch)
      if defaults.bool(forKey: ScreenTimeStorage.creditEnabled) {
        try restoreCreditPolicy(for: epoch)
        defaults.set(false, forKey: ScreenTimeStorage.creditReconfiguring)
      }
      else { creditStore.clearAllSettings() }
    } catch {
      stopCreditMonitor(epoch.id)
      defaults.removeObject(forKey: creditEpochKey(epoch.id))
      if let oldData { defaults.set(oldData, forKey: ScreenTimeStorage.creditCurrentEpoch) }
      else { defaults.removeObject(forKey: ScreenTimeStorage.creditCurrentEpoch) }
      defaults.set(false, forKey: ScreenTimeStorage.creditReconfiguring)
      if let oldEpoch, defaults.bool(forKey: ScreenTimeStorage.creditEnabled) { try? restoreCreditPolicy(for: oldEpoch) }
      throw error
    }
    return creditGateStatusAsync()
  }

  @MainActor
  private func leisureUsageAsync(ownerId rawOwnerId: String) throws -> [[String: Any]] {
    restoreOnceIfNeeded()
    let ownerId = rawOwnerId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !ownerId.isEmpty, ownerId.count <= 128 else { throw ruleError(48, "An account ID is required to read credit usage.") }
    let counterId = creditCounterId(ownerId)
    var totals: [String: (seconds: Int, updatedAt: Double)] = [:]
    for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(ScreenTimeStorage.creditEpochPrefix) {
      guard let data = defaults.data(forKey: key), let epoch = try? JSONDecoder().decode(CreditEpoch.self, from: data),
            epoch.ownerId == ownerId else { continue }
      let ledger = creditLedger(epoch.id)
      for (date, seconds) in ledger.dailySeconds {
        let prior = totals[date] ?? (0, 0)
        totals[date] = (prior.seconds + max(0, seconds), max(prior.updatedAt, ledger.updatedAt))
      }
    }
    return totals.keys.sorted().map { date in
      let value = totals[date] ?? (0, 0)
      return ["counterId": counterId, "date": date, "measuredSeconds": value.seconds, "updatedAt": value.updatedAt]
    }
  }

  @MainActor
  private func creditCounterId(_ ownerId: String) -> String {
    var counters = defaults.dictionary(forKey: ScreenTimeStorage.creditOwnerCounters) as? [String: String] ?? [:]
    if let existing = counters[ownerId], UUID(uuidString: existing) != nil { return existing }
    let created = UUID().uuidString.lowercased()
    counters[ownerId] = created
    defaults.set(counters, forKey: ScreenTimeStorage.creditOwnerCounters)
    return created
  }

  @MainActor
  private func presentPicker(initialSelection: FamilyActivitySelection) async throws -> FamilyActivitySelection? {
    guard !pickerInProgress else { throw ruleError(23, "The Screen Time picker is already open.") }
    pickerInProgress = true
    defer { pickerInProgress = false }
    return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<FamilyActivitySelection?, Error>) in
      guard let presenter = Self.topmostViewController() else {
        continuation.resume(throwing: ruleError(24, "FocusLock could not open the Screen Time picker."))
        return
      }
      let gate = PickerCompletionGate()
      let picker = ScreenTimePickerController(
        selection: initialSelection,
        onDone: { [weak self] selection in
          guard gate.beginDismissal() else { return }
          presenter.dismiss(animated: true) { continuation.resume(returning: selection) }
        },
        onCancel: {
          guard gate.beginDismissal() else { return }
          presenter.dismiss(animated: true) { continuation.resume(returning: nil) }
        }
      )
      picker.modalPresentationStyle = .formSheet
      presenter.present(picker, animated: true)
    }
  }

  @MainActor
  private func requireEditable() throws {
    try requireBuildCapability()
    restoreOnceIfNeeded()
    guard AuthorizationCenter.shared.authorizationStatus == .approved else {
      throw authorizationError(AuthorizationCenter.shared.authorizationStatus)
    }
    guard !isSuspended else { throw ruleError(11, "Resume Screen Time rules before editing them.") }
  }

  @MainActor
  private func validateRule(_ rule: StoredRule, old: StoredRule?) throws {
    guard Self.validId(rule.id) else { throw ruleError(25, "Rule IDs must use 1–32 letters, numbers, hyphens, or underscores.") }
    guard !rule.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, rule.title.count <= 80 else {
      throw ruleError(26, "Rule titles must contain 1–80 characters.")
    }
    switch rule.kind {
    case "schedule":
      guard let start = rule.startMinute, let end = rule.endMinute,
            (0...1439).contains(start), (0...1439).contains(end), start != end,
            let days = rule.weekdays, !days.isEmpty, days.allSatisfy({ (1...7).contains($0) }) else {
        throw ruleError(27, "Schedules need valid start/end minutes and weekdays (1=Sunday through 7=Saturday).")
      }
      let duration = end > start ? end - start : 1440 - start + end
      guard duration >= 15 else { throw ruleError(27, "Schedules must last at least 15 minutes on iOS.") }
    case "dailyLimit":
      guard let limit = rule.limitMinutes, (1...1440).contains(limit) else {
        throw ruleError(28, "Daily limits must be between 1 minute and 24 hours.")
      }
    case "permanent": break
    case "timed":
      guard let end = rule.endsAt, end.isFinite else { throw ruleError(29, "Timed rules need a valid end timestamp.") }
    case "task":
      guard let start = rule.startMinute, (0...1424).contains(start),
            let required = rule.requiredFocusMinutes, (1...1440).contains(required) else {
        throw ruleError(30, "Task rules need a start before 11:45 PM and required focus between 1 and 1440 minutes.")
      }
    default: throw ruleError(31, "Unsupported Screen Time rule kind.")
    }
    if old?.kind == "permanent", old?.enabled == true && rule.kind != "permanent" {
      throw ruleError(14, "An enabled permanent rule cannot be changed to another kind.")
    }
  }

  private func decodeInput(_ raw: [String: Any]) throws -> RuleInput {
    guard let data = try? JSONSerialization.data(withJSONObject: raw),
          let input = try? JSONDecoder().decode(RuleInput.self, from: data) else {
      throw ruleError(32, "Invalid Screen Time rule input.")
    }
    return input
  }

  @MainActor
  private func mayOnlyStrengthen(old: StoredRule, input: RuleInput) -> Bool {
    old.kind == input.kind && old.title == input.title && old.startMinute == input.startMinute
      && old.endMinute == input.endMinute && old.weekdays == input.weekdays
      && old.limitMinutes == input.limitMinutes && old.endsAt == input.endsAt
      && old.requiredFocusMinutes == input.requiredFocusMinutes && (!old.enabled && input.enabled)
  }

  @MainActor
  private func reconcile(_ rule: StoredRule) throws {
    let store = ruleStore(rule.id)
    if isSuspended || !rule.enabled || rule.selectionCount == 0
      || AuthorizationCenter.shared.authorizationStatus != .approved {
      stopMonitor(rule.id)
      store.clearAllSettings()
      return
    }
    let now = Date()
    switch rule.kind {
    case "permanent":
      stopMonitor(rule.id)
      apply(rule.selection, to: store)
    case "timed":
      guard let endsAt = rule.endsAt, endsAt > now.timeIntervalSince1970 * 1000 else {
        stopMonitor(rule.id)
        store.clearAllSettings()
        return
      }
      if !isHomeSuppressed { apply(rule.selection, to: store) }
      else { store.clearAllSettings() }
      let startComponents = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: now)
      let endComponents = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: Date(timeIntervalSince1970: endsAt / 1000))
      try startMonitor(rule, schedule: DeviceActivitySchedule(intervalStart: startComponents, intervalEnd: endComponents, repeats: false))
    case "dailyLimit":
      if rule.limitReachedDate == Self.localDateKey(now), !isHomeSuppressed {
        apply(rule.selection, to: store)
      } else {
        store.clearAllSettings()
      }
      let start = DateComponents(hour: 0, minute: 0)
      let end = DateComponents(hour: 23, minute: 59)
      let selection = rule.selection
      let limit = rule.limitMinutes ?? 1
      let event = DeviceActivityEvent(
        applications: selection.applicationTokens,
        categories: selection.categoryTokens,
        webDomains: selection.webDomainTokens,
        threshold: DateComponents(hour: limit / 60, minute: limit % 60),
        includesPastActivity: true
      )
      try startMonitor(rule, schedule: DeviceActivitySchedule(intervalStart: start, intervalEnd: end, repeats: true), events: [DeviceActivityEvent.Name("limit"): event])
    case "schedule":
      try startMonitor(rule, schedule: scheduleFor(rule, endMinute: rule.endMinute ?? 1439))
      if !isHomeSuppressed, ruleIsInActiveInterval(rule, now: now), isSelectedWeekday(rule, date: scheduleStartDate(rule, now: now)) { apply(rule.selection, to: store) }
      else { store.clearAllSettings() }
    case "task":
      try startMonitor(rule, schedule: scheduleFor(rule, endMinute: 1439))
      if !isHomeSuppressed, taskRuleIsActive(rule, now: now) { apply(rule.selection, to: store) }
      else { store.clearAllSettings() }
    default: break
    }
  }

  @MainActor
  private func restorePolicies() throws {
    if defaults.bool(forKey: ScreenTimeStorage.shielding) {
      let selection = loadManualSelection()
      if count(selection) > 0 && !isHomeSuppressed { apply(selection, to: manualStore) }
      else { manualStore.clearAllSettings() }
    } else {
      manualStore.clearAllSettings()
    }
    let rules = loadRules()
    guard rules.count <= ScreenTimeStorage.maximumRules else { throw ruleError(15, "Stored rule count exceeds the supported limit.") }
    for rule in rules { try reconcile(rule) }
    try restoreCreditPolicy()
    restoreAccountNuke()
  }

  @MainActor
  private func restoreCreditPolicy() throws {
    guard let epoch = currentCreditEpoch() else {
      creditStore.clearAllSettings()
      return
    }
    try restoreCreditPolicy(for: epoch)
  }

  @MainActor
  private func restoreCreditPolicy(for epoch: CreditEpoch) throws {
    guard defaults.bool(forKey: ScreenTimeStorage.creditEnabled) else {
      defaults.set(false, forKey: ScreenTimeStorage.creditReconfiguring)
      stopCreditMonitor(epoch.id)
      creditStore.clearAllSettings()
      return
    }
    guard !isSuspended else {
      defaults.set(false, forKey: ScreenTimeStorage.creditReconfiguring)
      creditStore.clearAllSettings()
      return
    }
    guard AuthorizationCenter.shared.authorizationStatus == .approved else {
      defaults.set(false, forKey: ScreenTimeStorage.creditReconfiguring)
      creditStore.clearAllSettings()
      return
    }
    guard count(creditSelection(epoch)) > 0 else {
      throw ruleError(52, "The saved credit app selection is missing. Choose apps and sync the allowance again.")
    }
    if isHomeSuppressed { creditStore.clearAllSettings() }
    let ledger = creditLedger(epoch.id)
    let expired = Date().timeIntervalSince1970 * 1000 >= epoch.endsAt
    let exhausted = epoch.allowanceMinutes == 0 || ledger.consumedMinutes >= epoch.allowanceMinutes || expired
    if exhausted {
      defaults.set(false, forKey: ScreenTimeStorage.creditReconfiguring)
      stopCreditMonitor(epoch.id)
      if !isHomeSuppressed { apply(creditSelection(epoch), to: creditStore) }
      return
    }
    if hasCreditMonitor(epoch.id) {
      defaults.set(false, forKey: ScreenTimeStorage.creditReconfiguring)
      creditStore.clearAllSettings()
      return
    }
    do {
      try startCreditMonitor(epoch)
      defaults.set(false, forKey: ScreenTimeStorage.creditReconfiguring)
      if !isHomeSuppressed { creditStore.clearAllSettings() }
    } catch {
      // If Apple refuses the monitor, keep unmeasured use blocked by this store.
      // This does not write a usage ledger entry or claim the allowance was spent.
      if !isHomeSuppressed { apply(creditSelection(epoch), to: creditStore) }
      throw error
    }
  }

  @MainActor
  private func startCreditMonitor(_ sourceEpoch: CreditEpoch) throws {
    let consumed = creditLedger(sourceEpoch.id).consumedMinutes
    let remaining = max(0, sourceEpoch.allowanceMinutes - consumed)
    guard remaining > 0 else {
      if !isHomeSuppressed { apply(creditSelection(sourceEpoch), to: creditStore) }
      return
    }
    let name = creditActivityName(sourceEpoch.id, segmentBase: consumed)
    if center.activities.contains(name) { return }
    guard center.activities.count < ScreenTimeStorage.maximumMonitors else {
      throw ruleError(33, "The iOS Screen Time monitor limit has been reached. Remove an unused rule first.")
    }
    let now = Date()
    let end = Date(timeIntervalSince1970: sourceEpoch.endsAt / 1000)
    let duration = end.timeIntervalSince(now)
    guard duration >= 15 * 60 else {
      // There is too little interval left for Apple's minimum monitor window.
      // Hold only our credit store until the current allowance expires; do not invent usage.
      if !isHomeSuppressed { apply(creditSelection(sourceEpoch), to: creditStore) }
      return
    }
    // Encode the immutable threshold baseline in this segment's activity name.
    // A delayed callback from a stopped segment must not inherit a newer base.
    let epoch = sourceEpoch
    var startParts = Calendar.current.dateComponents([.calendar, .timeZone, .year, .month, .day, .hour, .minute, .second], from: now)
    var endParts = Calendar.current.dateComponents([.calendar, .timeZone, .year, .month, .day, .hour, .minute, .second], from: end)
    startParts.calendar = Calendar.current
    endParts.calendar = Calendar.current
    let schedule = DeviceActivitySchedule(intervalStart: startParts, intervalEnd: endParts, repeats: false)
    let selection = creditSelection(epoch)
    guard count(selection) > 0 else {
      throw ruleError(52, "The saved credit app selection is missing. Choose apps and sync the allowance again.")
    }
    var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
    for minute in 1...min(remaining, 60) {
      events[DeviceActivityEvent.Name("usage_\(minute)")] = DeviceActivityEvent(
        applications: selection.applicationTokens,
        categories: selection.categoryTokens,
        webDomains: selection.webDomainTokens,
        threshold: DateComponents(minute: minute),
        includesPastActivity: false
      )
    }
    try center.startMonitoring(name, during: schedule, events: events)
    creditStore.clearAllSettings()
  }

  @MainActor
  private func stopCreditMonitor(_ id: String) {
    let names = center.activities.filter {
      $0.rawValue == ScreenTimeStorage.creditActivityPrefix + id
        || $0.rawValue.hasPrefix(ScreenTimeStorage.creditActivityPrefix + id + ".")
    }
    if !names.isEmpty { center.stopMonitoring(names) }
  }

  @MainActor
  private func hasCreditMonitor(_ id: String) -> Bool {
    center.activities.contains {
      $0.rawValue == ScreenTimeStorage.creditActivityPrefix + id
        || $0.rawValue.hasPrefix(ScreenTimeStorage.creditActivityPrefix + id + ".")
    }
  }

  @MainActor
  private func creditActivityName(_ id: String, segmentBase: Int) -> DeviceActivityName {
    DeviceActivityName(ScreenTimeStorage.creditActivityPrefix + id + "." + String(segmentBase))
  }

  @MainActor
  private func creditSelection(_ epoch: CreditEpoch) -> FamilyActivitySelection {
    guard let selection = try? JSONDecoder().decode(FamilyActivitySelection.self, from: epoch.selectionData) else {
      return FamilyActivitySelection()
    }
    return selection
  }

  @MainActor
  private func currentCreditEpoch() -> CreditEpoch? {
    guard let data = defaults.data(forKey: ScreenTimeStorage.creditCurrentEpoch) else { return nil }
    return try? JSONDecoder().decode(CreditEpoch.self, from: data)
  }

  @MainActor
  private func creditEpochKey(_ id: String) -> String { ScreenTimeStorage.creditEpochPrefix + id }

  @MainActor
  private func creditLedgerKey(_ id: String) -> String { ScreenTimeStorage.creditLedgerPrefix + id }

  @MainActor
  private func creditLedger(_ id: String) -> CreditUsageLedger {
    guard let data = defaults.data(forKey: creditLedgerKey(id)),
          let ledger = try? JSONDecoder().decode(CreditUsageLedger.self, from: data) else { return CreditUsageLedger() }
    return ledger
  }

  @MainActor
  private func saveCreditEpoch(_ epoch: CreditEpoch) throws {
    defaults.set(try JSONEncoder().encode(epoch), forKey: creditEpochKey(epoch.id))
  }

  @MainActor
  private func pruneCreditEpochs() {
    let cutoff = Date().timeIntervalSince1970 * 1000 - 90 * 24 * 60 * 60 * 1000
    for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(ScreenTimeStorage.creditEpochPrefix) {
      guard let data = defaults.data(forKey: key), let epoch = try? JSONDecoder().decode(CreditEpoch.self, from: data),
            epoch.endsAt < cutoff else { continue }
      defaults.removeObject(forKey: key)
      defaults.removeObject(forKey: creditLedgerKey(epoch.id))
    }
  }

  @MainActor
  private func restoreAccountNuke() {
    guard isAccountNukeActive, !isSuspended,
          AuthorizationCenter.shared.authorizationStatus == .approved else { return }
    let selection = accountNukeSelection()
    // Never widen to all apps, and never report success for an empty target set.
    // If sources become empty, leave any previous named-store policy intact.
    if count(selection) > 0 { apply(selection, to: accountNukeStore) }
  }

  @MainActor
  private func accountNukeSelection() -> FamilyActivitySelection {
    var combined = FamilyActivitySelection()
    let manual = loadManualSelection()
    combined.applicationTokens.formUnion(manual.applicationTokens)
    combined.categoryTokens.formUnion(manual.categoryTokens)
    combined.webDomainTokens.formUnion(manual.webDomainTokens)
    for rule in loadRules() where rule.enabled {
      let selection = rule.selection
      combined.applicationTokens.formUnion(selection.applicationTokens)
      combined.categoryTokens.formUnion(selection.categoryTokens)
      combined.webDomainTokens.formUnion(selection.webDomainTokens)
    }
    if defaults.bool(forKey: ScreenTimeStorage.creditEnabled) {
      let creditSelection = loadCreditSelection()
      combined.applicationTokens.formUnion(creditSelection.applicationTokens)
      combined.categoryTokens.formUnion(creditSelection.categoryTokens)
      combined.webDomainTokens.formUnion(creditSelection.webDomainTokens)
    }
    return combined
  }

  @MainActor
  private func startMonitor(_ rule: StoredRule, schedule: DeviceActivitySchedule, events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]) throws {
    let name = activityName(rule.id)
    let signature = monitorSignature(rule)
    let alreadyMonitoring = center.activities.contains(name)
    if alreadyMonitoring && defaults.string(forKey: monitorSignatureKey(rule.id)) == signature { return }
    if alreadyMonitoring { center.stopMonitoring([name]) }
    let monitored = center.activities
    if !monitored.contains(activityName(rule.id)) && monitored.count >= ScreenTimeStorage.maximumMonitors {
      throw ruleError(33, "The iOS Screen Time monitor limit has been reached. Remove an unused rule first.")
    }
    try center.startMonitoring(name, during: schedule, events: events)
    defaults.set(signature, forKey: monitorSignatureKey(rule.id))
  }

  @MainActor
  private func stopMonitor(_ id: String) {
    let name = activityName(id)
    if center.activities.contains(name) { center.stopMonitoring([name]) }
    defaults.removeObject(forKey: monitorSignatureKey(id))
  }

  @MainActor
  private func monitorSignature(_ rule: StoredRule) -> String {
    [rule.kind, String(rule.startMinute ?? -1), String(rule.endMinute ?? -1),
     (rule.weekdays ?? []).map(String.init).joined(separator: ","),
     String(rule.limitMinutes ?? -1), String(rule.endsAt ?? -1),
     rule.selectionData?.base64EncodedString() ?? ""].joined(separator: "|")
  }

  private func monitorSignatureKey(_ id: String) -> String { "focuslock.screenTime.monitorSignature.\(id)" }

  @MainActor
  private func scheduleFor(_ rule: StoredRule, endMinute: Int) -> DeviceActivitySchedule {
    let start = rule.startMinute ?? 0
    // Weekdays are filtered by the extension, so this interval recurs daily.
    return DeviceActivitySchedule(
      intervalStart: DateComponents(hour: start / 60, minute: start % 60),
      intervalEnd: DateComponents(hour: endMinute / 60, minute: endMinute % 60),
      repeats: true
    )
  }

  @MainActor
  private func ruleViews() -> [[String: Any]] {
    let authorized = AuthorizationCenter.shared.authorizationStatus == .approved && !isSuspended
    return loadRules().map { $0.view(shielding: authorized && hasAppliedPolicy(ruleStore($0.id))) }
  }

  @MainActor
  private func reevaluateHomeGatedPolicies() throws {
    guard AuthorizationCenter.shared.authorizationStatus == .approved else { return }
    if defaults.bool(forKey: ScreenTimeStorage.shielding) && !isHomeSuppressed {
      let manual = loadManualSelection()
      if count(manual) > 0 { apply(manual, to: manualStore) }
    } else {
      manualStore.clearAllSettings()
    }
    for rule in loadRules() where rule.kind != "permanent" {
      let store = ruleStore(rule.id)
      guard rule.enabled, !isSuspended, rule.selectionCount > 0, !isHomeSuppressed else {
        store.clearAllSettings()
        continue
      }
      let now = Date()
      switch rule.kind {
      case "schedule":
        if ruleIsInActiveInterval(rule, now: now), isSelectedWeekday(rule, date: scheduleStartDate(rule, now: now)) { apply(rule.selection, to: store) }
        else { store.clearAllSettings() }
      case "dailyLimit":
        if rule.limitReachedDate == Self.localDateKey(now) { apply(rule.selection, to: store) }
        else { store.clearAllSettings() }
      case "timed":
        if let end = rule.endsAt, end > now.timeIntervalSince1970 * 1000 { apply(rule.selection, to: store) }
        else { store.clearAllSettings() }
      case "task":
        if taskRuleIsActive(rule, now: now) { apply(rule.selection, to: store) }
        else { store.clearAllSettings() }
      default: store.clearAllSettings()
      }
    }
    try restoreCreditPolicy()
  }

  @MainActor
  private func isCurrentlyShielding(_ rule: StoredRule) -> Bool {
    guard rule.enabled, !isSuspended,
          AuthorizationCenter.shared.authorizationStatus == .approved,
          hasAppliedPolicy(ruleStore(rule.id)) else { return false }
    if rule.kind == "timed", let endsAt = rule.endsAt, endsAt <= Date().timeIntervalSince1970 * 1000 { return false }
    return true
  }

  @MainActor
  private func isRuleActive(_ rule: StoredRule) -> Bool {
    guard rule.enabled, !isSuspended,
          AuthorizationCenter.shared.authorizationStatus == .approved else { return false }
    if rule.kind != "permanent" && isHomeSuppressed { return false }
    if rule.kind == "permanent" { return hasAppliedPolicy(ruleStore(rule.id)) }
    if rule.kind == "timed", let endsAt = rule.endsAt, endsAt <= Date().timeIntervalSince1970 * 1000 { return false }
    return center.activities.contains(activityName(rule.id))
  }

  @MainActor
  private func ruleIsInActiveInterval(_ rule: StoredRule, now: Date) -> Bool {
    let calendar = Calendar.current
    let minutes = calendar.component(.hour, from: now) * 60 + calendar.component(.minute, from: now)
    let start = rule.startMinute ?? 0
    let end = rule.endMinute ?? 1439
    if start < end { return minutes >= start && minutes < end }
    // Overnight interval belongs to the weekday on which it starts.
    return minutes >= start || minutes < end
  }

  @MainActor
  private func scheduleStartDate(_ rule: StoredRule, now: Date) -> Date {
    let minute = Calendar.current.component(.hour, from: now) * 60 + Calendar.current.component(.minute, from: now)
    let start = rule.startMinute ?? 0
    let end = rule.endMinute ?? 1439
    if start > end && minute < end { return Calendar.current.date(byAdding: .day, value: -1, to: now) ?? now }
    return now
  }

  @MainActor
  private func isSelectedWeekday(_ rule: StoredRule, date: Date) -> Bool {
    guard let days = rule.weekdays else { return false }
    return days.contains(Calendar.current.component(.weekday, from: date))
  }

  @MainActor
  private func taskRuleIsActive(_ rule: StoredRule, now: Date) -> Bool {
    let minutes = Calendar.current.component(.hour, from: now) * 60 + Calendar.current.component(.minute, from: now)
    guard minutes >= (rule.startMinute ?? 0) && minutes < 1439 else { return false }
    let today = Self.localDateKey(now)
    return rule.completedDate != today && rule.exemptDate != today
  }

  @MainActor
  private func loadRules() -> [StoredRule] {
    guard let data = defaults.data(forKey: ScreenTimeStorage.rules),
          let rules = try? JSONDecoder().decode([StoredRule].self, from: data) else { return [] }
    return rules.map { stored in
      var rule = stored
      if let marker = defaults.string(forKey: limitReachedKey(rule.id)) { rule.limitReachedDate = marker }
      return rule
    }
  }

  @MainActor
  private func saveRules(_ rules: [StoredRule]) throws {
    guard rules.count <= ScreenTimeStorage.maximumRules else { throw ruleError(15, "FocusLock supports up to 12 Screen Time rules.") }
    defaults.set(try JSONEncoder().encode(rules), forKey: ScreenTimeStorage.rules)
  }

  private func limitReachedKey(_ id: String) -> String { "focuslock.screenTime.limitReached.\(id)" }

  @MainActor
  private func migrateManualPreferencesToAppGroup() {
    guard !defaults.bool(forKey: ScreenTimeStorage.migrated) else { return }
    let legacy = UserDefaults.standard
    if defaults.object(forKey: ScreenTimeStorage.selection) == nil,
       let data = legacy.data(forKey: ScreenTimeStorage.selection) {
      if let selection = try? PropertyListDecoder().decode(FamilyActivitySelection.self, from: data),
         let migrated = try? JSONEncoder().encode(selection) {
        defaults.set(migrated, forKey: ScreenTimeStorage.selection)
      }
    }
    if defaults.object(forKey: ScreenTimeStorage.shielding) == nil,
       let value = legacy.object(forKey: ScreenTimeStorage.shielding) as? Bool {
      defaults.set(value, forKey: ScreenTimeStorage.shielding)
    }
    defaults.set(true, forKey: ScreenTimeStorage.migrated)
  }

  @MainActor
  private func activeAccountNukeOwners() -> Set<String> {
    if let saved = defaults.stringArray(forKey: ScreenTimeStorage.accountNukeOwners) {
      return Set(saved.filter { !$0.isEmpty && $0.count <= 128 })
    }
    if defaults.bool(forKey: ScreenTimeStorage.accountNukeActive),
       let owner = defaults.string(forKey: ScreenTimeStorage.accountNukeOwner),
       !owner.isEmpty, owner.count <= 128 {
      defaults.set([owner], forKey: ScreenTimeStorage.accountNukeOwners)
      defaults.removeObject(forKey: ScreenTimeStorage.accountNukeOwner)
      defaults.removeObject(forKey: ScreenTimeStorage.accountNukeActive)
      return [owner]
    }
    defaults.set([String](), forKey: ScreenTimeStorage.accountNukeOwners)
    defaults.removeObject(forKey: ScreenTimeStorage.accountNukeOwner)
    defaults.removeObject(forKey: ScreenTimeStorage.accountNukeActive)
    return []
  }

  @MainActor
  private func restoreOnceIfNeeded() {
    guard ScreenTimeCapabilityGate.isEnabled else { return }
    migrateManualPreferencesToAppGroup()
    guard !didRestore else { return }
    didRestore = true
    guard AuthorizationCenter.shared.authorizationStatus == .approved, !isSuspended else { return }
    try? restorePolicies()
  }

  @MainActor
  private func evaluateRulesOnForeground() {
    guard ScreenTimeCapabilityGate.isEnabled else { return }
    restoreOnceIfNeeded()
    guard AuthorizationCenter.shared.authorizationStatus == .approved, !isSuspended else { return }
    try? restorePolicies()
  }

  private var isSuspended: Bool { defaults.bool(forKey: ScreenTimeStorage.suspended) }
  private var isAccountNukeActive: Bool { !activeAccountNukeOwners().isEmpty }
  private var strictUntil: Double { defaults.double(forKey: ScreenTimeStorage.strictUntil) }
  private var isStrictActive: Bool { strictUntil > Date().timeIntervalSince1970 * 1000 }
  private var homeOnly: Bool { defaults.bool(forKey: ScreenTimeStorage.homeOnly) }
  private var atHome: Bool? { defaults.object(forKey: ScreenTimeStorage.atHome) as? Bool }
  private var isHomeSuppressed: Bool { homeOnly && atHome == false }

  @MainActor private func loadManualSelection() -> FamilyActivitySelection {
    guard let data = defaults.data(forKey: ScreenTimeStorage.selection) else { return FamilyActivitySelection() }
    if let value = try? JSONDecoder().decode(FamilyActivitySelection.self, from: data) { return value }
    if let value = try? PropertyListDecoder().decode(FamilyActivitySelection.self, from: data) { return value }
    return FamilyActivitySelection()
  }

  @MainActor private func loadCreditSelection() -> FamilyActivitySelection {
    guard let data = defaults.data(forKey: ScreenTimeStorage.creditSelection),
          let selection = try? JSONDecoder().decode(FamilyActivitySelection.self, from: data) else {
      return FamilyActivitySelection()
    }
    return selection
  }

  @MainActor private func saveManualSelection(_ value: FamilyActivitySelection) throws {
    defaults.set(try JSONEncoder().encode(value), forKey: ScreenTimeStorage.selection)
  }

  @MainActor private func count(_ value: FamilyActivitySelection) -> Int {
    value.applicationTokens.count + value.categoryTokens.count + value.webDomainTokens.count
  }

  @MainActor private func selectionsMatch(_ lhs: FamilyActivitySelection, _ rhs: FamilyActivitySelection) -> Bool {
    lhs.applicationTokens == rhs.applicationTokens
      && lhs.categoryTokens == rhs.categoryTokens
      && lhs.webDomainTokens == rhs.webDomainTokens
  }

  @MainActor private func apply(_ selection: FamilyActivitySelection, to store: ManagedSettingsStore) {
    store.shield.applications = selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
    store.shield.webDomains = selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
    store.shield.applicationCategories = selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
    store.shield.webDomainCategories = selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
  }

  @MainActor private func hasAppliedPolicy(_ store: ManagedSettingsStore) -> Bool {
    let shield = store.shield
    return shield.applications?.isEmpty == false || shield.webDomains?.isEmpty == false
      || shield.applicationCategories.map { _ in true } == true
      || shield.webDomainCategories.map { _ in true } == true
  }

  @MainActor private func ruleStore(_ id: String) -> ManagedSettingsStore {
    ManagedSettingsStore(named: ManagedSettingsStore.Name(ScreenTimeStorage.rulePrefix + id))
  }

  @MainActor private func activityName(_ id: String) -> DeviceActivityName {
    DeviceActivityName(ScreenTimeStorage.activityPrefix + id)
  }

  private func authorizationName(_ status: AuthorizationStatus) -> String {
    switch status {
    case .notDetermined: return "notDetermined"
    case .denied: return "denied"
    case .approved: return "approved"
    @unknown default: return "denied"
    }
  }

  private func authorizationError(_ status: AuthorizationStatus) -> NSError {
    let message = status == .denied
      ? "Screen Time authorization was denied. Enable FocusLock in iOS Settings and try again."
      : "Approve Screen Time access before selecting apps or enabling rules."
    return NSError(domain: "FocusLockScreenTime", code: 2, userInfo: [NSLocalizedDescriptionKey: message])
  }

  private func ruleError(_ code: Int, _ message: String) -> NSError {
    NSError(domain: "FocusLockScreenTime", code: code, userInfo: [NSLocalizedDescriptionKey: message])
  }

  private static func validId(_ value: String) -> Bool {
    value.range(of: "^[A-Za-z0-9_-]{1,32}$", options: .regularExpression) != nil
  }

  private static func localDateKey(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.calendar = Calendar.current
    formatter.timeZone = TimeZone.current
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter.string(from: date)
  }

  @MainActor private static func topmostViewController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    var current = scenes.flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
    while let presented = current?.presentedViewController { current = presented }
    return current
  }
}

@MainActor
private final class PickerCompletionGate {
  private var dismissalStarted = false
  func beginDismissal() -> Bool {
    guard !dismissalStarted else { return false }
    dismissalStarted = true
    return true
  }
}

@MainActor
private final class ScreenTimePickerController: UIHostingController<ScreenTimePickerView> {
  init(selection: FamilyActivitySelection, onDone: @escaping (FamilyActivitySelection) -> Void, onCancel: @escaping () -> Void) {
    super.init(rootView: ScreenTimePickerView(selection: selection, onDone: onDone, onCancel: onCancel))
  }
  @available(*, unavailable) required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
}

@MainActor
private struct ScreenTimePickerView: View {
  @State private var selection: FamilyActivitySelection
  private let onDone: @MainActor (FamilyActivitySelection) -> Void
  private let onCancel: @MainActor () -> Void
  init(selection: FamilyActivitySelection, onDone: @escaping @MainActor (FamilyActivitySelection) -> Void, onCancel: @escaping @MainActor () -> Void) {
    _selection = State(initialValue: selection)
    self.onDone = onDone
    self.onCancel = onCancel
  }
  var body: some View {
    NavigationStack {
      FamilyActivityPicker(selection: $selection)
        .navigationTitle("Choose boundaries")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
          ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
          ToolbarItem(placement: .confirmationAction) { Button("Done") { onDone(selection) } }
        }
    }
    .interactiveDismissDisabled()
  }
}
