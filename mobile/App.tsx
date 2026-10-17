import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, AppState, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { ClerkProvider, useAuth, useClerk, useUser } from '@clerk/expo';
import { tokenCache } from '@clerk/expo/token-cache';
import { AuthView } from '@clerk/expo/native-components';
import { ConvexReactClient, useConvexAuth, useConvexConnectionState } from 'convex/react';
import { ConvexProviderWithClerk } from 'convex/react-clerk';
import { randomUUID } from 'expo-crypto';
import ScreenTime from './modules/focuslock-screen-time';
import FocusLockApp from './src/FocusLockApp';
import DeviceTools from './src/DeviceTools';
import AccountTools from './src/AccountTools';
import TickTickTools from './src/TickTickTools';
import HomeLocationControls, { reconcileHomeBoundary } from './src/HomeLocationControls';
import ManualWork from './src/ManualWork';
import CreditTools from './src/CreditTools';
import { CreditSync, freshCreditState, recordLeisureUsage } from './src/credit';
import { accountDashboard, accountNuke, dashboardView, deviceHeartbeat, recordWork } from './src/api';
import { installationId } from './src/device';
import { errorMessage, tokenBelongsTo } from './src/auth';
import {
  acknowledgeRecord, appendWorkRecord, completeSession, createManualRecord, createSession, elapsedMilliseconds,
  localDate, pauseSession, resumeSession,
} from './src/domain';
import type { Envelope, WorkRecord } from './src/domain';
import { SessionStore } from './src/storage';
import { focusProgress } from './src/progress';
import { colors } from './src/theme';
import type { Dashboard, MobileActions, ScreenTimeStatus } from './src/types';

const publishableKey = process.env.EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY;
const convexUrl = process.env.EXPO_PUBLIC_CONVEX_URL;
const configured = Boolean(publishableKey?.match(/^pk_(test|live)_/) && !publishableKey.endsWith('...') && convexUrl?.startsWith('https://') && !convexUrl.includes('your-deployment'));
const OwnerContext = createContext('');
const stores = new Map<string, SessionStore>();

function storeFor(ownerId: string) {
  let store = stores.get(ownerId);
  if (!store) {
    store = new SessionStore(AsyncStorage, ownerId);
    stores.set(ownerId, store);
  }
  return store;
}

function useScreenTime() {
  const [status, setStatus] = useState<ScreenTimeStatus>({ available: false, authorization: 'notDetermined', selectionCount: 0, shielding: false, activeRuleCount: 0, suspended: false, strictUntil: 0, homeOnly: false, atHome: null });
  const [error, setError] = useState<string | null>(null);
  const refresh = useCallback(async () => {
    try { setStatus(await reconcileHomeBoundary(await ScreenTime.getStatus())); }
    catch (reason) { setError(errorMessage(reason)); }
  }, []);
  useEffect(() => {
    void refresh();
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') void refresh();
    });
    return () => subscription.remove();
  }, [refresh]);
  const run = useCallback(async (action: () => Promise<ScreenTimeStatus>) => {
    try { setStatus(await action()); setError(null); }
    catch (reason) { setError(errorMessage(reason)); await refresh(); }
  }, [refresh]);
  return { status, error, setError, run, refresh };
}
type ScreenTimeControl = ReturnType<typeof useScreenTime>;

export default function App() {
  const screenTime = useScreenTime();
  return (
    <SafeAreaProvider>
      {configured ? (
        <ClerkProvider publishableKey={publishableKey!} tokenCache={tokenCache}>
          <AccountGate screenTime={screenTime} />
        </ClerkProvider>
      ) : (
        <LocalGate screenTime={screenTime} />
      )}
    </SafeAreaProvider>
  );
}

function LocalGate({ screenTime }: { screenTime: ScreenTimeControl }) {
  const [offline, setOffline] = useState(false);
  return offline ? (
    <Controller ownerId="local" displayName="On this iPhone" signedIn={false} screenTime={screenTime} onSignIn={() => setOffline(false)} />
  ) : (
    <Welcome screenTime={screenTime} configured={false} onLocal={() => setOffline(true)} />
  );
}

