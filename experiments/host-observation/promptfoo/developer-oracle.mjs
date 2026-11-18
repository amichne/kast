import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, lstat, open, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, isAbsolute, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual, promisify } from 'node:util';

const repository = fileURLToPath(new URL('../../../', import.meta.url));
const wrapper = ['gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'];
const workspaceState = new Set(['.git', '.gradle', '.idea', '.kotlin', '.kast', 'build']);
const maxFileBytes = 1_048_576;
const maxWorkspaceBytes = 8 * maxFileBytes;
const maxEntries = 256;
const sourceRoot = 'src/main/kotlin/';
const compilerTimeLimit = 60_000;
const executionTimeLimit = 10_000;
const sandbox = '/usr/bin/sandbox-exec';
const run = promisify(execFile);
let installedTools;

class InvalidWorkspace extends Error {}

function fail(reason) {
  return { pass: false, reason };
}

function below(root, path) {
  const name = relative(root, path);
  return name !== '' && name !== '..' && !name.startsWith('..' + sep) && !isAbsolute(name);
}

function relativePath(path) {
  return typeof path === 'string' && !path.includes('\\') && !path.includes('\0') &&
    path.split('/').every(segment => segment && segment !== '.' && segment !== '..');
}

function sourcePath(path) {
  return relativePath(path) && path.startsWith(sourceRoot) && path.endsWith('.kt');
}

function sourceDirectory(path) {
  return relativePath(path) && (path === sourceRoot.slice(0, -1) || path.startsWith(sourceRoot));
}

async function snapshot(workspace, ignoreWorkspaceState) {
  if (!(await lstat(workspace)).isDirectory()) throw new InvalidWorkspace('WORKSPACE_NOT_A_DIRECTORY');
  const files = new Map();
  let bytes = 0;
  let entries = 0;
  async function descend(directory, prefix = '') {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      if (++entries > maxEntries) throw new InvalidWorkspace('WORKSPACE_ENTRY_LIMIT');
      const name = prefix + entry.name;
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        if (prefix || !ignoreWorkspaceState || !workspaceState.has(name)) await descend(path, name + '/');
      } else if (entry.isFile()) {
        const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
        try {
          const stat = await file.stat();
          if (!stat.isFile() || stat.nlink !== 1) throw new InvalidWorkspace('WORKSPACE_SPECIAL_FILE');
          if (stat.size > maxFileBytes || (bytes += stat.size) > maxWorkspaceBytes) {
            throw new InvalidWorkspace('WORKSPACE_SIZE_LIMIT');
          }
          const content = Buffer.alloc(stat.size + 1);
          const { bytesRead } = await file.read(content, 0, content.length, 0);
          if (bytesRead !== stat.size) throw Error('Workspace files changed during capture');
          files.set(name, content.subarray(0, bytesRead));
        } finally {
          await file.close();
        }
      } else {
        throw new InvalidWorkspace('WORKSPACE_SPECIAL_FILE');
      }
    }
  }
  await descend(workspace);
  return files;
}

async function expectedWorkspace(task) {
  const files = await snapshot(task.fixture, false);
  for (const name of wrapper) {
    if (files.has(name)) throw Error('Developer fixture must use the repository Gradle wrapper');
    files.set(name, await readFile(join(repository, name)));
  }
  return files;
}

function workspaceDeclaration(task, baseline) {
  const editable = task.allowedChangedPaths;
  const additionDirectories = task.allowedNewSourceDirs ?? [];
  if (!Array.isArray(editable) || editable.some(name => !sourcePath(name) || !baseline.has(name)) ||
      new Set(editable).size !== editable.length) throw Error('Invalid developer fixture edit paths');
  if (!Array.isArray(additionDirectories) || additionDirectories.some(name => !sourceDirectory(name)) ||
      new Set(additionDirectories).size !== additionDirectories.length) {
    throw Error('Invalid developer source addition directories');
  }
  if (task.kind === 'trace') {
    if (editable.length || additionDirectories.length || !task.oracle.expectedTrace || !task.oracle.witness) {
      throw Error('Invalid developer trace oracle');
    }
  } else if (task.kind !== 'edit') {
    throw Error('Unknown developer fixture kind');
  }
  const oracle = task.kind === 'trace' ? task.oracle.witness : task.oracle;
  const sources = oracle.candidateSources;
  if (!Array.isArray(sources) || !sources.length || sources.some(name => !sourcePath(name) || !baseline.has(name)) ||
      new Set(sources).size !== sources.length ||
      editable.some(name => !sources.includes(name)) ||
      [...baseline.keys()].some(name => sourcePath(name) && !sources.includes(name)) ||
      !isAbsolute(oracle.main) || !/^\w+(?:\.\w+)+$/.test(oracle.mainClass) ||
      typeof oracle.expectedStdout !== 'string' || !oracle.expectedStdout) {
    throw Error('Invalid developer Kotlin oracle declaration');
  }
  return { sources, editable, additionDirectories };
}

