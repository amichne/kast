import { createHash } from 'node:crypto';
import { realpath, rm } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { text } from 'node:stream/consumers';
import { fileURLToPath } from 'node:url';
import { binary, runIsolatedPi } from './skill-provider.mjs';
import { corpusBaseline } from './corpus-source.mjs';
import { createParticipantDirectory } from './participant-surface.mjs';
import { projectPiTrial } from './pi-accounting.mjs';
import { createTrialTiming } from './trial-phases.mjs';

const extension = fileURLToPath(new URL('./corpus-tool.ts', import.meta.url));
const sourceTools = ['list_files', 'read_file', 'search_source'];
const system = 'You are a Kotlin developer examining the given repository at its pinned revision. Use the available read-only tools to answer from the repository. Return the answer in the exact format requested.';
export { system as corpusParticipantSystem };
const maxReceipts = 64;

function summarize(trial, tools) {
  const kastCalls = trial.calls.filter(call => call.tool === 'kast');
  const semanticReceipts = kastCalls.flatMap(call => call.receipt ? [call.receipt] : []);
  const toolFailures = trial.calls.flatMap(call => call.reason ? [{ tool: call.tool, reason: call.reason }] : []);
  const projection = projectPiTrial(trial);
  return {
    ...projection,
    metadata: {
      ...projection.metadata,
      elapsedMillis: Math.round(trial.elapsedMillis),
      toolCalls: trial.toolProposals,
      toolExecutions: trial.toolCalls,
      toolCounts: Object.fromEntries(tools.map(tool => [tool, trial.calls.filter(call => call.tool === tool).length])),
      kastCalls: kastCalls.length,
      completeKastCalls: semanticReceipts.filter(receipt => receipt.complete).length,
      hostRejectedCalls: semanticReceipts.filter(receipt => receipt.hostRejected).length,
      providerResponses: trial.providerResponses,
      termination: trial.termination ?? (trial.error ? 'PARTICIPANT_RUNTIME_FAILED' : 'COMPLETED'),
      ...(trial.terminalCause && { terminalCause: trial.terminalCause }),
      systemSha256: trial.systemSha256,
      semanticReceipts: semanticReceipts.slice(0, maxReceipts),
      semanticReceiptsOmitted: Math.max(0, semanticReceipts.length - maxReceipts),
      toolFailures: toolFailures.slice(0, maxReceipts),
      toolFailuresOmitted: Math.max(0, toolFailures.length - maxReceipts),
    },
  };
}

