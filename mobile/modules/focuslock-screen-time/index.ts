import { requireNativeViewManager, requireOptionalNativeModule } from 'expo-modules-core';
import type { ComponentType } from 'react';

export type ScreenTimeAuthorization = 'notDetermined' | 'denied' | 'approved';

export type ScreenTimeStatus = {
  available: boolean;
  authorization: ScreenTimeAuthorization;
  selectionCount: number;
  /** Manual selection only; rule shields are reported per rule below. */
  shielding: boolean;
  activeRuleCount: number;
  suspended: boolean;
  strictUntil: number;
  homeOnly: boolean;
  atHome: boolean | null;
};

export type NativeRuleKind = 'schedule' | 'dailyLimit' | 'permanent' | 'timed' | 'task';

export type NativeRule = {
  id: string;
  title: string;
  kind: NativeRuleKind;
  enabled: boolean;
  startMinute?: number;
  endMinute?: number;
  /** Calendar weekday numbering: 1=Sunday through 7=Saturday. */
  weekdays?: number[];
  limitMinutes?: number;
  endsAt?: number;
  requiredFocusMinutes?: number;
  completedDate?: string;
  exemptDate?: string;
  selectionCount: number;
  shielding: boolean;
};

/** Progress is deliberately not caller-configurable through saveRule. */
export type NativeRuleInput = Omit<
  NativeRule,
  'selectionCount' | 'shielding' | 'completedDate' | 'exemptDate'
>;

export interface FocusLockScreenTimeModule {
  getStatus(): Promise<ScreenTimeStatus>;
  requestAuthorization(): Promise<ScreenTimeStatus>;
  presentPicker(): Promise<ScreenTimeStatus>;
  setShielding(enabled: boolean): Promise<ScreenTimeStatus>;
  getRules(): Promise<NativeRule[]>;
  saveRule(input: NativeRuleInput): Promise<NativeRule[]>;
  pickRule(id: string): Promise<NativeRule[]>;
  deleteRule(id: string): Promise<NativeRule[]>;
  suspendAll(): Promise<ScreenTimeStatus>;
  resumeAll(): Promise<ScreenTimeStatus>;
  setStrictUntil(timestampMs: number): Promise<ScreenTimeStatus>;
  setHomeOnly(enabled: boolean, atHome: boolean | null): Promise<ScreenTimeStatus>;
  setAccountNuke(ownerId: string, active: boolean): Promise<ScreenTimeStatus>;
  updateTaskProgress(id: string, trackedMinutes: number, checkedOff: boolean, focusMinutesToday: number): Promise<NativeRule[]>;
}

const unavailableStatus: ScreenTimeStatus = {
  available: false,
  authorization: 'notDetermined',
  selectionCount: 0,
  shielding: false,
  activeRuleCount: 0,
  suspended: false,
  strictUntil: 0,
  homeOnly: false,
  atHome: null,
};

const nativeModule = requireOptionalNativeModule<FocusLockScreenTimeModule>(
  'FocusLockScreenTime',
);

/** Native, privacy-preserving Screen Time report; null outside the iOS native build. */
export const ScreenTimeReport: ComponentType<Record<string, never>> | null = (() => {
  try {
    return requireNativeViewManager('FocusLockScreenTime', 'FocusLockScreenTimeReport');
  } catch {
    return null;
  }
})();

function requireNative(): FocusLockScreenTimeModule {
  if (!nativeModule) {
    throw new Error(
      'Screen Time controls are unavailable in this app build. Use a prebuilt iOS app with the FocusLock Screen Time module installed.',
    );
  }
  return nativeModule;
}

/** Returns a safe unsupported status in Expo Go, on Android, and on web. */
export function getStatus(): Promise<ScreenTimeStatus> {
  return nativeModule ? nativeModule.getStatus() : Promise.resolve(unavailableStatus);
}

export function requestAuthorization(): Promise<ScreenTimeStatus> {
  return requireNative().requestAuthorization();
}

export function presentPicker(): Promise<ScreenTimeStatus> {
  return requireNative().presentPicker();
}

export function setShielding(enabled: boolean): Promise<ScreenTimeStatus> {
  return requireNative().setShielding(enabled);
}

export function getRules(): Promise<NativeRule[]> {
  return nativeModule ? nativeModule.getRules() : Promise.resolve([]);
}

export function saveRule(input: NativeRuleInput): Promise<NativeRule[]> {
  return requireNative().saveRule(input);
}

export function pickRule(id: string): Promise<NativeRule[]> {
  return requireNative().pickRule(id);
}

export function deleteRule(id: string): Promise<NativeRule[]> {
  return requireNative().deleteRule(id);
}

export function suspendAll(): Promise<ScreenTimeStatus> {
  return requireNative().suspendAll();
}

export function resumeAll(): Promise<ScreenTimeStatus> {
  return requireNative().resumeAll();
}

export function setStrictUntil(timestampMs: number): Promise<ScreenTimeStatus> {
  return requireNative().setStrictUntil(timestampMs);
}

export function setHomeOnly(enabled: boolean, atHome: boolean | null): Promise<ScreenTimeStatus> {
  return requireNative().setHomeOnly(enabled, atHome);
}

export function setAccountNuke(ownerId: string, active: boolean): Promise<ScreenTimeStatus> {
  return requireNative().setAccountNuke(ownerId, active);
}

export function updateTaskProgress(
  id: string,
  trackedMinutes: number,
  checkedOff: boolean,
  focusMinutesToday = trackedMinutes,
): Promise<NativeRule[]> {
  return requireNative().updateTaskProgress(id, trackedMinutes, checkedOff, focusMinutesToday);
}

export const FocusLockScreenTime = {
  getStatus,
  requestAuthorization,
  presentPicker,
  setShielding,
  getRules,
  saveRule,
  pickRule,
  deleteRule,
  suspendAll,
  resumeAll,
  setStrictUntil,
  setHomeOnly,
  setAccountNuke,
  ScreenTimeReport,
  updateTaskProgress,
};

export default FocusLockScreenTime;
