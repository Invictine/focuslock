import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Location from 'expo-location';
import * as TaskManager from 'expo-task-manager';
import type { ConvexReactClient } from 'convex/react';
import ScreenTime, { type ScreenTimeStatus } from 'focuslock-screen-time';
import { errorMessage } from './auth';
import { homeLocationRef, type HomePlace, type SavedHome } from './accountApi';
import {
  HOME_LOCATION_TASK,
  HOME_REGION_ID,
  isAtHome,
  parseSavedDeviceHome,
  validateHomePlace,
  type SavedDeviceHome,
} from './homeLocation';
import { colors, radii, spacing } from './theme';

const DEVICE_HOME_PREFIX = 'focuslock.home-location.v1:';
const ACTIVE_BOUNDARY_KEY = 'focuslock.home-location.boundary-enabled.v1';

// Expo requires task definitions to run at module load, outside React.
TaskManager.defineTask<{ eventType: Location.GeofencingEventType; region: Location.LocationRegion }>(
  HOME_LOCATION_TASK,
  async ({ data, error }) => {
    let status: ScreenTimeStatus;
    try {
      status = await ScreenTime.getStatus();
      if (!status.available || !status.homeOnly) return;
    } catch { return; }

    try {
      const boundaryActive = await AsyncStorage.getItem(ACTIVE_BOUNDARY_KEY) === 'true';
      const isEnter = data?.eventType === Location.GeofencingEventType.Enter;
      const isExit = data?.eventType === Location.GeofencingEventType.Exit;
      if (error || !data || !boundaryActive || data.region?.identifier !== HOME_REGION_ID || (!isEnter && !isExit)) {
        // A failed or incomplete callback must never preserve stale outside
        // state, which would incorrectly open home-gated rules.
        await ScreenTime.setHomeOnly(true, null);
        return;
      }
      // Native policy owns enforcement and keeps permanent rules outside this gate.
      await ScreenTime.setHomeOnly(true, isEnter);
    } catch {
      // Preserve shielding if storage or callback processing fails.
      await ScreenTime.setHomeOnly(true, null).catch(() => undefined);
    }
  },
);

export async function reconcileHomeBoundary(status: ScreenTimeStatus): Promise<ScreenTimeStatus> {
  if (!status.available || !status.homeOnly) return status;
  try {
    const [permission, registered, boundaryFlag] = await Promise.all([
      Location.getBackgroundPermissionsAsync(),
      TaskManager.isTaskRegisteredAsync(HOME_LOCATION_TASK),
      AsyncStorage.getItem(ACTIVE_BOUNDARY_KEY),
    ]);
    if (permission.status === 'granted' && registered && boundaryFlag === 'true') return status;
  } catch {
    // Treat failed health checks as unknown presence and retain the shield.
  }
  return ScreenTime.setHomeOnly(true, null);
}

type Props = {
  client?: ConvexReactClient | null;
  ownerId: string;
  onChanged: () => void;
  onError: (message: string) => void;
};

