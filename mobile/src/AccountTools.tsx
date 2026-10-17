import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  KeyboardAvoidingView,
  Pressable,
  Platform,
  ScrollView,
  Share,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import type { ConvexReactClient } from 'convex/react';
import * as Battery from 'expo-battery';
import { errorMessage } from './auth';
import {
  activateNukeRef,
  accountToolsDashboardRef,
  buildPreferencesPatch,
  buildStrictConfiguration,
  coachCheckinRef,
  completeMeditationRef,
  configureGuardianRef,
  groupsRef,
  homeLocationRef,
  normalizeWebsiteDomain,
  nukeStateRef,
  saveHomeLocationRef,
  savePrefsRef,
  requestApprovalEmailRef,
  setBlockedWebsiteRef,
  strictConfigRef,
  strictConfigureRef,
  strictGuardianRef,
  strictUnlockRef,
  unlockWithPlanRef,
  usageSummaryRef,
  type AccountGroup,
  type AccountToolsSnapshot,
  type HomePlace,
  type NukeState,
  type SavedHome,
  type StrictConfig,
  type StrictMethod,
  type StrictGuardian,
  type UsageSummary,
} from './accountApi';
import type { Dashboard } from './types';
import { colors, radii, spacing } from './theme';

type Props = {
  client: ConvexReactClient;
  dashboard: Dashboard | null;
  onError: (message: string) => void;
  onChanged?: () => void;
};
type Page = 'menu' | 'websites' | 'targets' | 'preferences' | 'strict' | 'nuke' | 'home' | 'usage' | 'export';
type ExtendedDashboard = Dashboard & {
  permanentBlocks?: Array<{ targetKind: string; targetKey: string; targetLabel: string }>;
  limits?: Array<{ targetKind: string; targetKey: string; label?: string; dailyLimitMinutes?: number; sessionLimitMinutes?: number; isBlockedNow?: boolean }>;
  schedules?: Array<{ scheduleId: string; label: string; targetKind: string; targetKey: string; days: number[]; startMinute: number; endMinute: number; isEnabled: boolean }>;
  devices?: Array<{ deviceId: string; deviceName?: string; name?: string; platform?: string; trackedSeconds?: number; date?: string }>;
  prefs?: (NonNullable<Dashboard['prefs']> & {
    weeklyReport?: boolean;
    taskBonusMinutes?: number;
    strictSessionId?: string;
    strictEndsAt?: number;
    strictPreset?: string;
  }) | null;
};

export default function AccountTools({ client, dashboard, onError, onChanged }: Props) {
  const [page, setPage] = useState<Page>('menu');
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now());
  const mounted = useRef(true);
  const actionLock = useRef(false);
  const errorRef = useRef(onError);
  const changedRef = useRef(onChanged);
  errorRef.current = onError;
  changedRef.current = onChanged;

  const [strictConfig, setStrictConfig] = useState<StrictConfig | undefined>();
  const [strictGuardian, setStrictGuardian] = useState<StrictGuardian | undefined>();
  const [nuke, setNuke] = useState<NukeState | undefined>();
  const [savedHome, setSavedHome] = useState<SavedHome | undefined>();
  const [usage, setUsage] = useState<UsageSummary | undefined>();
  const [groups, setGroups] = useState<AccountGroup[] | undefined>();
  const [accountSnapshot, setAccountSnapshot] = useState<AccountToolsSnapshot | undefined>();
  useAccountQuery(client, accountToolsDashboardRef, setAccountSnapshot, errorRef);
  const ext = useMemo<ExtendedDashboard | null>(() => {
    if (!accountSnapshot) return dashboard as ExtendedDashboard | null;
    return {
      state: dashboard ? dashboard.state : accountSnapshot.state,
      blockedApps: accountSnapshot.apps,
      blockedWebsites: accountSnapshot.sites,
      workRecords: dashboard?.workRecords ?? accountSnapshot.records,
      prefs: accountSnapshot.prefs ?? dashboard?.prefs ?? null,
      permanentBlocks: accountSnapshot.permanentBlocks,
      limits: accountSnapshot.limits,
      schedules: accountSnapshot.schedules,
      devices: (dashboard as ExtendedDashboard | null)?.devices,
    };
  }, [accountSnapshot, dashboard]);
  const prefs = ext?.prefs ?? null;
  const resolvedDashboard = ext;

  useEffect(() => {
    mounted.current = true;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => { mounted.current = false; clearInterval(timer); };
  }, [client]);

  useAccountQuery(client, strictConfigRef, setStrictConfig, errorRef);
  useAccountQuery(client, strictGuardianRef, setStrictGuardian, errorRef);
  useAccountQuery(client, nukeStateRef, setNuke, errorRef);
  useAccountQuery(client, homeLocationRef, setSavedHome, errorRef);
  useAccountQuery(client, usageSummaryRef, setUsage, errorRef);
  useAccountQuery(client, groupsRef, setGroups, errorRef);

  const run = async (operation: () => Promise<unknown>) => {
    if (actionLock.current || busy) return;
    actionLock.current = true;
    setBusy(true);
    try {
      await operation();
    } catch (error) {
      if (mounted.current) errorRef.current(errorMessage(error));
    } finally {
      actionLock.current = false;
      if (mounted.current) setBusy(false);
    }
  };

  const go = (next: Page) => setPage(next);
  const back = () => setPage('menu');
  const refreshParent = () => { if (mounted.current) changedRef.current?.(); };

  const title = pageTitle(page);
  return (
    <View style={styles.fill}>
      <View style={styles.topbar}>
        {page !== 'menu' ? <Pressable style={styles.back} onPress={back} accessibilityRole="button" accessibilityLabel="Back to account tools"><Text style={styles.backText}>‹ Back</Text></Pressable> : null}
        <Text style={styles.topTitle}>{title}</Text>
      </View>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      {page === 'menu' ? <Menu onNavigate={go} />
        : page === 'websites' ? <Websites dashboard={resolvedDashboard} client={client} busy={busy} run={run} onChanged={refreshParent} />
        : page === 'targets' ? <Targets dashboard={resolvedDashboard} groups={groups} usage={usage} />
        : page === 'preferences' ? <Preferences prefs={prefs} busy={busy} run={run} client={client} onError={onError} />
        : page === 'strict' ? <StrictPage prefs={prefs} prefsReady={resolvedDashboard !== null || accountSnapshot !== undefined} config={strictConfig} guardian={strictGuardian} client={client} now={now} busy={busy} run={run} onChanged={refreshParent} />
        : page === 'nuke' ? <NukePage state={nuke} client={client} now={now} busy={busy} run={run} onChanged={refreshParent} />
        : page === 'home' ? <HomePage saved={savedHome} client={client} busy={busy} run={run} onChanged={refreshParent} />
        : page === 'usage' ? <UsagePage usage={usage} />
        : <ExportPage dashboard={dashboard} onError={onError} />}
      </KeyboardAvoidingView>
    </View>
  );
}

