import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings

private enum MonitorStorage {
  static let appGroup = "group.com.focuslock.app.ios"
  static let rulesKey = "focuslock.screenTime.rules.v1"
  static let suspendedKey = "focuslock.screenTime.suspended.v1"
  static let homeOnlyKey = "focuslock.screenTime.homeOnly.v1"
  static let atHomeKey = "focuslock.screenTime.atHome.v1"
  static let activityPrefix = "focuslock.rule."
  static let storePrefix = "focuslock.rule."
  static let limitMarkerPrefix = "focuslock.screenTime.limitReached."
  static let creditEnabledKey = "focuslock.screenTime.credit.enabled.v1"
  static let creditReconfiguringKey = "focuslock.screenTime.credit.reconfiguring.v1"
  static let creditCurrentEpochKey = "focuslock.screenTime.credit.currentEpoch.v1"
  static let creditEpochPrefix = "focuslock.screenTime.credit.epoch."
  static let creditLedgerPrefix = "focuslock.screenTime.credit.ledger."
  static let creditActivityPrefix = "focuslock.credit."
  static let creditStore = "focuslock.credit"
}

private struct MonitorCreditEpoch: Codable {
  var id: String
  var ownerId: String
  var startedAt: Double
  var endsAt: Double
  var allowanceMinutes: Int
  var segmentBaseMinutes: Int
  var selectionData: Data
  var selection: FamilyActivitySelection {
    (try? JSONDecoder().decode(FamilyActivitySelection.self, from: selectionData)) ?? FamilyActivitySelection()
  }
}

private struct MonitorCreditLedger: Codable {
  var consumedMinutes: Int = 0
  var dailySeconds: [String: Int] = [:]
  var updatedAt: Double = 0
}

private struct MonitorRule: Codable {
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
}

/// Runs in the Device Activity extension process, including when the app is not active.
/// Time-limit tracking reports device activity, not completed work or FocusLock credit.
public final class FocusLockDeviceActivityMonitor: DeviceActivityMonitor {
  private static let creditUsageLock = NSLock()
  private var defaults: UserDefaults? { UserDefaults(suiteName: MonitorStorage.appGroup) }
  private var creditStore: ManagedSettingsStore {
    ManagedSettingsStore(named: ManagedSettingsStore.Name(MonitorStorage.creditStore))
  }

  public override func intervalDidStart(for activity: DeviceActivityName) {
    super.intervalDidStart(for: activity)
    if let epoch = creditEpoch(for: activity) {
      applyCreditPolicyIfNeeded(epoch)
      return
    }
    guard let rule = rule(for: activity), rule.enabled, !isSuspended,
          AuthorizationCenter.shared.authorizationStatus == .approved else { return }
    let selection = rule.selection
    guard !selection.applicationTokens.isEmpty || !selection.categoryTokens.isEmpty || !selection.webDomainTokens.isEmpty else { return }
    let now = Date()
    switch rule.kind {
    case "dailyLimit":
      // Each local calendar day gets a fresh usage counter and unblocked rule store.
      if defaults?.string(forKey: MonitorStorage.limitMarkerPrefix + rule.id) == Self.localDateKey(now) && !isHomeSuppressed {
        apply(rule.selection, id: rule.id)
      } else {
        store(for: rule.id).clearAllSettings()
      }
    case "schedule":
      if !isHomeSuppressed && isSelectedWeekday(rule, date: now) { apply(rule.selection, id: rule.id) }
      else { store(for: rule.id).clearAllSettings() }
    case "task":
      let today = Self.localDateKey(now)
      if !isHomeSuppressed, rule.completedDate != today && rule.exemptDate != today,
         minuteOfDay(now) >= (rule.startMinute ?? 0) {
        apply(rule.selection, id: rule.id)
      } else {
        store(for: rule.id).clearAllSettings()
      }
    case "timed":
      if !isHomeSuppressed, let endsAt = rule.endsAt, endsAt > now.timeIntervalSince1970 * 1000 {
        apply(rule.selection, id: rule.id)
      } else {
        store(for: rule.id).clearAllSettings()
      }
    case "permanent":
      apply(rule.selection, id: rule.id)
    default:
      store(for: rule.id).clearAllSettings()
    }
  }

