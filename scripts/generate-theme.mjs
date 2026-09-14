import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const theme = JSON.parse(await readFile(path.join(root, 'design/theme.json'), 'utf8'));
const kebab = (value) => value.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`);
for (const [name, value] of Object.entries(theme.colors)) {
  if (!/^[a-zA-Z]+$/.test(name) || !/^#[0-9a-f]{6}$/i.test(value)) {
    throw new Error(`Invalid theme color: ${name}`);
  }
}
// Theme edits must retain readable semantic foreground/background pairs.
const luminance = (hex) => {
  const rgb = hex.slice(1).match(/../g).map((part) => parseInt(part, 16) / 255)
    .map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
};
for (const [foreground, background] of [
  ['onSurface', 'surface'], ['onSurface', 'surfaceContainer'],
  ['onSurfaceVariant', 'surfaceContainerHighest'],
  ['onPrimary', 'primary'], ['onPrimaryContainer', 'primaryContainer'],
  ['onSecondary', 'secondary'], ['onSecondaryContainer', 'secondaryContainer'],
  ['onTertiary', 'tertiary'], ['onTertiaryContainer', 'tertiaryContainer'],
  ['onError', 'error'], ['onErrorContainer', 'errorContainer'],
  ['success', 'successContainer'], ['warning', 'warningContainer'],
]) {
  const pair = [luminance(theme.colors[foreground]), luminance(theme.colors[background])];
  const ratio = (Math.max(...pair) + 0.05) / (Math.min(...pair) + 0.05);
  if (ratio < 4.5) throw new Error(`${foreground}/${background} contrast ${ratio.toFixed(2)}:1 is below 4.5:1`);
}
const css = [
  '/* Generated from design/theme.json. Run npm run theme:generate at the repo root. */',
  ':root {',
  '  color-scheme: dark;',
  ...Object.entries(theme.colors).map(([name, value]) => `  --fl-${kebab(name)}: ${value};`),
  ...Object.entries(theme.radii).map(([name, value]) => `  --fl-radius-${kebab(name)}: ${value}px;`),
  `  --fl-font-family: ${theme.fontFamily};`,
  '}',
  '',
].join('\n');
const kotlin = [
  '// Generated from design/theme.json. Run npm run theme:generate at the repo root.',
  'package com.focuslock.app.ui.theme',
  '',
  'import androidx.compose.ui.graphics.Color',
  '',
  'object FocusLockPalette {',
  ...Object.entries(theme.colors).map(([name, value]) => `    val ${name} = Color(0xFF${value.slice(1).toUpperCase()})`),
  '}',
  '',
].join('\n');

const outputs = new Map([
  ['desktop/src/theme-tokens.css', css],
  ['extension/shared/theme-tokens.css', css],
  ['app/src/main/java/com/focuslock/app/ui/theme/FocusLockPalette.kt', kotlin],
]);
let stale = false;
for (const [relative, content] of outputs) {
  const file = path.join(root, relative);
  if (process.argv.includes('--check')) {
    const actual = await readFile(file, 'utf8').catch(() => '');
    if (actual.replace(/\r\n/g, '\n') !== content) {
      console.error(`Theme output is stale: ${relative}`);
      stale = true;
    }
  } else {
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, content);
  }
}
if (stale) process.exitCode = 1;
else console.log(process.argv.includes('--check') ? 'All platform themes match design/theme.json.' : 'Generated shared CSS and Android palette.');