function useAccountQuery<T>(
  client: ConvexReactClient,
  ref: Parameters<ConvexReactClient['watchQuery']>[0],
  setValue: (value: T | undefined) => void,
  errorRef: React.MutableRefObject<(message: string) => void>,
) {
  useEffect(() => {
    let active = true;
    let unsubscribe: (() => void) | undefined;
    try {
      const watch = client.watchQuery(ref as never, {} as never);
      const update = () => {
        if (!active) return;
        try {
          const value = watch.localQueryResult() as T | undefined;
          if (value !== undefined) setValue(value);
        } catch (error) {
          errorRef.current(errorMessage(error));
        }
      };
      unsubscribe = watch.onUpdate(update);
      update();
    } catch (error) {
      if (active) errorRef.current(errorMessage(error));
    }
    return () => { active = false; unsubscribe?.(); };
  }, [client, errorRef, ref, setValue]);
}

function Menu({ onNavigate }: { onNavigate: (page: Page) => void }) {
  const entries: Array<[Page, string, string]> = [
    ['websites', 'Account websites', 'Edit website boundaries saved to your account'],
    ['targets', 'Other device targets', 'View apps, permanent blocks, limits, schedules, groups, and devices'],
    ['preferences', 'Account preferences', 'Rewards and weekly report setting'],
    ['strict', 'Strict Mode commitment', 'Review an account commitment or available release route'],
    ['nuke', 'Account Nuke', 'Account-wide recovery flow'],
    ['home', 'Saved home location', 'Edit account place without requesting device location'],
    ['usage', 'Usage summary', 'Review recorded account usage'],
    ['export', 'Export account snapshot', 'Share selected public account data'],
  ];
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <Text style={styles.intro}>Account tools update account data. iPhone app blocking remains controlled by the separate Screen Time settings.</Text>
      {entries.map(([page, label, detail]) => <MenuRow key={page} label={label} detail={detail} onPress={() => onNavigate(page)} />)}
    </ScrollView>
  );
}

function Websites({ dashboard, client, busy, run, onChanged }: {
  dashboard: Dashboard | null; client: ConvexReactClient; busy: boolean; run: (fn: () => Promise<unknown>) => Promise<void>;
  onChanged: () => void;
}) {
  const [domain, setDomain] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [isBlocked, setIsBlocked] = useState(true);
  const [editing, setEditing] = useState<string | null>(null);
  const sites = dashboard?.blockedWebsites ?? [];
  const edit = (site?: Dashboard['blockedWebsites'][number]) => {
    setEditing(site?.domain ?? null);
    setDomain(site?.domain ?? '');
    setDisplayName(site?.displayName ?? '');
    setIsBlocked(site?.isBlocked ?? true);
  };
  const save = () => run(async () => {
    const normalized = normalizeWebsiteDomain(domain);
    const result = await client.mutation(setBlockedWebsiteRef, {
      domain: normalized,
      displayName: displayName.trim() || normalized,
      isBlocked,
      category: 'CUSTOM',
      updatedAt: Date.now(),
    });
    if (!result.applied) throw new Error('This website changed on another device. Reload account data and try again.');
    edit();
    onChanged();
  });
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <Text style={styles.intro}>These website rules are account settings. They do not turn on iPhone web filtering.</Text>
      <View style={styles.card}>
        <Text style={styles.cardTitle}>{editing ? 'Edit website' : 'Add website'}</Text>
        <Field label="Domain" value={domain} onChangeText={setDomain} placeholder="example.com" autoCapitalize="none" keyboardType="url" />
        <Field label="Display name" value={displayName} onChangeText={setDisplayName} placeholder="Optional name" />
        <View style={styles.switchRow}>
          <Text style={styles.rowTitle}>Blocked on account</Text>
          <Switch value={isBlocked} onValueChange={setIsBlocked} accessibilityLabel="Block this website on account" />
        </View>
        <Button label={busy ? 'Saving…' : 'Save website'} onPress={save} disabled={busy || !domain.trim()} />
        {editing ? <Button label="Cancel editing" variant="secondary" onPress={() => edit()} disabled={busy} /> : null}
      </View>
      <Text style={styles.sectionLabel}>Saved account websites</Text>
      {sites.length ? sites.map((site) => (
        <View key={site.domain} style={styles.listItem}>
          <View style={styles.itemCopy}><Text style={styles.rowTitle}>{site.displayName}</Text><Text style={styles.helper}>{site.domain} · {site.isBlocked ? 'Blocked on account' : 'Not blocked on account'}</Text></View>
          <Button label="Edit" variant="secondary" onPress={() => edit(site)} disabled={busy} compact />
        </View>
      )) : <Text style={styles.helper}>{dashboard ? 'No saved account websites.' : 'Account websites are unavailable until account data loads.'}</Text>}
    </ScrollView>
  );
}

