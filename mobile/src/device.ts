import AsyncStorage from '@react-native-async-storage/async-storage';
import { randomUUID } from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';

let installation: Promise<string> | null = null;
export function installationId(): Promise<string> {
  installation ??= (async () => {
    const key = 'focuslock.installation.v1';
    const saved = await SecureStore.getItemAsync(key);
    if (saved) return saved;
    // Preserve the existing counter namespace when upgrading. Keychain storage
    // keeps an App Group ledger that survives reinstallation from being billed
    // again under a newly generated installation ID.
    const id = await AsyncStorage.getItem(key) ?? `ios-${randomUUID()}`;
    await SecureStore.setItemAsync(key, id, { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY });
    await AsyncStorage.setItem('focuslock.installation.v1', id);
    return id;
  })().catch((error) => { installation = null; throw error; });
  return installation;
}
