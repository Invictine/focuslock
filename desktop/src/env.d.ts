// Convex server modules are included indirectly by generated API types during
// the desktop typecheck. They run in Convex, where `process.env` is available.
declare const process: {
  env: Record<string, string | undefined>;
};
