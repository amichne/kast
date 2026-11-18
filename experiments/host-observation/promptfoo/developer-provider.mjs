import { createHash } from 'node:crypto';
import { mkdtemp, realpath, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadKastGuidance } from './kast-guidance.mjs';
import { compileWorkspace } from './developer-build.mjs';
import { workspaceSourceIdentity } from './developer-workspace.mjs';
import { binary, child, runIsolatedPi } from './skill-provider.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';

export const developerArms = Object.freeze(['source', 'kast']);
const extension = fileURLToPath(new URL('./developer-tool.ts', import.meta.url));
const commonTools = Object.freeze(['list_files', 'read_file', 'search_source', 'replace_text', 'create_file', 'compile_project']);
const system = 'You are a Kotlin developer working in an isolated project. Use the provided tools to do the requested work. For edit tasks, change the files before you reply.';

async function selectedGradleHome() {
  const path = process.env.KAST_DEV_EVAL_GRADLE_HOME;
  if (path === undefined) return undefined;
  if (!isAbsolute(path)) throw Error('ABSOLUTE_GRADLE_HOME_REQUIRED');
  return realpath(path);
}

async function proveHost(kast, workspace, gradleHome, task) {
  const { name, kind } = task.warmup;
  const result = await child(kast, ['query', '--name', name, '--kind', kind, '--source-set', 'main', '--fields', 'name,location', '--json'], {
    cwd: workspace, timeout: 900_000,
    env: { ...runtimeEnvironment(), GRADLE_USER_HOME: gradleHome, KAST_WORKSPACE_DEPENDENCY_SHARING: 'SHARED' },
  });
  const reply = JSON.parse(result.stdout);
  const doc = reply?.document;
  return result.status === 0 && reply?.type === 'complete' && doc?.coverage?.exhaustive === true &&
    doc.original_evidence_summary?.failure_count === 0 && doc.original_evidence_summary?.omission_count === 0 &&
    doc.items?.length === 1 && doc.items[0].name === name;
}

async function stopHost(kast, workspace) {
  const stopped = await child(kast, ['stop', '--workspace', workspace, '--json'], { cwd: workspace, timeout: 60_000 });
  const outcome = JSON.parse(stopped.stdout);
  if (stopped.status === 0) return outcome?.type === 'STOPPED';
  if (outcome?.type !== 'REJECTED' || outcome.failure !== 'NOT_FOUND') return false;
  const status = await child(kast, ['status', '--all', '--json'], { cwd: workspace, timeout: 10_000 });
  const store = JSON.parse(status.stdout)?.workspaces;
  return status.status === 0 && store?.state === 'VERIFIED' && Array.isArray(store.value) &&
    !store.value.some(entry => entry.workspace === workspace);
}

function trialReceipt(trial) {
  const usage = trial.usage;
  const incomplete = trial.termination === 'PARTICIPANT_DEADLINE' || trial.termination === 'PARTICIPANT_OUTPUT_LIMIT';
  const toolCalls = trial.toolProposals ?? trial.toolCalls;
  return {
    ...(usage && usage.total > 0 && { tokenUsage: { total: usage.total, prompt: usage.input + usage.cacheRead + usage.cacheWrite,
      completion: usage.output, cached: usage.cacheRead } }),
    metadata: {
      ...(incomplete ? { observedToolCalls: toolCalls } : { toolCalls }), toolExecutions: trial.toolCalls,
      kastCalls: trial.calls.filter(call => call.tool === 'kast').length,
      systemSha256: trial.systemSha256,
      guidanceReads: trial.calls.filter(call => call.tool === 'read_kast_guidance').map(call => call.path),
      completeKastCalls: trial.calls.filter(call => call.tool === 'kast' && call.complete).length,
      providerResponses: trial.providerResponses, elapsedMillis: trial.elapsedMillis, usage: usage ?? null,
      termination: trial.termination ?? (trial.error ? 'PARTICIPANT_INFRASTRUCTURE_ERROR' : 'COMPLETED'),
      ...(trial.partialTranscript && { partialTranscript: trial.partialTranscript, finalizedUsage: trial.finalizedUsage ?? null }),
      toolResourceLimits: trial.calls.filter(call => ['KAST_TIME_LIMIT', 'KAST_OUTPUT_LIMIT', 'COMPILER_TIME_LIMIT',
        'COMPILER_OUTPUT_LIMIT', 'WORKSPACE_FILE_LIMIT'].includes(call.reason)).length,
    },
  };
}

