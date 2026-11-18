import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { runOwnedWorker } from '../pi_evaluation_controller.mjs';
import { AnswerCheck, Outcome } from '../pi_evaluation_policy.mjs';
import { expandReplayPlan, prepareReplayRun } from '../pi_evaluation_replay.mjs';
import { assessReplayTarget, normalizeReplayUsage, ReplayOutcome } from '../pi_evaluation_replay_accounting.mjs';
import { gradeReplayDecision, replayOracleControls } from '../pi_evaluation_replay_grading.mjs';
import { expectedPolicyOutcomes, validatePlan } from '../run_pi_evaluation.mjs';

const result = (pass, reason) => ({ pass, score: Number(pass), reason });
const count = value => Number.isSafeInteger(value) && value >= 0;

function suite({ id, rows, call, metric, maxConcurrency = 1 }) {
  const byId = new Map(rows.map(row => [row.rowId, row]));
  const caseTests = new Map();
  for (const { rowId, caseId, arm, repeat = 1, description, grade, metric: rowMetric = metric } of rows) {
    const tests = caseTests.get(caseId) ?? [];
    tests.push({
      description,
      vars: { rowId, caseId, arm, repeat },
      options: { disableVarExpansion: true },
      assert: [{ type: 'javascript', metric: rowMetric, value: (_output, context) => grade(context.providerResponse?.output) }],
    });
    caseTests.set(caseId, tests);
  }
  return {
    description: id,
    sharing: false,
    providers: [{
      id: () => id,
      handlesOwnRetries: true,
      async callApi(_prompt, context, options) {
        const row = byId.get(context?.vars?.rowId);
        return row ? call(row, options?.abortSignal) : { error: 'PI_EVALUATION_ROW_UNAVAILABLE' };
      },
    }],
    // Worker prompts come from pinned plans and fixtures. Promptfoo receives only case labels.
    prompts: ['{{rowId}}'],
    scenarios: [...caseTests.values()].map(tests => ({ config: [{}], tests })),
    evaluateOptions: { cache: false, maxConcurrency },
  };
}

export function createPiOracleConfig() {
  const rows = [];
  for (const caseId of ['C1', 'C2', 'C3', 'C4', 'C5', 'C6']) {
    const controls = replayOracleControls(caseId);
    for (const [arm, samples] of Object.entries(controls)) {
      for (const [index, sample] of samples.entries()) {
        rows.push({
          rowId: `${caseId}-${arm}-${index + 1}`, caseId, arm,
          description: `${caseId}: ${sample.name}`, sample,
          grade: output => result(
            output?.correct === (arm === 'accept') && (arm === 'accept' || output.reasons?.includes(sample.reason)),
            `${arm.toUpperCase()}: ${sample.reason ?? 'GRADED_CORRECT'}`,
          ),
        });
      }
    }
  }
  return suite({
    id: 'kast:pi-replay-oracle', rows, metric: 'oracle',
    call: ({ caseId, sample }) => ({ output: gradeReplayDecision(caseId, sample.decision, sample.admission) }),
  });
}

export function createPiTargetConfig(targetFile) {
  if (!path.isAbsolute(targetFile)) throw Error('ABSOLUTE_TARGET_INPUT_REQUIRED');
  const bytes = fs.readFileSync(targetFile);
  if (bytes.length > 4194304) throw Error('TARGET_INPUT_TOO_LARGE');
  const input = JSON.parse(bytes.toString('utf8'));
  return suite({
    id: 'kast:pi-replay-target', metric: 'target',
    rows: [{ rowId: 'replay-target', caseId: 'replay-target', arm: 'comparison', description: 'Replay performance target',
      grade: output => result(output?.status === 'TARGET_MET', output?.status ?? 'TARGET_ASSESSMENT_UNAVAILABLE') }],
    call: () => ({ output: assessReplayTarget(input) }),
  });
}

