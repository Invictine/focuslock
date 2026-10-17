const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);
// The palette is the same source used by Android, Windows, and Chrome.
config.watchFolders = [...config.watchFolders, path.resolve(__dirname, '../design')];
module.exports = config;
