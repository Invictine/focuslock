import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Pressable,
  ScrollView,
  Share,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Notifications from 'expo-notifications';
import { randomUUID } from 'expo-crypto';
import ScreenTime, { ScreenTimeReport, type NativeRule, type NativeRuleInput, type ScreenTimeStatus } from 'focuslock-screen-time';
import { errorMessage } from './auth';
import {
  formatClockTime,
  formatWeekdays,
  parseClockTime,
  ruleValidationError,
  type DeviceRuleKind,
  type RuleDraft,
} from './deviceRules';
import { colors, radii, spacing } from './theme';

type Props = {
  screenTime: ScreenTimeStatus;
  totalFocusMinutesToday: number;
  taskFocusMinutesByTitle?: Record<string, number>;
  onError: (message: string) => void;
  onChanged: () => void;
};

type Page = 'list' | 'editor';
type Reminder = { id: string; time: string };

const REMINDER_KEY = 'focuslock.device-tools.daily-reminder.v1';
const weekdays = [
  [1, 'Sun'], [2, 'Mon'], [3, 'Tue'], [4, 'Wed'], [5, 'Thu'], [6, 'Fri'], [7, 'Sat'],
] as const;
const kinds: Array<[DeviceRuleKind, string, string]> = [
  ['schedule', 'Schedule', 'Block selected apps during chosen days and hours'],
  ['dailyLimit', 'Daily limit', 'Set a daily allowance for selected apps'],
  ['permanent', 'Permanent block', 'Keep selected apps blocked until you suspend protection'],
  ['timed', 'Focus block', 'Block selected apps for a fixed period'],
  ['task', 'Frog task', 'Unlock only after your focus goal and manual task check'],
];