export async function participate(request, effects = {}) {
  const { getBinary = binary, runPi = runIsolatedPi, cleanup = rm, clock } = effects;
  const timing = createTrialTiming('CORPUS_PARTICIPANT', clock);
  const { workspace, model, arm, prompt, skillBody, revision, repository, caseId, requestedPanel = 'RESTRICTED' } = request ?? {};
  let observation = projectPiTrial(undefined);
  if (typeof workspace !== 'string' || !isAbsolute(workspace) || typeof model !== 'string' ||
      !/^(?:openai-codex|claude-code)\/[A-Za-z0-9_.-]+$/.test(model) || !['source', 'kast'].includes(arm) ||
      typeof prompt !== 'string' || !prompt.trim() || typeof revision !== 'string' || !/^[a-f0-9]{40}$/.test(revision) ||
      !['A', 'RESTRICTED'].includes(requestedPanel) ||
      (arm === 'kast' && (typeof skillBody !== 'string' || !skillBody.trim()))) {
    return { ...observation, error: 'PARTICIPANT_REQUEST_REJECTED',
      metadata: { ...observation.metadata, timing: timing.snapshot() } };
  }
  const toolNames = arm === 'kast' ? [...sourceTools, 'kast'] : [...sourceTools];
  const metadata = { revision, model, effort: 'low', arm, requestedPanel, panel: 'RESTRICTED', baseline: corpusBaseline,
    ...(typeof caseId === 'string' && /^[a-z0-9-]{1,100}$/.test(caseId) && { caseId }),
    admission: { state: requestedPanel === 'A' ? 'BLOCKED' : 'RESTRICTED', reason: corpusBaseline.panelA.reason },
    source: { revision, repository: typeof repository === 'string' ? repository : null },
    taskSha256: createHash('sha256').update(prompt).digest('hex'),
    permissions: { sourceRead: 'PINNED_REGULAR_GIT_BLOBS', workspaceMutation: false,
      arbitraryCommands: false, toolNames },
    budgets: { participantMillis: 240_000 },
  };
  if (requestedPanel === 'A') return { ...observation, error: 'PANEL_A_BASELINE_UNQUALIFIED',
    metadata: { ...observation.metadata, ...metadata, timing: timing.snapshot() } };
  let directory;
  let participantSettled = true;
  let stage = 'PINNED_WORKSPACE_UNAVAILABLE';
  try {
    const { root, git, kast } = await timing.measure('preparation', async () => {
      const root = await realpath(workspace);
      const git = await getBinary(undefined, 'git');
      stage = 'KAST_BINARY_UNAVAILABLE';
      const kast = arm === 'kast' ? await getBinary(process.env.KAST_EVAL_KAST, 'kast') : '';
      stage = 'PARTICIPANT_DIRECTORY_UNAVAILABLE';
      directory = await createParticipantDirectory();
      return { root, git, kast };
    });
    stage = 'PARTICIPANT_RUNTIME_FAILED';
    const trial = await timing.measure('participant', () => runPi({
      directory, prompt, system, model, effort: 'low', toolNames, extension, timeout: 240_000, captureLimits: true, visiblePaths: [root],
      toolUsagePolicy: 'no-model-helpers',
      ...(kast && { inlineSkill: skillBody }),
      extensionEnvironment: {
        KAST_CORPUS_WORKSPACE: root, KAST_CORPUS_GIT: git, KAST_CORPUS_REVISION: revision, KAST_CORPUS_KAST: kast,
        ...(kast && process.env.KAST_GRADLE_JAVA_HOME && { KAST_GRADLE_JAVA_HOME: process.env.KAST_GRADLE_JAVA_HOME }),
      },
    }), trial => trial.error || trial.termination ? 'FAILED' : 'COMPLETED');
    observation = summarize(trial, toolNames);
    const outcome = trial.error ? { error: 'PARTICIPANT_RUNTIME_FAILED' }
      : trial.termination ? { error: trial.termination }
      : { output: trial.reply ?? '' };
    Object.assign(observation, outcome);
  } catch (error) {
    if (error?.trial) {
      const rejected = projectPiTrial(error.trial);
      observation = { ...observation, ...rejected, metadata: { ...observation.metadata, ...rejected.metadata } };
    }
    const code = ['PI_OWNER_INTERRUPTED', 'PI_CHILD_REAP_UNAVAILABLE', 'PARTICIPANT_SURFACE_REJECTED'].includes(error?.code) ? error.code : stage;
    participantSettled = error?.code !== 'PI_CHILD_REAP_UNAVAILABLE';
    if (['PI_EVENT_PARSE_FAILED', 'PI_EVENT_EVIDENCE_REJECTED', 'PI_CUTOFF_EVIDENCE_UNAVAILABLE'].includes(error?.code)) {
      metadata.terminalCause = error.code;
    }
    observation.error = code;
    metadata.termination = code;
  } finally {
    if (directory) {
      try {
        await timing.measure('cleanup', async () => {
          if (!participantSettled) throw Error('PARTICIPANT_REAP_UNAVAILABLE');
          await cleanup(directory, { recursive: true, force: true });
        });
      } catch {
        metadata.cleanup = 'PARTICIPANT_CLEANUP_FAILED';
        observation.error ??= metadata.cleanup;
      }
    }
  }
  return { ...observation, metadata: { ...metadata, ...observation.metadata, timing: timing.snapshot() } };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  let result;
  try {
    result = await participate(JSON.parse(await text(process.stdin)));
  } catch {
    result = { ...projectPiTrial(undefined), error: 'PARTICIPANT_PROTOCOL_FAILED' };
  }
  process.stdout.write(JSON.stringify(result) + '\n');
}
