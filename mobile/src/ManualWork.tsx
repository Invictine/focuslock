import { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View, Pressable } from 'react-native';
import { colors } from './theme';

export default function ManualWork({ onSave, busy, local }: { onSave: (title: string, minutes: number, tasks: number) => void; busy: boolean; local: boolean }) {
  const [title, setTitle] = useState('Completed work');
  const [minutes, setMinutes] = useState('25');
  const [tasks, setTasks] = useState('0');
  const valid = title.trim() && /^\d+$/.test(minutes) && /^\d+$/.test(tasks) && Number(minutes) <= 1440 && Number(tasks) <= 100 && (Number(minutes) > 0 || Number(tasks) > 0);
  return <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
    <Text style={styles.title}>Log completed work</Text>
    <Text style={styles.copy}>Only log time you actually worked and tasks you actually completed. This is a self-reported entry.</Text>
    <Text style={styles.label}>Title</Text>
    <TextInput style={styles.input} accessibilityLabel="Work title" value={title} onChangeText={setTitle} maxLength={160} />
    <Text style={styles.label}>Minutes worked</Text>
    <TextInput style={styles.input} accessibilityLabel="Minutes worked" value={minutes} onChangeText={setMinutes} keyboardType="number-pad" />
    <Text style={styles.label}>Tasks completed</Text>
    <TextInput style={styles.input} accessibilityLabel="Tasks completed" value={tasks} onChangeText={setTasks} keyboardType="number-pad" />
    <Text style={styles.copy}>{local ? 'This entry will stay on this iPhone.' : 'The account work ratio and task bonus determine earned credit. Offline entries wait for this account to reconnect.'}</Text>
    <Pressable style={[styles.button, (!valid || busy) && { opacity: 0.45 }]} disabled={!valid || busy} accessibilityRole="button" onPress={() => onSave(title, Number(minutes), Number(tasks))}><Text style={styles.buttonText}>{busy ? 'Saving…' : 'Save completed work'}</Text></Pressable>
  </ScrollView>;
}
const styles = StyleSheet.create({
  content: { padding: 24, gap: 14 }, title: { color: colors.onSurface, fontSize: 28, fontWeight: '700' },
  copy: { color: colors.onSurfaceVariant, fontSize: 16, lineHeight: 23 }, label: { color: colors.onSurface, fontSize: 16 },
  input: { color: colors.onSurface, backgroundColor: colors.surfaceContainer, borderWidth: 1, borderColor: colors.outline, borderRadius: 12, minHeight: 48, padding: 14, fontSize: 17 },
  button: { backgroundColor: colors.primary, borderRadius: 12, minHeight: 48, padding: 16, alignItems: 'center' }, buttonText: { color: colors.onPrimary, fontSize: 17, fontWeight: '600' },
});
