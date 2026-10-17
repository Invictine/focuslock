import DeviceActivity
import ExpoModulesCore
import SwiftUI
import UIKit

@MainActor
public final class FocusLockScreenTimeReportView: ExpoView {
  private var host: UIHostingController<FocusLockDailyReportContent>?

  public required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    backgroundColor = .clear
    guard ScreenTimeCapabilityGate.isEnabled else {
      let label = UILabel()
      label.text = "Screen Time controls are unavailable in this build."
      label.numberOfLines = 0
      label.textAlignment = .center
      label.translatesAutoresizingMaskIntoConstraints = false
      addSubview(label)
      NSLayoutConstraint.activate([
        label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
        label.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
        label.topAnchor.constraint(equalTo: topAnchor),
        label.bottomAnchor.constraint(equalTo: bottomAnchor),
      ])
      return
    }
    let controller = UIHostingController(rootView: FocusLockDailyReportContent())
    controller.view.backgroundColor = .clear
    controller.view.translatesAutoresizingMaskIntoConstraints = false
    addSubview(controller.view)
    NSLayoutConstraint.activate([
      controller.view.leadingAnchor.constraint(equalTo: leadingAnchor),
      controller.view.trailingAnchor.constraint(equalTo: trailingAnchor),
      controller.view.topAnchor.constraint(equalTo: topAnchor),
      controller.view.bottomAnchor.constraint(equalTo: bottomAnchor),
    ])
    host = controller
  }
}

@MainActor
private struct FocusLockDailyReportContent: View {
  private let filter: DeviceActivityFilter = {
    let today = Calendar.current.dateInterval(of: .day, for: .now)
      ?? DateInterval(start: Calendar.current.startOfDay(for: .now), duration: 86_400)
    return DeviceActivityFilter(segment: .daily(during: today))
  }()

  var body: some View {
    DeviceActivityReport(.focusLockDaily, filter: filter)
      .frame(maxWidth: .infinity, minHeight: 96)
  }
}

extension DeviceActivityReport.Context {
  static let focusLockDaily = Self("FocusLockDaily")
}