function AccountGate({ screenTime }: { screenTime: ScreenTimeControl }) {
  const { isLoaded, isSignedIn, userId } = useAuth({ treatPendingAsSignedOut: false });
  const { user } = useUser();
  const { signOut } = useClerk();
  const [local, setLocal] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  // The auth modal stays mounted across account transitions so native session
  // tasks can finish. The SDK synchronizes the session without manual setActive.
  return (
    <View style={styles.fill}>
      {!isLoaded ? <Loading screenTime={screenTime} /> : isSignedIn && userId ? (
        <AccountClient key={userId} ownerId={userId} displayName={user?.firstName ?? user?.primaryEmailAddress?.emailAddress ?? 'Your account'} screenTime={screenTime} onSignOut={async () => { setLocal(false); await signOut(); }} />
      ) : local ? (
        <Controller key="local" ownerId="local" displayName="On this iPhone" signedIn={false} screenTime={screenTime} onSignIn={() => setAuthOpen(true)} />
      ) : (
        <Welcome screenTime={screenTime} configured onLocal={() => setLocal(true)} onSignIn={() => setAuthOpen(true)} />
      )}
      <Modal visible={authOpen} animationType="slide" presentationStyle="pageSheet" onRequestClose={() => setAuthOpen(false)}>
        <AuthView onDismiss={() => setAuthOpen(false)} />
      </Modal>
    </View>
  );
}

function useOwnedAuth() {
  const ownerId = useContext(OwnerContext);
  const auth = useAuth({ treatPendingAsSignedOut: false });
  const getToken = useCallback(async (options: { template?: 'convex'; skipCache?: boolean }) => {
    if (auth.userId !== ownerId) return null;
    const token = await auth.getToken(options);
    return tokenBelongsTo(token, ownerId) ? token : null;
  }, [auth.getToken, auth.userId, ownerId]);
  return { ...auth, isSignedIn: auth.isSignedIn && auth.userId === ownerId, getToken };
}

function AccountClient(props: { ownerId: string; displayName: string; screenTime: ScreenTimeControl; onSignOut: () => Promise<void> }) {
  // A different account gets a different client. A disconnected old queue can
  // never inherit a new account's token or subscriptions.
  const client = useMemo(() => new ConvexReactClient(convexUrl!, { unsavedChangesWarning: false }), [props.ownerId]);
  useEffect(() => () => { void client.close(); }, [client]);
  return (
    <OwnerContext.Provider value={props.ownerId}>
      <ConvexProviderWithClerk client={client} useAuth={useOwnedAuth}>
        <ConnectedController {...props} client={client} />
      </ConvexProviderWithClerk>
    </OwnerContext.Provider>
  );
}