export default function HomeLocationControls({ client, ownerId, onChanged, onError }: Props) {
  const [home, setHome] = useState<SavedDeviceHome | null>(null);
  const [accountHome, setAccountHome] = useState<SavedHome | null>(null);
  const [accountHomeState, setAccountHomeState] = useState<'loading' | 'loaded' | 'local' | 'error'>('loading');
  const [accountHomeError, setAccountHomeError] = useState<string | null>(null);
  const [status, setStatus] = useState<ScreenTimeStatus | null>(null);
  const [preview, setPreview] = useState<HomePlace | null>(null);
  const [placeLabel, setPlaceLabel] = useState('Home');
  const [radiusText, setRadiusText] = useState('150');
  const [busy, setBusy] = useState(false);
  const mounted = useRef(true);
  const errorRef = useRef(onError);
  errorRef.current = onError;

  useEffect(() => {
    mounted.current = true;
    setHome(null);
    setStatus(null);
    setAccountHome(null);
    setAccountHomeState(client ? 'loading' : 'local');
    setAccountHomeError(null);

    // Local coordinates and native policy must remain usable when Convex is
    // offline. Each device-side load settles independently of account queries.
    void AsyncStorage.getItem(`${DEVICE_HOME_PREFIX}${encodeURIComponent(ownerId)}`)
      .then((rawHome) => {
        const localHome = parseSavedDeviceHome(rawHome, ownerId);
        if (mounted.current) setHome(localHome);
      })
      .catch((error) => { if (mounted.current) errorRef.current(errorMessage(error)); });

    void (async () => {
      try {
        const nativeStatus = await ScreenTime.getStatus();
        if (!mounted.current) return;
        setStatus(await reconcileHomeBoundary(nativeStatus));
      } catch (error) {
        if (mounted.current) errorRef.current(errorMessage(error));
      }
    })();

    if (client) {
      void client.query(homeLocationRef, {}).then((saved) => {
        if (!mounted.current) return;
        setAccountHome(saved);
        setAccountHomeState('loaded');
      }).catch((error) => {
        if (!mounted.current) return;
        setAccountHomeError(errorMessage(error));
        setAccountHomeState('error');
      });
    }
    return () => { mounted.current = false; };
  }, [client, ownerId]);

  const reportError = (error: unknown) => errorRef.current(errorMessage(error));

  const captureCurrentPlace = async () => {
    setBusy(true);
    try {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (permission.status !== 'granted') throw new Error('Allow location while using FocusLock to preview your home boundary. No location is uploaded.');
      const position = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      if (!mounted.current) return;
      const place = validateHomePlace({
        label: placeLabel, latitude: position.coords.latitude, longitude: position.coords.longitude, radiusMeters: Number(radiusText),
      });
      setPreview(place);
    } catch (error) { reportError(error); }
    finally { if (mounted.current) setBusy(false); }
  };

  const saveDeviceHome = async () => {
    if (!preview) return;
    setBusy(true);
    try {
      if (status?.homeOnly) throw new Error('Turn off home-only blocking before changing this iPhone’s saved boundary.');
      const saved: SavedDeviceHome = { ownerId, ...validateHomePlace({ ...preview, label: placeLabel, radiusMeters: Number(radiusText) }) };
      await AsyncStorage.setItem(`${DEVICE_HOME_PREFIX}${encodeURIComponent(ownerId)}`, JSON.stringify(saved));
      if (!mounted.current) return;
      setHome(saved);
      setPreview(null);
    } catch (error) { reportError(error); }
    finally { if (mounted.current) setBusy(false); }
  };

  const enableBoundary = async () => {
    if (!home) return;
    if (!status?.available) {
      reportError('Home-only blocking needs an iOS build with Screen Time enabled. You can save this iPhone’s local home boundary, but this build cannot apply blocking.');
      return;
    }
    setBusy(true);
    try {
      if (status?.homeOnly) throw new Error('Home-only protection is already active. Turn it off before replacing the boundary.');
      const foreground = await Location.requestForegroundPermissionsAsync();
      if (foreground.status !== 'granted') throw new Error('Foreground location permission is needed to start the home boundary. Existing blocking stays unchanged.');
      const background = await Location.requestBackgroundPermissionsAsync();
      if (background.status !== 'granted') throw new Error('Allow Always location to use home-only blocking. Existing blocking stays unchanged.');

      let current: Location.LocationObject | null = null;
      try { current = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }); }
      catch { /* Unknown presence is passed as null, which preserves shielding. */ }
      const atHome = isAtHome(home, current ? current.coords : null);
      const isRegistered = await TaskManager.isTaskRegisteredAsync(HOME_LOCATION_TASK);
      if (isRegistered) await Location.stopGeofencingAsync(HOME_LOCATION_TASK);
      await AsyncStorage.setItem(ACTIVE_BOUNDARY_KEY, 'false');
      await Location.startGeofencingAsync(HOME_LOCATION_TASK, [{
        identifier: HOME_REGION_ID,
        latitude: home.latitude,
        longitude: home.longitude,
        radius: home.radiusMeters,
        notifyOnEnter: true,
        notifyOnExit: true,
      }]);
      try {
        // Null first ensures interruption or unavailable GPS cannot open rules.
        await ScreenTime.setHomeOnly(true, null);
        const next = await ScreenTime.setHomeOnly(true, atHome);
        await AsyncStorage.setItem(ACTIVE_BOUNDARY_KEY, 'true');
        if (mounted.current) setStatus(next);
        onChanged();
      } catch (error) {
        await AsyncStorage.setItem(ACTIVE_BOUNDARY_KEY, 'false').catch(() => undefined);
        await Location.stopGeofencingAsync(HOME_LOCATION_TASK).catch(() => undefined);
        await ScreenTime.setHomeOnly(false, null).catch(() => undefined);
        throw error;
      }
    } catch (error) { reportError(error); }
    finally { if (mounted.current) setBusy(false); }
  };

  const disableBoundary = () => Alert.alert(
    'Turn off home-only blocking?',
    'FocusLock will stop this iPhone’s home boundary and restore blocking everywhere. Your saved place stays on this iPhone.',
    [
      { text: 'Keep on', style: 'cancel' },
      { text: 'Turn off', style: 'destructive', onPress: () => { void (async () => {
        setBusy(true);
        try {
          await AsyncStorage.setItem(ACTIVE_BOUNDARY_KEY, 'false');
          const next = await ScreenTime.setHomeOnly(false, null);
          if (mounted.current) setStatus(next);
          if (await TaskManager.isTaskRegisteredAsync(HOME_LOCATION_TASK)) await Location.stopGeofencingAsync(HOME_LOCATION_TASK);
          onChanged();
        } catch (error) {
          // Even if iOS cannot stop the task, explicitly restore native blocking.
          await ScreenTime.setHomeOnly(false, null).then((next) => { if (mounted.current) setStatus(next); }).catch(() => undefined);
          reportError(error);
        } finally { if (mounted.current) setBusy(false); }
      })(); } },
    ],
  );

  const confirmDisableBoundary = () => {
    if (!status?.available) {
      reportError('Screen Time controls are unavailable in this build. Reopen FocusLock in a Screen Time-enabled iOS build to change native home-only blocking.');
      return;
    }
    disableBoundary();
  };

  const enabled = Boolean(status?.homeOnly);
  const anotherAccount = enabled && !home;
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.notice}>
        <Text style={styles.heading}>Home-only blocking</Text>
        <Text style={styles.body}>This is an iPhone-only boundary. FocusLock uses location on this device and does not upload GPS coordinates. It affects manual and scheduled rules; permanent blocks remain active.</Text>
        {status && !status.available ? <Text style={styles.warning}>Screen Time controls are unavailable in this build. You can preview and save local coordinates, but home-only blocking cannot be enabled or changed here.</Text> : null}
      </View>

      <View style={styles.card}>
        <Text style={styles.title}>On-device home</Text>
        <Text style={styles.body}>Saved locally for {ownerId === 'local' ? 'On this iPhone' : 'this signed-in account on this iPhone'}.</Text>
        {home ? <Text style={styles.detail}>{home.label} · {home.latitude.toFixed(5)}, {home.longitude.toFixed(5)} · {home.radiusMeters} m</Text> : <Text style={styles.detail}>No home boundary saved for this account on this iPhone.</Text>}
        {preview ? <View style={styles.preview}>
          <Text style={styles.body}>Preview coordinates · {preview.latitude.toFixed(5)}, {preview.longitude.toFixed(5)}</Text>
          <Text style={styles.detail}>Confirm the label and boundary radius before saving. Coordinates remain local to this iPhone.</Text>
          <TextInput accessibilityLabel="Place label" value={placeLabel} onChangeText={setPlaceLabel} placeholder="Home" placeholderTextColor={colors.onSurfaceVariant} style={styles.input} maxLength={120} />
          <TextInput accessibilityLabel="Boundary radius in metres" value={radiusText} onChangeText={setRadiusText} keyboardType="number-pad" style={styles.input} />
          <Button label={busy ? 'Saving…' : 'Save home on this iPhone'} disabled={busy || enabled} onPress={() => Alert.alert('Save this place on this iPhone?', 'Only this device’s local storage will be changed. Coordinates will not be sent to your FocusLock account.', [
            { text: 'Cancel', style: 'cancel' }, { text: 'Save on iPhone', onPress: () => { void saveDeviceHome(); } },
          ])} />
        </View> : null}
        <Button label={busy ? 'Checking location…' : 'Use current location'} disabled={busy || enabled} onPress={() => { void captureCurrentPlace(); }} />
        {home && !enabled ? <Button label={busy ? 'Requesting permissions…' : 'Use home-only blocking'} disabled={busy || !status?.available} onPress={() => Alert.alert('Enable home-only blocking?', 'FocusLock will request Always location permission and monitor this boundary in the background. If presence is unknown, blocking remains on.', [
          { text: 'Cancel', style: 'cancel' }, { text: 'Continue', onPress: () => { void enableBoundary(); } },
        ])} /> : null}
        {enabled ? <>
          <Text style={styles.state}>{status?.atHome === true ? 'Inside the saved home boundary' : status?.atHome === false ? 'Outside home · selected rules are paused; permanent blocks still apply' : 'Location unknown · blocking stays on'}</Text>
          <Button label={busy ? 'Turning off…' : 'Turn off home-only blocking'} disabled={busy || !status?.available} secondary onPress={confirmDisableBoundary} />
        </> : null}
        {anotherAccount ? <Text style={styles.warning}>Home-only protection remains enabled from an earlier account setup. This account’s saved place is never read from another account. Turn protection off before setting up a different boundary.</Text> : null}
      </View>

      <View style={styles.card}>
        <Text style={styles.title}>Account home location</Text>
        {accountHomeState === 'loading' ? <ActivityIndicator color={colors.primary} /> : null}
        {accountHomeState === 'local' ? <Text style={styles.body}>Local mode: sign in to view the separate account home setting. This iPhone’s saved boundary and device controls work independently.</Text> : null}
        {accountHomeState === 'error' ? <Text style={styles.warning}>Account home is unavailable right now. Your iPhone’s local boundary and device controls remain available. {accountHomeError}</Text> : null}
        {accountHomeState === 'loaded' ? accountHome?.place ? <Text style={styles.body}>A separate account home is saved as “{accountHome.place.label}.” It is read here for account settings only. Home-only blocking above uses this iPhone’s local boundary.</Text> : <Text style={styles.body}>No account home location is saved. You can manage it separately in Account settings.</Text> : null}
      </View>
      <Text style={styles.footnote}>Location boundaries depend on iOS background permissions and delivery. This is not uninstall protection; you can suspend blocking from the device controls.</Text>
    </ScrollView>
  );
}