  public override func intervalDidEnd(for activity: DeviceActivityName) {
    super.intervalDidEnd(for: activity)
    if let epoch = creditEpoch(for: activity) {
      applyCreditPolicyIfNeeded(epoch)
      return
    }
    guard let rule = rule(for: activity) else { return }
    // A stale monitor left by a previous rule kind must never clear a currently
    // enabled permanent rule. Emergency suspension is the only global stop path.
    if rule.kind == "permanent", rule.enabled, !isSuspended,
       AuthorizationCenter.shared.authorizationStatus == .approved {
      apply(rule.selection, id: rule.id)
      return
    }
    store(for: rule.id).clearAllSettings()
  }

  public override func eventDidReachThreshold(
    _ event: DeviceActivityEvent.Name,
    activity: DeviceActivityName
  ) {
    super.eventDidReachThreshold(event, activity: activity)
    if let epoch = creditEpoch(for: activity), let threshold = creditThreshold(event) {
      recordCreditThreshold(threshold, epoch: epoch)
      return
    }
    guard event.rawValue == "limit",
          let rule = rule(for: activity), rule.kind == "dailyLimit", rule.enabled else { return }
    let today = Self.localDateKey(Date())
    defaults?.set(today, forKey: MonitorStorage.limitMarkerPrefix + rule.id)
    guard !isSuspended, !isHomeSuppressed,
          AuthorizationCenter.shared.authorizationStatus == .approved else { return }
    apply(rule.selection, id: rule.id)
  }

  private var isSuspended: Bool { defaults?.bool(forKey: MonitorStorage.suspendedKey) ?? true }
  private var isHomeSuppressed: Bool {
    guard let defaults,
          defaults.bool(forKey: MonitorStorage.homeOnlyKey),
          let atHome = defaults.object(forKey: MonitorStorage.atHomeKey) as? Bool else { return false }
    return !atHome
  }

  private func rule(for activity: DeviceActivityName) -> MonitorRule? {
    let rawName = activity.rawValue
    guard rawName.hasPrefix(MonitorStorage.activityPrefix) else { return nil }
    let id = String(rawName.dropFirst(MonitorStorage.activityPrefix.count))
    guard let data = defaults?.data(forKey: MonitorStorage.rulesKey),
          let rules = try? JSONDecoder().decode([MonitorRule].self, from: data) else { return nil }
    return rules.first { $0.id == id }
  }

  private func creditEpoch(for activity: DeviceActivityName) -> MonitorCreditEpoch? {
    let rawName = activity.rawValue
    guard rawName.hasPrefix(MonitorStorage.creditActivityPrefix) else { return nil }
    let suffix = String(rawName.dropFirst(MonitorStorage.creditActivityPrefix.count))
    let components = suffix.split(separator: ".", omittingEmptySubsequences: false)
    guard let first = components.first, !first.isEmpty else { return nil }
    let id = String(first)
    guard let data = defaults?.data(forKey: MonitorStorage.creditEpochPrefix + id),
          var epoch = try? JSONDecoder().decode(MonitorCreditEpoch.self, from: data), epoch.id == id else { return nil }
    // New monitor names carry an immutable segment base. Legacy unsuffixed
    // names use the stored value so an upgrade can finish an existing interval.
    if components.count == 2, let segmentBase = Int(components[1]), segmentBase >= 0 {
      epoch.segmentBaseMinutes = segmentBase
    }
    return epoch
  }

  private func creditThreshold(_ event: DeviceActivityEvent.Name) -> Int? {
    let prefix = "usage_"
    guard event.rawValue.hasPrefix(prefix), let threshold = Int(event.rawValue.dropFirst(prefix.count)), (1...60).contains(threshold) else {
      return nil
    }
    return threshold
  }