function preparePiRun(bytes, plan) {
  fs.mkdirSync(plan.outputRoot, { mode: 0o700 });
  const snapshot = path.join(plan.outputRoot, 'plan.json');
  fs.writeFileSync(snapshot, bytes, { mode: 0o400, flag: 'wx' });
  const worker = fileURLToPath(new URL('../pi_evaluation_worker.mjs', import.meta.url));
  const cases = new Map();
  const publish = () => fs.writeFileSync(path.join(plan.outputRoot, 'report.json'), JSON.stringify({
    cases: plan.cases.map(item => cases.get(item.name) ?? { case: item.name, type: 'not-run' }),
    timing: plan.timing ?? 'UNQUALIFIED',
  }, null, 2) + '\n', { mode: 0o600 });
  publish();
  return {
    async run(index, signal) {
      const item = plan.cases[index];
      const deadline = Math.min(item.wallSeconds, item.mode === 'received-result' ? 60 : 120);
      const receipt = signal.aborted ? { type: 'cancelled-before-start' } :
        await runOwnedWorker(process.execPath, [worker, snapshot, String(index)], {
          cwd: plan.workspaceRoot, env: { ...process.env, KAST_TOOL_RPC_COMMAND: undefined },
          signal, wallMillis: deadline * 1000,
        });
      cases.set(item.name, { case: item.name, ...receipt });
      publish();
      return receipt;
    },
  };
}

function reportedUsage(usage) {
  const normalized = normalizeReplayUsage(usage);
  if (normalized.type !== 'known') return {};
  const { input, cacheRead, cacheWrite, output, totalTokens } = normalized.value;
  return { tokenUsage: { total: totalTokens, prompt: input + cacheRead + cacheWrite, completion: output, cached: cacheRead } };
}

function replayResponse(receipt) {
  const { outcome, grade, measurement, shellCalls, toolProposals, controller, providerSends, protectedFilesUnchanged } = receipt;
  const identity = receipt.identity?.model;
  const model = typeof identity === 'string' ? identity : identity?.id;
  const unavailable = [ReplayOutcome.INVALID, ReplayOutcome.CANCELLED].includes(outcome.type)
    || (controller?.type === 'report' && controller.exitCode !== 0);
  return {
    output: { type: outcome.type, reasons: outcome.reasons, gradeCorrect: grade?.correct ?? null,
      measured: measurement === 'LIVE_MODEL' && providerSends === 1,
      protectedFilesUnchanged: protectedFilesUnchanged === true, workerExit: controller?.exitCode ?? null },
    ...reportedUsage(receipt.usage),
    ...(unavailable ? { error: outcome.reasons.join(' / ') || 'REPLAY_WORKER_FAILED' } : {}),
    metadata: { model, effort: receipt.identity?.effort, ...(count(toolProposals) ? { toolCalls: toolProposals } : {}), ...(count(shellCalls) ? { shellCalls } : {}),
      ...(count(providerSends) ? { providerSends } : {}) },
  };
}

function liveResponse(receipt) {
  const report = receipt.report;
  const toolCalls = report?.modelProposals?.length;
  const observation = report?.requestObservations?.find(request => request.allow === true);
  const adaptersStarted = report?.modelProposals?.filter(proposal => proposal.adapterStartObserved === true).length;
  const unavailable = receipt.type !== 'report' || receipt.exitCode !== 0 || report?.protectedFilesUnchanged !== true
    || [Outcome.INVALID, Outcome.MODEL_ERROR, Outcome.MODEL_MISMATCH, Outcome.CANCELLED].includes(report?.outcome);
  return {
    output: { type: receipt.type, workerExit: receipt.exitCode ?? null, outcome: report?.outcome ?? null,
      protectedFilesUnchanged: report?.protectedFilesUnchanged === true,
      finalAnswer: report?.finalAnswer === true, toolProposals: report?.modelProposals?.length ?? null,
      answerCheck: report?.answerCheck ?? AnswerCheck.UNAVAILABLE,
      failure: receipt.failure?.reason ?? null },
    ...(report?.usage?.totalTokens > 0 ? reportedUsage(report.usage) : {}),
    ...(unavailable ? { error: receipt.failure?.reason ?? report?.outcome ?? 'PI_WORKER_FAILED' } : {}),
    metadata: { model: observation?.payloadModel, effort: observation?.effort, ...(count(toolCalls) ? { toolCalls } : {}),
      ...(count(adaptersStarted) ? { adaptersStarted } : {}),
      ...(count(report?.providerRequests) ? { admittedRequests: report.providerRequests } : {}) },
  };
}

