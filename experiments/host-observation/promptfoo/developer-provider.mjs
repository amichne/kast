import { createHash } from 'node:crypto';
import { realpath, rm } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadKastGuidance } from './kast-guidance.mjs';
import { compileWorkspace } from './developer-build.mjs';
import { workspaceSourceIdentity } from './developer-workspace.mjs';
import { createParticipantDirectory } from './participant-surface.mjs';
import { binary, child, runIsolatedPi } from './skill-provider.mjs';
import { runtimeEnvironment } from './skill-sandbox.mjs';
import { projectPiTrial } from './pi-accounting.mjs';
import { createTrialTiming } from './trial-phases.mjs';

export const developerArms = Object.freeze(['source', 'kast']);
const extension = fileURLToPath(new URL('./developer-tool.ts', import.meta.url));
const commonTools = Object.freeze(['list_files', 'read_file', 'search_source', 'replace_text', 'create_file', 'compile_project']);
const system = 'You are a Kotlin developer working in an isolated project. Use the provided tools to do the requested work. For edit tasks, change the files before you reply.';
export { system as developerSystem };
const participantMillis = 600_000;
const hostPreflightMillis = 900_000;
const developerBaseline = Object.freeze({
  id: 'structured-kotlin-fixture-v1',
  capabilities: ['source-read', 'literal-search', 'source-edit', 'source-create', 'kotlin-compile'],
  limits: { maximumFiles: 256, maximumFileBytes: 64 * 1024, searchMatches: 24 },
});

async function selectedGradleHome() {
  const path = process.env.KAST_DEV_EVAL_GRADLE_HOME;
  if (path === undefined) return undefined;
  if (!isAbsolute(path)) throw Error('ABSOLUTE_GRADLE_HOME_REQUIRED');
  return realpath(path);
}

