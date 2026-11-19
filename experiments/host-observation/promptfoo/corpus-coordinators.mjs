import { lstat, mkdir, mkdtemp, readFile, realpath, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { casesForSuite, delegateModels, suites } from './corpus-cases.mjs';
import { child, runIsolatedPi } from './skill-provider.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';
import { projectPiTrial } from './pi-accounting.mjs';
import { corpusBaseline } from './corpus-source.mjs';
import { exactAnswer } from './corpus-grading.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
const extension = fileURLToPath(new URL('./corpus-coordinator-tool.ts', import.meta.url));
const deadline = 20 * 60_000;
const coordinatorTool = 'run_corpus_evaluation';
const runtimeOptions = ['KAST_CORPUS_ROOT', 'KAST_CORPUS_SKILL', 'KAST_EVAL_PI_AUTH', 'KAST_EVAL_CODEX_AUTH',
  'KAST_GRADLE_JAVA_HOME', 'KAST_EVAL_KAST', 'KAST_EVAL_PI', 'KAST_EVAL_PI_MODELS', 'KAST_EVAL_PORTFOLIO'];

export function corpusRuntimeOptions(source = process.env) {
  return Object.fromEntries(runtimeOptions.filter(key => source[key] !== undefined).map(key => [key, source[key]]));
}

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

export const corpusTrialKey = vars => JSON.stringify([vars?.caseId, vars?.arm, vars?.model]);

export function plannedCorpusTrials(document, workspace) {
  const tasks = casesForSuite(workspace);
  const tests = document?.config?.tests;
  const configured = Array.isArray(tests) ? tests.filter(test => {
    const vars = test?.vars;
    const inferred = suites.toSorted((a, b) => b.length - a.length).find(suite =>
      typeof vars?.caseId === 'string' && vars.caseId.startsWith(suite + '-'));
    return (vars?.suite ?? inferred) === workspace;
  }) : undefined;
  if (!Array.isArray(configured) || !configured.length) return { state: 'invalid', reason: 'MISSING_TEST_PLAN' };
  const trials = [], keys = new Set();
  for (const { vars } of configured) {
    const task = tasks.find(task => task.id === vars?.caseId);
    const key = corpusTrialKey(vars);
    if (!task || !delegateModels.includes(vars.model) || !['source', 'kast'].includes(vars.arm) || keys.has(key) ||
        (vars.task !== undefined && vars.task !== task.task) || (vars.revision !== undefined && vars.revision !== task.revision)) {
      return { state: 'invalid', reason: 'NONCANONICAL_TEST_PLAN' };
    }
    keys.add(key);
    trials.push({ task, caseId: task.id, arm: vars.arm, model: vars.model });
  }
  if (trials.some(trial => !keys.has(corpusTrialKey({ ...trial, arm: trial.arm === 'source' ? 'kast' : 'source' })))) {
    return { state: 'invalid', reason: 'UNPAIRED_TEST_PLAN' };
  }
  return { state: 'ready', trials };
}

export function assessCorpusRow(row, task) {
  if (!row?.response || row.response.error || row.response.cached || typeof row.response.output !== 'string' ||
      typeof row.success !== 'boolean' || typeof row.gradingResult?.pass !== 'boolean' ||
      ![0, 1].includes(row.failureReason)) return { outcome: 'unassessed', reason: 'MISSING_ASSESSMENT' };
  if (row.prompt?.raw !== task.task) return { outcome: 'unassessed', reason: 'MISSING_CANONICAL_PROMPT' };
  const correct = exactAnswer(row.response.output, task.expected);
  if (row.success !== correct) return { outcome: 'unassessed', reason: 'ANSWER_GRADE_MISMATCH' };
  if (row.gradingResult.pass !== correct || row.failureReason !== (correct ? 0 : 1) ||
      (Object.hasOwn(row.namedScores ?? {}, 'correctness') && row.namedScores.correctness !== Number(correct))) {
    return { outcome: 'unassessed', reason: 'INCOHERENT_ASSESSMENT' };
  }
  return { outcome: correct ? 'correct' : 'incorrect' };
}

export function assessCorpusExport(document, workspace, exitCode) {
  const result = document?.results;
  const stats = result?.stats;
  const counts = [stats?.successes, stats?.failures, stats?.errors];
  const rows = Array.isArray(result?.results) ? result.results : [];
  const plan = plannedCorpusTrials(document, workspace);
  if (plan.state !== 'ready') return { status: 'INFRASTRUCTURE_FAILURE', reason: plan.reason, exitCode };
  const planned = new Set(plan.trials.map(corpusTrialKey));
  const observed = new Set(rows.map(row => corpusTrialKey(row?.vars)));
  if (planned.size !== rows?.length ||
      observed.size !== planned.size || [...observed].some(item => !planned.has(item)) ||
      !counts.every(count => Number.isSafeInteger(count) && count >= 0) ||
      counts.reduce((sum, count) => sum + count, 0) !== rows.length) {
    return { status: 'INFRASTRUCTURE_FAILURE', reason: 'INCOMPLETE_EXPORT', exitCode };
  }
  const outcomes = { passed: stats.successes, failed: stats.failures, errors: stats.errors, exitCode };
  if (stats.errors || rows.some(row => row.response?.error || row.response?.cached || row.failureReason === 2)) {
    return { status: 'INFRASTRUCTURE_FAILURE', reason: 'UNASSESSED_TRIAL', ...outcomes };
  }
  const tasks = new Map(plan.trials.map(trial => [corpusTrialKey(trial), trial.task]));
  const assessed = rows.map(row => assessCorpusRow(row, tasks.get(corpusTrialKey(row.vars))));
  if (assessed.some(row => row.outcome === 'unassessed')) {
    return { status: 'INFRASTRUCTURE_FAILURE', reason: 'UNASSESSED_TRIAL', ...outcomes };
  }
  if (stats.successes !== assessed.filter(row => row.outcome === 'correct').length ||
      stats.failures !== assessed.filter(row => row.outcome === 'incorrect').length) {
    return { status: 'INFRASTRUCTURE_FAILURE', reason: 'INCONSISTENT_ASSESSMENT_COUNTS', ...outcomes };
  }
  if (exitCode === 0 && stats.failures === 0) return { status: 'PASSED', ...outcomes };
  if (exitCode === 100 && stats.failures > 0) return { status: 'ASSESSED_WRONG_ANSWERS', ...outcomes };
  return { status: 'INFRASTRUCTURE_FAILURE', reason: 'UNEXPECTED_EXIT', ...outcomes };
}

export async function dispatchCorpus(workspace, outputDirectory, configDirectory) {
  profileFor(workspace);
  const output = join(outputDirectory, 'results.json');
  const env = { ...runtimeEnvironment(),
    ...corpusRuntimeOptions(),
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

export async function runCoordinator(workspace, outputPath, { environment = process.env, runPi = runIsolatedPi,
  cleanup = rm, clock = () => performance.now() } = {}) {
  const started = clock();
  const profile = profileFor(workspace);
  let cleanupStatus = 'NOT_RUN', retainedDirectory;
  const receipt = trial => {
    const projected = projectPiTrial(trial);
    return { ...projected, role: 'coordinator', profile,
      ...(trial?.error && { error: trial.error }), ...(trial?.terminalCause && { terminalCause: trial.terminalCause }),
      ...(trial?.termination && { termination: trial.termination }),
      metadata: { ...projected.metadata,
        cleanup: cleanupStatus, ...(retainedDirectory && { retainedDirectory }),
        costScope: { participant: 'coordinator-overhead', helper: 'dispatch-accounted-in-participant-exports' },
        timing: { clock: 'MONOTONIC', scope: 'coordinator-through-cleanup-before-receipt-persistence', wallMillis: clock() - started } } };
  };
  if (environment.KAST_EVAL_PORTFOLIO === 'curated' && !corpusBaseline.panelA.admitted) {
    return { ...receipt(undefined), coordinator: 'BLOCKED', baseline: corpusBaseline,
      evaluation: { status: 'INFRASTRUCTURE_FAILURE', reason: 'NATURAL_BASELINE_UNQUALIFIED' }, receipt: null, export: null };
  }
  const [corpusRoot, skill, outputRoot] = await Promise.all([
    absolutePath(environment.KAST_CORPUS_ROOT, 'KAST_CORPUS_ROOT', entry => entry.isDirectory()),
    absolutePath(environment.KAST_CORPUS_SKILL, 'KAST_CORPUS_SKILL', entry => entry.isFile()),
    absolutePath(outputPath, 'Output directory', entry => entry.isDirectory()),
  ]);
  if (contains(corpusRoot, outputRoot)) throw Error('Output directory must be outside the corpora');
  const outputDirectory = join(outputRoot, workspace);
  if (contains(corpusRoot, outputDirectory)) throw Error('Workspace result directory must be outside the corpora');
  await mkdir(outputDirectory, { mode: 0o700 });
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'kast-corpus-coordinator-')));
  const receiptPath = join(outputDirectory, 'receipt.json');
  const output = join(outputDirectory, 'results.json');
  let trial, participantSettled = true;
  try {
    trial = await runPi({
      directory, model: profile.model, effort: profile.effort, extension, captureLimits: true, role: 'coordinator',
      toolNames: [coordinatorTool], timeout: deadline + 60_000,
      system: 'You coordinate a frozen repository evaluation. Use the supplied tool once. Report the tool outcome without inferring individual answers.',
      prompt: `Run the frozen Promptfoo evaluation for ${workspace}.`,
      extensionEnvironment: {
        ...corpusRuntimeOptions(environment),
        KAST_CORPUS_ROOT: corpusRoot, KAST_CORPUS_SKILL: skill,
        KAST_CORPUS_COORDINATOR_WORKSPACE: workspace,
        KAST_CORPUS_COORDINATOR_OUTPUT: outputDirectory,
        KAST_CORPUS_COORDINATOR_CONFIG: join(directory, 'promptfoo'),
      },
    });
    participantSettled = trial.terminalCause !== 'PI_CHILD_REAP_UNAVAILABLE' && trial.error !== 'PI_CHILD_REAP_UNAVAILABLE';
  } catch (error) {
    trial = { ...error.trial, error: error.code ?? error.message,
      ...(error.code && { terminalCause: error.code }) };
    participantSettled = error.code !== 'PI_CHILD_REAP_UNAVAILABLE';
  } finally {
    if (!participantSettled) {
      cleanupStatus = 'WITHHELD_UNCONFIRMED_CHILD_SETTLEMENT';
      retainedDirectory = directory;
    } else {
      try {
        await cleanup(directory, { recursive: true, force: true });
        cleanupStatus = 'COMPLETED';
      } catch {
        cleanupStatus = 'FAILED';
        retainedDirectory = directory;
        trial = { ...trial, error: trial?.error ?? 'PI_COORDINATOR_CLEANUP_FAILED' };
      }
    }
  }
  const coordinator = trial.error || trial.termination || trial.toolCalls !== 1 ? 'FAILED' : 'COMPLETED';
  let evaluation;
  try {
    evaluation = JSON.parse(await readFile(receiptPath, 'utf8'));
  } catch {
    evaluation = { status: 'INFRASTRUCTURE_FAILURE', reason: 'MISSING_DISPATCH_RECEIPT' };
  }
  const exportExists = await lstat(output).then(entry => entry.isFile(), () => false);
  const result = { ...receipt(trial), coordinator, evaluation, receipt: receiptPath, export: exportExists ? output : null };
  await writeFile(join(outputDirectory, 'coordinator.json'), JSON.stringify(result) + '\n',
    { flag: 'wx', mode: 0o600 });
  return result;
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