async function writeTree(root, files) {
  for (const [name, content] of files) {
    const path = join(root, name);
    await mkdir(dirname(path), { recursive: true, mode: 0o700 });
    await writeFile(path, content, { flag: 'wx', mode: name === 'gradlew' ? 0o700 : 0o600 });
  }
}

export async function prepareWorkspace(task, workspace) {
  const files = await expectedWorkspace(task);
  workspaceDeclaration(task, files);
  await mkdir(workspace, { recursive: true, mode: 0o700 });
  if (!(await lstat(workspace)).isDirectory() || (await readdir(workspace)).length) {
    throw Error('Developer workspace must be a vacant directory');
  }
  await writeTree(workspace, files);
  await run('git', ['init', '--quiet', '--template='], {
    cwd: workspace, env: trustedEnvironment(), timeout: 10_000,
  });
  return realpath(workspace);
}

function trustedEnvironment() {
  return {
    PATH: (process.env.PATH ?? '/usr/bin:/bin').split(delimiter).filter(isAbsolute).join(delimiter),
    ...(process.env.HOME && { HOME: process.env.HOME }),
    LANG: 'C',
  };
}

function configuredTool(value, fallback) {
  if (value !== undefined && !isAbsolute(value)) throw Error(`Developer oracle needs an absolute ${fallback} executable path`);
  return value ?? fallback;
}

function sandboxProfile(java, directory, scratch) {
  const literal = path => JSON.stringify(path);
  return [
    '(version 1)',
    '(deny default)',
    '(import "system.sb")',
    '(allow file-read-metadata)',
    '(deny network*)',
    '(deny process-fork)',
    `(allow process-exec (literal ${literal(java)}))`,
    `(allow file-read* (subpath ${literal(dirname(dirname(java)))}) (subpath ${literal(directory)}))`,
    `(allow file-write* (subpath ${literal(scratch)}))`,
  ].join('\n');
}

async function confined(java, directory, scratch, args) {
  return run(sandbox, ['-p', sandboxProfile(java, directory, scratch), java,
    '-XX:-UsePerfData', '-Xmx128m', `-Djava.io.tmpdir=${scratch}`, `-Duser.home=${scratch}`, ...args], {
    cwd: scratch,
    env: { HOME: scratch, TMPDIR: scratch, LANG: 'C' },
    timeout: executionTimeLimit,
    killSignal: 'SIGKILL',
    maxBuffer: 65_536,
  });
}

async function examineTools() {
  if (process.platform !== 'darwin') throw Error('Developer Kotlin execution needs the macOS sandbox');
  const env = trustedEnvironment();
  const javaCommand = configuredTool(process.env.KAST_EVAL_JAVA, 'java');
  const compiler = configuredTool(process.env.KAST_EVAL_KOTLINC, 'kotlinc');
  const options = { cwd: repository, env, timeout: compilerTimeLimit, killSignal: 'SIGKILL', maxBuffer: 65_536 };
  let java;
  try {
    const version = await run(javaCommand, ['-XshowSettings:properties', '-version'], options);
    const javaHome = /^\s*java\.home = (.+)$/m.exec(version.stderr)?.[1];
    if (!javaHome || !isAbsolute(javaHome)) throw Error('Java home is unavailable');
    java = await realpath(join(javaHome, 'bin/java'));
    env.JAVA_HOME = javaHome;
    await run(compiler, ['-version'], options);
  } catch (error) {
    throw Error('Developer Kotlin compiler or Java runtime is unavailable', { cause: error });
  }

  const scratch = await realpath(await mkdtemp(join(tmpdir(), 'kast-developer-runtime-')));
  try {
    await confined(java, scratch, scratch, ['-version']);
  } catch (error) {
    throw Error('Developer Kotlin runtime sandbox is unavailable', { cause: error });
  } finally {
    await rm(scratch, { recursive: true, force: true });
  }
  return { java, compiler, env };
}

function tools() {
  installedTools ??= examineTools();
  return installedTools;
}

export async function preflightOracle() {
  await tools();
}

function differ(original, candidate) {
  return original === undefined ? candidate !== undefined : !candidate?.equals(original);
}

function forbiddenChange(expected, actual, editable) {
  return [...new Set([...expected.keys(), ...actual.keys()])]
    .some(name => !editable.includes(name) && differ(expected.get(name), actual.get(name)));
}

