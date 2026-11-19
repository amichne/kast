import { rm } from 'node:fs/promises';
import { comprehensionManifest, comprehensionSystem } from './comprehension-cases.mjs';
import { loadKastGuidance } from './kast-guidance.mjs';
import { createParticipantDirectory } from './participant-surface.mjs';
import { projectPiTrial } from './pi-accounting.mjs';
import { runIsolatedPi } from './skill-provider.mjs';
import { createTrialTiming } from './trial-phases.mjs';

export const comprehensionArms = Object.freeze(['evidence', 'evidence-with-kast-guidance']);
export const comprehensionBudgets = Object.freeze({
  wallMillis: 60_000, wallLimitScope: 'PARTICIPANT_PROCESS', maximumProviderResponses: 1, maximumToolProposals: 0, maximumToolExecutions: 0,
  responseLimitEnforcement: 'POST_HOC', toolsAvailable: 0,
  outputTokenLimit: null, outputTokenLimitEnforcement: 'UNENFORCED',
});

// Only the public packet and pinned ordinary skill reach Pi. Oracles are owned by
// comprehension-grading and never copied into this disposable working directory.
export function createComprehensionRunner({
  model = process.env.KAST_EVAL_PI_MODEL ?? 'openai-codex/gpt-6.1-sol', effort = 'low', runPi = runIsolatedPi,
} = {}) {
  if (typeof model !== 'string' || !model.trim() || typeof effort !== 'string' || !effort.trim()) {
    throw Error('COMPREHENSION_MODEL_SETTINGS_REJECTED');
  }
  const guidance = loadKastGuidance();
  if (guidance.skillSha256 !== comprehensionManifest.guidanceSha256) throw Error('COMPREHENSION_GUIDANCE_IDENTITY_CHANGED');
  const claimed = new Set();
  let active = false;
  return async function run(row, signal) {
    const timing = createTrialTiming('COMPREHENSION_PROVIDER');
    let directory, trial, failure;
    let participantSettled = true;
    let cleanup = 'NOT_RUN';
    const { item, arm, rowId } = row;
    const metadata = {
      panel: 'B', caseId: item.id, arm, taskSha256: item.participantSha256, inputSha256: item.inputSha256, sourceRevision: item.sourceRevision,
      evidenceKind: item.evidenceKind, contamination: item.contamination, model, effort, budgets: comprehensionBudgets,
      permissions: { tools: [] },
      guidance: { skillSha256: arm === 'evidence-with-kast-guidance' ? guidance.skillSha256 : null,
        sourceRevision: comprehensionManifest.sourceRevision, installedReleaseParity: 'UNVERIFIED' },
    };
    const response = (trial, error, measurement = 'LIVE_MODEL') => {
      const projected = projectPiTrial(trial);
      return {
        ...projected, ...(typeof trial?.reply === 'string' && { output: trial.reply }), ...(error && { error }),
        metadata: { ...projected.metadata, ...metadata, measurement, diagnosticQualified: !error,
          providerResponses: trial?.providerResponses ?? null, toolProposals: trial?.toolProposals ?? null,
          toolCalls: trial?.toolCalls ?? null, elapsedMillis: trial?.elapsedMillis ?? null,
          systemSha256: trial?.systemSha256 ?? null, termination: trial?.termination ?? null,
          terminalCause: trial?.terminalCause ?? null,
          cleanup, ...(!participantSettled && { retainedDirectory: directory }), timing: timing.snapshot() },
      };
    };
    if (!comprehensionArms.includes(arm)) return response(undefined, 'COMPREHENSION_ARM_REJECTED', 'NOT_RUN');
    if (signal?.aborted) return response(undefined, 'PI_EVALUATION_CANCELLED', 'NOT_RUN');
    if (claimed.has(rowId)) return response(undefined, 'PI_EVALUATION_DUPLICATE_ROW', 'NOT_RUN');
    if (active) return response(undefined, 'PI_EVALUATION_CONCURRENCY_LIMIT', 'NOT_RUN');
    claimed.add(rowId);
    active = true;
    let measurement = 'NOT_RUN';
    try {
      directory = await timing.measure('preparation', () => createParticipantDirectory());
      measurement = 'ATTEMPT_EVIDENCE_UNAVAILABLE';
      trial = await timing.measure('participant', () => runPi({
        directory, prompt: item.prompt, system: comprehensionSystem, model, effort,
        ...(arm === 'evidence-with-kast-guidance' && { inlineSkill: guidance.skillText }),
        toolNames: [], timeout: comprehensionBudgets.wallMillis, captureLimits: true, toolUsagePolicy: 'no-model-helpers',
      }), value => value.error || value.termination ? 'FAILED' : 'COMPLETED');
      measurement = 'LIVE_MODEL';
      // runIsolatedPi disables tools and owns model verification and timeout. The
      // response ceiling is checked after execution; it is not a pre-send cap.
      failure = trial.error ?? trial.termination ?? (signal?.aborted ? 'PI_EVALUATION_CANCELLED' :
        trial.providerResponses !== 1 || trial.toolProposals !== 0 || trial.toolCalls !== 0 || typeof trial.reply !== 'string'
          ? 'COMPREHENSION_BOUNDARY_REJECTED' : undefined);
    } catch (error) {
      trial = error?.trial ?? trial;
      failure = error?.code ?? 'PI_COMPREHENSION_SETUP_OR_RECORD_FAILURE';
      // A blinding rejection precedes the model process, so no attempt exists.
      if (failure === 'PARTICIPANT_SURFACE_REJECTED') measurement = 'NOT_RUN';
      participantSettled = error?.code !== 'PI_CHILD_REAP_UNAVAILABLE';
    } finally {
      try {
        if (directory) await timing.measure('cleanup', async () => {
          if (!participantSettled) throw Error('PI_CHILD_REAP_UNAVAILABLE');
          await rm(directory, { recursive: true, force: true });
          cleanup = 'COMPLETED';
        });
      } catch {
        cleanup = participantSettled ? 'FAILED' : 'WITHHELD_UNCONFIRMED_CHILD_SETTLEMENT';
        failure ??= 'PI_COMPREHENSION_CLEANUP_FAILED';
      }
      active = false;
    }
    return response(trial, failure, measurement);
  };
}
