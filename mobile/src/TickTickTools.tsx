import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import type { ConvexReactClient } from 'convex/react';
import * as WebBrowser from 'expo-web-browser';
import { errorMessage } from './auth';
import {
  tickTickAuthorizeRef,
  tickTickCompleteTaskRef,
  tickTickConnectRef,
  tickTickSaveConnectionRef,
  tickTickStatusRef,
  tickTickSyncFocusRef,
  tickTickTasksRef,
  type TickTickFocusImport,
  type TickTickStatus,
  type TickTickTask,
} from './ticktickApi';
import { colors, radii, spacing } from './theme';

type Props = {
  client: ConvexReactClient;
  totalFocusMinutesToday: number;
  onError: (message: string) => void;
  onStartTask: (title: string) => void;
  onPriorityTask: (title: string) => void;
};

export default function TickTickTools({ client, totalFocusMinutesToday, onError, onStartTask, onPriorityTask }: Props) {
  const [status, setStatus] = useState<TickTickStatus | null>(null);
  const [tasks, setTasks] = useState<TickTickTask[]>([]);
  const [callback, setCallback] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [taskLoadError, setTaskLoadError] = useState(false);
  const [statusLoadError, setStatusLoadError] = useState(false);
  const [focusImport, setFocusImport] = useState<TickTickFocusImport | null>(null);
  const mounted = useRef(true);
  const lock = useRef(false);
  const requestId = useRef(0);
  const errorRef = useRef(onError);
  errorRef.current = onError;

  const refresh = useCallback(async (showLoading = true) => {
    const id = ++requestId.current;
    if (showLoading && mounted.current) setLoading(true);
    try {
      const next = await client.action(tickTickStatusRef, {});
      if (!mounted.current || id !== requestId.current) return;
      setStatusLoadError(false);
      setStatus(next);
      if (next.connected) {
        try {
          const nextTasks = await client.action(tickTickTasksRef, {});
          if (!mounted.current || id !== requestId.current) return;
          setTasks(nextTasks);
          setTaskLoadError(false);
        } catch (error) {
          if (!mounted.current || id !== requestId.current) return;
          setTaskLoadError(true);
          errorRef.current(errorMessage(error));
        }
      } else {
        setTasks([]);
        setTaskLoadError(false);
      }
    } catch (error) {
      if (mounted.current && id === requestId.current) {
        setStatusLoadError(true);
        errorRef.current(errorMessage(error));
      }
    } finally {
      if (mounted.current && id === requestId.current && showLoading) setLoading(false);
    }
  }, [client]);

  useEffect(() => {
    mounted.current = true;
    void refresh(true);
    return () => { mounted.current = false; requestId.current += 1; };
  }, [client, refresh]);

  const run = async (operation: () => Promise<unknown>) => {
    if (lock.current) return;
    lock.current = true;
    if (mounted.current) setBusy(true);
    try {
      await operation();
    } catch (error) {
      if (mounted.current) errorRef.current(errorMessage(error));
    } finally {
      lock.current = false;
      if (mounted.current) setBusy(false);
    }
  };

  const refreshTasks = () => run(async () => { await refresh(false); });
  const connect = () => run(async () => {
    const { url } = await client.action(tickTickAuthorizeRef, {});
    // TickTick redirects to a loopback URL, so the user must paste its full address below.
    await WebBrowser.openBrowserAsync(url);
  });
  const submitCallback = () => run(async () => {
    const value = callback.trim();
    if (!value) throw new Error('Paste the full TickTick redirect address first.');
    await client.action(tickTickConnectRef, { callback: value });
    if (mounted.current) setCallback('');
    await refresh(false);
  });
  const disconnect = () => {
    if (!status) return;
    Alert.alert('Disconnect TickTick?', 'TickTick tasks and focus import will stop until you connect again.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Disconnect', style: 'destructive', onPress: () => {
        void run(async () => {
          const result = await client.mutation(tickTickSaveConnectionRef, { expectedRevision: status.revision, connection: null });
          if (!result.applied) {
            await refresh(false);
            throw new Error('The TickTick connection changed. Its latest status has been refreshed.');
          }
          setFocusImport(null);
          await refresh(false);
        });
      } },
    ]);
  };
  const importFocus = () => run(async () => {
    const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (!timeZone) throw new Error('This iPhone did not provide a time zone.');
    const result = await client.action(tickTickSyncFocusRef, { timeZone });
    if (mounted.current) setFocusImport(result);
  });
  const completeTask = (task: TickTickTask) => {
    Alert.alert('Complete this TickTick task?', `“${task.title}” will be marked complete in TickTick. This does not record focus time.`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Complete task', onPress: () => {
        void run(async () => {
          await client.action(tickTickCompleteTaskRef, { taskId: task.id, projectId: task.projectId });
          await refresh(false);
        });
      } },
    ]);
  };

  return (
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Text style={styles.heading}>TickTick</Text>
      <Text style={styles.body}>Connect your TickTick account to choose tasks for focus sessions and import recorded focus blocks.</Text>

      {loading && status === null ? <Text accessibilityRole="text" style={styles.muted}>Checking TickTick connection…</Text> : status?.connected ? (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Connected account</Text>
          <Text style={styles.body}>{status.userName ? `Connected as ${status.userName}` : 'TickTick is connected.'}</Text>
          {statusLoadError ? <>
            <Text style={styles.muted}>Connection status could not be refreshed. Your last verified connection, task list, and import result are still shown.</Text>
            <ActionButton label="Retry connection check" onPress={() => { void refresh(true); }} disabled={busy} secondary />
          </> : null}
          <ActionButton label="Refresh tasks" onPress={refreshTasks} disabled={busy} />
          {taskLoadError ? <Text style={styles.muted}>The task list could not be refreshed. Your current list is kept on screen; try again when you are online.</Text> : null}
          <ActionButton label="Disconnect TickTick" onPress={disconnect} disabled={busy} secondary />
        </View>
      ) : statusLoadError ? (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Connection status unavailable</Text>
          <Text style={styles.muted}>TickTick could not be reached. Your saved connection status and task list have not been changed.</Text>
          <ActionButton label="Retry connection check" onPress={() => { void refresh(true); }} disabled={busy} />
        </View>
      ) : (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Connect your account</Text>
          {status?.serverConfigured ? <Text style={styles.body}>TickTick sign-in opens in your browser. After allowing access, copy the complete address from the browser bar and paste it below. The address carries a short-lived sign-in code; FocusLock sends it securely to the server and does not store TickTick credentials on this iPhone.</Text> : <Text style={styles.muted}>TickTick sign-in is unavailable because server credentials are not configured.</Text>}
          <ActionButton label="Authorize TickTick" onPress={connect} disabled={busy || !status?.serverConfigured} />
          <TextInput
            value={callback}
            onChangeText={setCallback}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            returnKeyType="done"
            accessibilityLabel="TickTick redirect address"
            placeholder="Paste the full redirect address"
            placeholderTextColor={colors.onSurfaceVariant}
            style={styles.input}
          />
          <ActionButton label="Finish connecting" onPress={submitCallback} disabled={busy || !status?.serverConfigured || !callback.trim()} secondary />
        </View>
      )}

      {status?.connected ? <>
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Your tasks</Text>
          <Text style={styles.muted}>Choose a task to start a 25-minute focus session, set a daily priority, or mark it complete in TickTick.</Text>
          {!tasks.length && !taskLoadError ? <Text style={styles.muted}>No open tasks were returned. Refresh to check again.</Text> : null}
          {tasks.map((task) => <View key={`${task.projectId}:${task.id}`} style={styles.taskRow}>
            <Text style={styles.taskTitle}>{task.title}</Text>
            <Text style={styles.muted}>{task.projectName || 'TickTick project'}</Text>
            <View style={styles.actionsRow}>
              <ActionButton label="Focus" onPress={() => onStartTask(task.title)} disabled={busy} compact />
              <ActionButton label="Daily priority" onPress={() => onPriorityTask(task.title)} disabled={busy} secondary compact />
              <ActionButton label="Complete" onPress={() => completeTask(task)} disabled={busy} secondary compact />
            </View>
          </View>)}
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Import TickTick focus</Text>
          <Text style={styles.body}>Import actual TickTick focus blocks from the last 7 days. Existing record IDs are checked, including records imported on Android, so duplicates are skipped. A timer ending never completes a task.</Text>
          <Text style={styles.muted}>Focus recorded in FocusLock today: {totalFocusMinutesToday} min</Text>
          <ActionButton label="Import focus blocks" onPress={importFocus} disabled={busy} />
          {focusImport ? <Text style={styles.result}>{`Checked ${focusImport.found} blocks · imported ${focusImport.imported} new · ${focusImport.earnedMinutes} earned min`}</Text> : null}
        </View>
      </> : null}
      {busy ? <Text accessibilityRole="text" style={styles.muted}>Working…</Text> : null}
    </ScrollView>
  );
}

