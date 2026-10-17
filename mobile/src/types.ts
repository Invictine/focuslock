import type { Session, WorkRecord } from './domain';

export type { ScreenTimeStatus } from '../modules/focuslock-screen-time';
import type { ScreenTimeStatus } from '../modules/focuslock-screen-time';

export type Dashboard = {
  state: null | {
    creditBalanceSeconds: number;
    totalWorkSecondsToday: number;
    lastResetDate: string;
  };
  blockedApps: { packageName: string; appName: string; isBlocked: boolean }[];
  blockedWebsites: { domain: string; displayName: string; isBlocked: boolean }[];
  workRecords: { recordId: string; title: string; durationMinutes: number; timestamp: number; source: string }[];
  prefs: null | { workRatio?: number; taskBonusMinutes?: number; strictMode?: boolean; strictEndsAt?: number; strictSessionId?: string; weeklyReport?: boolean };
};

export type MobileModel = {
  displayName: string;
  signedIn: boolean;
  connection: 'loading' | 'connected' | 'offline';
  dashboard: Dashboard | null;
  today: string;
  screenTime: ScreenTimeStatus;
  session: Session | null;
  elapsedMs: number;
  pending: WorkRecord[];
  busy: boolean;
  error: string | null;
  onboardingStep: number | null;
};

export type MobileActions = {
  startSession: (title: string, minutes: number) => void;
  pauseSession: () => void;
  resumeSession: () => void;
  finishSession: () => void;
  discardSession: () => void;
  authorizeScreenTime: () => void;
  chooseDistractions: () => void;
  toggleShielding: () => void;
  stopAllBlocking: () => void;
  openDeviceTools: () => void;
  openAccountTools: () => void;
  openTickTick: () => void;
  openHome: () => void;
  openManualLog: () => void;
  openCreditTools: () => void;
  retrySync: () => void;
  signIn: () => void;
  signOut: () => void;
  nextOnboarding: () => void;
  previousOnboarding: () => void;
  replayOnboarding: () => void;
  dismissError: () => void;
};