function Targets({ dashboard, groups, usage }: { dashboard: ExtendedDashboard | null; groups: AccountGroup[] | undefined; usage: UsageSummary | undefined }) {
  const apps = dashboard?.blockedApps ?? [];
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.notice}><Text style={styles.noticeTitle}>Reference only</Text><Text style={styles.helper}>These targets belong to other FocusLock platforms. iOS does not map their package IDs or automatically enforce their rules.</Text></View>
      <ReadOnlyList title="Apps" rows={apps.map((app) => `${app.appName} · ${app.isBlocked ? 'Blocked on account' : 'Not blocked on account'} · ${app.packageName}`)} empty={dashboard ? 'No account apps.' : 'Account targets are loading.'} />
      <ReadOnlyList title="Permanent blocks" rows={(dashboard?.permanentBlocks ?? []).map((row) => `${row.targetLabel} · ${row.targetKind} · ${row.targetKey}`)} empty={dashboard ? 'No permanent blocks.' : 'Account targets are loading.'} />
      <ReadOnlyList title="App and website limits" rows={(dashboard?.limits ?? []).map((row) => `${row.label ?? row.targetKey} · ${row.targetKind}${row.dailyLimitMinutes !== undefined ? ` · ${row.dailyLimitMinutes} min/day` : ''}${row.sessionLimitMinutes !== undefined ? ` · ${row.sessionLimitMinutes} min/session` : ''}${row.isBlockedNow ? ' · currently blocked' : ''}`)} empty={dashboard ? 'No target limits.' : 'Account targets are loading.'} />
      <ReadOnlyList title="Schedules" rows={(dashboard?.schedules ?? []).map((row) => `${row.label} · ${row.isEnabled ? 'Enabled on account' : 'Disabled'} · ${row.targetKind}: ${row.targetKey} · ${formatDays(row.days)} ${formatMinute(row.startMinute)}–${formatMinute(row.endMinute)}`)} empty={dashboard ? 'No schedules.' : 'Account targets are loading.'} />
      <ReadOnlyList title="Groups" rows={(groups ?? []).map((group) => `${group.name} · ${group.members.length} targets${group.limitEnabled && group.dailyLimitMinutes ? ` · ${group.dailyLimitMinutes} min/day` : ''}`)} empty={groups ? 'No target groups.' : 'Loading account groups…'} />
      <ReadOnlyList title="Device activity" rows={(usage?.devices ?? []).map((device) => `${device.deviceName} · ${formatDuration(device.trackedSeconds * 1000)} tracked · ${device.deviceId}`)} empty={usage ? 'No other-device activity is available.' : 'Device activity is loading.'} />
    </ScrollView>
  );
}

function Preferences({ prefs, busy, run, client, onError }: {
  prefs: ExtendedDashboard['prefs']; busy: boolean; run: (fn: () => Promise<unknown>) => Promise<void>; client: ConvexReactClient; onError: Props['onError'];
}) {
  const [ratio, setRatio] = useState(String(prefs?.workRatio ?? 4));
  const [bonus, setBonus] = useState(String(prefs?.taskBonusMinutes ?? 5));
  useEffect(() => { setRatio(String(prefs?.workRatio ?? 4)); setBonus(String(prefs?.taskBonusMinutes ?? 5)); }, [prefs?.workRatio, prefs?.taskBonusMinutes]);
  const save = () => run(async () => {
    const workRatio = parseWhole(ratio, 1, 10, 'Work ratio');
    const taskBonusMinutes = parseWhole(bonus, 0, 20, 'Task bonus');
    await client.mutation(savePrefsRef, buildPreferencesPatch({ updatedAt: Date.now(), workRatio, taskBonusMinutes }));
  });
  const toggleWeekly = (weeklyReport: boolean) => run(async () => {
    await client.mutation(savePrefsRef, buildPreferencesPatch({ updatedAt: Date.now(), weeklyReport }));
  });
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Rewards</Text>
        <Text style={styles.helper}>Changes are saved to your account and may be constrained by an active Strict Mode commitment.</Text>
        <Field label="Work minutes per earned minute (1–10)" value={ratio} onChangeText={setRatio} keyboardType="number-pad" />
        <Field label="Task bonus minutes (0–20)" value={bonus} onChangeText={setBonus} keyboardType="number-pad" />
        <Button label="Save reward settings" onPress={save} disabled={busy} />
      </View>
      <View style={styles.card}>
        <View style={styles.switchRow}>
          <View style={styles.itemCopy}><Text style={styles.rowTitle}>Weekly report</Text><Text style={styles.helper}>Account email preference</Text></View>
          <Switch value={prefs?.weeklyReport ?? true} onValueChange={toggleWeekly} disabled={busy} accessibilityLabel="Weekly report" />
        </View>
      </View>
    </ScrollView>
  );
}

