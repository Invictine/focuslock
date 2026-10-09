import { readdir, readFile, mkdir, copyFile, lstat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

// Only source is mirrored. Never copy Git metadata, credentials or build output.
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const integratedRoot = path.join(projectRoot, "windows", "void");
const args = process.argv.slice(2);
const option = (name) => args[args.indexOf(name) + 1];
const standaloneArg = args.includes("--void-root") ? option("--void-root") : process.env.VOID_REPO_PATH;
if (!standaloneArg || standaloneArg.startsWith("--")) {
  throw new Error("Specify the standalone repository: --void-root <path> (or VOID_REPO_PATH).");
}
const standaloneRoot = path.resolve(standaloneArg);
if (standaloneRoot === integratedRoot || standaloneRoot === projectRoot) {
  throw new Error("Standalone Void must be a separate checkout, not FocusLock or windows/void.");
}
const direction = args.includes("--direction") ? option("--direction") : "to-standalone";
if (!["to-standalone", "from-standalone"].includes(direction)) throw new Error("Invalid direction.");
const copy = args.includes("--write");
const excludedDirectories = new Set([".git", ".vs", ".idea", "bin", "obj", "work", "outputs", "portable", "node_modules", "target", "artifacts"]);
const allowedSource = (relative) => /\.(cs|xaml|csproj)$/.test(relative) ||
  ["config.json", "build-installer.ps1", "README.md", "AGENTS.md"].includes(relative) ||
  /^(tests|packaging)\/.*\.(json|iss|ps1|md)$/.test(relative);
async function inventory(root) {
  const result = [];
  async function visit(relativeDirectory = "") {
    for (const entry of await readdir(path.join(root, relativeDirectory), { withFileTypes: true })) {
      if (excludedDirectories.has(entry.name)) continue;
      const relative = relativeDirectory ? `${relativeDirectory}/${entry.name}` : entry.name;
      if (entry.isSymbolicLink()) throw new Error(`Refusing symbolic link in source: ${relative}`);
      if (entry.isFile() && allowedSource(relative)) result.push(relative);
      if (entry.isDirectory()) await visit(relative);
    }
  }
  await visit();
  return result.sort();
}
await lstat(path.join(standaloneRoot, "Void.csproj"));
const [integrated, standalone] = await Promise.all([inventory(integratedRoot), inventory(standaloneRoot)]);
const allFiles = [...new Set([...integrated, ...standalone])].sort();
const sourceRoot = direction === "to-standalone" ? integratedRoot : standaloneRoot;
const destinationRoot = direction === "to-standalone" ? standaloneRoot : integratedRoot;
const differences = [];
for (const relative of allFiles) {
  const source = path.join(sourceRoot, relative);
  const destination = path.join(destinationRoot, relative);
  const [from, to] = await Promise.all([
    readFile(source).catch((error) => { if (error.code === "ENOENT") return null; throw error; }),
    readFile(destination).catch((error) => { if (error.code === "ENOENT") return null; throw error; }),
  ]);
  if (from && to && from.equals(to)) continue;
  differences.push(relative);
  if (!copy) continue;
  if (!from) throw new Error(`Source has no ${relative}; reconcile removed files manually. No files are deleted by this tool.`);
  // Guard against links in the destination; copying must stay in the named checkout.
  for (const candidate of [path.dirname(destination), destination]) {
    const stat = await lstat(candidate).catch((error) => { if (error.code === "ENOENT") return null; throw error; });
    if (stat?.isSymbolicLink()) throw new Error(`Refusing symbolic-link destination: ${candidate}`);
  }
  await mkdir(path.dirname(destination), { recursive: true });
  await copyFile(source, destination);
}
if (differences.length && !copy) {
  console.error(`Void source parity failed (${differences.length} files):\n${differences.join("\n")}`);
  process.exitCode = 1;
} else {
  console.log(copy ? `Mirrored ${differences.length} source files (${direction}). Run again without --write to verify parity.` : `Void source parity passed (${allFiles.length} files).`);
}