function todayLocal(): string {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function asDraft(rule: NativeRule): RuleDraft {
  const remaining = rule.endsAt === undefined ? 0 : Math.ceil((rule.endsAt - Date.now()) / 60_000);
  return {
    ...rule,
    ...(rule.kind === 'timed' ? { timedDurationMinutes: remaining >= 15 ? remaining : 25 } : {}),
    weekdays: rule.weekdays ? [...rule.weekdays] : undefined,
  };
}

function toInput(rule: RuleDraft, enabled = rule.enabled): NativeRuleInput {
  return {
    id: rule.id!, title: rule.title.trim(), kind: rule.kind, enabled,
    ...(rule.startMinute === undefined ? {} : { startMinute: rule.startMinute }),
    ...(rule.endMinute === undefined ? {} : { endMinute: rule.endMinute }),
    ...(rule.weekdays === undefined ? {} : { weekdays: [...rule.weekdays] }),
    ...(rule.limitMinutes === undefined ? {} : { limitMinutes: rule.limitMinutes }),
    ...(rule.kind === 'timed' && rule.timedDurationMinutes !== undefined
      ? { endsAt: Date.now() + rule.timedDurationMinutes * 60_000 }
      : rule.endsAt === undefined ? {} : { endsAt: rule.endsAt }),
    ...(rule.requiredFocusMinutes === undefined ? {} : { requiredFocusMinutes: rule.requiredFocusMinutes }),
  };
}

function newDraft(kind: DeviceRuleKind): RuleDraft {
  const id = `r-${Date.now().toString(36)}-${randomUUID().replace(/-/g, '').slice(0, 8)}`;
  const common = { id, title: '', kind, enabled: false, selectionCount: 0 };
  switch (kind) {
    case 'schedule': return { ...common, title: 'Weekday focus', startMinute: 9 * 60, endMinute: 17 * 60, weekdays: [2, 3, 4, 5, 6] };
    case 'dailyLimit': return { ...common, title: 'Daily app limit', limitMinutes: 30 };
    case 'permanent': return { ...common, title: 'Permanent block' };
    case 'timed': return { ...common, title: 'Focus block', timedDurationMinutes: 25, endsAt: Date.now() + 25 * 60_000 };
    case 'task': return { ...common, title: 'Wake at 7:00', startMinute: 7 * 60, requiredFocusMinutes: 25 };
  }
}

export default function DeviceTools({ screenTime, totalFocusMinutesToday, taskFocusMinutesByTitle = {}, onError, onChanged }: Props) {
  const [rules, setRules] = useState<NativeRule[]>([]);
  const [status, setStatus] = useState(screenTime);
  const [page, setPage] = useState<Page>('list');
  const [draft, setDraft] = useState<RuleDraft | null>(null);
  const [busy, setBusy] = useState(false);
  const [emergencyBusy, setEmergencyBusy] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [reminderTime, setReminderTime] = useState('07:00');
  const [reminder, setReminder] = useState<Reminder | null>(null);
  const mounted = useRef(true);
  const errorRef = useRef(onError);
  const changedRef = useRef(onChanged);
  errorRef.current = onError;
  changedRef.current = onChanged;

  useEffect(() => { setStatus(screenTime); }, [screenTime]);

  useEffect(() => {
    mounted.current = true;
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    let active = true;
    const initialize = async () => {
      try {
        const [latestStatus, savedRules, savedReminder] = await Promise.all([
          ScreenTime.getStatus(),
          ScreenTime.getRules(),
          AsyncStorage.getItem(REMINDER_KEY),
        ]);
        if (!active) return;
        setStatus(latestStatus);
        setRules(savedRules);
        if (savedReminder) {
          const parsed = JSON.parse(savedReminder) as Reminder;
          if (typeof parsed.id === 'string' && typeof parsed.time === 'string') {
            setReminder(parsed);
            setReminderTime(parsed.time);
          }
        }
      } catch (error) {
        if (active) errorRef.current(errorMessage(error));
      }
    };
    void initialize();
    return () => { active = false; mounted.current = false; clearInterval(timer); };
  }, []);

  const strictActive = status.strictUntil > now;
  const strictUntilLabel = strictActive ? new Date(status.strictUntil).toLocaleString() : '';
  const orderedRules = useMemo(() => [...rules].sort((a, b) => Number(b.enabled) - Number(a.enabled) || a.title.localeCompare(b.title)), [rules]);
  const report = (error: unknown) => { if (mounted.current) errorRef.current(errorMessage(error)); };
  const run = async (operation: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    try { await operation(); }
    catch (error) { report(error); }
    finally { if (mounted.current) setBusy(false); }
  };
  const commitRules = (next: NativeRule[]) => { if (mounted.current) setRules(next); };
  const refreshStatus = async () => {
    const latest = await ScreenTime.getStatus();
    if (mounted.current) setStatus(latest);
    return latest;
  };
  const changed = () => { if (mounted.current) changedRef.current(); };

  const beginCreate = (kind: DeviceRuleKind) => {
    if (strictActive) return;
    setDraft(newDraft(kind));
    setPage('editor');
  };

  const saveNewAndPick = () => {
    if (!draft || strictActive) return;
    if (status.authorization !== 'approved') { errorRef.current('Approve Screen Time access before choosing apps.'); return; }
    const candidate = draft.kind === 'timed' && draft.timedDurationMinutes !== undefined
      ? { ...draft, endsAt: Date.now() + draft.timedDurationMinutes * 60_000 }
      : draft;
    const validation = ruleValidationError({ ...candidate, enabled: false }, rules, status.authorization, Date.now());
    if (validation) { errorRef.current(validation); return; }
    void run(async () => {
      const saved = await ScreenTime.saveRule(toInput(candidate, false));
      commitRules(saved);
      const picked = await ScreenTime.pickRule(candidate.id!);
      commitRules(picked);
      const updated = picked.find((rule) => rule.id === candidate.id);
      if (updated && mounted.current) setDraft(asDraft(updated));
      await refreshStatus();
      changed();
      if (mounted.current) setPage('list');
    });
  };

  const saveEditedRule = () => {
    if (!draft || !draft.id || strictActive) return;
    const candidate = draft.kind === 'timed' && draft.timedDurationMinutes !== undefined
      ? { ...draft, endsAt: Date.now() + draft.timedDurationMinutes * 60_000 }
      : draft;
    const validation = ruleValidationError(candidate, rules, status.authorization, Date.now());
    if (validation) { errorRef.current(validation); return; }
    void run(async () => {
      const next = await ScreenTime.saveRule(toInput(candidate));
      commitRules(next);
      await refreshStatus();
      changed();
      if (mounted.current) setPage('list');
    });
  };

  const chooseApps = (rule: NativeRule) => {
    if (strictActive || busy) return;
    if (status.authorization !== 'approved') {
      errorRef.current('Approve Screen Time access before choosing apps.');
      return;
    }
    void run(async () => {
      const next = await ScreenTime.pickRule(rule.id);
      commitRules(next);
      if (mounted.current) {
        const updated = next.find((item) => item.id === rule.id);
        if (updated && draft?.id === rule.id) setDraft(asDraft(updated));
      }
      await refreshStatus();
      changed();
    });
  };

  const setEnabled = (rule: NativeRule, enabled: boolean) => {
    if (strictActive || busy) return;
    if (rule.kind === 'permanent' && rule.enabled && !enabled) return;
    const candidate = { ...asDraft(rule), enabled };
    const validation = ruleValidationError(candidate, rules, status.authorization, Date.now());
    if (enabled && rule.kind === 'timed' && (rule.endsAt ?? 0) < Date.now() + 15 * 60_000) {
      errorRef.current('This timed block is expired or has less than 15 minutes left. Edit it and set a fresh duration before enabling it.');
      return;
    }
    if (validation) { errorRef.current(validation); return; }
    const persist = () => void run(async () => {
      const next = await ScreenTime.saveRule(toInput(candidate, enabled));
      commitRules(next);
      await refreshStatus();
      changed();
    });
    if (enabled && rule.kind === 'permanent') {
      Alert.alert(
        'Enable a permanent block?',
        'This keeps the selected apps blocked for as long as Screen Time access and FocusLock remain available. Emergency suspend is always available here.',
        [{ text: 'Cancel', style: 'cancel' }, { text: 'Enable block', style: 'destructive', onPress: persist }],
      );
      return;
    }
    persist();
  };

  const removeRule = (rule: NativeRule) => {
    if (strictActive || busy || (rule.kind === 'permanent' && rule.enabled)) return;
    Alert.alert('Delete this device rule?', `“${rule.title}” will be removed from this iPhone.`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => void run(async () => {
        commitRules(await ScreenTime.deleteRule(rule.id));
        changed();
      }) },
    ]);
  };

  const requestAccess = () => void run(async () => {
    setStatus(await ScreenTime.requestAuthorization());
    commitRules(await ScreenTime.getRules());
  });

  const emergencyAction = () => {
    if (emergencyBusy) return;
    const resuming = status.suspended;
    Alert.alert(
      resuming ? 'Resume device rules?' : 'Suspend all device rules?',
      resuming
        ? 'Selected apps will be protected again under the saved rules.'
        : 'This temporarily stops Screen Time blocking. Your saved rules and app selections stay in place.',
      [{ text: 'Cancel', style: 'cancel' }, { text: resuming ? 'Resume rules' : 'Suspend all', style: resuming ? 'default' : 'destructive', onPress: () => {
        setEmergencyBusy(true);
        void (async () => {
          try {
            setStatus(resuming ? await ScreenTime.resumeAll() : await ScreenTime.suspendAll());
            commitRules(await ScreenTime.getRules());
            changed();
          } catch (error) { report(error); }
          finally { if (mounted.current) setEmergencyBusy(false); }
        })();
      } }],
    );
  };

  const startStrict = (hours: number) => {
    const until = Date.now() + hours * 60 * 60_000;
    if (until <= status.strictUntil || busy) return;
    void run(async () => {
      setStatus(await ScreenTime.setStrictUntil(until));
      changed();
    });
  };

  const updateTask = (rule: NativeRule, checked: boolean) => void run(async () => {
    commitRules(await ScreenTime.updateTaskProgress(rule.id, taskFocusMinutesByTitle[rule.title] ?? 0, checked, totalFocusMinutesToday));
    changed();
  });

  const exportMetadata = () => void run(async () => {
    const metadata = rules.map(({ id, title, kind, enabled, startMinute, endMinute, weekdays: days, limitMinutes, endsAt, requiredFocusMinutes }) => ({
      id, title, kind, enabled, startMinute, endMinute, weekdays: days, limitMinutes, endsAt, requiredFocusMinutes,
    }));
    await Share.share({ title: 'FocusLock device rule metadata', message: JSON.stringify({ version: 1, rules: metadata }, null, 2) });
  });

  const saveReminder = () => {
    const minuteOfDay = parseClockTime(reminderTime);
    if (minuteOfDay === null) { errorRef.current('Enter a reminder time as HH:MM.'); return; }
    void run(async () => {
      const permission = await Notifications.requestPermissionsAsync();
      if (!permission.granted) throw new Error('Allow notifications to set a daily reminder.');
      const hour = Math.floor(minuteOfDay / 60);
      const minute = minuteOfDay % 60;
      const id = await Notifications.scheduleNotificationAsync({
        content: { title: 'FocusLock', body: 'Take a moment to begin your planned focus.' },
        trigger: { type: Notifications.SchedulableTriggerInputTypes.DAILY, hour, minute },
      });
      const next = { id, time: reminderTime.trim() };
      try {
        await AsyncStorage.setItem(REMINDER_KEY, JSON.stringify(next));
      } catch (error) {
        await Notifications.cancelScheduledNotificationAsync(id);
        throw error;
      }
      if (reminder?.id) await Notifications.cancelScheduledNotificationAsync(reminder.id);
      if (mounted.current) setReminder(next);
    });
  };

  const cancelReminder = () => void run(async () => {
    if (reminder?.id) await Notifications.cancelScheduledNotificationAsync(reminder.id);
    await AsyncStorage.removeItem(REMINDER_KEY);
    if (mounted.current) setReminder(null);
  });

  return (
    <View style={styles.fill}>
      <View style={styles.topbar}>
        {page === 'editor' ? <Pressable onPress={() => setPage('list')} accessibilityRole="button" accessibilityLabel="Back to device rules"><Text style={styles.back}>‹ Back</Text></Pressable> : null}
        <Text style={styles.topTitle}>{page === 'editor' ? (draft?.id && rules.some((rule) => rule.id === draft.id) ? 'Edit device rule' : 'New device rule') : 'Device tools'}</Text>
      </View>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        {!status.available ? (
          <View style={styles.notice}>
            <Text style={styles.noticeTitle}>Screen Time controls need an installed build</Text>
            <Text style={styles.body}>This development environment does not include the FocusLock Screen Time extension. Your device rules are unavailable here.</Text>
          </View>
        ) : null}
        {status.available && status.authorization !== 'approved' ? (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Screen Time access</Text>
            <Text style={styles.body}>Approve access before selecting apps and enabling device rules.</Text>
            <Button label={busy ? 'Opening…' : 'Approve Screen Time access'} onPress={requestAccess} disabled={busy || strictActive} />
          </View>
        ) : null}
        <View style={styles.card}>
          <Text style={styles.eyebrow}>DEVICE STATUS</Text>
          <Text style={styles.cardTitle}>{status.suspended ? 'Protection suspended' : `${status.activeRuleCount} active ${status.activeRuleCount === 1 ? 'rule' : 'rules'}`}</Text>
          <Text style={styles.body}>Screen Time rules are local to this iPhone and depend on its Screen Time authorization.</Text>
          <Button
            label={emergencyBusy ? 'Applying…' : status.suspended ? 'Resume saved rules' : 'Emergency suspend all rules'}
            onPress={emergencyAction}
            disabled={!status.available || emergencyBusy}
            variant={status.suspended ? 'primary' : 'danger'}
          />
          {strictActive ? <Text style={styles.warningText}>Strict Mode locks rule editing until {strictUntilLabel}. Emergency suspend remains available.</Text> : null}
        </View>

        {page === 'list' ? (
          <View style={styles.card}>
            <Text style={styles.eyebrow}>APPLE SCREEN TIME</Text>
            <Text style={styles.cardTitle}>Today on this iPhone</Text>
            <Text style={styles.body}>Apple’s private on-device report. FocusLock does not convert this usage into credits.</Text>
            {!status.available ? <Text style={styles.muted}>The report is available in a prebuilt iOS app with Screen Time access.</Text> : status.authorization !== 'approved' ? <Text style={styles.muted}>Approve Screen Time access to view the report.</Text> : ScreenTimeReport ? (
              <View style={styles.reportFrame}><ScreenTimeReport /></View>
            ) : <Text style={styles.muted}>Apple’s report view is unavailable in this app build.</Text>}
          </View>
        ) : null}

        <View style={styles.card}>
          <Text style={styles.eyebrow}>LOCAL STRICT MODE</Text>
          <Text style={styles.cardTitle}>{strictActive ? `Locked until ${strictUntilLabel}` : 'Lock device rule edits'}</Text>
          <Text style={styles.body}>This local setting locks rule changes for the selected period. It is separate from account Strict Mode and is not uninstall protection.</Text>
          <View style={styles.buttonRow}>
            {[1, 4, 24].map((hours) => (
              <Button key={hours} label={`${hours}h`} onPress={() => startStrict(hours)} disabled={!status.available || busy || Date.now() + hours * 3_600_000 <= status.strictUntil} variant="secondary" />
            ))}
          </View>
        </View>

        {page === 'editor' && draft ? (
          <RuleEditor
            draft={draft}
            rules={rules}
            authorization={status.authorization}
            now={now}
            busy={busy}
            onDraftChange={setDraft}
            onSave={rules.some((rule) => rule.id === draft.id) ? saveEditedRule : saveNewAndPick}
            onPick={() => {
              const rule = rules.find((item) => item.id === draft.id);
              if (rule) chooseApps(rule);
            }}
            isExisting={rules.some((rule) => rule.id === draft.id)}
            locked={strictActive}
          />
        ) : (
          <>
            <View style={styles.card}>
              <Text style={styles.eyebrow}>YOUR DEVICE RULES</Text>
              <Text style={styles.cardTitle}>Rules stay on this iPhone</Text>
              <Text style={styles.body}>New rules start disabled. Choose apps in Apple’s picker, then enable the rule when the selection and authorization are ready.</Text>
              {orderedRules.length === 0 ? <Text style={styles.muted}>No device rules yet.</Text> : orderedRules.map((rule) => (
                <RuleRow
                  key={rule.id}
                  rule={rule}
                  today={todayLocal()}
                  totalFocusMinutesToday={totalFocusMinutesToday}
                  taskFocusMinutes={taskFocusMinutesByTitle[rule.title] ?? 0}
                  disabled={busy || strictActive || !status.available}
                  canEdit={!strictActive && !(rule.kind === 'permanent' && rule.enabled)}
                  onEdit={() => { setDraft(asDraft(rule)); setPage('editor'); }}
                  onEnabled={(enabled) => setEnabled(rule, enabled)}
                  onDelete={() => removeRule(rule)}
                  onTaskChecked={(checked) => updateTask(rule, checked)}
            taskProgressDisabled={busy || !status.available}
                />
              ))}
              <Text style={styles.helper}>Focus time shown for tasks comes from FocusLock’s saved and pending work records.</Text>
              <Button label="Export rule metadata" onPress={exportMetadata} disabled={busy || rules.length === 0} variant="secondary" />
            </View>
            <View style={styles.card}>
              <Text style={styles.eyebrow}>ADD A RULE</Text>
              <Text style={styles.cardTitle}>Choose what you need</Text>
              {kinds.map(([kind, title, detail]) => (
                <Pressable key={kind} style={styles.kindRow} onPress={() => beginCreate(kind)} disabled={busy || strictActive || !status.available} accessibilityRole="button">
                  <View style={styles.flex}>
                    <Text style={styles.rowTitle}>{title}</Text>
                    <Text style={styles.helper}>{detail}</Text>
                  </View>
                  <Text style={styles.chevron}>›</Text>
                </Pressable>
              ))}
            </View>
          </>
        )}

        {page === 'list' ? (
          <View style={styles.card}>
            <Text style={styles.eyebrow}>DAILY REMINDER</Text>
            <Text style={styles.cardTitle}>{reminder ? `Reminder set for ${reminder.time}` : 'Choose a time to check in'}</Text>
            <Text style={styles.body}>Notifications are requested only when you save this reminder. FocusLock cancels only the reminder it created.</Text>
            <Field label="Local time" value={reminderTime} onChangeText={setReminderTime} placeholder="07:00" keyboardType="numbers-and-punctuation" />
            <Button label={busy ? 'Saving…' : 'Save daily reminder'} onPress={saveReminder} disabled={busy} />
            {reminder ? <Button label="Cancel this reminder" onPress={cancelReminder} disabled={busy} variant="secondary" /> : null}
          </View>
        ) : null}
      </ScrollView>
    </View>
  );
}

