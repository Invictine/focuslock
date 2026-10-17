import DeviceActivity
import SwiftUI

extension DeviceActivityReport.Context {
  static let focusLockDaily = Self("FocusLockDaily")
}

private struct DailyUsageReport: DeviceActivityReportScene {
  let context: DeviceActivityReport.Context = .focusLockDaily
  let content: (TimeInterval) -> DailyUsageReportContent

  func makeConfiguration(representing data: DeviceActivityResults<DeviceActivityData>) async -> TimeInterval {
    var total: TimeInterval = 0
    for await device in data {
      for await segment in device.activitySegments {
        total += segment.totalActivityDuration
      }
    }
    return total
  }
}

private struct DailyUsageReportContent: View {
  let totalDuration: TimeInterval

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text("Today's device use")
        .font(.headline)
      Text(formattedDuration)
        .font(.title2.weight(.semibold))
        .monospacedDigit()
    }
    .frame(maxWidth: .infinity, minHeight: 84, alignment: .leading)
    .padding(.horizontal, 16)
    .padding(.vertical, 10)
  }

  private var formattedDuration: String {
    let minutes = max(0, Int(totalDuration / 60))
    let hours = minutes / 60
    let remainingMinutes = minutes % 60
    return hours > 0 ? "\(hours)h \(remainingMinutes)m" : "\(minutes)m"
  }
}

@main
struct FocusLockDailyReportExtension: DeviceActivityReportExtension {
  var body: some DeviceActivityReportScene {
    DailyUsageReport { duration in
      DailyUsageReportContent(totalDuration: duration)
    }
  }
}
