import AsyncStorage from '@react-native-async-storage/async-storage';
import { randomUUID } from 'expo-crypto';

let installation: Promise<string> | null = null;
export function installationId(): Promise<string> {
  installation ??= (async () => {
    const saved = await AsyncStorage.getItem('focuslock.installation.v1');
    if (saved) return saved;
    const id = `ios-${randomUUID()}`;
    await AsyncStorage.setItem('focuslock.installation.v1', id);
    return id;
  })().catch((error) => { installation = null; throw error; });
  return installation;
}