function RuleEditor({
  draft, rules, authorization, now, busy, onDraftChange, onSave, onPick, isExisting,
  locked,
}: {
  draft: RuleDraft;
  rules: NativeRule[];
  authorization: ScreenTimeStatus['authorization'];
  now: number;
  busy: boolean;
  onDraftChange: (draft: RuleDraft) => void;
  onSave: () => void;
  onPick: () => void;
  isExisting: boolean;
  locked: boolean;
}) {
  const [startText, setStartText] = useState(draft.startMinute === undefined ? '' : formatClockTime(draft.startMinute));
  const [endText, setEndText] = useState(draft.endMinute === undefined ? '' : formatClockTime(draft.endMinute));
  useEffect(() => {
    setStartText(draft.startMinute === undefined || Number.isNaN(draft.startMinute) ? '' : formatClockTime(draft.startMinute));
    setEndText(draft.endMinute === undefined || Number.isNaN(draft.endMinute) ? '' : formatClockTime(draft.endMinute));
  }, [draft.id]);
  const validation = ruleValidationError(draft, rules, authorization, now);
  const update = (patch: Partial<RuleDraft>) => onDraftChange({ ...draft, ...patch });
  const timeField = (label: string, text: string, setText: (text: string) => void, onChange: (minute: number) => void) => (
    <Field label={label} value={text} onChangeText={(value) => { setText(value); onChange(parseClockTime(value) ?? Number.NaN); }} placeholder="09:00" keyboardType="numbers-and-punctuation" editable={!busy && !locked} />
  );
  const numericField = (label: string, value: number | undefined, onChange: (value: number) => void, placeholder: string) => (
    <Field label={label} value={value === undefined || Number.isNaN(value) ? '' : String(value)} onChangeText={(text) => onChange(text.trim() === '' ? Number.NaN : Number(text))} placeholder={placeholder} keyboardType="number-pad" editable={!busy && !locked} />
  );

  return (
    <View style={styles.card}>
      <Text style={styles.eyebrow}>{draft.kind.toUpperCase()}</Text>
      <Text style={styles.cardTitle}>{isExisting ? 'Rule details' : 'Set up the rule'}</Text>
      <Field label="Title" value={draft.title} onChangeText={(title) => update({ title })} placeholder="Give this rule a name" editable={!busy && !locked} maxLength={80} />
      {draft.kind === 'schedule' ? (
        <>
          {timeField('Starts at', startText, setStartText, (startMinute) => update({ startMinute }))}
          {timeField('Ends at', endText, setEndText, (endMinute) => update({ endMinute }))}
          <Text style={styles.fieldLabel}>Days</Text>
          <View style={styles.buttonRow}>
            {weekdays.map(([day, label]) => {
              const selected = draft.weekdays?.includes(day) ?? false;
              return <Pressable key={day} onPress={() => update({ weekdays: selected ? draft.weekdays!.filter((value) => value !== day) : [...(draft.weekdays ?? []), day].sort() })} disabled={busy || locked} style={[styles.dayChip, selected ? styles.dayChipSelected : null, busy || locked ? styles.buttonDisabled : null]} accessibilityRole="checkbox" accessibilityState={{ checked: selected }}><Text style={selected ? styles.selectedChipText : styles.chipText}>{label}</Text></Pressable>;
            })}
          </View>
          <Text style={styles.helper}>Overnight schedules may end after midnight. Calendar weekdays start with Sunday.</Text>
        </>
      ) : null}
      {draft.kind === 'dailyLimit' ? numericField('Daily limit in minutes', draft.limitMinutes, (limitMinutes) => update({ limitMinutes }), '30') : null}
      {draft.kind === 'timed' ? <>
        {numericField('Block duration in minutes', draft.timedDurationMinutes ?? 25, (minutes) => update({ timedDurationMinutes: minutes, endsAt: Date.now() + minutes * 60_000 }), '25')}
        <Text style={styles.helper}>{isExisting && (draft.endsAt ?? 0) <= now + 15 * 60_000 ? 'This rule is expired or nearly finished. Saving a new duration starts a fresh block from now.' : 'The selected duration starts when you save this rule.'}</Text>
      </> : null}
      {draft.kind === 'task' ? (
        <>
          {timeField('Task unlock time', startText, setStartText, (startMinute) => update({ startMinute }))}
          {numericField('Focus minutes required', draft.requiredFocusMinutes, (requiredFocusMinutes) => update({ requiredFocusMinutes }), '25')}
          <Text style={styles.helper}>A 7:00 AM unlock time and 25-minute focus goal are prefilled. The task stays device-only, and marking it complete is a separate action.</Text>
        </>
      ) : null}
      {isExisting ? (
        <Button label="Choose apps" onPress={onPick} disabled={busy || locked || authorization !== 'approved'} variant="secondary" />
      ) : null}
      {validation ? <Text style={styles.errorText}>{validation}</Text> : null}
      <Button label={busy ? 'Saving…' : isExisting ? 'Save changes' : 'Save disabled rule and choose apps'} onPress={onSave} disabled={busy || locked || !!validation} />
    </View>
  );
}

