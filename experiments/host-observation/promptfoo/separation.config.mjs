// Separation suite. Each twin case delivers byte-identical text to the `source`
// and `kast` participants in every world. The `kast` participant also receives the
// canonical skill and the read-only Kast CLI against its world's workspace. The
// `materialized` participant receives the text plus the one untracked fact that
// differs between worlds. All three use the same model, effort and answer contract.
import { createHash } from 'node:crypto';
import { rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { loadKastGuidance } from './kast-guidance.mjs';
import { createParticipantDirectory } from './participant-surface.mjs';
import { projectPiTrial } from './pi-accounting.mjs';
import { binary, runIsolatedPi } from './skill-provider.mjs';
import { arms, gradeAnswer } from './separation.mjs';
import { caseIds, loadSeparation } from './separation-cases.mjs';

const root = process.env.KAST_SEPARATION_ROOT;
if (!root) throw Error('Set KAST_SEPARATION_ROOT to a root prepared by separation-cases.mjs');
const selected = process.env.KAST_SEPARATION_CASES?.split(',') ?? caseIds;
const model = process.env.KAST_EVAL_PI_MODEL ?? 'openai-codex/gpt-6.1-sol';
const effort = process.env.KAST_EVAL_PI_EFFORT ?? 'low';
const tool = fileURLToPath(new URL('./skill-tool.ts', import.meta.url));
const sha256 = text => createHash('sha256').update(text).digest('hex');

const kast = await binary(process.env.KAST_EVAL_KAST, 'kast');
const loaded = await loadSeparation(root, kast, selected);
const guidance = loadKastGuidance();

// The admission travels with every row, so the report regrades from the export alone.
const summary = admission => ({
  id: admission.id, ceiling: admission.ceiling, visibleSha256: admission.visibleSha256,
  worlds: admission.worlds.map(({ world, oracle, factSha256, promptSha256 }) => ({ world, oracle, factSha256, promptSha256 })),
});

async function callApi(_label, { vars: { caseId, world, arm } }) {
  const entry = loaded.find(item => item.admission.id === caseId);
  const admitted = entry?.admission.worlds.find(item => item.world === world);
  if (!admitted || !arms.includes(arm)) return { error: 'SEPARATION_ROW_UNKNOWN' };
  const prompt = admitted.prompts[arm];
  const workspace = entry.workspaces[world];
  const separation = { caseId, world, arm, promptSha256: sha256(prompt), admission: summary(entry.admission) };
  let directory;
  try {
    directory = await createParticipantDirectory();
    const trial = await runIsolatedPi({
      directory, prompt, model, effort, toolUsagePolicy: 'no-model-helpers',
      ...(arm === 'kast' && {
        guidance, extension: tool, toolNames: ['kast'], visiblePaths: [workspace],
        extensionEnvironment: {
          KAST_EVAL_TRIAL_SCOPE: 'fixture', KAST_EVAL_TRIAL_WORKSPACE: workspace, KAST_EVAL_TRIAL_BIN: kast,
        },
      }),
    });
    const observation = projectPiTrial(trial);
    const outcome = trial.error ? 'ERROR' : gradeAnswer(trial.reply, admitted.oracle);
    return {
      ...observation,
      ...(trial.error ? { error: trial.error } : { output: trial.reply }),
      metadata: {
        ...observation.metadata,
        separation: { ...separation, outcome },
        model, effort, usage: trial.usage, toolCalls: trial.toolCalls, calls: trial.calls,
        providerResponses: trial.providerResponses, elapsedMillis: trial.elapsedMillis,
        systemSha256: trial.systemSha256, ...(arm === 'kast' && trial.guidance),
      },
    };
  } catch (error) {
    const reason = error instanceof Error && /^[A-Z][A-Z_]+$/.test(error.message) ? error.message : 'SEPARATION_TRIAL_UNAVAILABLE';
    return { ...projectPiTrial(error?.trial), error: reason, metadata: { separation: { ...separation, outcome: 'ERROR' } } };
  } finally {
    if (directory) await rm(directory, { force: true, recursive: true });
  }
}

export default {
  description: 'Twin workspaces with identical tracked text and different compiled answers',
  sharing: false,
  providers: [{ id: () => 'kast:separation', handlesOwnRetries: true, callApi }],
  prompts: ['separation'],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  tests: loaded.flatMap(({ admission }) => admission.worlds.flatMap(({ world }, index) =>
    [...arms.slice(index), ...arms.slice(0, index)].map(arm => ({
      description: `${admission.id} / ${world} / ${arm}`,
      vars: { caseId: admission.id, world, arm, comparison: 'separation' },
      assert: [{
        type: 'javascript', metric: 'correctness',
        value: (_output, context) => context.providerResponse?.metadata?.separation?.outcome === 'CORRECT',
      }],
    })))),
};