function StrictPage({ prefs, prefsReady, config, guardian, client, now, busy, run, onChanged }: {
  prefs: ExtendedDashboard['prefs']; prefsReady: boolean; config: StrictConfig | undefined; guardian: StrictGuardian | undefined;
  client: ConvexReactClient; now: number; busy: boolean;
  run: (fn: () => Promise<unknown>) => Promise<void>; onChanged: () => void;
}) {
  const [minutes, setMinutes] = useState(60);
  const [methods, setMethods] = useState<StrictMethod[]>(['timer']);
  const [primary, setPrimary] = useState<StrictMethod>('timer');
  const [cooldownMinutes, setCooldownMinutes] = useState('5');
  const [pin, setPin] = useState('');
  const [guardianEmail, setGuardianEmail] = useState('');
  const [selectedRoute, setSelectedRoute] = useState<StrictMethod>('timer');
  const [message, setMessage] = useState('');
  const [approvalResult, setApprovalResult] = useState('');
  const active = !!prefs?.strictMode && (!prefs.strictEndsAt || prefs.strictEndsAt > now);
  const configuredRoutes: StrictMethod[] = config ? (config.methods ?? [config.method]) : ['timer'];
  const strictId = prefs?.strictSessionId;
  const strictEndsAt = prefs?.strictEndsAt;
  const cooldownAvailableAt = config && config.unlockSessionId === strictId && config.unlockEndsAt === strictEndsAt && config.unlockRequestedAt
    ? config.unlockRequestedAt + config.cooldownMinutes * 60_000
    : undefined;
  const protections = config ? [
    config.preventUninstall && 'Prevent uninstall', config.blockDeviceSettings && 'Block device Settings',
    config.blockRecents && 'Block Recents', config.blockSplitScreen && 'Block split-screen',
    config.blockReinstalledApps && 'Block reinstalled apps',
  ].filter(Boolean) as string[] : [];

  useEffect(() => {
    if (config === undefined) return;
    const nextMethods = config ? [...(config.methods ?? [config.method])] : ['timer' as StrictMethod];
    setMethods(nextMethods);
    setPrimary(config?.method ?? 'timer');
    setCooldownMinutes(String(config?.cooldownMinutes ?? 5));
  }, [config]);
  useEffect(() => { if (guardian !== undefined) setGuardianEmail(guardian?.email ?? ''); }, [guardian]);
  useEffect(() => { if (!configuredRoutes.includes(selectedRoute)) setSelectedRoute(configuredRoutes[0] ?? 'timer'); }, [configuredRoutes.join(','), selectedRoute]);

  const configure = () => run(async () => {
    const cooldown = parseWhole(cooldownMinutes, 2, 10, 'Cooldown');
    const args = buildStrictConfiguration({ methods, primary, cooldownMinutes: cooldown, previous: config ?? null, ...(pin.trim() ? { pin: pin.trim() } : {}) });
    const result = await client.action(strictConfigureRef, { ...args, expectedRevision: config?.revision ?? 0 });
    if (!result) throw new Error('Strict Mode settings were not returned. Refresh and try again.');
    setPin('');
    setMessage('Account release settings saved. Android-specific protections were left unchanged.');
    onChanged();
  });
  const start = (durationMinutes: number) => run(async () => {
    await client.mutation(savePrefsRef, {
      strictMode: true,
      strictEndsAt: Date.now() + durationMinutes * 60_000,
      strictPreset: 'CUSTOM',
      updatedAt: Date.now(),
    });
    onChanged();
  });
  const tryUnlock = (route: StrictMethod, charging = false) => run(async () => {
    setMessage('');
    if (!strictId || !strictEndsAt) throw new Error('The current account commitment details are still loading.');
    if (route === 'charger') {
      const batteryState = await Battery.getBatteryStateAsync();
      if (batteryState !== Battery.BatteryState.CHARGING && batteryState !== Battery.BatteryState.FULL) {
        setMessage('This iPhone is not reporting that it is connected to a charger. No unlock request was sent.');
        return;
      }
      charging = true;
    }
    const result = await client.action(strictUnlockRef, {
      strictSessionId: strictId,
      strictEndsAt,
      method: route,
      ...(route === 'pin' ? { pin: pin.trim() } : {}),
      ...(route === 'charger' ? { charging } : {}),
    });
    if (result.released) {
      setMessage('The account commitment was released. iOS Screen Time shielding remains a separate device control.');
      setPin('');
      onChanged();
    } else setMessage(result.reason ?? 'The account did not release this commitment.');
  });
  const saveGuardian = () => run(async () => {
    const result = await client.mutation(configureGuardianRef, { email: guardianEmail.trim() });
    setGuardianEmail(result.email);
    setMessage('Approval contact saved to your account.');
    onChanged();
  });
  const requestApproval = () => {
    if (!strictId || !strictEndsAt) return;
    Alert.alert(
      'Email your approval contact?',
      `This sends an early-release request to ${guardian?.email ?? 'your saved contact'}. The contact must approve it; this will not end the commitment by itself.`,
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Send request', onPress: () => { void run(async () => {
          const result = await client.action(requestApprovalEmailRef, { strictSessionId: strictId, strictEndsAt });
          if (result.sent) setApprovalResult(`Request sent. The approval link expires ${result.expiresAt ? new Date(result.expiresAt).toLocaleString() : 'in 30 minutes'}.`);
          else setApprovalResult(`Request was not sent${result.reason ? `: ${result.reason}` : '.'}`);
          onChanged();
        }); } },
      ],
    );
  };

  return (
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <View style={styles.notice}><Text style={styles.noticeTitle}>Account setting</Text><Text style={styles.helper}>Strict Mode is an account commitment and does not enforce blocking on iPhone. Configure supported account release routes here; local Screen Time shielding is controlled separately.</Text></View>
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Current commitment</Text>
        {!prefs ? <Text style={styles.helper}>Account preferences are loading.</Text> : active ? <Text style={styles.body}>{strictEndsAt ? `Active until ${new Date(strictEndsAt).toLocaleString()}.` : 'An active account commitment has no saved end time.'}</Text> : <Text style={styles.body}>No active account commitment.</Text>}
        {config ? <Text style={styles.helper}>Configured routes: {configuredRoutes.map(methodLabel).join(', ')}. Cooldown: {config.cooldownMinutes} minutes. PIN: {config.hasPin ? 'configured' : 'not configured'}.</Text> : config === null ? <Text style={styles.helper}>No strict access configuration was returned yet. Timer remains the default route.</Text> : <Text style={styles.helper}>Loading strict access configuration…</Text>}
        <Text style={styles.helper}>Android-only protections (uninstall, device Settings, Recents, split-screen, and reinstall protections): {protections.length ? protections.join(', ') : 'none configured'}. These settings are not enforced on iPhone.</Text>
        {active ? <>
          <Text style={styles.fieldLabel}>Available release routes</Text>
          <View style={styles.choiceRow}>{configuredRoutes.map((route) => <Button key={route} label={methodLabel(route)} variant={selectedRoute === route ? 'primary' : 'secondary'} onPress={() => setSelectedRoute(route)} disabled={busy} compact />)}</View>
          {selectedRoute === 'timer' || selectedRoute === 'schedules' ? <Text style={styles.helper}>{selectedRoute === 'timer' ? 'Timer route: the account commitment remains until its scheduled end.' : 'Schedule route: the account commitment remains until its schedule allows release.'} This route does not release early from iOS.</Text> : null}
          {selectedRoute === 'pin' ? <>
            <Field label="Account PIN" value={pin} onChangeText={setPin} keyboardType="number-pad" secureTextEntry maxLength={12} />
            <Button label="Unlock with PIN" onPress={() => tryUnlock('pin')} disabled={busy || !/^\d{4,12}$/.test(pin.trim())} />
          </> : null}
          {selectedRoute === 'cooldown' ? <>
            <Text style={styles.helper}>Starting a cooldown does not unlock immediately. Once the waiting period ends, return here to request release.</Text>
            <Button label={busy ? 'Checking…' : cooldownAvailableAt && now < cooldownAvailableAt ? `Cooldown until ${new Date(cooldownAvailableAt).toLocaleTimeString()}` : cooldownAvailableAt ? 'Complete cooldown release' : 'Start cooldown'} onPress={() => tryUnlock('cooldown')} disabled={busy || !!(cooldownAvailableAt && now < cooldownAvailableAt)} />
          </> : null}
          {selectedRoute === 'charger' ? <>
            <Text style={styles.helper}>FocusLock checks this iPhone’s current battery state immediately before requesting release. The account server still validates the active commitment.</Text>
            <Button label="Check charger and request release" onPress={() => tryUnlock('charger')} disabled={busy} />
          </> : null}
          {selectedRoute === 'approval' ? <>
            <Text style={styles.helper}>{guardian?.email ? `Saved approval contact: ${guardian.email}` : 'No approval contact is saved. Configure one before starting commitments that use this route.'}</Text>
            <Button label="Email approval request" onPress={requestApproval} disabled={busy || !guardian?.email || !strictId || !strictEndsAt} />
            {approvalResult ? <Text style={styles.noticeText}>{approvalResult}</Text> : null}
          </> : null}
        </> : null}
        {message ? <Text style={styles.noticeText}>{message}</Text> : null}
      </View>
      {!active && prefsReady ? <>
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Configure release routes</Text>
          <Text style={styles.helper}>Settings are account-scoped. They do not enable or strengthen iPhone blocking. The server prevents changes that would weaken an active commitment.</Text>
          <Text style={styles.fieldLabel}>Choose one or more routes</Text>
          <View style={styles.choiceRow}>{(['timer', 'schedules', 'pin', 'charger', 'cooldown', 'approval'] as StrictMethod[]).map((route) => {
            const selected = methods.includes(route);
            const toggle = () => setMethods((current) => {
              if (selected) {
                const next = current.filter((item) => item !== route);
                if (!next.length) return current;
                if (primary === route) setPrimary(next[0]);
                return next;
              }
              return [...current, route];
            });
            return <Button key={route} label={`${selected ? '✓ ' : ''}${methodLabel(route)}`} variant={selected ? 'primary' : 'secondary'} onPress={toggle} disabled={busy} compact />;
          })}</View>
          <Text style={styles.fieldLabel}>Primary route</Text>
          <View style={styles.choiceRow}>{methods.map((route) => <Button key={route} label={methodLabel(route)} variant={primary === route ? 'primary' : 'secondary'} onPress={() => setPrimary(route)} disabled={busy} compact />)}</View>
          <Field label="Cooldown (2–10 minutes)" value={cooldownMinutes} onChangeText={setCooldownMinutes} keyboardType="number-pad" />
          {methods.includes('pin') ? <Field label={config?.hasPin ? 'Change PIN (leave blank to keep current)' : 'New account PIN (4–12 digits)'} value={pin} onChangeText={setPin} keyboardType="number-pad" secureTextEntry maxLength={12} /> : null}
          <Button label={busy ? 'Saving…' : 'Save release settings'} onPress={configure} disabled={busy || !methods.length || !methods.includes(primary) || (methods.includes('pin') && !config?.hasPin && !/^\d{4,12}$/.test(pin.trim())) || (!!pin.trim() && !/^\d{4,12}$/.test(pin.trim()))} />
        </View>
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Approval contact</Text>
          <Text style={styles.helper}>A saved contact can receive an email request while a commitment is active. The contact must approve it; email delivery does not release the commitment on its own.</Text>
          {active ? <Text style={styles.body}>{guardian?.email ?? 'No approval contact is saved.'}</Text> : <Field label="Contact email" value={guardianEmail} onChangeText={setGuardianEmail} keyboardType="email-address" autoCapitalize="none" autoCorrect={false} />}
          {!active ? <Button label={busy ? 'Saving…' : guardian?.email ? 'Update approval contact' : 'Save approval contact'} onPress={saveGuardian} disabled={busy || !guardianEmail.trim()} /> : null}
        </View>
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Start an account commitment</Text>
          <Text style={styles.helper}>Choose a duration. iOS does not gain additional blocking from this account commitment.</Text>
          <View style={styles.choiceRow}>{[60, 240, 1440].map((duration) => <Button key={duration} label={duration === 60 ? '1 hour' : duration === 240 ? '4 hours' : '24 hours'} variant={minutes === duration ? 'primary' : 'secondary'} onPress={() => setMinutes(duration)} disabled={busy} compact />)}</View>
          <Button label={busy ? 'Saving…' : 'Commit'} onPress={() => start(minutes)} disabled={busy} />
        </View>
      </> : null}
    </ScrollView>
  );
}