  private func recordCreditThreshold(_ threshold: Int, epoch: MonitorCreditEpoch) {
    guard let defaults else { return }
    Self.creditUsageLock.lock()
    defer { Self.creditUsageLock.unlock() }
    let ledgerKey = MonitorStorage.creditLedgerPrefix + epoch.id
    let prior = defaults.data(forKey: ledgerKey).flatMap { try? JSONDecoder().decode(MonitorCreditLedger.self, from: $0) }
      ?? MonitorCreditLedger()
    let absoluteMinutes = min(epoch.allowanceMinutes, epoch.segmentBaseMinutes + threshold)
    guard absoluteMinutes > prior.consumedMinutes else {
      applyCreditPolicyIfNeeded(epoch)
      return
    }
    var ledger = prior
    let delta = absoluteMinutes - prior.consumedMinutes
    let date = Self.localDateKey(Date())
    // DeviceActivity exposes threshold callbacks, not exact seconds here. Keep a
    // conservative whole-minute lower bound and never infer additional usage.
    ledger.consumedMinutes = absoluteMinutes
    ledger.dailySeconds[date, default: 0] += delta * 60
    ledger.updatedAt = Date().timeIntervalSince1970 * 1000
    if let data = try? JSONEncoder().encode(ledger) { defaults.set(data, forKey: ledgerKey) }
    applyCreditPolicyIfNeeded(epoch)
  }

  private func applyCreditPolicyIfNeeded(_ epoch: MonitorCreditEpoch) {
    guard let defaults,
          defaults.bool(forKey: MonitorStorage.creditEnabledKey),
          let currentData = defaults.data(forKey: MonitorStorage.creditCurrentEpochKey),
          let current = try? JSONDecoder().decode(MonitorCreditEpoch.self, from: currentData),
          current.id == epoch.id else { return }
    let expired = Date().timeIntervalSince1970 * 1000 >= epoch.endsAt
    let ledgerData = defaults.data(forKey: MonitorStorage.creditLedgerPrefix + epoch.id)
    let ledger = ledgerData.flatMap { try? JSONDecoder().decode(MonitorCreditLedger.self, from: $0) } ?? MonitorCreditLedger()
    let exhausted = epoch.allowanceMinutes == 0 || ledger.consumedMinutes >= epoch.allowanceMinutes || expired
    guard !isSuspended, !isHomeSuppressed,
          AuthorizationCenter.shared.authorizationStatus == .approved else {
      creditStore.clearAllSettings()
      return
    }
    if defaults.bool(forKey: MonitorStorage.creditReconfiguringKey) {
      apply(epoch.selection, to: creditStore)
      return
    }
    if exhausted { apply(epoch.selection, to: creditStore) }
    else { creditStore.clearAllSettings() }
  }

  private func apply(_ selection: FamilyActivitySelection, to store: ManagedSettingsStore) {
    store.shield.applications = selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
    store.shield.webDomains = selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
    store.shield.applicationCategories = selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
    store.shield.webDomainCategories = selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
  }

  private func apply(_ selection: FamilyActivitySelection, id: String) {
    let store = store(for: id)
    store.shield.applications = selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
    store.shield.webDomains = selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
    store.shield.applicationCategories = selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
    store.shield.webDomainCategories = selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
  }

  private func store(for id: String) -> ManagedSettingsStore {
    ManagedSettingsStore(named: ManagedSettingsStore.Name(MonitorStorage.storePrefix + id))
  }

  private func isSelectedWeekday(_ rule: MonitorRule, date: Date) -> Bool {
    guard let weekdays = rule.weekdays else { return false }
    let weekday = Calendar.current.component(.weekday, from: date)
    let start = rule.startMinute ?? 0
    let end = rule.endMinute ?? 1439
    if start > end && minuteOfDay(date) < end {
      // Overnight intervals are attributed to the day on which they began.
      let yesterday = Calendar.current.date(byAdding: .day, value: -1, to: date) ?? date
      return weekdays.contains(Calendar.current.component(.weekday, from: yesterday))
    }
    return weekdays.contains(weekday)
  }

  private func minuteOfDay(_ date: Date) -> Int {
    Calendar.current.component(.hour, from: date) * 60 + Calendar.current.component(.minute, from: date)
  }

  private static func localDateKey(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.calendar = Calendar.current
    formatter.timeZone = TimeZone.current
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter.string(from: date)
  }
}