async function candidatePatch(expected, files, editable) {
  const changes = editable.filter(name => differ(expected.get(name), files.get(name))).toSorted();
  if (!changes.length) return '';
  const directory = await mkdtemp(join(tmpdir(), 'kast-developer-patch-'));
  const options = {
    cwd: directory,
    env: { ...trustedEnvironment(), GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null' },
    timeout: 10_000,
    maxBuffer: 4 * maxWorkspaceBytes,
  };
  try {
    await run('git', ['init', '--quiet', '--template='], options);
    await writeTree(directory, changes.filter(name => expected.has(name)).map(name => [name, expected.get(name)]));
    await run('git', ['add', '--all', '--'], options);
    for (const name of changes) {
      if (expected.has(name)) await rm(join(directory, name));
    }
    await writeTree(directory, changes.filter(name => files.has(name)).map(name => [name, files.get(name)]));
    const added = changes.filter(name => !expected.has(name));
    if (added.length) await run('git', ['add', '--intent-to-add', '--', ...added], options);
    const diff = await run('git', [
      'diff', '--binary', '--no-ext-diff', '--no-textconv', '--no-color',
      '--src-prefix=a/', '--dst-prefix=b/', '--', ...changes,
    ], options);
    return diff.stdout;
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

async function gradeKotlin(oracle, fixture, workspace, files, sources) {
  if (sources.some(name => !files.has(name))) return fail('CANDIDATE_SOURCE_MISSING');
  const { java, compiler, env } = await tools();
  const main = await realpath(oracle.main);
  if (below(workspace, main) || below(await realpath(fixture), main) ||
      !main.endsWith('.kt') || !(await lstat(main)).isFile()) {
    throw Error('Developer Kotlin oracle must be outside the participant fixture');
  }
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'kast-developer-oracle-')));
  try {
    const staging = join(directory, 'candidate');
    const scratch = join(directory, 'runtime');
    const output = join(directory, 'oracle.jar');
    await mkdir(scratch, { mode: 0o700 });
    await writeTree(staging, sources.map(name => [name, files.get(name)]));
    try {
      await run(compiler, [...sources.map(name => join(staging, name)), main, '-include-runtime', '-d', output], {
        cwd: repository, env, timeout: compilerTimeLimit, killSignal: 'SIGKILL', maxBuffer: 65_536,
      });
    } catch (error) {
      if (error.code === 1) return fail('CANDIDATE_COMPILATION_FAILED');
      throw Error('Developer Kotlin compiler did not complete', { cause: error });
    }
    try {
      const execution = await confined(java, directory, scratch, ['-cp', output, oracle.mainClass]);
      if (execution.stdout.trim() !== oracle.expectedStdout) return fail('BEHAVIOR_WITNESS_MISMATCH');
      return { pass: true, reason: 'COMPILED_BEHAVIOR_MATCHED' };
    } catch (error) {
      if (error.killed || error.code === 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER') return fail('CANDIDATE_EXECUTION_LIMIT');
      if (typeof error.code === 'number') return fail('CANDIDATE_BEHAVIOR_FAILED');
      throw Error('Developer Kotlin oracle process did not complete', { cause: error });
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

export async function gradeWorkspace(task, workspace, answer) {
  const expected = await expectedWorkspace(task);
  const { sources, editable, additionDirectories } = workspaceDeclaration(task, expected);
  let files;
  try {
    files = await snapshot(workspace, true);
  } catch (error) {
    if (error instanceof InvalidWorkspace) return { ...fail(error.message), patch: '' };
    throw error;
  }
  const added = [...files.keys()].filter(name => !expected.has(name) && sourcePath(name) &&
    additionDirectories.includes(dirname(name))).toSorted();
  const allowed = [...editable, ...added];
  const patch = await candidatePatch(expected, files, allowed);
  if (forbiddenChange(expected, files, allowed)) {
    return { ...fail('UNDECLARED_FILE_CHANGE'), patch };
  }
  const candidate = await realpath(workspace);
  if (task.kind === 'edit') {
    const verdict = await gradeKotlin(task.oracle, task.fixture, candidate, files, [...sources, ...added]);
    return { ...verdict, patch };
  }
  if (typeof answer !== 'string' || Buffer.byteLength(answer) > 16_384) {
    return { ...fail('TRACE_ANSWER_UNAVAILABLE'), patch };
  }
  let trace;
  try {
    trace = JSON.parse(answer);
  } catch {
    return { ...fail('TRACE_ANSWER_NOT_JSON'), patch };
  }
  if (!isDeepStrictEqual(trace, task.oracle.expectedTrace)) return { ...fail('TRACE_MISMATCH'), patch };
  const witness = await gradeKotlin(task.oracle.witness, task.fixture, candidate, files, sources);
  if (!witness.pass) throw Error(`Trusted dispatch witness failed (${witness.reason})`);
  return { pass: true, reason: 'EXACT_TRACE_MATCHED', patch };
}