function NukePage({ state, client, now, busy, run, onChanged }: {
  state: NukeState | undefined; client: ConvexReactClient; now: number; busy: boolean; run: (fn: () => Promise<unknown>) => Promise<void>; onChanged: () => void;
}) {
  const [plan, setPlan] = useState('');
  const [coachText, setCoachText] = useState('');
  const [coachReply, setCoachReply] = useState('');
  const [meditationDone, setMeditationDone] = useState(false);
  const active = !!state?.isActive;
  const elapsed = state?.startedAt ? Math.max(0, now - state.startedAt) : 0;
  const remaining = Math.max(0, 10 * 60_000 - elapsed);
  const complete = !!state?.meditationCompletedAt || meditationDone;
  const begin = () => Alert.alert(
    'Activate account-wide Nuke?',
    'This activates the Nuke state for your account across connected FocusLock devices. This does not force-enable iOS blocking; iPhone Screen Time rules remain controlled separately.',
    [{ text: 'Cancel', style: 'cancel' }, { text: 'Activate Nuke', style: 'destructive', onPress: () => { void run(async () => { await client.mutation(activateNukeRef, {}); onChanged(); }); } }],
  );
  const meditate = () => run(async () => { await client.mutation(completeMeditationRef, {}); setMeditationDone(true); onChanged(); });
  const submitPlan = () => run(async () => { await client.mutation(unlockWithPlanRef, { planText: plan.trim() }); onChanged(); });
  const coach = () => run(async () => {
    const result = await client.action(coachCheckinRef, { message: coachText.trim() });
    setCoachReply(result.reply);
    if (result.approved) onChanged();
  });
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.notice}><Text style={styles.noticeTitle}>Account-wide recovery</Text><Text style={styles.helper}>Nuke changes account state for connected devices. iOS enforcement depends on Screen Time rules you turn on separately; this account control does not force iPhone blocking.</Text></View>
      <View style={styles.card}>
        <Text style={styles.cardTitle}>{active ? 'Nuke is active' : 'Nuke state'}</Text>
        {state === undefined ? <Text style={styles.helper}>Loading account recovery state…</Text> : active ? (
          <>
            <Text style={styles.body}>Started {new Date(state.startedAt).toLocaleString()}.</Text>
            {!complete ? <Text style={styles.body}>{remaining > 0 ? `10-minute reset: ${formatDuration(remaining)} remaining.` : '10-minute reset is ready to complete.'}</Text> : <Text style={styles.body}>10-minute reset completed.</Text>}
            {!complete ? <Button label="Complete 10-minute reset" onPress={meditate} disabled={busy || remaining > 0} /> : (
              <>
                <Field label="Your next-step plan" value={plan} onChangeText={setPlan} placeholder="One action, a time box, and what you will avoid" multiline />
                <Button label={busy ? 'Saving…' : 'Finish with this plan'} onPress={submitPlan} disabled={busy || plan.trim().length < 3} />
                <Text style={styles.sectionLabel}>Optional coach check-in</Text>
                <Field label="Describe your plan" value={coachText} onChangeText={setCoachText} placeholder="What will you do, for how long, and what will you avoid?" multiline />
                <Button label={busy ? 'Checking…' : 'Ask the coach'} variant="secondary" onPress={coach} disabled={busy || coachText.trim().length < 3} />
                {coachReply ? <Text style={styles.noticeText}>{coachReply}</Text> : null}
              </>
            )}
            <Text style={styles.helper}>The server enforces the 10-minute wait before completing the reset.</Text>
          </>
        ) : <>
          <Text style={styles.body}>Nuke is not active.</Text>
          {state?.planText ? <Text style={styles.helper}>Last saved plan{state.unlockedAt ? ` · ${new Date(state.unlockedAt).toLocaleString()}` : ''}: {state.planText}</Text> : null}
          <Button label={busy ? 'Activating…' : 'Activate account-wide Nuke'} onPress={begin} disabled={busy} />
        </>}
      </View>
    </ScrollView>
  );
}

