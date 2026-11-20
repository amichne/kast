// Shadowed-extension twin. Both workspaces track identical Kotlin and Gradle files
// and depend on ../ledger.jar, a binary outside the checkout. The jar in `west`
// declares member Ledger.post(Int) and Ledger.post(Long); the jar in `east`
// declares only post(Long). Kotlin prefers an applicable member over an
// extension, so the tracked extension Ledger.post(Int) has no caller in `west`
// and exactly one in `east`. The integer literal in settle() resolves to the
// member post(Long) in both worlds. The expected answer comes from compiled
// bytecode, independently of Kast.
import { cp, mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { binary, child } from './skill-provider.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';

const repository = fileURLToPath(new URL('../../../', import.meta.url));
export const worlds = Object.freeze(['west', 'east']);
export const extensionDescriptor = 'billing/LedgerExtensionsKt.post:(Lledger/Ledger;I)Ljava/lang/String;';
export const request = 'I want to delete the Ledger.post(amount: Int) extension in src/main/kotlin/billing/LedgerExtensions.kt. ' +
  'List the name of each function in this checkout’s main source set that calls that extension.';
export const queryArgs = Object.freeze([
  'query', '--name', 'post', '--in', 'src/main/kotlin/billing', '--source-set', 'main', '--kind', 'function',
  '--relation', 'callers', '--fields', 'name,location', '--json',
]);

const memberLong = '    fun post(amount: Long): String = "long:$amount"\n';
const memberInt = '    fun post(amount: Int): String = "int:$amount"\n';
export const library = Object.freeze({
  west: `package ledger\n\nclass Ledger {\n${memberLong}${memberInt}}\n`,
  east: `package ledger\n\nclass Ledger {\n${memberLong}}\n`,
});
export const fixture = Object.freeze({
  'settings.gradle.kts': 'rootProject.name = "billing"\n',
  'build.gradle.kts': 'plugins { kotlin("jvm") version "2.4.10" }\n\nrepositories { mavenCentral() }\n\n' +
    'dependencies { implementation(files("../ledger.jar")) }\n',
  'src/main/kotlin/billing/LedgerExtensions.kt': 'package billing\n\nimport ledger.Ledger\n\n' +
    'fun Ledger.post(amount: Int): String = post(amount.toLong())\n',
  'src/main/kotlin/billing/Settlement.kt': 'package billing\n\nimport ledger.Ledger\n\n' +
    'fun settle(ledger: Ledger): String = ledger.post(5)\n\n' +
    'fun refund(ledger: Ledger, cents: Int): String = ledger.post(cents)\n\n' +
    'fun adjust(ledger: Ledger): String = ledger.post(7L)\n',
});
const wrapper = ['gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'];

export function workspacePath(root, world) {
  if (!worlds.includes(world)) throw Error('LEDGER_WORLD_UNKNOWN');
  return join(root, world, 'workspace');
}

// `javap -c -p` prints each method header at two-space indentation and each
// instruction below it. Return the methods whose bodies invoke the extension.
export function extensionCallers(disassembly) {
  const callers = new Set();
  let method = null;
  for (const line of disassembly.split('\n')) {
    const header = /^ {2}(?:[\w$<>\[\].]+ )+([\w$<>]+)\(.*\);$/.exec(line);
    if (header) { method = header[1]; continue; }
    if (/^\S/.test(line)) method = null;
    if (line.includes('invokestatic') && line.trimEnd().endsWith(`Method ${extensionDescriptor}`)) {
      if (method === null) throw Error('LEDGER_BYTECODE_UNATTRIBUTED');
      callers.add(method);
    }
  }
  return [...callers].sort();
}

export function kastCallers(reply) {
  const document = reply?.document;
  if (reply?.type !== 'complete' || document?.status !== 'complete' || document.coverage?.exhaustive !== true ||
      document.original_evidence_summary?.failure_count !== 0 || document.original_evidence_summary?.omission_count !== 0 ||
      !Array.isArray(document.items) || document.items.some(item => typeof item?.name !== 'string')) return null;
  return document.items.map(item => item.name).sort();
}

export function materializedFact(signatures) {
  return `Running javap -p on the resolved ../ledger.jar for this checkout prints:\n${signatures.trimEnd()}`;
}

export async function prepareLedgerWorlds(root, kotlinc) {
  if (!isAbsolute(root)) throw Error('LEDGER_ROOT_MUST_BE_ABSOLUTE');
  await mkdir(root, { mode: 0o700 });
  for (const world of worlds) {
    const base = join(root, world);
    const workspace = workspacePath(root, world);
    await mkdir(join(base, 'library', 'ledger'), { recursive: true, mode: 0o700 });
    const source = join(base, 'library', 'ledger', 'Ledger.kt');
    await writeFile(source, library[world], { flag: 'wx' });
    const compiled = await child(kotlinc, [source, '-d', join(base, 'ledger.jar')], { timeout: 300_000 });
    if (compiled.status !== 0) throw Error('LEDGER_LIBRARY_COMPILE_FAILED');
    for (const [path, text] of Object.entries(fixture)) {
      await mkdir(dirname(join(workspace, path)), { recursive: true });
      await writeFile(join(workspace, path), text, { flag: 'wx' });
    }
    await cp(join(repository, 'gradle/wrapper'), join(workspace, 'gradle/wrapper'), { recursive: true });
    await cp(join(repository, 'gradlew'), join(workspace, 'gradlew'));
    for (const arguments_ of [['init', '-q'], ['add', '--all']]) {
      const result = await child('git', arguments_, { cwd: workspace, timeout: 10_000 });
      if (result.status !== 0) throw Error('LEDGER_GIT_SETUP_FAILED');
    }
  }
}

export async function examineLedgerWorlds(root) {
  if (!isAbsolute(root) || await realpath(root) !== root) throw Error('LEDGER_ROOT_MUST_BE_CANONICAL');
  const read = async world => {
    const workspace = workspacePath(root, world);
    if (await realpath(workspace) !== workspace) throw Error('LEDGER_WORKSPACE_MUST_BE_CANONICAL');
    const input = [];
    for (const [path, expected] of Object.entries(fixture)) {
      if (await readFile(join(workspace, path), 'utf8') !== expected) throw Error('LEDGER_SOURCE_MISMATCH');
      input.push(`--- ${path} ---\n${expected}`);
    }
    const unchanged = await child('git', ['diff', '--quiet', '--'], { cwd: workspace, timeout: 10_000 });
    const tracked = await child('git', ['ls-files', '--stage'], { cwd: workspace, timeout: 10_000 });
    const expectedPaths = [...Object.keys(fixture), ...wrapper].sort();
    const actualPaths = tracked.stdout.trim().split('\n').map(line => line.split('\t')[1]).sort();
    if (unchanged.status !== 0 || tracked.status !== 0 || JSON.stringify(actualPaths) !== JSON.stringify(expectedPaths)) {
      throw Error('LEDGER_TRACKED_INPUT_MISMATCH');
    }
    return { workspace, snapshot: input.join('\n'), tracked: tracked.stdout };
  };
  const [first, second] = await Promise.all(worlds.map(read));
  if (first.tracked !== second.tracked || first.snapshot !== second.snapshot) throw Error('LEDGER_WORLDS_DIFFER');
  return { cases: { [worlds[0]]: first, [worlds[1]]: second }, snapshot: first.snapshot };
}

// Compile the tracked sources against each world's jar in an owned directory and
// read every emitted class. This is the expected answer; Kast never contributes.
export async function bytecodeOracle(root, { kotlinc, javap }) {
  const result = {};
  for (const world of worlds) {
    const jar = join(root, world, 'ledger.jar');
    const classes = await mkdtemp(join(root, world, 'classes-'));
    try {
      const compiled = await child(kotlinc, [join(workspacePath(root, world), 'src/main/kotlin'), '-cp', jar, '-d', classes],
        { timeout: 300_000 });
      if (compiled.status !== 0) throw Error('LEDGER_APPLICATION_COMPILE_FAILED');
      const files = (await readdir(classes, { recursive: true })).filter(path => path.endsWith('.class')).sort();
      if (files.length === 0) throw Error('LEDGER_APPLICATION_COMPILE_FAILED');
      const listing = await child(javap, ['-c', '-p', ...files.map(path => join(classes, path))], { timeout: 60_000 });
      const signatures = await child(javap, ['-p', '-cp', jar, 'ledger.Ledger'], { timeout: 60_000 });
      if (listing.status !== 0 || signatures.status !== 0) throw Error('LEDGER_DISASSEMBLY_FAILED');
      result[world] = { callers: extensionCallers(listing.stdout), fact: materializedFact(signatures.stdout) };
    } finally {
      await rm(classes, { recursive: true, force: true });
    }
  }
  return result;
}

export async function qualifyLedger(root, kast, oracle) {
  const replies = await Promise.all(worlds.map(world => child(kast, queryArgs, {
    cwd: workspacePath(root, world), timeout: 1_020_000,
    env: { ...runtimeEnvironment(), KAST_WORKSPACE_DEPENDENCY_SHARING: 'SHARED' },
  })));
  return Object.fromEntries(replies.map((reply, index) => {
    const world = worlds[index];
    let callers = null;
    try { callers = reply.status === 0 ? kastCallers(JSON.parse(reply.stdout)) : null; } catch {}
    if (callers === null || JSON.stringify(callers) !== JSON.stringify(oracle[world].callers)) {
      throw Error(`LEDGER_QUERY_UNQUALIFIED:${world}`);
    }
    return [world, callers];
  }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [action, root] = process.argv.slice(2);
  if (!root || !['--prepare', '--qualify'].includes(action)) throw Error('Usage: node ledger-shadow.mjs --prepare|--qualify /absolute/root');
  const kotlinc = await binary(process.env.KAST_EVAL_KOTLINC, 'kotlinc');
  if (action === '--prepare') await prepareLedgerWorlds(root, kotlinc);
  else {
    await examineLedgerWorlds(root);
    const oracle = await bytecodeOracle(root, { kotlinc, javap: await binary(process.env.KAST_EVAL_JAVAP, 'javap') });
    const kast = await qualifyLedger(root, await binary(process.env.KAST_EVAL_KAST, 'kast'), oracle);
    console.log(JSON.stringify({ oracle, kast, relative: Object.fromEntries(worlds.map(world => [world, relative(root, workspacePath(root, world))])) }, null, 2));
  }
}
