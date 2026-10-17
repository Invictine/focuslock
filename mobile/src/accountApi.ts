import { makeFunctionReference } from 'convex/server';

export type WebsiteArgs = {
  domain: string;
  displayName: string;
  isBlocked: boolean;
  category: 'CUSTOM';
  updatedAt: number;
};

export type PreferencesPatch = {
  updatedAt: number;
  workRatio?: number;
  workRatioUpdatedAt?: number;
  taskBonusMinutes?: number;
  taskBonusMinutesUpdatedAt?: number;
  weeklyReport?: boolean;
  strictMode?: boolean;
  strictEndsAt?: number;
  strictPreset?: string;
};

export type HomePlace = { label: string; latitude: number; longitude: number; radiusMeters: number };
export type SavedHome = { revision: number; place: HomePlace | null; updatedAt: number };
export type HomeSaveResult = { applied: boolean; revision: number };
export type StrictConfig = null | {
  method: StrictMethod;
  methods: StrictMethod[];
  cooldownMinutes: number;
  preventUninstall: boolean;
  blockDeviceSettings: boolean;
  blockRecents: boolean;
  blockSplitScreen: boolean;
  blockReinstalledApps: boolean;
  hasPin: boolean;
  revision: number;
  unlockRequestedAt?: number;
  unlockEndsAt?: number;
  unlockSessionId?: string;
};
export type StrictMethod = 'timer' | 'schedules' | 'pin' | 'charger' | 'cooldown' | 'approval';
export type StrictUnlockResult = { released: boolean; reason?: string; availableAt?: number };
export type StrictConfigurationArgs = {
  method: StrictMethod;
  methods: StrictMethod[];
  cooldownMinutes: number;
  preventUninstall: boolean;
  blockDeviceSettings: boolean;
  blockRecents: boolean;
  blockSplitScreen: boolean;
  blockReinstalledApps: boolean;
  expectedRevision: number;
  pin?: string;
};
export type StrictGuardian = { email: string } | null;
export type ApprovalEmailResult = { sent: boolean; reason?: string; expiresAt?: number };
export type NukeState = null | {
  isActive: boolean;
  startedAt: number;
  meditationCompletedAt?: number;
  planText?: string;
  unlockedAt?: number;
};
export type CoachResult = { approved: boolean; reply: string };
export type UsageSummary = {
  totalTrackedSeconds: number;
  days: Array<{ date: string; trackedSeconds?: number; blockedSeconds?: number }>;
  devices: Array<{ deviceId: string; deviceName: string; trackedSeconds: number; blockedSeconds?: number }>;
  targets: Array<{ targetKind: string; targetKey: string; targetLabel: string; trackedSeconds: number; blockedSeconds?: number }>;
};
export type AccountGroup = {
  groupId: string;
  name: string;
  category?: string;
  members: Array<{ targetKind: 'app' | 'website'; targetKey: string; targetLabel: string }>;
  dailyLimitMinutes?: number;
  limitEnabled?: boolean;
  updatedAt: number;
};
export type AccountToolsSnapshot = {
  state: {
    creditBalanceSeconds: number;
    totalWorkSecondsToday: number;
    lastResetDate: string;
  } | null;
  apps: Array<{ packageName: string; appName: string; isBlocked: boolean }>;
  sites: Array<{ domain: string; displayName: string; isBlocked: boolean }>;
  records: Array<{ recordId: string; title: string; durationMinutes: number; timestamp: number; source: string }>;
  prefs: null | {
    workRatio?: number; workRatioUpdatedAt?: number; taskBonusMinutes?: number; taskBonusMinutesUpdatedAt?: number;
    weeklyReport?: boolean; strictMode?: boolean; strictEndsAt?: number; strictSessionId?: string; strictPreset?: string;
  };
  limits: Array<{ targetKind: string; targetKey: string; label?: string; dailyLimitMinutes?: number; sessionLimitMinutes?: number; isBlockedNow?: boolean }>;
  schedules: Array<{ scheduleId: string; label: string; targetKind: string; targetKey: string; days: number[]; startMinute: number; endMinute: number; isEnabled: boolean }>;
  devices: Array<{ deviceId: string; name: string; platform: string; date: string }>;
  permanentBlocks: Array<{ targetKind: string; targetKey: string; targetLabel: string }>;
};