function ConnectedController({ client, ...props }: {
  ownerId: string; displayName: string; screenTime: ScreenTimeControl; onSignOut: () => Promise<void>; client: ConvexReactClient;
}) {
  const auth = useConvexAuth();
  const connection = useConvexConnectionState();
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [presenceError, setPresenceError] = useState<string | null>(null);
  const [date, setDate] = useState(() => localDate(Date.now()));
  useEffect(() => {
    const timer = setInterval(() => setDate(localDate(Date.now())), 30_000);
    return () => clearInterval(timer);
  }, []);
  useEffect(() => {
    if (!auth.isAuthenticated) return;
    const watch = client.watchQuery(accountDashboard, { fromDate: date, toDate: date });
    const update = () => {
      try {
        const value = watch.localQueryResult();
        if (value) { setDashboard(dashboardView(value)); setSyncError(null); }
      } catch (reason) { setSyncError(`Account sync: ${errorMessage(reason)}`); }
    };
    const unsubscribe = watch.onUpdate(update);
    update();
    return unsubscribe;
  }, [auth.isAuthenticated, client, date, revision]);
  useEffect(() => {
    if (!auth.isAuthenticated || !props.screenTime.status.available || props.screenTime.status.authorization !== 'approved') return;
    let active = true;
    const watch = client.watchQuery(accountNuke, {});
    const update = () => {
      try {
        const value = watch.localQueryResult();
        if (value !== undefined && active) void props.screenTime.run(() => ScreenTime.setAccountNuke(props.ownerId, value?.isActive ?? false));
      } catch (reason) { if (active) setSyncError(`Account reset could not sync: ${errorMessage(reason)}`); }
    };
    const unsubscribe = watch.onUpdate(update);
    update();
    return () => { active = false; unsubscribe(); };
  }, [auth.isAuthenticated, client, props.ownerId, props.screenTime.status.available, props.screenTime.status.authorization, props.screenTime.status.selectionCount, revision]);
  const canSync = auth.isAuthenticated && connection.isWebSocketConnected;
  const [creditError, setCreditError] = useState<string | null>(null);
  const creditActive = useRef(true);
  const creditOnline = useRef(false);
  creditOnline.current = Boolean(canSync && props.screenTime.status.available && props.screenTime.status.authorization === 'approved');
  useEffect(() => {
    creditActive.current = true;
    return () => { creditActive.current = false; };
  }, []);
  const creditSync = useMemo(() => new CreditSync({
    ownerId: props.ownerId,
    isCurrent: () => creditActive.current && creditOnline.current,
    installationId,
    native: ScreenTime,
    acknowledge: (deviceId, buckets) => client.mutation(recordLeisureUsage, { deviceId, buckets }),
    balance: async () => (await client.query(freshCreditState, {})).state?.creditBalanceSeconds ?? null,
  }), [client, props.ownerId]);
  const syncCredit = useCallback(async () => {
    await creditSync.sync();
    if (creditActive.current) { setCreditError(null); await props.screenTime.refresh(); }
  }, [creditSync, props.screenTime.refresh]);
  useEffect(() => {
    if (!creditOnline.current) return;
    let active = true;
    const refresh = () => {
      if (AppState.currentState !== 'active') return;
      void syncCredit().catch((reason) => { if (active) setCreditError(`Leisure usage is saved on this iPhone and will retry: ${errorMessage(reason)}`); });
    };
    refresh();
    const timer = setInterval(refresh, 30_000);
    const foreground = AppState.addEventListener('change', (state) => { if (state === 'active') refresh(); });
    return () => { active = false; clearInterval(timer); foreground.remove(); };
  }, [canSync, props.screenTime.status.available, props.screenTime.status.authorization, revision, syncCredit]);
  useEffect(() => {
    if (!canSync) return;
    let active = true;
    const heartbeat = async () => {
      if (AppState.currentState !== 'active') return;
      try {
        const deviceId = await installationId();
        if (!active) return;
        await client.mutation(deviceHeartbeat, {
          deviceId, name: 'iPhone', platform: 'ios', appVersion: '0.7.0',
          trackingStatus: props.screenTime.status.available && props.screenTime.status.authorization !== 'approved' ? 'permission_required' : 'paused',
          statusDetail: props.screenTime.status.available ? 'Screen Time controls on iPhone; usage reports remain private to this device.' : 'Basic iPhone build; Screen Time blocking is unavailable.',
          lastSeen: Date.now(),
        });
        if (active) setPresenceError(null);
      } catch (reason) { if (active) setPresenceError(`Device status could not sync: ${errorMessage(reason)}`); }
    };
    void heartbeat();
    const timer = setInterval(() => { void heartbeat(); }, 30_000);
    return () => { active = false; clearInterval(timer); };
  }, [canSync, client, props.screenTime.status.available, props.screenTime.status.authorization]);
  return (
    <Controller
      {...props} signedIn
      client={client}
      dashboard={dashboard}
      connection={canSync && dashboard && !syncError ? 'connected' : !connection.isWebSocketConnected || syncError || (!auth.isLoading && !auth.isAuthenticated) ? 'offline' : 'loading'}
      externalError={syncError ?? presenceError ?? creditError ?? (!auth.isLoading && !auth.isAuthenticated ? 'Account sync could not authenticate. Check your connection and the Clerk convex token template, then retry.' : null)}
      canSync={canSync}
      sendWork={(record) => client.mutation(recordWork, record)}
      syncCredit={syncCredit}
      onRetry={() => { setSyncError(null); setRevision((value) => value + 1); }}
    />
  );
}

type ControllerProps = {
  ownerId: string;
  displayName: string;
  signedIn: boolean;
  screenTime: ScreenTimeControl;
  onSignIn?: () => void;
  onSignOut?: () => Promise<void>;
  dashboard?: Dashboard | null;
  connection?: 'loading' | 'connected' | 'offline';
  externalError?: string | null;
  canSync?: boolean;
  sendWork?: (record: WorkRecord) => Promise<{ applied: boolean; duplicate: boolean }>;
  onRetry?: () => void;
  client?: ConvexReactClient;
  syncCredit?: () => Promise<void>;
};

