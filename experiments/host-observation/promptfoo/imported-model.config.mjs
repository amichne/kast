import { mkdtemp, realpath, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadKastGuidance } from './kast-guidance.mjs';
import { binary, runIsolatedPi } from './skill-provider.mjs';
import {
  declarationPath, digest, examineWorlds, gradeImportedAnswer, participantPrompt,
  qualifyWorlds, worlds,
} from './imported-model.mjs';

const arms = ['source', 'kast', 'materialized'];
const extension = fileURLToPath(new URL('./imported-model-tool.ts', import.meta.url));
const tool = 'query_imported_main';
const model = process.env.KAST_EVAL_PI_MODEL ?? 'openai-codex/gpt-6.1-sol';

async function prepareComparison() {
  const root = process.env.KAST_EVAL_IMPORTED_MODEL_ROOT;
  if (!root) throw Error('Set KAST_EVAL_IMPORTED_MODEL_ROOT to a prepared absolute fixture root');
  const kast = await binary(process.env.KAST_EVAL_KAST, 'kast');
  const admitted = await examineWorlds(root);
  await qualifyWorlds(root, kast);
  return { ...admitted, kast };
}

let preparation;
async function callApi(_label, { vars: { caseId, arm } }) {
  if (!worlds.includes(caseId) || !arms.includes(arm)) return { error: 'Unknown imported-model case or arm' };
  let directory;
  try {
    preparation ??= prepareComparison();
    const { cases, kast, sourceSha256 } = await preparation;
    const { snapshot, selection, workspace } = cases[caseId];
    const prompt = participantPrompt(snapshot, arm === 'materialized' ? selection : undefined);
    directory = await realpath(await mkdtemp(join(tmpdir(), 'kast-imported-model-pi-')));
    const trial = await runIsolatedPi({
      directory, prompt, model, effort: 'low',
      ...(arm === 'kast' && {
        guidance: loadKastGuidance(),
        system: 'Use query_imported_main for the Kast declaration query in this session.',
        extension, toolNames: [tool],
        extensionEnvironment: {
          KAST_EVAL_TRIAL_BIN: kast, KAST_EVAL_TRIAL_WORKSPACE: workspace,
        },
      }),
    });
    const queryPath = trial.calls.find(call => call.tool === tool && call.type === 'COMPLETE')?.path ?? null;
    return {
      ...(trial.error ? { error: trial.error } : { output: trial.reply }),
      tokenUsage: {
        total: trial.usage.total, prompt: trial.usage.input + trial.usage.cacheRead + trial.usage.cacheWrite,
        completion: trial.usage.output, cached: trial.usage.cacheRead,
      },
      metadata: {
        model, effort: 'low', ...trial.guidance, usage: trial.usage, toolCalls: trial.toolCalls, providerResponses: trial.providerResponses,
        elapsedMillis: trial.elapsedMillis, promptSha256: digest(prompt), sourceSha256, queryPath,
        qualifiedPath: declarationPath(caseId),
      },
    };
  } catch (error) {
    const reason = error instanceof Error && /^IMPORTED_MODEL_[A-Z_]+$/.test(error.message)
      ? error.message : 'IMPORTED_MODEL_TRIAL_UNAVAILABLE';
    return { error: reason };
  } finally {
    if (directory) await rm(directory, { force: true, recursive: true });
  }
}

export default {
  description: 'Resolve a declaration in two imported Gradle models with identical source files',
  sharing: false,
  providers: [{ id: () => 'kast:imported-model', handlesOwnRetries: true, callApi }],
  prompts: ['imported Gradle model'],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  scenarios: worlds.map((caseId, index) => ({
    description: `Imported ${caseId}`,
    config: [{ vars: { caseId, comparison: 'imported-gradle-model' } }],
    tests: [...arms.slice(index), ...arms.slice(0, index)].map(arm => ({
      description: `Imported ${caseId} / ${arm}`,
      vars: { arm },
      assert: [{
        type: 'javascript', metric: 'correctness',
        value: output => gradeImportedAnswer(output, caseId),
      }],
    })),
  })),
};
