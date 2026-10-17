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
  private var defaults: UserDefaults? { UserDefaults(suiteName: MonitorStorage.appGroup) }

  public override func intervalDidStart(for activity: DeviceActivityName) {
    super.intervalDidStart(for: activity)
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