function HomePage({ saved, client, busy, run, onChanged }: {
  saved: { revision: number; place: HomePlace | null; updatedAt: number } | undefined; client: ConvexReactClient; busy: boolean;
  run: (fn: () => Promise<unknown>) => Promise<void>; onChanged: () => void;
}) {
  const [label, setLabel] = useState('');
  const [latitude, setLatitude] = useState('');
  const [longitude, setLongitude] = useState('');
  const [radius, setRadius] = useState('100');
  useEffect(() => {
    const place = saved?.place;
    setLabel(place?.label ?? ''); setLatitude(place ? String(place.latitude) : ''); setLongitude(place ? String(place.longitude) : ''); setRadius(String(place?.radiusMeters ?? 100));
  }, [saved?.revision]);
  const save = () => run(async () => {
    if (!saved) throw new Error('Saved home location is still loading.');
    const place = label.trim() || latitude.trim() || longitude.trim() ? {
      label: label.trim(), latitude: parseFinite(latitude, 'Latitude'), longitude: parseFinite(longitude, 'Longitude'), radiusMeters: parseFinite(radius, 'Radius'),
    } : null;
    const result = await client.mutation(saveHomeLocationRef, { expectedRevision: saved.revision, place, updatedAt: Date.now() });
    if (!result.applied) throw new Error('Home location changed on another device. Reload it before saving.');
    onChanged();
  });
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.notice}><Text style={styles.noticeTitle}>Manual account location</Text><Text style={styles.helper}>This edits the saved account place only. FocusLock does not request or upload live device location here. Location access on a device is separate consent.</Text></View>
      <View style={styles.card}>
        {saved === undefined ? <Text style={styles.helper}>Loading saved home location…</Text> : null}
        {saved?.place ? <Text style={styles.helper}>Current saved place: {saved.place.label} · {saved.place.latitude}, {saved.place.longitude} · {saved.place.radiusMeters} m</Text> : null}
        <Field label="Place label" value={label} onChangeText={setLabel} placeholder="Home" />
        <Field label="Latitude" value={latitude} onChangeText={setLatitude} keyboardType="numbers-and-punctuation" />
        <Field label="Longitude" value={longitude} onChangeText={setLongitude} keyboardType="numbers-and-punctuation" />
        <Field label="Radius in metres (25–5000)" value={radius} onChangeText={setRadius} keyboardType="number-pad" />
        <Button label={busy ? 'Saving…' : 'Save home location'} onPress={save} disabled={busy || saved === undefined} />
        {saved?.place ? <Button label="Remove saved location" variant="secondary" onPress={() => run(async () => {
          const result = await client.mutation(saveHomeLocationRef, { expectedRevision: saved.revision, place: null, updatedAt: Date.now() });
          if (!result.applied) throw new Error('Home location changed on another device. Reload it before removing.');
          onChanged();
        })} disabled={busy} /> : null}
      </View>
    </ScrollView>
  );
}

