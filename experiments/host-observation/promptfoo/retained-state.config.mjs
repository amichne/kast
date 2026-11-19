import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadKastGuidance } from './kast-guidance.mjs';
import { binary, child, prepareWorkspace, runIsolatedPi } from './skill-provider.mjs';
import { invocationFile } from './skill-cases.mjs';
import { gradeName, participantPrompt, worlds } from './retained-state.mjs';

const arms = ['kast', 'source', 'materialized'];
const tool = fileURLToPath(new URL('./retained-tool.ts', import.meta.url));
const model = process.env.KAST_EVAL_PI_MODEL ?? 'openai-codex/gpt-6.1-sol';
const digest = (text) => createHash('sha256').update(text).digest('hex');

async function callApi(_label, { vars: { caseId, arm } }) {
  if (!worlds.includes(caseId) || !arms.includes(arm)) return { error: 'Unknown retained-state case' };
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'kast-retained-')));
  try {
    const kast = await binary(process.env.KAST_EVAL_KAST, 'kast');
    const workspace = await prepareWorkspace({ workspace: 'fixture' }, directory);
    const source = await readFile(join(workspace.root, invocationFile), 'utf8');
    const seed = await child(
      kast,
      [
        'query',
        '--name',
        caseId,
        '--in',
        'invocation',
        '--kind',
        'function',
        '--source-set',
        'main',
        '--retain',
        '--json',
      ],
      { cwd: workspace.root, timeout: 900_000 },
    );
    const reply = JSON.parse(seed.stdout);
    const doc = reply.document;
    if (
      seed.status !== 0 ||
      reply.type !== 'complete' ||
      doc.coverage?.exhaustive !== true ||
      doc.original_evidence_summary?.failure_count !== 0 ||
      doc.original_evidence_summary?.omission_count !== 0 ||
      doc.items?.length !== 1 ||
      doc.items[0].name !== caseId ||
      doc.retention?.kind !== 'retained' ||
      typeof doc.retention.reference !== 'string' ||
      !doc.retention.reference.startsWith('result:v1:')
    ) {
      return { error: 'The fixture did not issue the expected complete retained result' };
    }
    const prompt = participantPrompt(arm, source, caseId);
    const trial = await runIsolatedPi({
      directory,
      prompt,
      model,
      effort: 'low',
      ...(arm === 'kast' && {
        guidance: loadKastGuidance(),
        system: 'Use read_previous_result for the Kast retained result read in this session.',
        extension: tool,
        toolNames: ['read_previous_result'],
        extensionEnvironment: {
          KAST_EVAL_RESULT_REF: doc.retention.reference,
          KAST_EVAL_TRIAL_BIN: kast,
          KAST_EVAL_TRIAL_WORKSPACE: workspace.root,
        },
      }),
    });
    const readFailed = trial.calls.some(
      (call) => call.tool === 'read_previous_result' && call.complete !== true,
    );
    const error = trial.error || (readFailed ? 'Kast could not deliver the retained result' : undefined);
    return {
      ...(error ? { error } : { output: trial.reply }),
      tokenUsage: {
        total: trial.usage.total,
        prompt: trial.usage.input + trial.usage.cacheRead + trial.usage.cacheWrite,
        completion: trial.usage.output,
        cached: trial.usage.cacheRead,
      },
      metadata: {
        model,
        effort: 'low',
        ...trial.guidance,
        usage: trial.usage,
        toolCalls: trial.toolCalls,
        reads: trial.calls,
        promptSha256: digest(prompt),
        sourceSha256: digest(source),
        seedSha256: digest(seed.stdout),
        outcome: error
          ? 'ERROR'
          : trial.reply === 'UNKNOWN'
            ? 'ABSTAINED'
            : trial.reply === caseId
              ? 'CORRECT'
              : 'INCORRECT',
      },
    };
  } finally {
    await rm(directory, { force: true, recursive: true });
  }
}

export default {
  description: 'Recover a retained declaration across two indistinguishable source inputs',
  sharing: false,
  providers: [{ id: () => 'kast:retained-state', handlesOwnRetries: true, callApi }],
  prompts: ['retained result'],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  scenarios: worlds.map((caseId, i) => ({
    description: 'Retained ' + caseId,
    config: [{ vars: { caseId, comparison: 'retained-state' } }],
    tests: [...arms.slice(i), ...arms.slice(0, i)].map((arm) => ({
      description: 'Retained ' + caseId + ' / ' + arm,
      vars: { arm },
      assert: [
        {
          type: 'javascript',
          metric: 'correctness',
          value: (output, context) => gradeName(output, caseId, context.providerResponse?.metadata?.reads),
        },
      ],
    })),
  })),
};
