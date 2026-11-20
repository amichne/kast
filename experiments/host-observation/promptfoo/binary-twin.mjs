// Binary-dependency twins. Both workspaces track identical Kotlin and Gradle files
// and depend on one jar outside the checkout, named by the spec, that differs between
// the worlds. The expected answer is the set of application methods whose compiled
// bytecode invokes one tracked declaration's JVM descriptor; Kast never contributes.
import { cp, mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { child } from './skill-provider.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';

const repository = fileURLToPath(new URL('../../../', import.meta.url));
const wrapper = ['gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'];
export const buildScript = jar => 'plugins { kotlin("jvm") version "2.4.10" }\n\nrepositories { mavenCentral() }\n\n' +
  `dependencies { implementation(files("../${jar}")) }\n`;

export function workspacePath(spec, root, world) {
  if (!spec.worlds.includes(world)) throw Error(`${spec.code}_WORLD_UNKNOWN`);
  return join(root, world, 'workspace');
}

// `javap -c -p` prints each method header at two-space indentation and each
// instruction below it. Return the methods whose bodies invoke the descriptor.
export function invokingMethods(disassembly, descriptor) {
  const callers = new Set();
  let method = null;
  for (const line of disassembly.split('\n')) {
    const header = /^ {2}(?:[\w$<>\[\].]+ )+([\w$<>]+)\(.*\);$/.exec(line);
    if (header) { method = header[1]; continue; }
    if (/^\S/.test(line)) method = null;
    if (line.includes('invokestatic') && line.trimEnd().endsWith(`Method ${descriptor}`)) {
      if (method === null) throw Error('TWIN_BYTECODE_UNATTRIBUTED');
      callers.add(method);
    }
  }
  return [...callers].sort();
}

export function completeNames(reply) {
  const document = reply?.document;
  if (reply?.type !== 'complete' || document?.status !== 'complete' || document.coverage?.exhaustive !== true ||
      document.original_evidence_summary?.failure_count !== 0 || document.original_evidence_summary?.omission_count !== 0 ||
      !Array.isArray(document.items) || document.items.some(item => typeof item?.name !== 'string')) return null;
  return document.items.map(item => item.name).sort();
}

export function materializedFact(jar, signatures) {
  return `Running javap -p on the resolved ../${jar} for this checkout prints:\n${signatures.trimEnd()}`;
}

export async function prepareBinaryTwin(spec, root, kotlinc) {
  if (!isAbsolute(root)) throw Error(`${spec.code}_ROOT_MUST_BE_ABSOLUTE`);
  await mkdir(root, { mode: 0o700 });
  for (const world of spec.worlds) {
    const base = join(root, world);
    const workspace = workspacePath(spec, root, world);
    const sources = [];
    for (const [path, text] of Object.entries(spec.library[world])) {
      const file = join(base, 'library', path);
      await mkdir(dirname(file), { recursive: true, mode: 0o700 });
      await writeFile(file, text, { flag: 'wx' });
      sources.push(file);
    }
    const compiled = await child(kotlinc, [...sources, '-d', join(base, spec.jar)], { timeout: 300_000 });
    if (compiled.status !== 0) throw Error(`${spec.code}_LIBRARY_COMPILE_FAILED`);
    for (const [path, text] of Object.entries(spec.fixture)) {
      await mkdir(dirname(join(workspace, path)), { recursive: true });
      await writeFile(join(workspace, path), text, { flag: 'wx' });
    }
    await cp(join(repository, 'gradle/wrapper'), join(workspace, 'gradle/wrapper'), { recursive: true });
    await cp(join(repository, 'gradlew'), join(workspace, 'gradlew'));
    for (const arguments_ of [['init', '-q'], ['add', '--all']]) {
      const result = await child('git', arguments_, { cwd: workspace, timeout: 10_000 });
      if (result.status !== 0) throw Error(`${spec.code}_GIT_SETUP_FAILED`);
    }
  }
}

export async function examineBinaryTwin(spec, root) {
  if (!isAbsolute(root) || await realpath(root) !== root) throw Error(`${spec.code}_ROOT_MUST_BE_CANONICAL`);
  const read = async world => {
    const workspace = workspacePath(spec, root, world);
    if (await realpath(workspace) !== workspace) throw Error(`${spec.code}_WORKSPACE_MUST_BE_CANONICAL`);
    const input = [];
    for (const [path, expected] of Object.entries(spec.fixture)) {
      if (await readFile(join(workspace, path), 'utf8') !== expected) throw Error(`${spec.code}_SOURCE_MISMATCH`);
      input.push(`--- ${path} ---\n${expected}`);
    }
    const unchanged = await child('git', ['diff', '--quiet', '--'], { cwd: workspace, timeout: 10_000 });
    const tracked = await child('git', ['ls-files', '--stage'], { cwd: workspace, timeout: 10_000 });
    const expectedPaths = [...Object.keys(spec.fixture), ...wrapper].sort();
    const actualPaths = tracked.stdout.trim().split('\n').map(line => line.split('\t')[1]).sort();
    if (unchanged.status !== 0 || tracked.status !== 0 || JSON.stringify(actualPaths) !== JSON.stringify(expectedPaths)) {
      throw Error(`${spec.code}_TRACKED_INPUT_MISMATCH`);
    }
    return { workspace, snapshot: input.join('\n'), tracked: tracked.stdout };
  };
  const results = await Promise.all(spec.worlds.map(read));
  if (results.some(item => item.tracked !== results[0].tracked || item.snapshot !== results[0].snapshot)) {
    throw Error(`${spec.code}_WORLDS_DIFFER`);
  }
  return { cases: Object.fromEntries(spec.worlds.map((world, index) => [world, results[index]])), snapshot: results[0].snapshot };
}

// Compile the tracked sources against each world's jar in an owned directory and
// read every emitted class.
export async function binaryOracle(spec, root, { kotlinc, javap }) {
  const result = {};
  for (const world of spec.worlds) {
    const jar = join(root, world, spec.jar);
    const classes = await mkdtemp(join(root, world, 'classes-'));
    try {
      const compiled = await child(kotlinc, [join(workspacePath(spec, root, world), 'src/main/kotlin'), '-cp', jar, '-d', classes],
        { timeout: 300_000 });
      if (compiled.status !== 0) throw Error(`${spec.code}_APPLICATION_COMPILE_FAILED`);
      const files = (await readdir(classes, { recursive: true })).filter(path => path.endsWith('.class')).sort();
      if (files.length === 0) throw Error(`${spec.code}_APPLICATION_COMPILE_FAILED`);
      const listing = await child(javap, ['-c', '-p', ...files.map(path => join(classes, path))], { timeout: 60_000 });
      const signatures = await child(javap, ['-p', '-cp', jar, ...spec.libraryClasses], { timeout: 60_000 });
      if (listing.status !== 0 || signatures.status !== 0) throw Error(`${spec.code}_DISASSEMBLY_FAILED`);
      result[world] = { callers: invokingMethods(listing.stdout, spec.descriptor), fact: materializedFact(spec.jar, signatures.stdout) };
    } finally {
      await rm(classes, { recursive: true, force: true });
    }
  }
  return result;
}

export async function qualifyBinaryTwin(spec, root, kast, oracle) {
  const env = { ...runtimeEnvironment(), KAST_WORKSPACE_DEPENDENCY_SHARING: 'SHARED' };
  const replies = await Promise.all(spec.worlds.map(world => child(kast, spec.queryArgs, {
    cwd: workspacePath(spec, root, world), timeout: 1_020_000, env,
  })));
  return Object.fromEntries(replies.map((reply, index) => {
    const world = spec.worlds[index];
    let callers = null;
    try { callers = reply.status === 0 ? completeNames(JSON.parse(reply.stdout)) : null; } catch {}
    if (callers === null || JSON.stringify(callers) !== JSON.stringify(oracle[world].callers)) {
      throw Error(`${spec.code}_QUERY_UNQUALIFIED:${world}`);
    }
    return [world, callers];
  }));
}