function RuleRow({ rule, today, totalFocusMinutesToday, taskFocusMinutes, disabled, taskProgressDisabled, canEdit, onEdit, onEnabled, onDelete, onTaskChecked }: {
  rule: NativeRule;
  today: string;
  totalFocusMinutesToday: number;
  taskFocusMinutes: number;
  disabled: boolean;
  taskProgressDisabled: boolean;
  canEdit: boolean;
  onEdit: () => void;
  onEnabled: (enabled: boolean) => void;
  onDelete: () => void;
  onTaskChecked: (checked: boolean) => void;
}) {
  const completed = rule.completedDate === today;
  const exempt = (rule.exemptDate === today || totalFocusMinutesToday > 30) && !completed;
  return (
    <View style={styles.ruleRow}>
      <Pressable style={styles.flex} onPress={canEdit ? onEdit : undefined} disabled={!canEdit || disabled} accessibilityRole="button">
        <Text style={styles.rowTitle}>{rule.title}</Text>
        <Text style={styles.helper}>{ruleDescription(rule)}</Text>
        {rule.selectionCount === 0 ? <Text style={styles.warningText}>Choose apps before enabling</Text> : null}
      </Pressable>
      {rule.kind === 'task' ? (
        <View style={styles.taskBox}>
          <View style={styles.switchRow}>
            <Text style={styles.rowLabel}>{rule.enabled ? 'Task enabled' : 'Task paused'}</Text>
            <Switch value={rule.enabled} onValueChange={onEnabled} disabled={disabled || rule.selectionCount === 0 && !rule.enabled} accessibilityLabel={`${rule.enabled ? 'Disable' : 'Enable'} ${rule.title}`} />
          </View>
          {exempt ? <Text style={styles.helper}>Over 30 focus minutes today: focus requirement exempt. This does not complete the task.</Text> : null}
          <View style={styles.switchRow}>
            <Text style={styles.rowLabel}>{completed ? 'Task completed' : 'Mark task complete'}</Text>
            <Switch value={completed} onValueChange={onTaskChecked} disabled={taskProgressDisabled} accessibilityLabel={`Mark ${rule.title} complete`} />
          </View>
          <Text style={styles.helper}>{taskFocusMinutes} of {rule.requiredFocusMinutes ?? 0} focus minutes for this task</Text>
        </View>
      ) : (
        <Switch value={rule.enabled} onValueChange={onEnabled} disabled={disabled || rule.kind === 'permanent' && rule.enabled || rule.selectionCount === 0 && !rule.enabled} accessibilityLabel={`${rule.enabled ? 'Disable' : 'Enable'} ${rule.title}`} />
      )}
      <View style={styles.rowActions}>
        {canEdit ? <Pressable onPress={onEdit} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>Edit</Text></Pressable> : <Text style={styles.muted}>Locked</Text>}
        {!(rule.kind === 'permanent' && rule.enabled) ? <Pressable onPress={onDelete} disabled={disabled} accessibilityRole="button"><Text style={styles.deleteLink}>Delete</Text></Pressable> : <Text style={styles.muted}>Permanent</Text>}
      </View>
    </View>
  );
}