function UsagePage({ usage }: { usage: UsageSummary | undefined }) {
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.notice}><Text style={styles.noticeTitle}>Recorded account usage</Text><Text style={styles.helper}>These totals reflect data returned by the account. Missing data is shown as unavailable, never as zero activity.</Text></View>
      {usage === undefined ? <Text style={styles.helper}>Loading usage summary…</Text> : (
        <>
          <View style={styles.card}><Text style={styles.cardTitle}>Total tracked</Text><Text style={styles.metric}>{formatDuration(usage.totalTrackedSeconds * 1000)}</Text><Text style={styles.helper}>HH:MM:SS across available account usage.</Text></View>
          <ReadOnlyList title="Devices" rows={usage.devices.map((device) => `${device.deviceName} · ${formatDuration(device.trackedSeconds * 1000)} tracked${device.blockedSeconds !== undefined ? ` · ${formatDuration(device.blockedSeconds * 1000)} blocked` : ''}`)} empty="No device usage returned." />
          <ReadOnlyList title="Targets" rows={usage.targets.slice(0, 50).map((target) => `${target.targetLabel} · ${formatDuration(target.trackedSeconds * 1000)}${target.blockedSeconds !== undefined ? ` · ${formatDuration(target.blockedSeconds * 1000)} blocked` : ''}`)} empty="No target usage returned." />
          <ReadOnlyList title="Days" rows={usage.days.slice(0, 30).map((day) => `${day.date} · ${day.trackedSeconds === undefined ? 'tracked total unavailable' : formatDuration(day.trackedSeconds * 1000)}${day.blockedSeconds !== undefined ? ` · ${formatDuration(day.blockedSeconds * 1000)} blocked` : ''}`)} empty="No daily usage returned." />
        </>
      )}
    </ScrollView>
  );
}

function ExportPage({ dashboard, onError }: { dashboard: Dashboard | null; onError: Props['onError'] }) {
  const [shared, setShared] = useState(false);
  const payload = useMemo(() => JSON.stringify(publicDashboard(dashboard), null, 2), [dashboard]);
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Share account snapshot</Text>
        <Text style={styles.helper}>Exports selected public dashboard data only: account totals, app and website boundaries, work history, and supported preferences. No tokens, location coordinates, strict-access secrets, or coach messages are included.</Text>
        <Button label={dashboard ? 'Share JSON snapshot' : 'Account snapshot unavailable'} disabled={!dashboard} onPress={() => { void Share.share({ title: 'FocusLock account snapshot', message: payload }).then(() => setShared(true)).catch((error) => onError(errorMessage(error))); }} />
        {shared ? <Text style={styles.helper}>Share sheet opened.</Text> : null}
      </View>
    </ScrollView>
  );
}

function publicDashboard(dashboard: Dashboard | null) {
  if (!dashboard) return { accountData: 'unavailable' };
  return {
    exportedAt: new Date().toISOString(),
    state: dashboard.state ? {
      creditBalanceSeconds: dashboard.state.creditBalanceSeconds,
      totalWorkSecondsToday: dashboard.state.totalWorkSecondsToday,
      lastResetDate: dashboard.state.lastResetDate,
    } : null,
    apps: dashboard.blockedApps.map(({ packageName, appName, isBlocked }) => ({ packageName, appName, isBlocked })),
    websites: dashboard.blockedWebsites.map(({ domain, displayName, isBlocked }) => ({ domain, displayName, isBlocked })),
    workRecords: dashboard.workRecords.map(({ recordId, title, durationMinutes, timestamp, source }) => ({ recordId, title, durationMinutes, timestamp, source })),
    preferences: dashboard.prefs ? {
      workRatio: dashboard.prefs.workRatio,
      strictMode: dashboard.prefs.strictMode,
      strictEndsAt: dashboard.prefs.strictEndsAt,
    } : null,
  };
}

function ReadOnlyList({ title, rows, empty }: { title: string; rows: string[]; empty: string }) {
  return (
    <View style={styles.card}>
      <Text style={styles.cardTitle}>{title}</Text>
      {rows.length ? rows.map((row, index) => <Text key={`${index}-${row}`} style={styles.listText}>{row}</Text>) : <Text style={styles.helper}>{empty}</Text>}
    </View>
  );
}

function MenuRow({ label, detail, onPress }: { label: string; detail: string; onPress: () => void }) {
  return <Pressable style={({ pressed }) => [styles.menuRow, pressed ? styles.pressed : null]} onPress={onPress} accessibilityRole="button"><View style={styles.itemCopy}><Text style={styles.rowTitle}>{label}</Text><Text style={styles.helper}>{detail}</Text></View><Text style={styles.chevron}>›</Text></Pressable>;
}

function Field({ label, value, onChangeText, placeholder, keyboardType, secureTextEntry, multiline, maxLength, autoCapitalize = 'sentences', autoCorrect }: {
  label: string; value: string; onChangeText: (value: string) => void; placeholder?: string; keyboardType?: 'default' | 'number-pad' | 'decimal-pad' | 'numbers-and-punctuation' | 'url' | 'email-address'; secureTextEntry?: boolean; multiline?: boolean; maxLength?: number; autoCapitalize?: 'none' | 'sentences' | 'words' | 'characters'; autoCorrect?: boolean;
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <TextInput style={[styles.input, multiline ? styles.multiline : null]} value={value} onChangeText={onChangeText} placeholder={placeholder} placeholderTextColor={colors.onSurfaceVariant} keyboardType={keyboardType} secureTextEntry={secureTextEntry} multiline={multiline} maxLength={maxLength} autoCapitalize={autoCapitalize} autoCorrect={autoCorrect} />
    </View>
  );
}