function ActionButton({ label, onPress, disabled, secondary, compact }: {
  label: string; onPress: () => void; disabled?: boolean; secondary?: boolean; compact?: boolean;
}) {
  return <Pressable
    accessibilityRole="button"
    accessibilityState={{ disabled: Boolean(disabled) }}
    onPress={onPress}
    disabled={disabled}
    style={({ pressed }) => [styles.button, secondary && styles.secondaryButton, compact && styles.compactButton, disabled && styles.disabledButton, pressed && !disabled && styles.pressedButton]}
  ><Text style={[styles.buttonText, secondary && styles.secondaryText]}>{label}</Text></Pressable>;
}

const styles = StyleSheet.create({
  content: { padding: spacing.medium, paddingBottom: spacing.xlarge, gap: spacing.medium },
  heading: { color: colors.onBackground, fontSize: 30, fontWeight: '700' },
  body: { color: colors.onSurface, fontSize: 16, lineHeight: 24 },
  muted: { color: colors.onSurfaceVariant, fontSize: 14, lineHeight: 21 },
  section: { padding: spacing.medium, gap: spacing.small, backgroundColor: colors.surfaceContainerLow, borderRadius: radii.large },
  sectionTitle: { color: colors.onBackground, fontSize: 19, fontWeight: '700' },
  taskRow: { paddingVertical: spacing.medium, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.outlineVariant, gap: spacing.xsmall },
  taskTitle: { color: colors.onSurface, fontSize: 17, fontWeight: '600', flexShrink: 1 },
  actionsRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.small, paddingTop: spacing.xsmall },
  button: { minHeight: 48, justifyContent: 'center', alignItems: 'center', backgroundColor: colors.primary, borderRadius: radii.medium, paddingHorizontal: spacing.medium, paddingVertical: spacing.small },
  secondaryButton: { backgroundColor: colors.surfaceContainer },
  compactButton: { minHeight: 44, paddingHorizontal: spacing.small, flexGrow: 1 },
  buttonText: { color: colors.onPrimary, fontSize: 15, fontWeight: '700', textAlign: 'center' },
  secondaryText: { color: colors.onSurface },
  disabledButton: { opacity: 0.48 },
  pressedButton: { opacity: 0.78 },
  input: { minHeight: 52, borderRadius: radii.medium, borderWidth: 1, borderColor: colors.outlineVariant, color: colors.onSurface, backgroundColor: colors.background, paddingHorizontal: spacing.medium, fontSize: 16 },
  result: { color: colors.success, fontSize: 15, lineHeight: 22 },
});
