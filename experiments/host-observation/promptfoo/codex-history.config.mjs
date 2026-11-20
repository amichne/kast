import { comprehensionCases } from './comprehension-cases.mjs';
import { comprehensionControls, gradeComprehension } from './comprehension-grading.mjs';
import { historyDecisions } from './history-decisions.mjs';
import { codexDecisionProvider, decisionOnly } from './codex-decision-provider.mjs';

const cases = [
  ...historyDecisions,
  ...comprehensionCases().map(item => ({
    id: item.id, source: item.sourceRevision, prompt: item.prompt,
    grade: output => gradeComprehension(item.id, output).correct,
    controls: comprehensionControls(item.id),
  })),
];
const mode = process.env.KAST_CODEX_HISTORY_MODE ?? 'live';
if (!['controls', 'live'].includes(mode)) throw Error('Unknown KAST_CODEX_HISTORY_MODE');
const live = mode === 'live';
export default {
  description: `Kast history decisions / Codex / ${mode}`,
  sharing: false,
  prompts: ['{{task}}'],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  providers: live ? [await codexDecisionProvider()] : ['echo'],
  tests: cases.flatMap(item => (live ? [{ output: item.prompt, correct: true }] : item.controls).map(sample => ({
    description: [item.id, sample.name].filter(Boolean).join(' / '),
    metadata: { source: item.source },
    vars: { task: sample.output },
    options: { disableVarExpansion: true },
    assert: [{
      type: 'javascript', metric: live ? 'decision' : 'graderControl',
      value: (_output, context) => {
        const response = context.providerResponse;
        if (live && !decisionOnly(response)) return { pass: false, score: 0, reason: 'Unexpected Codex turn evidence' };
        return item.grade(response.output) === sample.correct;
      },
    }],
  }))),
};