export function createDeveloperProvider({ cases, prepareWorkspace, gradeWorkspace, preflightOracle }, effects = {}) {
  const byId = new Map(cases.map(task => [task.id, task]));
  const model = process.env.KAST_EVAL_PI_MODEL ?? 'openai-codex/gpt-6.1-sol';
  const effort = process.env.KAST_EVAL_PI_EFFORT ?? 'low';
  const { getGradleHome = selectedGradleHome, compile = compileWorkspace,
    compiler = process.env.KAST_EVAL_KOTLINC ?? 'kotlinc',
    getKast = () => binary(process.env.KAST_EVAL_KAST, 'kast'), warmup = proveHost,
    runPi = runIsolatedPi, stop = stopHost } = effects;
  let interrupted = false;
  return {
    id: () => 'kast:developer-tasks',
    handlesOwnRetries: true,
    async callApi(prompt, context) {
      if (interrupted) return { error: 'DEVELOPER_EVALUATION_INTERRUPTED' };
      const { caseId, arm } = context?.vars ?? {};
      const task = byId.get(caseId);
      if (!task || !developerArms.includes(arm) || prompt !== task.prompt) return { error: 'DEVELOPER_CASE_REJECTED' };
      let directory, workspace, kast, hostAttempted = false, participantSettled = true;
      let stage = 'WORKSPACE_PREPARATION';
      let observation = {};
      const metadata = { model, effort, hostAvailable: null,
        taskSha256: createHash('sha256').update(prompt).digest('hex') };
      try {
        directory = await realpath(await mkdtemp(join(tmpdir(), 'kast-developer-trial-')));
        workspace = join(directory, 'workspace');
        await prepareWorkspace(task, workspace);
        metadata.sourceSha256 = workspaceSourceIdentity(workspace);
        stage = 'INDEPENDENT_ORACLE_PREFLIGHT';
        await preflightOracle();
        stage = 'PUBLIC_COMPILER_PREFLIGHT';
        if ((await compile(workspace, compiler)).type !== 'COMPILED') throw Error(stage);
        const extensionEnvironment = {
          KAST_DEV_EVAL_WORKSPACE: workspace, KAST_DEV_EVAL_KOTLINC: compiler, KAST_DEV_EVAL_KAST: '', GRADLE_USER_HOME: undefined,
        };
        if (arm === 'kast') {
          stage = 'HOST_PREFLIGHT';
          metadata.hostAvailable = false;
          kast = await getKast();
          const gradleHome = await getGradleHome();
          hostAttempted = true;
          metadata.hostAvailable = await warmup(kast, workspace, gradleHome, task);
          if (metadata.hostAvailable !== true) throw Error(stage);
          Object.assign(extensionEnvironment, { GRADLE_USER_HOME: gradleHome, KAST_DEV_EVAL_KAST: kast });
        }
        stage = 'MATCHED_WORKSPACE_PREFLIGHT';
        if (workspaceSourceIdentity(workspace) !== metadata.sourceSha256) throw Error(stage);
        stage = 'PARTICIPANT_EXECUTION';
        const guidance = arm === 'kast' ? loadKastGuidance() : undefined;
        const trial = await runPi({
          directory, prompt, system, guidance, model, effort, extension, timeout: 600_000, captureLimits: true,
          toolNames: arm === 'kast' ? [...commonTools, 'kast'] : [...commonTools],
          extensionEnvironment,
        });
        observation = trialReceipt(trial);
        if (trial.error) throw Error(stage);
        if (guidance) {
          stage = 'GUIDANCE_DELIVERY';
          if (trial.guidance?.skillSha256 !== guidance.skillSha256 ||
              trial.guidance?.guideSha256 !== guidance.guideSha256) throw Error(stage);
          metadata.skillSha256 = trial.guidance.skillSha256;
          metadata.guideSha256 = trial.guidance.guideSha256;
        }
        if (trial.calls.some(call => call.reason === 'KAST_EFFECT_UNAVAILABLE' || call.reason === 'COMPILER_UNAVAILABLE')) {
          stage = 'PARTICIPANT_TOOL_UNAVAILABLE';
          throw Error(stage);
        }
        stage = 'INDEPENDENT_GRADING';
        const grade = await gradeWorkspace(task, workspace, trial.reply);
        if (typeof grade?.pass !== 'boolean' || typeof grade.reason !== 'string' || !grade.reason) throw Error(stage);
        observation.output = trial.reply ?? '';
        metadata.grade = { pass: grade.pass, reason: grade.reason };
        if (typeof grade.patch === 'string') metadata.patch = grade.patch;
      } catch (error) {
        observation.error = `DEVELOPER_${stage}_FAILED`;
        interrupted ||= error?.code === 'PI_OWNER_INTERRUPTED';
        participantSettled = error?.code !== 'PI_CHILD_REAP_UNAVAILABLE';
      } finally {
        try {
          if (!participantSettled) throw Error('PARTICIPANT_REAP_UNAVAILABLE');
          if (hostAttempted && !await stop(kast, workspace)) throw Error('HOST_STOP_UNAVAILABLE');
          if (directory) await rm(directory, { recursive: true, force: true });
        } catch {
          metadata.cleanup = 'DEVELOPER_TRIAL_CLEANUP_FAILED';
          observation.error ??= metadata.cleanup;
        }
      }
      return { ...observation, metadata: { ...metadata, ...observation.metadata } };
    },
  };
}

export function gradeDeveloperResponse(response) {
  const grade = response?.metadata?.grade;
  const pass = !response?.error && grade?.pass === true;
  return { pass, score: Number(pass), reason: response?.error ?? grade?.reason ?? 'DEVELOPER_GRADE_UNAVAILABLE' };
}
