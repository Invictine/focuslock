import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { ConvexReactClient } from 'convex/react';
import ScreenTime, { type ScreenTimeStatus } from 'focuslock-screen-time';
import { errorMessage } from './auth';
import { colors, radii, spacing } from './theme';

type Props = {
  client: ConvexReactClient | null;
  ownerId: string;
  balanceSeconds: number | null;
  onError: (message: string) => void;
  onChanged: () => void;
  onSync: () => Promise<void>;
};

type CreditGate = Awaited<ReturnType<typeof ScreenTime.getCreditGate>>;
type LeisureUsage = Awaited<ReturnType<typeof ScreenTime.getLeisureUsage>>;

function isAccountOwner(ownerId: string): boolean {
  return Boolean(ownerId && ownerId !== 'local');
}

function minutesLabel(seconds: number | null): string {
  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return '—';
  const minutes = Math.floor(seconds / 60);
  return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`;
}

export default function CreditTools({ client, ownerId, balanceSeconds, onError, onChanged, onSync }: Props) {
  const [deviceStatus, setDeviceStatus] = useState<ScreenTimeStatus | null>(null);
  const [gate, setGate] = useState<CreditGate | null>(null);
  const [usage, setUsage] = useState<LeisureUsage>([]);
  const [busy, setBusy] = useState(false);
  const [emergencyBusy, setEmergencyBusy] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const mounted = useRef(true);
  const onErrorRef = useRef(onError);
  const onChangedRef = useRef(onChanged);
  const onSyncRef = useRef(onSync);
  onErrorRef.current = onError;
  onChangedRef.current = onChanged;
  onSyncRef.current = onSync;

  const report = (error: unknown) => {
    if (mounted.current) onErrorRef.current(errorMessage(error));
  };

  const refreshDeviceState = useCallback(async () => {
    const status = await ScreenTime.getStatus();
    if (!mounted.current) return;
    setDeviceStatus(status);
    if (!status.available) {
      setGate(null);
      setUsage([]);
      return;
    }

    const currentGate = await ScreenTime.getCreditGate();
    if (!mounted.current) return;
    setGate(currentGate);

    // Usage is private to this account/device pairing. Do not request it for
    // local mode or while signed out, and never display a prior owner's rows.
    if (client && isAccountOwner(ownerId)) {
      const rows = await ScreenTime.getLeisureUsage(ownerId);
      if (mounted.current) setUsage(rows);
    } else {
      setUsage([]);
    }
  }, [client, ownerId]);

  useEffect(() => {
    mounted.current = true;
    setDeviceStatus(null);
    setGate(null);
    setUsage([]);
    setLoadError(null);
    void refreshDeviceState().catch((error) => {
      if (mounted.current) setLoadError(errorMessage(error));
    });
    return () => { mounted.current = false; };
  }, [refreshDeviceState, ownerId]);

  const run = async (operation: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    try { await operation(); }
    catch (error) { report(error); }
    finally { if (mounted.current) setBusy(false); }
  };

  const syncFreshBank = async () => {
    if (!client || !isAccountOwner(ownerId)) throw new Error('Sign in to an account before syncing credit for this iPhone.');
    await onSyncRef.current();
    // The parent sync path acknowledges the server work first, then configures
    // the native allowance from the fresh server balance.
    await refreshDeviceState();
  };

  const chooseLeisureApps = () => {
    if (busy || !deviceStatus?.available || deviceStatus.authorization !== 'approved') {
      report(new Error('Approve Screen Time access before choosing leisure apps.'));
      return;
    }
    if (gate?.enabled) {
      report(new Error('Turn off the credit gate before changing its selected apps.'));
      return;
    }
    void run(async () => {
      await ScreenTime.pickCreditApps();
      await refreshDeviceState();
      onChangedRef.current();
    });
  };

  const enableGate = () => {
    if (!client || !isAccountOwner(ownerId)) {
      report(new Error('Sign in before enabling account credit on this iPhone.'));
      return;
    }
    if (!deviceStatus?.available || deviceStatus.authorization !== 'approved') {
      report(new Error('Approve Screen Time access in Device Tools before enabling the credit gate.'));
      return;
    }
    if (!gate || gate.selectionCount < 1) {
      report(new Error('Choose at least one leisure app before enabling the credit gate.'));
      return;
    }
    if (gate?.enabled && gate.ownerId && gate.ownerId !== ownerId) {
      report(new Error('The saved iPhone credit gate belongs to a different account. Disable it from that account before switching accounts.'));
      return;
    }
    Alert.alert(
      'Enable credit for selected apps?',
      'Apple will count actual leisure-app usage in minute-sized events. FocusLock syncs that usage to your account and debits it from the credit bank. The gate only receives up to 60 minutes from a fresh bank balance; it blocks again when that allowance is used. Opening FocusLock syncs unused bank credit. Usage before you enable this gate is not charged.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Enable credit gate', onPress: () => { void run(async () => {
          if (!gate || gate.selectionCount < 1) throw new Error('Choose at least one leisure app before enabling the credit gate.');
          // Start from a zero allowance so a prior account's or stale sync's
          // bank can never grant time during account switching or re-enable.
          // The account sync below can grant a bounded allowance only after it
          // has acknowledged the fresh server balance.
          await ScreenTime.configureCreditAllowance(ownerId, 0);
          await ScreenTime.setCreditGateEnabled(true);
          if (mounted.current) setGate(await ScreenTime.getCreditGate());
          try {
            await syncFreshBank();
          } catch (error) {
            // Native enable starts with no allowance. If fresh account sync
            // fails, selected apps stay blocked until a successful sync grants
            // a bounded allowance or the user disables the gate.
            if (mounted.current) setLoadError('The gate is enabled with no fresh allowance. Selected apps stay blocked until FocusLock syncs successfully.');
            throw error;
          }
          onChangedRef.current();
        }); } },
      ],
    );
  };

  const disableGate = () => Alert.alert(
    'Turn off the credit gate?',
    'This stops blocking selected leisure apps through the credit gate. Usage already recorded on this iPhone remains in its account-scoped ledger and can sync later.',
    [
      { text: 'Keep on', style: 'cancel' },
      { text: 'Turn off', style: 'destructive', onPress: () => { void run(async () => {
        await ScreenTime.setCreditGateEnabled(false);
        await refreshDeviceState();
        try { await syncFreshBank(); }
        catch (error) { report(error); }
        onChangedRef.current();
      }); } },
    ],
  );

  const syncNow = () => void run(async () => {
    await syncFreshBank();
    if (mounted.current) setLoadError(null);
    onChangedRef.current();
  });

  const emergencySuspend = () => {
    if (emergencyBusy || !deviceStatus?.available) return;
    setEmergencyBusy(true);
    void (async () => {
      try {
        setDeviceStatus(await ScreenTime.suspendAll());
        onChangedRef.current();
      } catch (error) { report(error); }
      finally { if (mounted.current) setEmergencyBusy(false); }
    })();
  };

  const accountReady = Boolean(client && isAccountOwner(ownerId));
  const active = Boolean(gate?.enabled);
  const allowanceRemaining = gate?.allowanceMinutes ?? null;

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.notice}>
        <Text style={styles.heading}>Credit for leisure apps</Text>
        <Text style={styles.body}>Choose apps on this iPhone. Apple measures actual usage in minute-sized events; FocusLock syncs the device counter to your account on foreground refresh and debits the account credit bank.</Text>
        <Text style={styles.body}>The iPhone receives at most 60 minutes from a fresh bank balance, then blocks the selected apps again when that allowance runs out. Opening FocusLock syncs unused bank credit. Usage before you enable the gate is not charged.</Text>
      </View>

      {!deviceStatus?.available ? (
        <View style={styles.card}>
          <Text style={styles.title}>Screen Time controls unavailable</Text>
          <Text style={styles.body}>Use a Screen Time-enabled iOS build to select apps and apply a credit allowance. No credit is granted by this screen.</Text>
        </View>
      ) : null}

      <View style={styles.card}>
        <Text style={styles.eyebrow}>ACCOUNT CREDIT</Text>
        <Text style={styles.title}>Available bank: {minutesLabel(balanceSeconds)}</Text>
        {!accountReady ? <Text style={styles.body}>Sign in to sync account credit. Existing device policy remains account-scoped and does not grant a new allowance while signed out.</Text> : null}
        {balanceSeconds === null && accountReady ? <Text style={styles.body}>Balance is being refreshed. An unknown balance never becomes a zero-cost allowance.</Text> : null}
        {gate?.ownerId && gate.ownerId !== ownerId ? <Text style={styles.warning}>{gate.enabled ? 'This iPhone has an active credit gate for another account. Switch back to that account to disable it before changing owners.' : 'The saved gate is off for another account. Enabling it here will first set the allowance to zero, then sync only this account’s fresh bank.'}</Text> : null}
        <Button label={busy ? 'Syncing…' : 'Sync account credit now'} onPress={syncNow} disabled={busy || !accountReady} secondary />
      </View>

      <View style={styles.card}>
        <Text style={styles.eyebrow}>THIS IPHONE</Text>
        <Text style={styles.title}>{active ? gate?.exhausted ? 'Allowance used · apps blocked' : 'Credit gate is on' : 'Credit gate is off'}</Text>
        <Text style={styles.body}>Selected leisure apps: {gate?.selectionCount ?? '—'}. Choose apps only while the gate is off. Other device rules, permanent blocks, Frog, and account Nuke can still block these apps.</Text>
        {deviceStatus?.available && deviceStatus.authorization !== 'approved' ? <Text style={styles.warning}>Approve Screen Time access in Device Tools before choosing apps.</Text> : null}
        {active ? <Text style={styles.detail}>Current device allowance: {allowanceRemaining === null ? '—' : `${allowanceRemaining} minutes`}{gate?.expiresAt ? ` · refresh by ${new Date(gate.expiresAt).toLocaleTimeString()}` : ''}</Text> : null}
        {gate?.ownerId === ownerId && gate.exhausted ? <Text style={styles.warning}>The last granted allowance is exhausted. Selected apps stay blocked until a successful account sync grants more from available bank credit.</Text> : null}
        <Button label="Choose leisure apps" onPress={chooseLeisureApps} disabled={busy || !deviceStatus?.available || deviceStatus.authorization !== 'approved' || active} secondary />
        {active ? <Button label={busy ? 'Updating…' : 'Turn off credit gate'} onPress={disableGate} disabled={busy || gate?.ownerId !== ownerId} danger /> : <Button label={busy ? 'Enabling…' : 'Enable credit gate'} onPress={enableGate} disabled={busy || !deviceStatus?.available || deviceStatus.authorization !== 'approved' || !accountReady || !gate || gate.selectionCount < 1 || (gate.enabled && gate.ownerId !== undefined && gate.ownerId !== ownerId)} />}
        <Button label={emergencyBusy ? 'Suspending…' : 'Emergency suspend device rules'} onPress={emergencySuspend} disabled={!deviceStatus?.available || emergencyBusy} danger secondary />
      </View>

      <View style={styles.card}>
        <Text style={styles.eyebrow}>MEASURED DEVICE USAGE</Text>
        <Text style={styles.title}>Private iPhone usage ledger</Text>
        <Text style={styles.body}>Rows are actual Device Activity measurements for this signed-in account. They are not inferred from app presence or converted into extra credits by this view.</Text>
        {usage.length === 0 ? <Text style={styles.muted}>{accountReady ? 'No measured leisure usage is available yet.' : 'Sign in to view account-scoped usage.'}</Text> : usage.slice(0, 14).map((row) => (
          <View key={row.date} style={styles.usageRow}>
            <Text style={styles.body}>{row.date}</Text>
            <Text style={styles.value}>{minutesLabel(row.measuredSeconds)}</Text>
          </View>
        ))}
      </View>

      {loadError ? <Text style={styles.warning}>{loadError}</Text> : null}
    </ScrollView>
  );
}

function Button({ label, onPress, disabled = false, secondary = false, danger = false }: {
  label: string; onPress: () => void; disabled?: boolean; secondary?: boolean; danger?: boolean;
}) {
  return <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={({ pressed }) => [styles.button, secondary && styles.secondaryButton, danger && styles.dangerButton, disabled && styles.disabled, pressed && !disabled && styles.pressed]}>
    <Text style={secondary || danger ? styles.secondaryLabel : styles.buttonLabel}>{label}</Text>
  </Pressable>;
}

const styles = StyleSheet.create({
  content: { padding: spacing.large, gap: spacing.medium, paddingBottom: spacing.xlarge },
  notice: { backgroundColor: colors.surface, borderRadius: radii.medium, padding: spacing.medium, gap: spacing.small },
  card: { backgroundColor: colors.surface, borderRadius: radii.medium, padding: spacing.medium, gap: spacing.small },
  heading: { color: colors.onSurface, fontSize: 20, fontWeight: '700' },
  title: { color: colors.onSurface, fontSize: 17, fontWeight: '700' },
  eyebrow: { color: colors.primary, fontSize: 12, letterSpacing: 1, fontWeight: '700' },
  body: { color: colors.onSurfaceVariant, fontSize: 14, lineHeight: 20 },
  detail: { color: colors.onSurface, fontSize: 14, lineHeight: 20, fontWeight: '600' },
  muted: { color: colors.onSurfaceVariant, fontSize: 13, lineHeight: 18 },
  warning: { color: colors.warning, fontSize: 13, lineHeight: 19 },
  usageRow: { minHeight: 42, borderTopWidth: StyleSheet.hairlineWidth, borderColor: colors.outlineVariant, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: spacing.medium },
  value: { color: colors.onSurface, fontSize: 14, fontWeight: '700' },
  button: { minHeight: 48, justifyContent: 'center', alignItems: 'center', backgroundColor: colors.primary, borderRadius: radii.small, paddingHorizontal: spacing.medium, marginTop: spacing.xsmall },
  secondaryButton: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.outlineVariant },
  dangerButton: { backgroundColor: colors.errorContainer },
  buttonLabel: { color: colors.onPrimary, fontWeight: '700', textAlign: 'center' },
  secondaryLabel: { color: colors.onSurface, fontWeight: '700', textAlign: 'center' },
  disabled: { opacity: 0.5 },
  pressed: { opacity: 0.78 },
});