function Controller(props: ControllerProps) {
  const { ownerId, screenTime } = props;
  const store = useMemo(() => storeFor(ownerId), [ownerId]);
  const [envelope, setEnvelope] = useState<Envelope | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [retry, setRetry] = useState(0);
  const [guideLoaded, setGuideLoaded] = useState(false);
  const [guideStep, setGuideStep] = useState<number | null>(null);
  const [panel, setPanel] = useState<'device' | 'account' | 'ticktick' | 'home' | 'manual' | 'credit' | null>(null);
  const mounted = useRef(true);
  const actionLock = useRef(false);
  const flushing = useRef(false);
  const guideKey = `focuslock.guide.v1.${encodeURIComponent(ownerId)}`;
  const today = localDate(now);
  const progress = useMemo(() => focusProgress(props.dashboard ?? null, envelope?.pending ?? [], today), [props.dashboard, envelope?.pending, today]);
  useEffect(() => {
    if (!envelope || progress.totalMinutes <= 30 || !screenTime.status.available || screenTime.status.authorization !== 'approved') return;
    let active = true;
    void (async () => {
      try {
        const rules = await ScreenTime.getRules();
        for (const rule of rules) if (active && rule.enabled && rule.kind === 'task' && rule.exemptDate !== today) {
          await ScreenTime.updateTaskProgress(rule.id, progress.byTitle[rule.title.trim()] ?? 0, false, progress.totalMinutes);
        }
        if (active) await screenTime.refresh();
      } catch (reason) { if (active) setError(errorMessage(reason)); }
    })();
    return () => { active = false; };
  }, [envelope !== null, progress, today, screenTime.status.available, screenTime.status.authorization]);

  const load = useCallback(async () => {
    try { setEnvelope(await store.load()); setError(null); }
    catch (reason) { setError(`Could not open saved work: ${errorMessage(reason)}. Your saved data has been kept. Retry after restarting the app.`); }
  }, [store]);
  useEffect(() => {
    mounted.current = true;
    const unsubscribe = store.subscribe((value) => { if (mounted.current) setEnvelope(value); });
    void load();
    void AsyncStorage.getItem(guideKey).then((raw) => {
      if (!mounted.current) return;
      const step = raw === null ? 0 : Number(raw);
      setGuideStep(step === 4 ? null : Number.isInteger(step) && step >= 0 && step < 4 ? step : 0);
      setGuideLoaded(true);
    }).catch((reason) => { if (mounted.current) { setError(errorMessage(reason)); setGuideLoaded(true); setGuideStep(0); } });
    return () => { mounted.current = false; unsubscribe(); };
  }, [guideKey, load, store]);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    const foreground = AppState.addEventListener('change', (state) => {
      if (state === 'active') { setNow(Date.now()); setRetry((value) => value + 1); }
    });
    return () => { clearInterval(timer); foreground.remove(); };
  }, []);
  useEffect(() => {
    if (!props.signedIn || !envelope?.pending.length) return;
    const timer = setInterval(() => setRetry((value) => value + 1), 15_000);
    return () => clearInterval(timer);
  }, [props.signedIn, envelope?.pending.length]);
  useEffect(() => {
    if (!props.canSync || !props.sendWork || !envelope?.pending.length || flushing.current) return;
    flushing.current = true;
    const sendWork = props.sendWork;
    void (async () => {
      try {
        while (mounted.current) {
          const record = (await store.load()).pending[0];
          if (!record) break;
          const response = await sendWork(record);
          if (!response.applied) throw new Error('The server did not accept this work record.');
          // Acknowledgement only removes this ID, retaining any sessions or
          // records added while the network request was in progress.
          await store.update((current) => acknowledgeRecord(current, record.recordId));
        }
      } catch (reason) {
        if (mounted.current) setError(`Work is saved on this iPhone and will retry: ${errorMessage(reason)}`);
      } finally { flushing.current = false; }
    })();
  }, [props.canSync, props.sendWork, envelope?.pending.length, retry, store]);

  const action = (operation: () => Promise<unknown>) => {
    if (actionLock.current) return;
    actionLock.current = true;
    setBusy(true);
    void operation().catch((reason) => { if (mounted.current) setError(errorMessage(reason)); }).finally(() => {
      actionLock.current = false;
      if (mounted.current) { setBusy(false); setNow(Date.now()); }
    });
  };
  const saveGuide = async (step: number) => {
    await AsyncStorage.setItem(guideKey, String(step));
    if (mounted.current) setGuideStep(step === 4 ? null : step);
  };
  const actions: MobileActions = {
    startSession: (title, minutes) => action(async () => {
      if (props.signedIn && !props.dashboard) throw new Error('Connect to your account once before starting a session so its credit ratio is available.');
      await store.update((current) => {
        if (current.session) throw new Error('Finish or discard the current session first.');
        return { ...current, session: createSession({ id: randomUUID(), ownerId, title, targetMinutes: minutes, workRatio: props.dashboard?.prefs?.workRatio ?? 4, now: Date.now() }) };
      });
    }),
    pauseSession: () => action(() => store.update((current) => ({ ...current, session: current.session ? pauseSession(current.session, Date.now()) : null }))),
    resumeSession: () => action(() => store.update((current) => ({ ...current, session: current.session ? resumeSession(current.session, Date.now()) : null }))),
    finishSession: () => action(() => store.update((current) => completeSession(current, Date.now()))),
    discardSession: () => action(() => store.update((current) => ({ ...current, session: null }))),
    authorizeScreenTime: () => action(() => screenTime.run(() => ScreenTime.requestAuthorization())),
    chooseDistractions: () => action(() => screenTime.run(() => ScreenTime.presentPicker())),
    // Emergency off is independent of account/storage locks and never clears
    // any synced Frog or permanent-boundary policy.
    toggleShielding: () => { void screenTime.run(() => ScreenTime.setShielding(!screenTime.status.shielding)); },
    stopAllBlocking: () => { void screenTime.run(() => ScreenTime.suspendAll()); },
    openDeviceTools: () => setPanel('device'),
    openAccountTools: () => setPanel('account'),
    openTickTick: () => setPanel('ticktick'),
    openHome: () => setPanel('home'),
    openManualLog: () => setPanel('manual'),
    openCreditTools: () => setPanel('credit'),
    retrySync: () => { void load(); props.onRetry?.(); setRetry((value) => value + 1); },
    signIn: () => props.onSignIn?.(),
    signOut: () => action(async () => { await props.onSignOut?.(); }),
    nextOnboarding: () => action(() => saveGuide((guideStep ?? 0) + 1)),
    previousOnboarding: () => action(() => saveGuide(Math.max(0, (guideStep ?? 0) - 1))),
    replayOnboarding: () => action(() => saveGuide(0)),
    dismissError: () => { setError(null); screenTime.setError(null); },
  };
  if (!envelope || !guideLoaded) return (
    <Loading screenTime={screenTime} message={error ?? 'Opening your saved work…'} onRetry={error ? actions.retrySync : undefined} />
  );
  const focusMinutesToday = progress.totalMinutes;
  const onChanged = () => { void screenTime.refresh(); props.onRetry?.(); };
  if (panel) {
    const fail = (message: string) => setError(message);
    return <SafeAreaView style={styles.fill}>
      <View style={styles.panelHeader}><Control label="‹ Back" onPress={() => setPanel(null)} /></View>
      <Recovery screenTime={screenTime} />
      {error || screenTime.error ? <Text accessibilityRole="alert" style={styles.panelError}>{error ?? screenTime.error}</Text> : null}
      {panel === 'device' ? <DeviceTools screenTime={screenTime.status} totalFocusMinutesToday={focusMinutesToday} taskFocusMinutesByTitle={progress.byTitle} onError={fail} onChanged={onChanged} /> : null}
      {panel === 'account' && props.client ? <AccountTools client={props.client} dashboard={props.dashboard ?? null} onError={fail} onChanged={onChanged} /> : null}
      {panel === 'home' ? <HomeLocationControls client={props.client ?? null} ownerId={ownerId} onError={fail} onChanged={onChanged} /> : null}
      {panel === 'credit' ? <CreditTools client={props.client ?? null} ownerId={ownerId} balanceSeconds={props.dashboard?.state?.creditBalanceSeconds ?? null} onError={fail} onChanged={onChanged} onSync={async () => {
        if (!props.syncCredit) throw new Error('Sign in to sync account credit.');
        await props.syncCredit();
      }} /> : null}
      {panel === 'ticktick' && props.client ? <TickTickTools client={props.client} totalFocusMinutesToday={focusMinutesToday} onError={fail} onStartTask={(title) => { actions.startSession(title, 25); setPanel(null); }} onPriorityTask={(title) => action(async () => {
        const id = 'ticktick-frog';
        const existing = (await ScreenTime.getRules()).find((rule) => rule.id === id);
        if (existing?.enabled) throw new Error('Turn off or finish the current daily priority before choosing a different task.');
        await ScreenTime.saveRule({ id, title, kind: 'task', enabled: false, startMinute: 7 * 60, requiredFocusMinutes: 25 });
        setPanel('device');
        onChanged();
      })} /> : null}
      {panel === 'manual' ? <ManualWork busy={busy} local={!props.signedIn} onSave={(title, durationMinutes, tasksCompleted) => action(async () => {
        if (props.signedIn && !props.dashboard) throw new Error('Connect to your account once before logging work so its reward settings are available.');
        const record = createManualRecord({ id: randomUUID(), title, durationMinutes, tasksCompleted, workRatio: props.dashboard?.prefs?.workRatio ?? 4, taskBonusMinutes: props.dashboard?.prefs?.taskBonusMinutes ?? 0, now: Date.now() });
        await store.update((current) => appendWorkRecord(current, record));
        setPanel(null);
      })} /> : null}
      {panel !== 'device' && panel !== 'manual' && panel !== 'home' && panel !== 'credit' && !props.client ? <Text style={styles.copy}>Sign in to use these account tools.</Text> : null}
    </SafeAreaView>;
  }
  return (
    <FocusLockApp model={{
      displayName: props.displayName, signedIn: props.signedIn,
      connection: props.connection ?? 'offline', dashboard: props.dashboard ?? null,
      today: localDate(now), screenTime: screenTime.status, session: envelope.session,
      elapsedMs: envelope.session ? elapsedMilliseconds(envelope.session, now) : 0,
      pending: envelope.pending, busy, error: error ?? screenTime.error ?? props.externalError ?? null,
      onboardingStep: guideStep,
    }} actions={actions} />
  );
}

