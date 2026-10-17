Pod::Spec.new do |s|
  s.name           = 'FocusLockScreenTime'
  s.version        = '1.0.0'
  s.summary        = 'Local iOS Screen Time selection and shielding for FocusLock.'
  s.description    = 'Uses FamilyControls and ManagedSettings for explicit, on-device app and website shielding.'
  s.license        = { :type => 'UNLICENSED' }
  s.author         = 'FocusLock'
  s.homepage       = 'https://github.com/Invictine/focuslock'
  s.platforms      = { :ios => '17.4' }
  s.swift_version  = '5.9'
  s.static_framework = true
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  s.source         = { :git => 'https://github.com/Invictine/focuslock.git', :tag => s.version.to_s }
  s.source_files   = 'ios/**/*.{h,m,mm,swift}'
  s.frameworks     = 'FamilyControls', 'ManagedSettings', 'DeviceActivity', 'SwiftUI', 'UIKit'
  s.dependency 'ExpoModulesCore'
end
