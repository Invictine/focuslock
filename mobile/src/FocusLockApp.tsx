import React, { useMemo, useState } from 'react';
import {
  Alert,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { formatDuration } from './domain';
import type { MobileActions, MobileModel } from './types';
import { colors, radii, spacing } from './theme';

type Tab = 'Focus' | 'Boundaries' | 'History' | 'Settings';
const tabs: Tab[] = ['Focus', 'Boundaries', 'History', 'Settings'];
const durations = [15, 25, 50];

export default function FocusLockApp({ model, actions }: { model: MobileModel; actions: MobileActions }) {
  const [tab, setTab] = useState<Tab>('Focus');
  const [title, setTitle] = useState('Focused work');
  const [minutes, setMinutes] = useState(25);
  const session = model.session;
  const goalMs = (session?.targetMinutes ?? 0) * 60_000;
  const elapsed = Math.max(0, Math.min(goalMs, model.elapsedMs));
  const remaining = Math.max(0, goalMs - elapsed);
  const progress = goalMs > 0 ? Math.min(1, elapsed / goalMs) : 0;
  const accountState = model.dashboard?.state;
  const history = useMemo(() => {
    const records = model.dashboard?.workRecords ?? [];
    const merged = new Map<string, { recordId: string; title: string; durationMinutes: number; timestamp: number; source: string; pending: boolean }>();
    for (const record of records) merged.set(record.recordId, { ...record, pending: false });
    for (const record of model.pending) {
      if (!merged.has(record.recordId)) merged.set(record.recordId, { ...record, pending: true });
    }
    return [...merged.values()].sort((a, b) => b.timestamp - a.timestamp);
  }, [model.dashboard?.workRecords, model.pending]);

  const confirmDiscard = () => Alert.alert(
    'Discard this focus session?',
    'Its time will not be saved to your work history.',
    [{ text: 'Keep session', style: 'cancel' }, { text: 'Discard', style: 'destructive', onPress: actions.discardSession }],
  );
  const confirmSignOut = () => Alert.alert(
    'Sign out of FocusLock?',
    'Your saved work stays with this account. Screen Time blocking stays on until you turn it off.',
    [{ text: 'Cancel', style: 'cancel' }, { text: 'Sign out', style: 'destructive', onPress: actions.signOut }],
  );

  const content = tab === 'Focus'
    ? <FocusScreen
        model={model}
        actions={actions}
        title={title}
        setTitle={setTitle}
        minutes={minutes}
        setMinutes={setMinutes}
        elapsed={elapsed}
        remaining={remaining}
        progress={progress}
        creditSeconds={accountState?.creditBalanceSeconds}
        todaySeconds={accountState?.lastResetDate === model.today ? accountState.totalWorkSecondsToday : undefined}
        onDiscard={confirmDiscard}
      />
    : tab === 'Boundaries'
      ? <BoundariesScreen model={model} actions={actions} />
      : tab === 'History'
        ? <HistoryScreen records={history} model={model} />
        : <SettingsScreen model={model} actions={actions} onSignOut={confirmSignOut} />;

  return (
    <SafeAreaView style={styles.safe} edges={['top', 'left', 'right', 'bottom']}>
      <StatusBar barStyle="light-content" />
      {model.error ? (
        <View style={styles.errorBanner} accessibilityRole="alert">
          <Text style={styles.errorText}>{model.error}</Text>
          <Pressable style={styles.errorDismiss} onPress={actions.dismissError} accessibilityRole="button" accessibilityLabel="Dismiss error">
            <Text style={styles.errorClose}>×</Text>
          </Pressable>
        </View>
      ) : null}
      {model.onboardingStep !== null ? (
        <OnboardingScreen model={model} actions={actions} />
      ) : (
        <>
          <View style={styles.header}>
            <View>
              <Text style={styles.brand}>FocusLock</Text>
              <Text style={styles.headerSubtitle}>{tab}</Text>
            </View>
            <View style={[styles.connectionPill, model.connection === 'connected' ? styles.connectedPill : null]}>
              <View style={[styles.connectionDot, model.connection === 'connected' ? styles.connectedDot : null]} />
              <Text style={styles.connectionText}>
                {model.connection === 'loading' ? 'Syncing' : model.connection === 'connected' ? 'Connected' : 'Offline'}
              </Text>
            </View>
          </View>
          <View style={styles.page}>{content}</View>
          <View style={styles.tabBar} accessibilityRole="tablist">
            {tabs.map((item) => {
              const selected = item === tab;
              return (
                <Pressable
                  key={item}
                  style={styles.tabButton}
                  onPress={() => setTab(item)}
                  accessibilityRole="tab"
                  accessibilityState={{ selected }}
                  accessibilityLabel={`${item}${selected ? ', selected' : ''}`}
                >
                  <Text style={[styles.tabGlyph, selected ? styles.selectedTabText : null]}>{tabGlyph(item)}</Text>
                  <Text style={[styles.tabLabel, selected ? styles.selectedTabText : null]}>{item}</Text>
                </Pressable>
              );
            })}
          </View>
        </>
      )}
      {model.screenTime.shielding || model.screenTime.activeRuleCount > 0 ? (
        <Pressable
          style={styles.emergencyButton}
          onPress={actions.stopAllBlocking}
          accessibilityRole="button"
          accessibilityLabel="Suspend all blocking"
        >
          <Text style={styles.emergencyButtonText}>Suspend all blocking</Text>
        </Pressable>
      ) : null}
    </SafeAreaView>
  );
}

function FocusScreen(props: {
  model: MobileModel;
  actions: MobileActions;
  title: string;
  setTitle: (value: string) => void;
  minutes: number;
  setMinutes: (value: number) => void;
  elapsed: number;
  remaining: number;
  progress: number;
  creditSeconds?: number;
  todaySeconds?: number;
  onDiscard: () => void;
}) {
  const { model, actions, title, setTitle, minutes, setMinutes, elapsed, remaining, progress, creditSeconds, todaySeconds, onDiscard } = props;
  const session = model.session;
  const disabled = model.busy;
  return (
    <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={styles.scrollContent} keyboardShouldPersistTaps="handled">
        {model.signedIn ? (
          <View style={styles.statsRow}>
            <Stat label="Credit bank" value={creditSeconds === undefined ? '—' : `${Math.floor(creditSeconds / 60)} min`} />
            {todaySeconds !== undefined ? <Stat label="Focus today" value={formatFocusTotal(todaySeconds)} /> : null}
          </View>
        ) : (
          <View style={styles.inlineNotice}>
            <Text style={styles.body}>Local sessions stay on this iPhone. Sign in to see account credit and account work history.</Text>
            <ActionButton label="Sign in" onPress={actions.signIn} disabled={disabled} />
          </View>
        )}
        {model.signedIn ? <ActionButton label="Use credit for leisure apps" onPress={actions.openCreditTools} variant="secondary" /> : null}
        {session ? (
          <View style={styles.section}>
            <Text style={styles.eyebrow}>CURRENT SESSION</Text>
            <Text style={styles.sectionTitle}>{session.title}</Text>
            <Text style={styles.timer}>{formatDuration(remaining)}</Text>
            <Text style={styles.bodyCenter}>{remaining === 0 ? 'Ready to finish and save' : session.runningSince === null ? 'Paused' : 'Time remaining'}</Text>
            <View style={styles.track} accessibilityRole="progressbar" accessibilityValue={{ min: 0, max: 100, now: Math.round(progress * 100) }}>
              <View style={[styles.progress, { width: `${Math.round(progress * 100)}%` }]} />
            </View>
            <Text style={styles.mutedCenter}>{formatDuration(elapsed)} focused · {session.targetMinutes} min goal</Text>
            {remaining === 0 ? <Text style={styles.helperCenter}>The timer is ready. Finish it when you are done; FocusLock will not complete a task automatically.</Text> : null}
            <View style={styles.buttonStack}>
              {session.runningSince === null
                ? <ActionButton label="Resume focus" onPress={actions.resumeSession} disabled={disabled} />
                : <ActionButton label="Pause" onPress={actions.pauseSession} disabled={disabled} variant="secondary" />}
              <ActionButton label="Finish & save" onPress={actions.finishSession} disabled={disabled || elapsed < 60_000} />
              {elapsed < 60_000 ? <Text style={styles.helperCenter}>FocusLock can save a timer after at least one minute.</Text> : null}
              <ActionButton label="Discard session" onPress={onDiscard} disabled={disabled} variant="text" />
            </View>
          </View>
        ) : (
          <View style={styles.section}>
            <Text style={styles.eyebrow}>MAKE SPACE TO FOCUS</Text>
            <Text style={styles.sectionTitle}>Start a focus session</Text>
            <Text style={styles.body}>Choose a task and a timer. Starting a timer does not turn on app blocking.</Text>
            <Text style={styles.fieldLabel}>What are you working on?</Text>
            <TextInput
              style={styles.input}
              value={title}
              onChangeText={setTitle}
              placeholder="For example, chemistry revision"
              placeholderTextColor={colors.onSurfaceVariant}
              maxLength={160}
              returnKeyType="done"
              accessibilityLabel="Focus session title"
            />
            <Text style={styles.fieldLabel}>Session length</Text>
            <View style={styles.choiceRow}>
              {durations.map((duration) => (
                <Pressable
                  key={duration}
                  style={[styles.choice, minutes === duration ? styles.choiceSelected : null]}
                  onPress={() => setMinutes(duration)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: minutes === duration }}
                  accessibilityLabel={`${duration} minutes${minutes === duration ? ', selected' : ''}`}
                >
                  <Text style={[styles.choiceText, minutes === duration ? styles.choiceSelectedText : null]}>{duration} min</Text>
                </Pressable>
              ))}
            </View>
            <ActionButton
              label={disabled ? 'Starting…' : model.signedIn && !model.dashboard ? (model.connection === 'offline' ? 'Account unavailable' : 'Loading account…') : 'Start focus'}
              onPress={() => actions.startSession(title.trim(), minutes)}
              disabled={disabled || title.trim().length === 0 || (model.signedIn && !model.dashboard)}
            />
          </View>
        )}
        <Text style={styles.screenTimeHint}>App blocking is managed separately in Boundaries.</Text>
        <ActionButton label="Log completed work or tasks" onPress={actions.openManualLog} variant="secondary" />
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

function BoundariesScreen({ model, actions }: { model: MobileModel; actions: MobileActions }) {
  const status = model.screenTime;
  const disabled = model.busy;
  return (
    <ScrollView contentContainerStyle={styles.scrollContent}>
      <Text style={styles.pageTitle}>Boundaries</Text>
      <Text style={styles.body}>Choose which apps Screen Time should shield on this iPhone. You control when shielding is active.</Text>
      <ActionButton label="Schedules, limits & daily priority" onPress={actions.openDeviceTools} variant="secondary" />
      <View style={styles.section}>
        <Text style={styles.eyebrow}>THIS IPHONE</Text>
        <Text style={styles.sectionTitle}>Screen Time</Text>
        {!status.available ? (
          <View style={styles.noticeBox}>
            <Text style={styles.noticeTitle}>Development build required</Text>
            <Text style={styles.body}>App selection and shielding need the FocusLock iOS development build on a supported device.</Text>
          </View>
        ) : (
          <>
            <Text style={styles.body}>Authorization: {authorizationLabel(status.authorization)}</Text>
            <Text style={styles.body}>Apps selected: {status.selectionCount}</Text>
            {status.authorization === 'notDetermined' || status.authorization === 'denied' ? (
              <ActionButton
                label={status.authorization === 'denied' ? 'Review Screen Time access' : 'Set up Screen Time'}
                onPress={actions.authorizeScreenTime}
                disabled={disabled}
                variant="secondary"
              />
            ) : null}
            <ActionButton
              label={status.selectionCount > 0 ? 'Choose apps' : 'Select apps to shield'}
              onPress={actions.chooseDistractions}
              disabled={disabled || status.shielding || status.authorization !== 'approved'}
              variant="secondary"
            />
            <Text style={styles.helper}>App selection is stored by iOS on this device. It does not convert Android package names or Windows app identifiers.</Text>
            <View style={styles.separator} />
            <Text style={styles.fieldLabel}>Manual shielding</Text>
        <Text style={styles.body}>A focus timer does not change this switch. Blocking stays on until you turn it off, including after the timer ends or the app closes.</Text>
            <View style={styles.toggleRow}>
              <View style={styles.toggleCopy}>
                <Text style={styles.toggleTitle}>{status.shielding ? 'Shielding is on' : 'Shielding is off'}</Text>
                <Text style={styles.helper}>{status.shielding ? 'Selected apps are currently shielded.' : 'Selected apps can open normally.'}</Text>
              </View>
              <Switch
                style={styles.nativeSwitch}
                value={status.shielding}
                onValueChange={actions.toggleShielding}
                disabled={!status.shielding && (disabled || !status.available || status.authorization !== 'approved' || status.selectionCount === 0)}
                trackColor={{ false: colors.surfaceContainerHighest, true: colors.primaryContainer }}
                thumbColor={status.shielding ? colors.primary : colors.onSurfaceVariant}
                ios_backgroundColor={colors.surfaceContainerHighest}
                accessibilityLabel="Manual app shielding"
                accessibilityHint={status.shielding ? 'Turn off app shielding' : 'Turn on app shielding for selected apps'}
              />
            </View>
          </>
        )}
      </View>
      <View style={styles.section}>
        <Text style={styles.eyebrow}>ACCOUNT BOUNDARIES · REFERENCE</Text>
        <Text style={styles.sectionTitle}>Blocked websites</Text>
        <Text style={styles.helper}>This synced list is read-only here. iOS web filtering is not active from these account entries.</Text>
        {model.dashboard?.blockedWebsites.length ? model.dashboard.blockedWebsites.map((site) => (
          <View key={site.domain} style={styles.listRow}>
            <View style={styles.listCopy}>
              <Text style={styles.rowTitle}>{site.displayName}</Text>
              <Text style={styles.helper}>{site.domain}</Text>
            </View>
            <Text style={site.isBlocked ? styles.statusActive : styles.statusMuted}>{site.isBlocked ? 'Blocked on account' : 'Not blocked on account'}</Text>
          </View>
        )) : <Text style={styles.helper}>{!model.signedIn
          ? 'Sign in to view account website boundaries.'
          : !model.dashboard
            ? model.connection === 'offline' ? 'Account website boundaries are unavailable while offline.' : 'Loading account website boundaries…'
            : 'No account website boundaries are available.'}</Text>}
      </View>
    </ScrollView>
  );
}

function HistoryScreen({ records, model }: { records: { recordId: string; title: string; durationMinutes: number; timestamp: number; source: string; pending: boolean }[]; model: MobileModel }) {
  const unavailable = model.signedIn && !model.dashboard;
  const intro = !model.signedIn
    ? 'Sessions saved in local mode stay on this iPhone.'
    : model.connection === 'offline'
      ? 'Account history may be unavailable offline. Signed-in sessions wait to sync when connected; local sessions stay on this iPhone.'
      : 'Saved work sessions from your account and sessions waiting to sync from this device.';
  return (
    <ScrollView contentContainerStyle={styles.scrollContent}>
      <Text style={styles.pageTitle}>Work history</Text>
      <Text style={styles.body}>{intro}</Text>
      {records.length === 0 && unavailable ? (
        <View style={styles.emptyState}>
          <Text style={styles.sectionTitle}>Account history unavailable</Text>
          <Text style={styles.bodyCenter}>{model.connection === 'offline' ? 'Reconnect to load your account work history.' : 'Account history is loading.'}</Text>
        </View>
      ) : records.length === 0 ? (
        <View style={styles.emptyState}>
          <Text style={styles.emptyMark}>◷</Text>
          <Text style={styles.sectionTitle}>{model.signedIn ? 'No work sessions yet' : 'No local work sessions yet'}</Text>
          <Text style={styles.bodyCenter}>Finish a focus session to add it here.</Text>
        </View>
      ) : records.map((record) => (
        <View key={record.recordId} style={styles.historyRow}>
          <View style={styles.historyTop}>
            <Text style={[styles.rowTitle, styles.historyTitle]}>{record.title}</Text>
            <Text style={styles.durationLabel}>{record.durationMinutes} min</Text>
          </View>
          <Text style={styles.helper}>{new Date(record.timestamp).toLocaleString()} · {sourceLabel(record.source)}</Text>
          {record.pending ? <Text style={styles.pendingLabel}>{model.signedIn ? 'Waiting to sync' : 'Saved on this iPhone'}</Text> : null}
        </View>
      ))}
    </ScrollView>
  );
}

function SettingsScreen({ model, actions, onSignOut }: { model: MobileModel; actions: MobileActions; onSignOut: () => void }) {
  return (
    <ScrollView contentContainerStyle={styles.scrollContent}>
      <Text style={styles.pageTitle}>Settings</Text>
      <View style={styles.section}>
        <Text style={styles.eyebrow}>ACCOUNT</Text>
        <Text style={styles.sectionTitle}>{model.signedIn ? model.displayName : 'Not signed in'}</Text>
        <Text style={styles.body}>{connectionDescription(model.connection, model.signedIn)}</Text>
        {model.signedIn ? (
          <>
            {model.connection !== 'connected' ? <ActionButton label="Retry sync" onPress={actions.retrySync} disabled={model.busy} variant="secondary" /> : null}
            <ActionButton label="Sign out" onPress={onSignOut} disabled={model.busy} variant="text" />
          </>
        ) : <ActionButton label="Sign in" onPress={actions.signIn} disabled={model.busy} />}
      </View>
      <View style={styles.section}>
        <Text style={styles.eyebrow}>IOS LIMITS</Text>
        <Text style={styles.sectionTitle}>What this app can control</Text>
        <Text style={styles.body}>FocusLock can apply manual Screen Time shields to apps you select. You can revoke Screen Time authorization in iOS Settings.</Text>
        <Text style={styles.body}>Schedules, daily limits, permanent selections, timed blocks, and daily priority tasks use this iPhone’s Screen Time controls. Apple lets you revoke this access in Settings.</Text>
        <Text style={styles.body}>Android’s uninstall, Settings, and recents protections have no equivalent here. Choose leisure apps separately to spend account credit through iOS usage allowances.</Text>
        <ActionButton label="Credit for leisure apps" onPress={actions.openCreditTools} variant="secondary" />
        <ActionButton label="Device controls & reminders" onPress={actions.openDeviceTools} variant="secondary" />
        <ActionButton label="Home location & consent" onPress={actions.openHome} variant="secondary" />
        {model.signedIn ? <>
          <ActionButton label="Account controls & reports" onPress={actions.openAccountTools} variant="secondary" />
          <ActionButton label="TickTick tasks & focus import" onPress={actions.openTickTick} variant="secondary" />
        </> : null}
      </View>
      <View style={styles.section}>
        <Text style={styles.eyebrow}>GETTING STARTED</Text>
        <Text style={styles.body}>Review focus sessions, account credit, Screen Time setup, and recovery guidance.</Text>
        <ActionButton label="Replay setup guide" onPress={actions.replayOnboarding} disabled={model.busy} variant="secondary" />
      </View>
    </ScrollView>
  );
}

function OnboardingScreen({ model, actions }: { model: MobileModel; actions: MobileActions }) {
  const step = Math.max(0, Math.min(3, model.onboardingStep ?? 0));
  const pages = [
    { title: 'Focus sessions', copy: 'Set a task and timer, then save the work you completed. The timer and app shielding are separate controls.' },
    { title: 'Account credit', copy: 'Sign in to see your account credit and sync completed work. Sessions made in local mode stay only on this iPhone and will not upload if you sign in later.' },
    { title: 'Screen Time', copy: 'Choose apps on this iPhone and turn manual shielding on when you want it. FocusLock asks for access only when you start setup.' },
    { title: 'Recovery', copy: 'You can pause or discard a timer, turn off shielding, or revoke Screen Time access in iOS Settings.' },
  ];
  const page = pages[step];
  return (
    <ScrollView style={styles.onboardingScroll} contentContainerStyle={styles.onboarding}>
      <Text style={styles.brand}>FocusLock</Text>
      <View style={styles.stepDots} accessibilityLabel={`Step ${step + 1} of ${pages.length}`}>
        {pages.map((_, index) => <View key={index} style={[styles.stepDot, index === step ? styles.stepDotActive : null]} />)}
      </View>
      <Text style={styles.eyebrow}>STEP {step + 1} OF {pages.length}</Text>
      <Text style={styles.onboardingTitle}>{page.title}</Text>
      <Text style={styles.body}>{page.copy}</Text>
      <View style={styles.onboardingButtons}>
        {step > 0 ? <ActionButton label="Back" onPress={actions.previousOnboarding} disabled={model.busy} variant="secondary" /> : null}
        <ActionButton label={step === pages.length - 1 ? 'Finish setup' : 'Next'} onPress={actions.nextOnboarding} disabled={model.busy} />
      </View>
    </ScrollView>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.stat}>
      <Text style={styles.statLabel}>{label}</Text>
      <Text style={styles.statValue}>{value}</Text>
    </View>
  );
}

function ActionButton({ label, onPress, disabled, variant = 'primary' }: { label: string; onPress: () => void; disabled?: boolean; variant?: 'primary' | 'secondary' | 'text' }) {
  return (
    <Pressable
      style={({ pressed }) => [
        styles.actionButton,
        variant === 'secondary' ? styles.actionSecondary : null,
        variant === 'text' ? styles.actionText : null,
        pressed && !disabled ? styles.pressed : null,
        disabled ? styles.disabled : null,
      ]}
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityState={{ disabled: Boolean(disabled) }}
    >
      <Text style={[styles.actionLabel, variant !== 'primary' ? styles.actionSecondaryLabel : null]}>{label}</Text>
    </Pressable>
  );
}

function tabGlyph(tab: Tab) {
  switch (tab) {
    case 'Focus': return '◷';
    case 'Boundaries': return '◈';
    case 'History': return '≡';
    case 'Settings': return '⚙';
  }
}

function authorizationLabel(value: MobileModel['screenTime']['authorization']) {
  if (value === 'approved') return 'Approved';
  if (value === 'denied') return 'Not approved';
  return 'Not set up';
}

function sourceLabel(source: string) {
  if (source === 'IOS_TIMER') return 'Self-reported timer';
  return source.replace(/[_-]+/g, ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function connectionDescription(connection: MobileModel['connection'], signedIn: boolean) {
  if (!signedIn) return 'Local sessions stay on this iPhone. Screen Time choices stay on this device.';
  if (connection === 'connected') return 'Sync is connected.';
  if (connection === 'loading') return 'Sync is connecting.';
  return 'Offline. Account sessions stay with your account; local sessions stay on this iPhone.';
}

function formatFocusTotal(seconds: number) {
  const totalMinutes = Math.floor(seconds / 60);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return hours > 0 ? `${hours} hr ${minutes} min` : `${totalMinutes} min`;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  flex: { flex: 1 },
  header: { minHeight: 66, paddingHorizontal: spacing.large, paddingVertical: spacing.small, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  brand: { color: colors.onSurface, fontSize: 21, fontWeight: '700', letterSpacing: 0.2 },
  headerSubtitle: { color: colors.onSurfaceVariant, fontSize: 13, marginTop: 2 },
  connectionPill: { flexDirection: 'row', alignItems: 'center', gap: 7, paddingHorizontal: 11, paddingVertical: 8, borderRadius: 24, backgroundColor: colors.surfaceContainerHigh },
  connectedPill: { backgroundColor: colors.successContainer },
  connectionDot: { width: 7, height: 7, borderRadius: 5, backgroundColor: colors.warning },
  connectedDot: { backgroundColor: colors.success },
  connectionText: { color: colors.onSurfaceVariant, fontSize: 12 },
  errorBanner: { marginHorizontal: spacing.large, marginBottom: spacing.small, backgroundColor: colors.errorContainer, borderRadius: radii.medium, paddingHorizontal: spacing.medium, paddingVertical: 12, flexDirection: 'row', alignItems: 'center', gap: 10 },
  errorText: { color: colors.onErrorContainer, flex: 1, fontSize: 14 },
  errorClose: { color: colors.onErrorContainer, fontSize: 24, paddingHorizontal: 5 },
  errorDismiss: { minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  page: { flex: 1 },
  scrollContent: { paddingHorizontal: spacing.large, paddingTop: spacing.medium, paddingBottom: spacing.xlarge, gap: spacing.medium },
  pageTitle: { color: colors.onSurface, fontSize: 30, fontWeight: '700', marginBottom: 2 },
  body: { color: colors.onSurfaceVariant, fontSize: 16, lineHeight: 23 },
  bodyCenter: { color: colors.onSurfaceVariant, textAlign: 'center', fontSize: 15, lineHeight: 22 },
  helper: { color: colors.onSurfaceVariant, fontSize: 13, lineHeight: 19 },
  statsRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.small },
  stat: { flexGrow: 1, flexBasis: '45%', minHeight: 82, padding: spacing.medium, borderRadius: radii.medium, backgroundColor: colors.surfaceContainerLow, justifyContent: 'center' },
  statLabel: { color: colors.onSurfaceVariant, fontSize: 13 },
  statValue: { color: colors.onSurface, fontSize: 22, fontWeight: '700', marginTop: 5 },
  inlineNotice: { borderRadius: radii.medium, padding: spacing.medium, gap: spacing.small, backgroundColor: colors.surfaceContainerLow },
  section: { backgroundColor: colors.surfaceContainerLow, borderRadius: radii.large, padding: spacing.large, gap: spacing.small },
  eyebrow: { color: colors.primary, fontSize: 12, fontWeight: '700', letterSpacing: 1.1 },
  sectionTitle: { color: colors.onSurface, fontSize: 23, fontWeight: '700' },
  timer: { color: colors.onSurface, fontSize: 48, fontWeight: '600', fontVariant: ['tabular-nums'], textAlign: 'center', marginTop: spacing.medium },
  track: { height: 8, borderRadius: 8, backgroundColor: colors.surfaceContainerHighest, overflow: 'hidden', marginTop: spacing.small },
  progress: { height: '100%', backgroundColor: colors.primary, borderRadius: 8 },
  mutedCenter: { color: colors.onSurfaceVariant, textAlign: 'center', fontSize: 13 },
  helperCenter: { color: colors.onSurfaceVariant, textAlign: 'center', fontSize: 14, lineHeight: 20 },
  buttonStack: { gap: 4, marginTop: spacing.small },
  fieldLabel: { color: colors.onSurface, fontSize: 15, fontWeight: '600', marginTop: spacing.small },
  input: { minHeight: 52, borderWidth: 1, borderColor: colors.outline, borderRadius: radii.medium, paddingHorizontal: spacing.medium, color: colors.onSurface, fontSize: 16, backgroundColor: colors.surfaceContainerLowest },
  choiceRow: { flexDirection: 'row', gap: spacing.small, marginBottom: spacing.small },
  choice: { flex: 1, minHeight: 48, alignItems: 'center', justifyContent: 'center', borderRadius: radii.medium, borderWidth: 1, borderColor: colors.outlineVariant, backgroundColor: colors.surfaceContainer },
  choiceSelected: { borderColor: colors.primary, backgroundColor: colors.primaryContainer },
  choiceText: { color: colors.onSurfaceVariant, fontSize: 15, fontWeight: '600' },
  choiceSelectedText: { color: colors.onPrimaryContainer },
  actionButton: { minHeight: 48, paddingHorizontal: spacing.medium, paddingVertical: 12, borderRadius: radii.medium, backgroundColor: colors.primary, alignItems: 'center', justifyContent: 'center' },
  actionSecondary: { backgroundColor: colors.surfaceContainerHigh },
  actionText: { backgroundColor: 'transparent' },
  actionLabel: { color: colors.onPrimary, fontSize: 16, fontWeight: '700', textAlign: 'center' },
  actionSecondaryLabel: { color: colors.onSurface },
  pressed: { opacity: 0.78 },
  disabled: { opacity: 0.45 },
  screenTimeHint: { color: colors.onSurfaceVariant, textAlign: 'center', fontSize: 13, paddingHorizontal: spacing.large },
  noticeBox: { borderRadius: radii.medium, backgroundColor: colors.warningContainer, padding: spacing.medium, gap: spacing.small },
  noticeTitle: { color: colors.warning, fontSize: 16, fontWeight: '700' },
  separator: { height: 1, backgroundColor: colors.outlineVariant, marginVertical: spacing.small },
  toggleRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.medium, minHeight: 58 },
  toggleCopy: { flex: 1, gap: 3 },
  toggleTitle: { color: colors.onSurface, fontSize: 16, fontWeight: '600' },
  nativeSwitch: { minWidth: 54, minHeight: 44 },
  listRow: { minHeight: 62, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.small, borderTopWidth: 1, borderTopColor: colors.outlineVariant, paddingVertical: spacing.small },
  listCopy: { flex: 1, gap: 3 },
  rowTitle: { color: colors.onSurface, fontSize: 16, fontWeight: '600' },
  statusActive: { color: colors.error, fontSize: 13, fontWeight: '600' },
  statusMuted: { color: colors.onSurfaceVariant, fontSize: 13 },
  emptyState: { alignItems: 'center', paddingVertical: spacing.xlarge, gap: spacing.small },
  emptyMark: { color: colors.primary, fontSize: 34 },
  historyRow: { paddingVertical: spacing.medium, borderBottomWidth: 1, borderBottomColor: colors.outlineVariant, gap: 6 },
  historyTop: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.small, alignItems: 'flex-start' },
  historyTitle: { flex: 1, flexShrink: 1 },
  durationLabel: { color: colors.primary, fontSize: 15, fontWeight: '700', flexShrink: 0 },
  pendingLabel: { color: colors.warning, fontSize: 13 },
  tabBar: { minHeight: 62, paddingTop: 5, paddingBottom: 3, backgroundColor: colors.surfaceContainerLow, borderTopWidth: 1, borderTopColor: colors.outlineVariant, flexDirection: 'row' },
  tabButton: { flex: 1, minHeight: 52, justifyContent: 'center', alignItems: 'center', gap: 1 },
  tabGlyph: { color: colors.onSurfaceVariant, fontSize: 18 },
  tabLabel: { color: colors.onSurfaceVariant, fontSize: 11, fontWeight: '600' },
  selectedTabText: { color: colors.primary },
  onboardingScroll: { flex: 1 },
  onboarding: { flexGrow: 1, justifyContent: 'center', padding: spacing.large, gap: spacing.medium },
  stepDots: { flexDirection: 'row', gap: spacing.small, marginVertical: spacing.medium },
  stepDot: { flex: 1, height: 4, borderRadius: 4, backgroundColor: colors.surfaceContainerHighest },
  stepDotActive: { backgroundColor: colors.primary },
  onboardingTitle: { color: colors.onSurface, fontSize: 34, fontWeight: '700', lineHeight: 42 },
  onboardingButtons: { gap: spacing.small, marginTop: spacing.large },
  emergencyButton: { minHeight: 48, marginHorizontal: spacing.large, marginBottom: spacing.small, borderRadius: radii.medium, backgroundColor: colors.error, alignItems: 'center', justifyContent: 'center', paddingHorizontal: spacing.medium },
  emergencyButtonText: { color: colors.onError, fontSize: 16, fontWeight: '700' },
});