function Button({ label, onPress, disabled = false, variant = 'primary', compact = false }: { label: string; onPress: () => void; disabled?: boolean; variant?: 'primary' | 'secondary'; compact?: boolean }) {
  return <Pressable style={({ pressed }) => [styles.button, compact ? styles.compactButton : null, variant === 'secondary' ? styles.secondaryButton : null, disabled ? styles.disabled : null, pressed && !disabled ? styles.pressed : null]} onPress={onPress} disabled={disabled} accessibilityRole="button" accessibilityState={{ disabled }}><Text style={[styles.buttonText, variant === 'secondary' ? styles.secondaryButtonText : null]}>{label}</Text></Pressable>;
}

function methodLabel(method: string) {
  switch (method) {
    case 'pin': return 'PIN';
    case 'cooldown': return 'Cooldown';
    case 'charger': return 'Charger';
    case 'approval': return 'Approval contact';
    case 'schedules': return 'Schedule';
    default: return 'Timer';
  }
}

function pageTitle(page: Page) {
  return ({ menu: 'Account tools', websites: 'Account websites', targets: 'Other device targets', preferences: 'Account preferences', strict: 'Strict Mode', nuke: 'Account Nuke', home: 'Saved home location', usage: 'Usage summary', export: 'Export account data' } as const)[page];
}

function parseWhole(value: string, min: number, max: number, label: string) {
  if (!/^\d+$/.test(value.trim())) throw new TypeError(`${label} must be a whole number from ${min} to ${max}.`);
  const number = Number(value);
  if (number < min || number > max) throw new RangeError(`${label} must be from ${min} to ${max}.`);
  return number;
}

function parseFinite(value: string, label: string) {
  const number = Number(value);
  if (!value.trim() || !Number.isFinite(number)) throw new TypeError(`${label} must be a number.`);
  return number;
}

function formatDuration(secondsMs: number) {
  const seconds = Math.max(0, Math.floor(secondsMs / 1000));
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remainder = seconds % 60;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
}

function formatDays(days: number[]) {
  const labels = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  return days.map((day) => labels[day] ?? '?').join(', ');
}

function formatMinute(value: number) {
  return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: colors.background },
  flex: { flex: 1 },
  topbar: { minHeight: 52, flexDirection: 'row', alignItems: 'center', paddingHorizontal: spacing.medium, gap: spacing.small, borderBottomWidth: 1, borderBottomColor: colors.outlineVariant },
  back: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 4 },
  backText: { color: colors.primary, fontSize: 16, fontWeight: '600' },
  topTitle: { color: colors.onSurface, fontSize: 19, fontWeight: '700', flex: 1 },
  content: { padding: spacing.large, paddingBottom: spacing.xlarge, gap: spacing.medium },
  intro: { color: colors.onSurfaceVariant, fontSize: 15, lineHeight: 22 },
  menuRow: { minHeight: 72, borderRadius: radii.medium, backgroundColor: colors.surfaceContainerLow, paddingHorizontal: spacing.medium, paddingVertical: spacing.small, flexDirection: 'row', alignItems: 'center', gap: spacing.small },
  itemCopy: { flex: 1, gap: 4 },
  rowTitle: { color: colors.onSurface, fontSize: 16, fontWeight: '600', flexShrink: 1 },
  chevron: { color: colors.primary, fontSize: 28 },
  card: { padding: spacing.medium, borderRadius: radii.large, backgroundColor: colors.surfaceContainerLow, gap: spacing.small },
  cardTitle: { color: colors.onSurface, fontSize: 20, fontWeight: '700' },
  body: { color: colors.onSurface, fontSize: 16, lineHeight: 23 },
  helper: { color: colors.onSurfaceVariant, fontSize: 14, lineHeight: 20 },
  notice: { padding: spacing.medium, borderRadius: radii.medium, backgroundColor: colors.warningContainer, gap: 5 },
  noticeTitle: { color: colors.warning, fontWeight: '700', fontSize: 16 },
  noticeText: { color: colors.onSurface, fontSize: 15, lineHeight: 21 },
  field: { gap: 6 },
  fieldLabel: { color: colors.onSurface, fontSize: 14, fontWeight: '600' },
  input: { minHeight: 48, borderWidth: 1, borderColor: colors.outline, borderRadius: radii.small, paddingHorizontal: spacing.small, color: colors.onSurface, fontSize: 16, backgroundColor: colors.surfaceContainerLowest },
  multiline: { minHeight: 94, textAlignVertical: 'top', paddingVertical: spacing.small },
  switchRow: { minHeight: 52, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.medium },
  button: { minHeight: 48, paddingHorizontal: spacing.medium, paddingVertical: 11, borderRadius: radii.medium, backgroundColor: colors.primary, justifyContent: 'center', alignItems: 'center' },
  compactButton: { minWidth: 68, paddingHorizontal: spacing.small },
  secondaryButton: { backgroundColor: colors.surfaceContainerHigh },
  buttonText: { color: colors.onPrimary, fontSize: 15, fontWeight: '700', textAlign: 'center' },
  secondaryButtonText: { color: colors.onSurface },
  disabled: { opacity: 0.48 },
  pressed: { opacity: 0.78 },
  listItem: { flexDirection: 'row', alignItems: 'center', gap: spacing.small, paddingVertical: spacing.small, borderBottomWidth: 1, borderBottomColor: colors.outlineVariant },
  sectionLabel: { color: colors.primary, fontSize: 13, fontWeight: '700', letterSpacing: 0.8, marginTop: spacing.small },
  choiceRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.small },
  listText: { color: colors.onSurface, fontSize: 14, lineHeight: 20, paddingVertical: 5, borderBottomWidth: 1, borderBottomColor: colors.outlineVariant },
  metric: { color: colors.primary, fontSize: 30, fontWeight: '700', fontVariant: ['tabular-nums'] },
});