export const setBlockedWebsiteRef = makeFunctionReference<'mutation', WebsiteArgs, { applied: boolean; updatedAt: number }>('focus:setBlockedWebsite');
export const savePrefsRef = makeFunctionReference<'mutation', PreferencesPatch, string>('focus:savePrefs');
export const strictConfigRef = makeFunctionReference<'query', Record<string, never>, StrictConfig>('strictAccess:getConfig');
export const strictUnlockRef = makeFunctionReference<'action', {
  strictSessionId: string; strictEndsAt: number; method?: StrictMethod; pin?: string; charging?: boolean;
}, StrictUnlockResult>('strictAccess:unlock');
export const strictConfigureRef = makeFunctionReference<'action', StrictConfigurationArgs, StrictConfig>('strictAccess:configure');
export const strictGuardianRef = makeFunctionReference<'query', Record<string, never>, StrictGuardian>('strictApproval:getGuardian');
export const configureGuardianRef = makeFunctionReference<'mutation', { email: string }, { email: string }>('strictApproval:configureGuardian');
export const requestApprovalEmailRef = makeFunctionReference<'action', { strictSessionId: string; strictEndsAt: number }, ApprovalEmailResult>('strictApproval:requestApprovalEmail');
export const nukeStateRef = makeFunctionReference<'query', Record<string, never>, NukeState>('nuke:getNuke');
export const activateNukeRef = makeFunctionReference<'mutation', Record<string, never>, string>('nuke:activate');
export const completeMeditationRef = makeFunctionReference<'mutation', Record<string, never>, null>('nuke:completeMeditation');
export const unlockWithPlanRef = makeFunctionReference<'mutation', { planText: string }, null>('nuke:unlockWithPlan');
export const coachCheckinRef = makeFunctionReference<'action', { message: string }, CoachResult>('nuke:checkin');
export const homeLocationRef = makeFunctionReference<'query', Record<string, never>, SavedHome>('homeLocation:getSaved');
export const saveHomeLocationRef = makeFunctionReference<'mutation', { expectedRevision: number; place: HomePlace | null; updatedAt: number }, HomeSaveResult>('homeLocation:saveSaved');
export const usageSummaryRef = makeFunctionReference<'query', { fromDate?: string; toDate?: string }, UsageSummary>('usage:getUsageSummary');
export const groupsRef = makeFunctionReference<'query', Record<string, never>, AccountGroup[]>('groups:listGroups');
export const accountToolsDashboardRef = makeFunctionReference<'query', Record<string, never>, AccountToolsSnapshot>('focus:getConfiguration');

export function normalizeWebsiteDomain(value: string): string {
  const normalized = value.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/^www\./, '').split(/[/?#]/, 1)[0].replace(/\.$/, '');
  if (!normalized || normalized.length > 253 || !/^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/.test(normalized)) {
    throw new TypeError('Enter a website domain such as example.com');
  }
  return normalized;
}

export function buildPreferencesPatch(input: {
  updatedAt: number;
  workRatio?: number;
  taskBonusMinutes?: number;
  weeklyReport?: boolean;
}): PreferencesPatch {
  const patch: PreferencesPatch = { updatedAt: input.updatedAt };
  if (input.workRatio !== undefined) {
    patch.workRatio = input.workRatio;
    patch.workRatioUpdatedAt = input.updatedAt;
  }
  if (input.taskBonusMinutes !== undefined) {
    patch.taskBonusMinutes = input.taskBonusMinutes;
    patch.taskBonusMinutesUpdatedAt = input.updatedAt;
  }
  if (input.weeklyReport !== undefined) patch.weeklyReport = input.weeklyReport;
  return patch;
}

export function buildStrictConfiguration(input: {
  methods: StrictMethod[];
  primary: StrictMethod;
  cooldownMinutes: number;
  pin?: string;
  previous: StrictConfig;
}): Omit<StrictConfigurationArgs, 'expectedRevision'> {
  const { methods, primary, cooldownMinutes, previous } = input;
  if (!methods.length || new Set(methods).size !== methods.length || !methods.includes(primary)) {
    throw new TypeError('Choose one or more unique routes and a primary route.');
  }
  if (!Number.isInteger(cooldownMinutes) || cooldownMinutes < 2 || cooldownMinutes > 10) {
    throw new RangeError('Cooldown must be from 2 to 10 minutes.');
  }
  if (methods.includes('pin') && !previous?.hasPin && !/^\d{4,12}$/.test(input.pin ?? '')) {
    throw new TypeError('Set a PIN with 4 to 12 digits before choosing PIN access.');
  }
  if (input.pin !== undefined && !/^\d{4,12}$/.test(input.pin)) {
    throw new TypeError('PIN must contain 4 to 12 digits.');
  }
  return {
    method: primary,
    methods: [...methods],
    cooldownMinutes,
    preventUninstall: previous?.preventUninstall ?? false,
    blockDeviceSettings: previous?.blockDeviceSettings ?? false,
    blockRecents: previous?.blockRecents ?? false,
    blockSplitScreen: previous?.blockSplitScreen ?? false,
    blockReinstalledApps: previous?.blockReinstalledApps ?? false,
    ...(input.pin === undefined ? {} : { pin: input.pin }),
  };
}
