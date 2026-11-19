import { mkdtemp, realpath, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join } from 'node:path';
import { text } from 'node:stream/consumers';
import { fileURLToPath } from 'node:url';
import { binary, runIsolatedPi } from './skill-provider.mjs';

const extension = fileURLToPath(new URL('./corpus-tool.ts', import.meta.url));
const sourceTools = ['list_files', 'read_file', 'search_source'];
const system = 'You are a Kotlin developer examining the given repository at its pinned revision. Use the available read-only tools to answer from the repository. Return the answer in the exact format requested.';
const maxReceipts = 64;

function summarize(trial, tools) {
  const kastCalls = trial.calls.filter(call => call.tool === 'kast');
  const semanticReceipts = kastCalls.flatMap(call => call.receipt ? [call.receipt] : []);
  const toolFailures = trial.calls.flatMap(call => call.reason ? [{ tool: call.tool, reason: call.reason }] : []);
  const usage = trial.usage;
  return {
    metadata: {
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
    ...(usage && usage.total > 0 && { tokenUsage: { total: usage.total, prompt: usage.input + usage.cacheRead + usage.cacheWrite,
      completion: usage.output, cached: usage.cacheRead } }),
  };
}

async function participate(request) {
  const { workspace, model, arm, prompt, skillBody, revision } = request;
  if (typeof workspace !== 'string' || !isAbsolute(workspace) || typeof model !== 'string' ||
      !/^openai-codex\/[A-Za-z0-9_.-]+$/.test(model) || !['source', 'kast'].includes(arm) ||
      typeof prompt !== 'string' || !prompt.trim() || typeof revision !== 'string' || !/^[a-f0-9]{40}$/.test(revision) ||
      (arm === 'kast' && (typeof skillBody !== 'string' || !skillBody.trim()))) {
    return { error: 'PARTICIPANT_REQUEST_REJECTED', metadata: {} };
  }
  const metadata = { revision, model, arm };
  let directory;
  let stage = 'PINNED_WORKSPACE_UNAVAILABLE';
  try {
    const root = await realpath(workspace);
    const git = await binary(undefined, 'git');
    stage = 'KAST_BINARY_UNAVAILABLE';
    const kast = arm === 'kast' ? await binary(process.env.KAST_EVAL_KAST, 'kast') : '';
    stage = 'PARTICIPANT_RUNTIME_FAILED';
    directory = await mkdtemp(join(tmpdir(), 'kast-corpus-participant-'));
    const toolNames = kast ? [...sourceTools, 'kast'] : sourceTools;
    const trial = await runIsolatedPi({
      directory, prompt, system, model, toolNames, extension, timeout: 240_000, captureLimits: true,
      ...(kast && { inlineSkill: skillBody }),
      extensionEnvironment: {
        KAST_CORPUS_WORKSPACE: root, KAST_CORPUS_GIT: git, KAST_CORPUS_REVISION: revision, KAST_CORPUS_KAST: kast,
        ...(kast && process.env.KAST_GRADLE_JAVA_HOME && { KAST_GRADLE_JAVA_HOME: process.env.KAST_GRADLE_JAVA_HOME }),
      },
    });
    const observation = summarize(trial, toolNames);
    const outcome = trial.error ? { error: 'PARTICIPANT_RUNTIME_FAILED' }
      : trial.termination ? { error: trial.termination }
      : { output: trial.reply ?? '' };
    return { ...outcome, ...observation, metadata: { ...metadata, ...observation.metadata } };
  } catch (error) {
    const code = ['PI_OWNER_INTERRUPTED', 'PI_CHILD_REAP_UNAVAILABLE'].includes(error?.code) ? error.code : stage;
    if (['PI_EVENT_PARSE_FAILED', 'PI_EVENT_EVIDENCE_REJECTED', 'PI_CUTOFF_EVIDENCE_UNAVAILABLE'].includes(error?.code)) {
      metadata.terminalCause = error.code;
    }
    return { error: code, metadata };
  } finally {
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

let result;
try {
  result = await participate(JSON.parse(await text(process.stdin)));
} catch {
  result = { error: 'PARTICIPANT_PROTOCOL_FAILED', metadata: {} };
}
process.stdout.write(JSON.stringify(result) + '\n');