function Recovery({ screenTime }: { screenTime: ScreenTimeControl }) {
  if (!screenTime.status.shielding && screenTime.status.activeRuleCount === 0) return null;
  return <Control label="Suspend all blocking" onPress={() => { void screenTime.run(() => ScreenTime.suspendAll()); }} />;
}

function Welcome({ screenTime, onSignIn, onLocal, configured: hasConfig }: {
  screenTime: ScreenTimeControl; onSignIn?: () => void; onLocal: () => void; configured: boolean;
}) {
  return (
    <SafeAreaView style={styles.fill}>
      <ScrollView contentContainerStyle={styles.welcome}>
        <Text style={styles.brand}>FocusLock</Text>
        <Text style={styles.title}>Make room for what matters.</Text>
        <Text style={styles.copy}>Focus on one task. Keep distractions behind a boundary you choose.</Text>
        {hasConfig ? <Control label="Sign in or create account" onPress={onSignIn!} /> : <Text style={styles.copy}>Account sync is not configured in this build. You can use focus sessions and Screen Time on this iPhone.</Text>}
        <Control label="Use on this iPhone" onPress={onLocal} />
        <Text style={styles.small}>Sessions saved without an account stay on this device. Screen Time choices also stay on this iPhone.</Text>
        {screenTime.error ? <Text accessibilityRole="alert" style={styles.copy}>{screenTime.error}</Text> : null}
        <Recovery screenTime={screenTime} />
      </ScrollView>
    </SafeAreaView>
  );
}

