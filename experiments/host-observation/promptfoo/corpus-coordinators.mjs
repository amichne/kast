import { lstat, mkdir, mkdtemp, readFile, realpath, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { suites } from './corpus-cases.mjs';
import { child, runIsolatedPi } from './skill-provider.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
const extension = fileURLToPath(new URL('./corpus-coordinator-tool.ts', import.meta.url));
const deadline = 20 * 60_000;
const coordinatorTool = 'run_corpus_evaluation';
const runtimeOptions = ['KAST_CORPUS_ROOT', 'KAST_CORPUS_SKILL', 'KAST_EVAL_PI_AUTH', 'KAST_EVAL_CODEX_AUTH', 'KAST_GRADLE_JAVA_HOME'];

export const corpusProfiles = Object.freeze(Object.fromEntries(suites.map(workspace => [workspace,
  Object.freeze({ workspace, model: 'openai-codex/gpt-6.2-sol', effort: 'high' }),
])));

function profileFor(workspace) {
  if (!Object.hasOwn(corpusProfiles, workspace)) throw Error('Unknown corpus workspace');
  return corpusProfiles[workspace];
}

function contains(parent, candidate) {
  const path = relative(parent, candidate);
  return !isAbsolute(path) && path !== '..' && !path.startsWith(`..${sep}`);
}

export function assessCorpusExport(document, workspace, exitCode) {
  const result = document?.results;
  const stats = result?.stats;
  const counts = [stats?.successes, stats?.failures, stats?.errors];
  const rows = result?.results;
  const key = vars => JSON.stringify([vars?.caseId, vars?.arm, vars?.model]);
  const configured = document?.config?.tests?.filter(test => (test.vars?.suite ?? test.vars?.caseId?.split('-')[0]) === workspace);
  const planned = new Set(configured?.map(test => key(test.vars)));
  const observed = new Set(rows?.map(row => key(row.vars)));
  if (!planned.size || planned.size !== configured.length || planned.size !== rows?.length ||
      observed.size !== planned.size || [...observed].some(item => !planned.has(item)) ||
      !counts.every(count => Number.isSafeInteger(count) && count >= 0) ||
      counts.reduce((sum, count) => sum + count, 0) !== rows.length) {
    return { status: 'INFRASTRUCTURE_FAILURE', reason: 'INCOMPLETE_EXPORT', exitCode };
  }
  const outcomes = { passed: stats.successes, failed: stats.failures, errors: stats.errors, exitCode };
  if (stats.errors || rows.some(row => row.response?.error || row.response?.cached || row.failureReason === 2)) {
    return { status: 'INFRASTRUCTURE_FAILURE', reason: 'UNASSESSED_TRIAL', ...outcomes };
  }
  if (exitCode === 0 && stats.failures === 0) return { status: 'PASSED', ...outcomes };
  if (exitCode === 100 && stats.failures > 0) return { status: 'ASSESSED_WRONG_ANSWERS', ...outcomes };
  return { status: 'INFRASTRUCTURE_FAILURE', reason: 'UNEXPECTED_EXIT', ...outcomes };
}

export async function dispatchCorpus(workspace, outputDirectory, configDirectory) {
  profileFor(workspace);
  const output = join(outputDirectory, 'results.json');
  const env = { ...runtimeEnvironment(),
    ...Object.fromEntries(runtimeOptions.filter(key => process.env[key] !== undefined).map(key => [key, process.env[key]])),
    PROMPTFOO_CONFIG_DIR: configDirectory, PROMPTFOO_DISABLE_TELEMETRY: '1',
    PROMPTFOO_MAX_EVAL_TIME_MS: String(deadline - 15_000),
  };
  let result;
  try {
    result = await child('npm', ['run', 'eval', '--', '-c', 'corpus.config.mjs', '--filter-pattern', '^' + workspace + ' / ',
      '-o', output, '--max-concurrency', '2'], { cwd: here, env, timeout: deadline, captureLimits: true });
  } catch {
    return { status: 'INFRASTRUCTURE_FAILURE', reason: 'PROMPTFOO_PROCESS_UNAVAILABLE' };
  }
  if (result.termination || ![0, 100].includes(result.status)) {
    return { status: 'INFRASTRUCTURE_FAILURE', reason: result.termination ?? 'UNEXPECTED_EXIT', exitCode: result.status };
  }
  try {
    return assessCorpusExport(JSON.parse(await readFile(output, 'utf8')), workspace, result.status);
  } catch {
    return { status: 'INFRASTRUCTURE_FAILURE', reason: 'INVALID_EXPORT', exitCode: result.status };
  }
}

async function absolutePath(path, label, predicate) {
  if (!path || !isAbsolute(path)) throw Error(`${label} must be an absolute path`);
  const resolved = await realpath(path);
  if (!predicate(await stat(resolved))) throw Error(`${label} has the wrong file type`);
  return resolved;
}

async function runCoordinator(workspace, outputPath) {
  const profile = profileFor(workspace);
  const [corpusRoot, skill, outputRoot] = await Promise.all([
    absolutePath(process.env.KAST_CORPUS_ROOT, 'KAST_CORPUS_ROOT', entry => entry.isDirectory()),
    absolutePath(process.env.KAST_CORPUS_SKILL, 'KAST_CORPUS_SKILL', entry => entry.isFile()),
    absolutePath(outputPath, 'Output directory', entry => entry.isDirectory()),
  ]);
  if (contains(corpusRoot, outputRoot)) throw Error('Output directory must be outside the corpora');
  const outputDirectory = join(outputRoot, workspace);
  if (contains(corpusRoot, outputDirectory)) throw Error('Workspace result directory must be outside the corpora');
  await mkdir(outputDirectory, { mode: 0o700 });
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'kast-corpus-coordinator-')));
  const receiptPath = join(outputDirectory, 'receipt.json');
  const output = join(outputDirectory, 'results.json');
  let trial;
  try {
    trial = await runIsolatedPi({
      directory, model: profile.model, effort: profile.effort, extension, captureLimits: true,
      toolNames: [coordinatorTool], timeout: deadline + 60_000,
      system: 'You coordinate a frozen repository evaluation. Use the supplied tool once. Report the tool outcome without inferring individual answers.',
      prompt: `Run the frozen Promptfoo evaluation for ${workspace}.`,
      extensionEnvironment: {
        KAST_CORPUS_ROOT: corpusRoot, KAST_CORPUS_SKILL: skill,
        ...Object.fromEntries(runtimeOptions.slice(2).filter(key => process.env[key] !== undefined)
          .map(key => [key, resolve(process.env[key])])),
        KAST_CORPUS_COORDINATOR_WORKSPACE: workspace,
        KAST_CORPUS_COORDINATOR_OUTPUT: outputDirectory,
        KAST_CORPUS_COORDINATOR_CONFIG: join(directory, 'promptfoo'),
      },
    });
  } catch {
    trial = { error: true };
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
  const coordinator = trial.error || trial.termination || trial.toolCalls !== 1 ? 'FAILED' : 'COMPLETED';
  let evaluation;
  try {
    evaluation = JSON.parse(await readFile(receiptPath, 'utf8'));
  } catch {
    evaluation = { status: 'INFRASTRUCTURE_FAILURE', reason: 'MISSING_DISPATCH_RECEIPT' };
  }
  const exportExists = await lstat(output).then(entry => entry.isFile(), () => false);
  await writeFile(join(outputDirectory, 'coordinator.json'), JSON.stringify({ profile, coordinator }) + '\n',
    { flag: 'wx', mode: 0o600 });
  return { profile, coordinator, evaluation, receipt: receiptPath, export: exportExists ? output : null };
}

export async function recordCorpusDispatch(workspace, outputDirectory, configDirectory) {
  const outcome = await dispatchCorpus(workspace, outputDirectory, configDirectory);
  await writeFile(join(outputDirectory, 'receipt.json'), JSON.stringify(outcome) + '\n', { flag: 'wx', mode: 0o600 });
  return outcome;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    if (process.argv.length !== 4) throw Error('Usage: node corpus-coordinators.mjs <ktfmt|coroutines|spring|spring-holdout> <absolute output directory>');
    const result = await runCoordinator(process.argv[2], process.argv[3]);
    console.log(JSON.stringify(result));
    process.exitCode = result.coordinator !== 'COMPLETED' || result.evaluation.status === 'INFRASTRUCTURE_FAILURE' ? 1 :
      result.evaluation.exitCode;
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