async function proveHost(kast, workspace, gradleHome, task) {
  const { name, kind } = task.warmup;
  const result = await child(kast, ['query', '--name', name, '--kind', kind, '--source-set', 'main', '--fields', 'name,location', '--json'], {
    cwd: workspace, timeout: hostPreflightMillis,
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
  const projection = projectPiTrial(trial);
  const incomplete = trial.termination === 'PARTICIPANT_DEADLINE' || trial.termination === 'PARTICIPANT_OUTPUT_LIMIT';
  const toolCalls = trial.toolProposals ?? trial.toolCalls;
  return {
    ...projection,
    metadata: {
      ...projection.metadata,
      ...(incomplete ? { observedToolCalls: toolCalls } : { toolCalls }), toolExecutions: trial.toolCalls,
      kastCalls: trial.calls.filter(call => call.tool === 'kast').length,
      systemSha256: trial.systemSha256,
      guidanceReads: trial.calls.filter(call => call.tool === 'read_kast_guidance').map(call => call.path),
      completeKastCalls: trial.calls.filter(call => call.tool === 'kast' && call.complete).length,
      providerResponses: trial.providerResponses, elapsedMillis: trial.elapsedMillis, usage: usage ?? null,
      termination: trial.termination ?? (trial.error ? 'PARTICIPANT_INFRASTRUCTURE_ERROR' : 'COMPLETED'),
      ...(trial.terminalCause && { terminalCause: trial.terminalCause }),
      ...(trial.partialTranscript && { partialTranscript: trial.partialTranscript }),
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
    runPi = runIsolatedPi, stop = stopHost, cleanup = rm, clock } = effects;
  let interrupted = false;
  return {
    id: () => 'kast:developer-tasks',
    handlesOwnRetries: true,
    async callApi(prompt, context) {
      const timing = createTrialTiming('DEVELOPER_PROVIDER', clock);
      let observation = projectPiTrial(undefined);
      if (interrupted) return { ...observation, error: 'DEVELOPER_EVALUATION_INTERRUPTED',
        metadata: { ...observation.metadata, timing: timing.snapshot() } };
      const { caseId, arm, requestedPanel = 'A' } = context?.vars ?? {};
      const task = byId.get(caseId);
      if (!task || !developerArms.includes(arm) || prompt !== task.prompt || !['A', 'RESTRICTED'].includes(requestedPanel)) {
        return { ...observation, error: 'DEVELOPER_CASE_REJECTED', metadata: { ...observation.metadata, timing: timing.snapshot() } };
      }
      let directory, workspace, kast, hostAttempted = false, participantSettled = true;
      let stage = 'WORKSPACE_PREPARATION';
      const toolNames = arm === 'kast' ? [...commonTools, 'kast'] : [...commonTools];
      const metadata = { caseId, arm, model, effort, hostAvailable: null, requestedPanel, panel: 'RESTRICTED',
        baseline: { ...developerBaseline, qualification: 'UNQUALIFIED' },
        admission: { state: 'BLOCKED', reason: 'PREREQUISITES_NOT_COMPLETED' },
        source: task.source ?? { type: 'REPOSITORY_FIXTURE' },
        permissions: { sourceRead: 'ISOLATED_FIXTURE', sourceWrite: 'KOTLIN_PRODUCTION_EXISTING_DIRECTORIES',
          arbitraryCommands: false, toolNames: arm === 'kast' ? [...toolNames, 'read_kast_guidance'] : toolNames,
          gradedEditPaths: task.allowedChangedPaths ?? [],
          gradedNewSourceDirs: task.allowedNewSourceDirs ?? [] },
        budgets: { participantMillis, hostPreflightMillis },
        taskSha256: createHash('sha256').update(prompt).digest('hex') };
      try {
        await timing.measure('preparation', async () => {
          directory = await createParticipantDirectory();
          workspace = join(directory, 'workspace');
          await prepareWorkspace(task, workspace);
          metadata.sourceSha256 = workspaceSourceIdentity(workspace);
        });
        stage = 'INDEPENDENT_ORACLE_PREFLIGHT';
        await timing.measure('oracle-preflight', () => preflightOracle());
        stage = 'PUBLIC_COMPILER_PREFLIGHT';
        await timing.measure('compiler-preflight', async () => {
          if ((await compile(workspace, compiler)).type !== 'COMPILED') throw Error(stage);
        });
        const extensionEnvironment = {
          KAST_DEV_EVAL_WORKSPACE: workspace, KAST_DEV_EVAL_KOTLINC: compiler, KAST_DEV_EVAL_KAST: '', GRADLE_USER_HOME: undefined,
        };
        if (arm === 'kast') {
          stage = 'HOST_PREFLIGHT';
          metadata.hostAvailable = false;
          await timing.measure('host-preflight', async () => {
            kast = await getKast();
            const gradleHome = await getGradleHome();
            hostAttempted = true;
            metadata.hostAvailable = await warmup(kast, workspace, gradleHome, task);
            if (metadata.hostAvailable !== true) throw Error(stage);
            Object.assign(extensionEnvironment, { GRADLE_USER_HOME: gradleHome, KAST_DEV_EVAL_KAST: kast });
          });
        }
        stage = 'MATCHED_WORKSPACE_PREFLIGHT';
        if (workspaceSourceIdentity(workspace) !== metadata.sourceSha256) throw Error(stage);
        metadata.baseline.qualification = 'TASK_QUALIFIED';
        metadata.admission = { state: 'ADMITTED', reason: 'ISOLATED_FIXTURE_CAPABILITIES_READY' };
        metadata.panel = requestedPanel;
        stage = 'PARTICIPANT_EXECUTION';
        const guidance = arm === 'kast' ? loadKastGuidance() : undefined;
        const trial = await timing.measure('participant', () => runPi({
          directory, prompt, system, guidance, model, effort, extension, timeout: participantMillis, captureLimits: true,
          toolNames, toolUsagePolicy: 'no-model-helpers', extensionEnvironment, visiblePaths: [workspace],
        }), trial => trial.error || trial.termination ? 'FAILED' : 'COMPLETED');
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
        const grade = await timing.measure('grading', async () => {
          const grade = await gradeWorkspace(task, workspace, trial.reply);
          if (typeof grade?.pass !== 'boolean' || typeof grade.reason !== 'string' || !grade.reason) throw Error(stage);
          return grade;
        });
        observation.output = trial.reply ?? '';
        metadata.grade = { pass: grade.pass, reason: grade.reason };
        if (typeof grade.patch === 'string') metadata.patch = grade.patch;
      } catch (error) {
        if (error?.trial) {
          const rejected = projectPiTrial(error.trial);
          observation = { ...observation, ...rejected, metadata: { ...observation.metadata, ...rejected.metadata } };
        }
        observation.error = `DEVELOPER_${stage}_FAILED`;
        metadata.terminalCause = /^[A-Z][A-Z0-9_]+$/.test(error?.code) ? error.code : observation.error;
        if (metadata.admission.state === 'BLOCKED') metadata.admission.reason = observation.error;
        interrupted ||= error?.code === 'PI_OWNER_INTERRUPTED';
        participantSettled = error?.code !== 'PI_CHILD_REAP_UNAVAILABLE';
      } finally {
        try {
          if (directory) await timing.measure('cleanup', async () => {
            if (!participantSettled) throw Error('PARTICIPANT_REAP_UNAVAILABLE');
            if (hostAttempted && !await stop(kast, workspace)) throw Error('HOST_STOP_UNAVAILABLE');
            await cleanup(directory, { recursive: true, force: true });
          });
        } catch {
          metadata.cleanup = 'DEVELOPER_TRIAL_CLEANUP_FAILED';
          observation.error ??= metadata.cleanup;
        }
      }
      return { ...observation, metadata: { ...metadata, ...observation.metadata, timing: timing.snapshot() } };
    },
  };
}

export function gradeDeveloperResponse(response) {
  const grade = response?.metadata?.grade;
  const pass = !response?.error && grade?.pass === true;
  return { pass, score: Number(pass), reason: response?.error ?? grade?.reason ?? 'DEVELOPER_GRADE_UNAVAILABLE' };
}