export function createPiConfig(planFile) {
  if (!path.isAbsolute(planFile)) throw Error('ABSOLUTE_PI_PLAN_REQUIRED');
  const bytes = fs.readFileSync(planFile);
  const plan = validatePlan(JSON.parse(bytes.toString('utf8')));
  const replay = plan.mode === 'replay';
  const maxConcurrency = replay ? plan.workers : 1;
  const rows = replay ? expandReplayPlan(plan).map(trial => ({
    rowId: trial.trialId, caseId: trial.caseId, arm: trial.format, repeat: trial.repetition, description: trial.trialId,
    index: trial.trialId,
    grade: output => result(output?.type === ReplayOutcome.CORRECT && output.gradeCorrect === true &&
      output.measured && output.protectedFilesUnchanged && output.workerExit === 0,
    [output?.type ?? 'REPLAY_RESULT_UNAVAILABLE', ...(output?.reasons ?? [])].join(' / ')),
  })) : plan.cases.map((item, index) => ({
    rowId: item.name, caseId: item.name, arm: item.finalPresentation ?? 'canonical', description: item.name, index,
    metric: item.expectedAnswerSha256 === undefined ? 'policy' : 'correctness',
    grade: output => result(output?.type === 'report' && output.workerExit === 0 && output.protectedFilesUnchanged &&
      expectedPolicyOutcomes.includes(output.outcome) && (item.expectedOutcome === undefined || output.outcome === item.expectedOutcome) &&
      (output.outcome === Outcome.REJECTION || output.finalAnswer) && (item.mode !== 'received-result' || output.toolProposals === 0) &&
      (item.expectedAnswerSha256 === undefined || output.answerCheck === AnswerCheck.MATCHED),
    [output?.failure ?? output?.outcome ?? 'PI_REPORT_UNAVAILABLE', ...(item.expectedAnswerSha256 === undefined ? [] : [output?.answerCheck ?? AnswerCheck.UNAVAILABLE])].join(' / ')),
  }));

  // One invocation per admitted identity. Promptfoo retries or repeats cannot debit the plan again.
  const claimed = new Set();
  const controller = new AbortController();
  const cancel = () => controller.abort();
  let active = 0;
  let prepared;
  return suite({
    id: replay ? 'kast:pi-replay-live' : 'kast:pi-live', rows, maxConcurrency,
    metric: replay ? 'correctness' : 'policy',
    async call(row, signal) {
      const workerSignal = signal ? AbortSignal.any([controller.signal, signal]) : controller.signal;
      if (workerSignal.aborted) return { error: 'PI_EVALUATION_CANCELLED' };
      if (claimed.has(row.rowId)) return { error: 'PI_EVALUATION_DUPLICATE_ROW' };
      if (active >= maxConcurrency) return { error: 'PI_EVALUATION_CONCURRENCY_LIMIT' };
      claimed.add(row.rowId);
      if (active++ === 0) {
        process.once('SIGINT', cancel);
        process.once('SIGTERM', cancel);
      }
      try {
        if (replay) {
          prepared ??= prepareReplayRun(planFile, bytes, plan, controller.signal);
          const run = await prepared;
          return replayResponse(await run.run(row.index, workerSignal));
        }
        prepared ??= preparePiRun(bytes, plan);
        return liveResponse(await prepared.run(row.index, workerSignal));
      } catch (error) {
        return { error: error?.code === 'EEXIST' ? 'PI_OUTPUT_ALREADY_EXISTS' : 'PI_EVALUATION_SETUP_OR_RECORD_FAILURE' };
      } finally {
        if (--active === 0) {
          process.removeListener('SIGINT', cancel);
          process.removeListener('SIGTERM', cancel);
        }
      }
    },
  });
}

const planFile = process.env.KAST_PI_EVAL_PLAN;
const targetFile = process.env.KAST_PI_EVAL_TARGET;
if (planFile !== undefined && targetFile !== undefined) throw Error('PI_EVALUATION_INPUTS_MUTUALLY_EXCLUSIVE');

export default planFile !== undefined ? createPiConfig(planFile)
  : targetFile !== undefined ? createPiTargetConfig(targetFile) : createPiOracleConfig();
