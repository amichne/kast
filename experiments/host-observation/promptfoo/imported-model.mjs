import { createHash } from 'node:crypto';
import { cp, mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { binary, child } from './skill-provider.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';

const repository = fileURLToPath(new URL('../../../', import.meta.url));
export const worlds = Object.freeze(['adapter-a', 'adapter-b']);
export const queryArgs = Object.freeze([
  'query', '--name', 'formatInvoiceReference', '--source-set', 'main', '--kind', 'function',
  '--fields', 'name,location', '--json',
]);
export const declarationPath = project => `${project}/src/main/kotlin/invoices/InvoiceReference.kt`;
export const digest = bytes => createHash('sha256').update(bytes).digest('hex');
export const fixture = Object.freeze({
  'settings.gradle.kts': 'rootProject.name = "invoice-reference"\nval adapter = file("../selection.txt").readText().trim()\ninclude(":$adapter")\n',
  'build.gradle.kts': 'plugins { kotlin("jvm") version "2.4.10" apply false }\n\nsubprojects {\n    apply(plugin = "org.jetbrains.kotlin.jvm")\n    repositories { mavenCentral() }\n}\n',
  ...Object.fromEntries(worlds.map(project => [declarationPath(project),
    'package invoices\n\nfun formatInvoiceReference(id: Long): String = "invoice-$id"\n'])),
});

export function workspacePath(root, world) {
  if (!worlds.includes(world)) throw Error('IMPORTED_MODEL_WORLD_UNKNOWN');
  return join(root, world, 'workspace');
}

export function completeDeclarationPath(reply, workspace) {
  const document = reply?.document;
  const row = document?.items?.[0];
  const file = row?.location?.file;
  if (reply?.type !== 'complete' || document?.status !== 'complete' || document.coverage?.exhaustive !== true ||
      document.original_evidence_summary?.failure_count !== 0 || document.original_evidence_summary?.omission_count !== 0 ||
      document.items?.length !== 1 || row?.name !== 'formatInvoiceReference' || typeof file !== 'string' || !isAbsolute(file)) return null;
  const path = relative(workspace, file);
  return worlds.some(world => declarationPath(world) === path) ? path : null;
}

export function participantPrompt(snapshot, selection) {
  const question = 'Which file supplies formatInvoiceReference(Long) to this checkout’s imported Gradle main source set?';
  const instructions = 'Return only its workspace-relative path. Return UNKNOWN if the available evidence does not identify the file.';
  return `${question}\n${instructions}\n\nWorkspace snapshot:\n${snapshot}` +
    (selection === undefined ? '' : `\nThe observed ../selection.txt for this checkout contains exactly:\n${selection}`);
}

export function gradeImportedAnswer(output, world) {
  const expected = declarationPath(world);
  const pass = output === expected;
  return { pass, score: Number(pass), reason: pass ? 'EXPECTED_DECLARATION' : output === 'UNKNOWN' ? 'ABSTAINED' : 'WRONG_DECLARATION' };
}

export async function prepareWorlds(root) {
  if (!isAbsolute(root)) throw Error('IMPORTED_MODEL_ROOT_MUST_BE_ABSOLUTE');
  await mkdir(root, { mode: 0o700 });
  for (const world of worlds) {
    const workspace = workspacePath(root, world);
    await mkdir(workspace, { recursive: true, mode: 0o700 });
    await writeFile(join(root, world, 'selection.txt'), `${world}\n`, { mode: 0o600, flag: 'wx' });
    for (const [path, source] of Object.entries(fixture)) {
      await mkdir(dirname(join(workspace, path)), { recursive: true });
      await writeFile(join(workspace, path), source, { flag: 'wx' });
    }
    await cp(join(repository, 'gradle/wrapper'), join(workspace, 'gradle/wrapper'), { recursive: true });
    await cp(join(repository, 'gradlew'), join(workspace, 'gradlew'));
    for (const arguments_ of [['init', '-q'], ['add', '--all']]) {
      const result = await child('git', arguments_, { cwd: workspace, timeout: 10_000 });
      if (result.status !== 0) throw Error('IMPORTED_MODEL_GIT_SETUP_FAILED');
    }
  }
}

export async function examineWorlds(root) {
  if (!isAbsolute(root) || await realpath(root) !== root) throw Error('IMPORTED_MODEL_ROOT_MUST_BE_CANONICAL');
  const read = async world => {
    const workspace = workspacePath(root, world);
    if (await realpath(workspace) !== workspace) throw Error('IMPORTED_MODEL_WORKSPACE_MUST_BE_CANONICAL');
    const selection = await readFile(join(root, world, 'selection.txt'), 'utf8');
    if (selection !== `${world}\n`) throw Error('IMPORTED_MODEL_SELECTION_MISMATCH');
    const input = [];
    for (const [path, expected] of Object.entries(fixture)) {
      const content = await readFile(join(workspace, path), 'utf8');
      if (content !== expected) throw Error('IMPORTED_MODEL_SOURCE_MISMATCH');
      input.push(`--- ${path} ---\n${content}`);
    }
    const checked = await child('git', ['diff', '--quiet', '--'], { cwd: workspace, timeout: 10_000 });
    const tracked = await child('git', ['ls-files', '--stage'], { cwd: workspace, timeout: 10_000 });
    const expectedPaths = [...Object.keys(fixture), 'gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'];
    const actualPaths = tracked.stdout.trim().split('\n').map(line => line.split('\t')[1]);
    if (checked.status !== 0 || tracked.status !== 0 || actualPaths.length !== expectedPaths.length ||
        !expectedPaths.every(path => actualPaths.includes(path))) throw Error('IMPORTED_MODEL_TRACKED_INPUT_MISMATCH');
    return { workspace, selection, snapshot: input.join('\n'), tracked: tracked.stdout };
  };
  const [first, second] = await Promise.all(worlds.map(read));
  if (first.tracked !== second.tracked || first.snapshot !== second.snapshot) throw Error('IMPORTED_MODEL_WORLDS_DIFFER');
  return { cases: { [worlds[0]]: first, [worlds[1]]: second }, sourceSha256: digest(first.tracked) };
}

export async function qualifyWorlds(root, kast) {
  const replies = await Promise.all(worlds.map(world => child(kast, queryArgs, {
    cwd: workspacePath(root, world), timeout: 1_020_000,
    env: { ...runtimeEnvironment(), KAST_WORKSPACE_DEPENDENCY_SHARING: 'SHARED' },
  })));
  return Object.fromEntries(replies.map((reply, index) => {
    const world = worlds[index];
    const response = reply.stdout ? JSON.parse(reply.stdout) : null;
    if (reply.status !== 0 || completeDeclarationPath(response, workspacePath(root, world)) !== declarationPath(world)) {
      throw Error(`IMPORTED_MODEL_QUERY_UNAVAILABLE:${world}:${response?.type ?? reply.status}`);
    }
    return [world, response];
  }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [action, root] = process.argv.slice(2);
  if (!root || !['--prepare', '--qualify'].includes(action)) throw Error('Usage: node imported-model.mjs --prepare|--qualify /absolute/root');
  if (action === '--prepare') await prepareWorlds(root);
  else {
    await examineWorlds(root);
    console.log(JSON.stringify(await qualifyWorlds(root, await binary(process.env.KAST_EVAL_KAST, 'kast')), null, 2));
  }
}