function ruleDescription(rule: NativeRule): string {
  switch (rule.kind) {
    case 'schedule': return `${formatWeekdays(rule.weekdays)} · ${formatClockTime(rule.startMinute ?? -1)}–${formatClockTime(rule.endMinute ?? -1)}`;
    case 'dailyLimit': return `${rule.limitMinutes ?? 0} minutes per day`;
    case 'permanent': return 'Permanent block';
    case 'timed': return rule.endsAt ? `Until ${new Date(rule.endsAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : 'Timed block';
    case 'task': return `${rule.requiredFocusMinutes ?? 25} focus minutes required`;
  }
}

function Field({ label, value, onChangeText, placeholder, keyboardType, editable = true, maxLength }: {
  label: string; value: string; onChangeText: (text: string) => void; placeholder: string;
  keyboardType?: 'default' | 'number-pad' | 'numbers-and-punctuation'; editable?: boolean;
  maxLength?: number;
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <TextInput style={styles.input} value={value} onChangeText={onChangeText} placeholder={placeholder} placeholderTextColor={colors.onSurfaceVariant} keyboardType={keyboardType} editable={editable} maxLength={maxLength} />
    </View>
  );
}

function Button({ label, onPress, disabled, variant = 'primary' }: {
  label: string; onPress: () => void; disabled?: boolean; variant?: 'primary' | 'secondary' | 'danger';
}) {
  return (
    <Pressable style={[styles.button, variant === 'secondary' ? styles.buttonSecondary : null, variant === 'danger' ? styles.buttonDanger : null, disabled ? styles.buttonDisabled : null]} onPress={onPress} disabled={disabled} accessibilityRole="button">
      <Text style={[styles.buttonText, variant === 'secondary' ? styles.buttonSecondaryText : null, variant === 'danger' ? styles.buttonDangerText : null]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  topbar: { minHeight: 52, paddingHorizontal: spacing.large, flexDirection: 'row', alignItems: 'center', gap: 14 },
  topTitle: { color: colors.onSurface, fontSize: 22, fontWeight: '700' },
  back: { color: colors.primary, fontSize: 16, fontWeight: '700' },
  content: { padding: spacing.large, paddingTop: spacing.small, paddingBottom: 48, gap: spacing.medium },
  card: { backgroundColor: colors.surfaceContainerLow, borderRadius: radii.large, padding: spacing.large, gap: spacing.small },
  notice: { backgroundColor: colors.warningContainer, borderRadius: radii.medium, padding: spacing.medium, gap: spacing.small },
  noticeTitle: { color: colors.warning, fontSize: 17, fontWeight: '700' },
  eyebrow: { color: colors.primary, fontSize: 12, letterSpacing: 1, fontWeight: '700' },
  cardTitle: { color: colors.onSurface, fontSize: 21, fontWeight: '700' },
  body: { color: colors.onSurfaceVariant, fontSize: 15, lineHeight: 22 },
  helper: { color: colors.onSurfaceVariant, fontSize: 13, lineHeight: 19 },
  muted: { color: colors.onSurfaceVariant, fontSize: 13 },
  warningText: { color: colors.warning, fontSize: 13, lineHeight: 18 },
  errorText: { color: colors.error, fontSize: 14, lineHeight: 19 },
  button: { minHeight: 48, paddingHorizontal: spacing.medium, paddingVertical: 12, borderRadius: radii.medium, backgroundColor: colors.primary, alignItems: 'center', justifyContent: 'center' },
  buttonSecondary: { backgroundColor: colors.surfaceContainerHigh },
  buttonDanger: { backgroundColor: colors.errorContainer },
  buttonDisabled: { opacity: 0.45 },
  buttonText: { color: colors.onPrimary, fontWeight: '700', fontSize: 15, textAlign: 'center' },
  buttonSecondaryText: { color: colors.onSurface },
  buttonDangerText: { color: colors.onErrorContainer },
  buttonRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.small },
  reportFrame: { height: 220, overflow: 'hidden', borderRadius: radii.medium, backgroundColor: colors.surfaceContainer },
  field: { gap: 6 },
  fieldLabel: { color: colors.onSurface, fontSize: 15, fontWeight: '600' },
  input: { minHeight: 50, borderWidth: 1, borderColor: colors.outline, borderRadius: radii.medium, paddingHorizontal: spacing.medium, color: colors.onSurface, fontSize: 16, backgroundColor: colors.surfaceContainerLowest },
  kindRow: { minHeight: 70, borderTopWidth: 1, borderTopColor: colors.outlineVariant, flexDirection: 'row', alignItems: 'center', gap: spacing.medium, paddingVertical: spacing.small },
  flex: { flex: 1 },
  chevron: { color: colors.primary, fontSize: 26 },
  rowTitle: { color: colors.onSurface, fontSize: 16, fontWeight: '700' },
  ruleRow: { borderTopWidth: 1, borderTopColor: colors.outlineVariant, paddingTop: spacing.medium, paddingBottom: spacing.small, gap: spacing.small },
  taskBox: { gap: 6 },
  switchRow: { minHeight: 46, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.medium },
  rowLabel: { color: colors.onSurface, fontSize: 15, fontWeight: '600', flex: 1 },
  rowActions: { flexDirection: 'row', gap: spacing.large, alignItems: 'center' },
  link: { color: colors.primary, fontSize: 14, fontWeight: '700', paddingVertical: 8 },
  deleteLink: { color: colors.error, fontSize: 14, fontWeight: '700', paddingVertical: 8 },
  dayChip: { minWidth: 42, minHeight: 42, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 8, borderRadius: 22, borderWidth: 1, borderColor: colors.outlineVariant, backgroundColor: colors.surfaceContainer },
  dayChipSelected: { backgroundColor: colors.primaryContainer, borderColor: colors.primary },
  chipText: { color: colors.onSurfaceVariant, fontSize: 14, fontWeight: '600' },
  selectedChipText: { color: colors.onPrimaryContainer, fontSize: 14, fontWeight: '700' },
});