function Loading({ screenTime, message = 'Opening FocusLock…', onRetry }: { screenTime: ScreenTimeControl; message?: string; onRetry?: () => void }) {
  return (
    <SafeAreaView style={styles.fill}>
      <View style={styles.welcome}>
        <ActivityIndicator color={colors.primary} accessibilityLabel="Loading" />
        <Text style={styles.copy}>{message}</Text>
        {onRetry ? <Control label="Retry" onPress={onRetry} /> : null}
        <Recovery screenTime={screenTime} />
      </View>
    </SafeAreaView>
  );
}

function Control({ label, onPress }: { label: string; onPress: () => void }) {
  return <Pressable style={styles.control} onPress={onPress} accessibilityRole="button"><Text style={styles.controlText}>{label}</Text></Pressable>;
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: colors.background },
  welcome: { flexGrow: 1, justifyContent: 'center', gap: 22, padding: 28 },
  brand: { color: colors.primary, fontSize: 22, fontWeight: '700' },
  title: { color: colors.onSurface, fontSize: 38, fontWeight: '700' },
  copy: { color: colors.onSurfaceVariant, fontSize: 17, lineHeight: 25 },
  small: { color: colors.onSurfaceVariant, fontSize: 14, lineHeight: 21 },
  panelHeader: { paddingHorizontal: 18, paddingVertical: 8, alignItems: 'flex-start' },
  panelError: { color: colors.error, padding: 16, fontSize: 15 },
  control: { backgroundColor: colors.primary, minHeight: 48, padding: 15, borderRadius: 12, alignItems: 'center' },
  controlText: { color: colors.onPrimary, fontSize: 17, fontWeight: '600', textAlign: 'center' },
});
