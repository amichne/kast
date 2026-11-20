import { rmSync } from 'node:fs';
import { comprehensionCases, comprehensionSystem } from './comprehension-cases.mjs';
import { comprehensionControls, gradeComprehension } from './comprehension-grading.mjs';
import { historyDecisions } from './history-decisions.mjs';
import { createParticipantDirectory } from './participant-surface.mjs';

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
const workspace = live ? await createParticipantDirectory() : undefined;
if (workspace) process.once('exit', () => rmSync(workspace, { recursive: true, force: true }));

function decisionOnly(response) {
  const turn = response?.metadata?.codexAppServer;
  return turn?.turnId && turn.itemCounts?.agentMessage > 0 &&
    Object.keys(turn.itemCounts).every(type => ['userMessage', 'reasoning', 'agentMessage'].includes(type));
}

export default {
  description: `Kast history decisions / Codex / ${mode}`,
  sharing: false,
  prompts: ['{{task}}'],
  evaluateOptions: { cache: false, maxConcurrency: 1 },
  providers: live ? [{
    id: 'openai:codex-app-server:gpt-6.1-sol',
    config: {
      working_dir: workspace, skip_git_repo_check: true, model_reasoning_effort: 'low', model_provider: 'openai',
      sandbox_mode: 'read-only', approval_policy: 'never', ephemeral: true, persist_threads: false,
      network_access_enabled: false, turn_timeout_ms: 120000,
      base_instructions: comprehensionSystem + ' Use only the evidence in the request. Do not use tools.',
      cli_config: { features: { shell_tool: false, apps: false, memories: false }, web_search: 'disabled' },
    },
  }] : ['echo'],
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