function Button({ label, onPress, disabled = false, secondary = false }: { label: string; onPress: () => void; disabled?: boolean; secondary?: boolean }) {
  return <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={({ pressed }) => [styles.button, secondary && styles.secondaryButton, disabled && styles.disabled, pressed && !disabled && styles.pressed]}>
    <Text style={secondary ? styles.secondaryLabel : styles.buttonLabel}>{label}</Text>
  </Pressable>;
}

const styles = StyleSheet.create({
  content: { padding: spacing.large, gap: spacing.medium, paddingBottom: spacing.xlarge },
  notice: { backgroundColor: colors.surface, borderRadius: radii.medium, padding: spacing.medium, gap: spacing.small },
  heading: { color: colors.onSurface, fontSize: 20, fontWeight: '700' },
  card: { backgroundColor: colors.surface, borderRadius: radii.medium, padding: spacing.medium, gap: spacing.small },
  title: { color: colors.onSurface, fontSize: 17, fontWeight: '700' },
  body: { color: colors.onSurfaceVariant, fontSize: 14, lineHeight: 20 },
  detail: { color: colors.onSurface, fontSize: 14, lineHeight: 20 },
  state: { color: colors.primary, fontWeight: '700', fontSize: 14, lineHeight: 20 },
  warning: { color: colors.warning, fontSize: 13, lineHeight: 19 },
  footnote: { color: colors.onSurfaceVariant, fontSize: 12, lineHeight: 18, paddingHorizontal: 4 },
  preview: { borderTopWidth: StyleSheet.hairlineWidth, borderColor: colors.outlineVariant, paddingTop: spacing.small, gap: spacing.small },
  input: { minHeight: 44, borderWidth: 1, borderColor: colors.outlineVariant, borderRadius: radii.small, paddingHorizontal: spacing.small, color: colors.onSurface },
  button: { minHeight: 48, justifyContent: 'center', alignItems: 'center', backgroundColor: colors.primary, borderRadius: radii.small, paddingHorizontal: spacing.medium, marginTop: spacing.xsmall },
  secondaryButton: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.outlineVariant },
  buttonLabel: { color: colors.onPrimary, fontWeight: '700', textAlign: 'center' },
  secondaryLabel: { color: colors.onSurface, fontWeight: '700', textAlign: 'center' },
  disabled: { opacity: 0.5 },
  pressed: { opacity: 0.78 },
});
