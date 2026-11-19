import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { assessCorpusExport, assessCorpusRow, corpusTrialKey, plannedCorpusTrials } from './corpus-coordinators.mjs';
import { suites } from './corpus-cases.mjs';
import { piMeasurementsForReport } from './pi-accounting.mjs';

const digest = text => createHash('sha256').update(text).digest('hex');
const count = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
const reasons = values => [...new Set(values)].sort();
const receiptFields = ['elapsedMillis', 'providerResponses', 'usage', 'finalizedUsage', 'accounting', 'context',
  'evidence', 'timing', 'participantTiming', 'toolCalls', 'toolCounts', 'completeKastCalls', 'semanticReceipts',
  'semanticReceiptsOmitted', 'toolFailures', 'toolFailuresOmitted', 'terminalCause', 'termination', 'partialTranscript',
  'skillSha256', 'taskSha256', 'systemSha256', 'sourceSha256', 'source', 'permissions', 'budgets',
  'baseline', 'admission', 'panel', 'requestedPanel'];

function readOptional(path) {
  try { return JSON.parse(readFileSync(path)); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}

function trialRecord(planned, row) {
  const { task, caseId, model, arm } = planned;
  const identity = { caseId, model, arm };
  if (!row) return { ...identity, outcome: 'unassessed', reason: 'MISSING_TRIAL', fresh: false, totalTokens: null };
  const { metadata = {}, tokenUsage, error, output, cached } = row.response ?? {};
  const expected = { revision: task.revision, taskSha256: digest(task.task), model, effort: 'low', arm };
  if ((row.prompt?.raw !== undefined && row.prompt.raw !== task.task) ||
      (row.vars?.task !== undefined && row.vars.task !== task.task) ||
      Object.entries(expected).some(([key, value]) => metadata[key] !== undefined && metadata[key] !== value)) {
    throw Error('Trial task identity differs from the canonical source pin');
  }
  const retained = Object.fromEntries(receiptFields.filter(key => Object.hasOwn(metadata, key)).map(key => [key, metadata[key]]));
  const result = { ...identity, ...retained, ...(output !== undefined && { answer: output }), ...(error && { error }),
    ...(tokenUsage && { tokenUsage }), fresh: Boolean(row.response) && !cached,
    totalTokens: cached ? null : count(tokenUsage?.total) };
  if (cached) return { ...result, outcome: 'unassessed', reason: 'RESPONSE_CACHE_REPLAY', cached: true };
  if (error) return { ...result, outcome: 'error', reason: 'PARTICIPANT_ERROR' };
  if (!row.response || row.failureReason === 2 || typeof row.success !== 'boolean' || output === undefined) {
    return { ...result, outcome: 'unassessed', reason: 'MISSING_ASSESSMENT' };
  }
  if (row.prompt?.raw !== task.task || Object.entries(expected).some(([key, value]) => metadata[key] !== value) ||
      (arm === 'source' ? metadata.skillSha256 !== null : !/^[0-9a-f]{64}$/.test(metadata.skillSha256))) {
    return { ...result, outcome: 'unassessed', reason: 'MISSING_TRIAL_IDENTITY' };
  }
  const assessment = assessCorpusRow(row, task);
  if (assessment.reason === 'ANSWER_GRADE_MISMATCH') throw Error('Trial answer and independent grade disagree');
  return { ...result, ...assessment };
}

function record(directory, workspace) {
  const path = join(directory, workspace);
  const exported = readOptional(join(path, 'results.json'));
  const dispatch = readOptional(join(path, 'receipt.json'));
  const coordinator = readOptional(join(path, 'coordinator.json'));
  if (coordinator && coordinator.profile?.workspace !== workspace) throw Error('Coordinator workspace identity differs');
  if (!exported) return { suite: workspace, plannedTrials: null, rows: [], coordinator, dispatch,
    evaluation: { status: 'INFRASTRUCTURE_FAILURE', reason: 'MISSING_EXPORT' } };
  const plan = plannedCorpusTrials(exported, workspace);
  if (plan.state !== 'ready') throw Error('Invalid canonical test plan: ' + plan.reason);
  const planned = new Set(plan.trials.map(corpusTrialKey));
  const observed = new Map();
  for (const row of exported.results?.results ?? []) {
    const key = corpusTrialKey(row.vars);
    if (!planned.has(key) || observed.has(key)) throw Error('Unexpected or duplicate canonical trial identity');
    observed.set(key, row);
  }
  const evaluation = assessCorpusExport(exported, workspace, dispatch?.exitCode);
  const rows = plan.trials.map(trial => trialRecord(trial, observed.get(corpusTrialKey(trial))));
  return { suite: workspace, workspace: plan.trials[0].task.workspace, revision: plan.trials[0].task.revision,
    plannedTrials: plan.trials.length, evalId: exported.evalId, sha256: digest(readFileSync(join(path, 'results.json'))),
    coordinator, dispatch, dispatchAgreement: dispatch?.status === evaluation.status && dispatch?.reason === evaluation.reason,
    environment: exported.metadata, evaluation, rows };
}

function provenance(values) {
  const reported = values.some(value => ['provider-reported', 'mixed'].includes(value));
  const estimated = values.some(value => ['catalog-estimate', 'mixed'].includes(value));
  return reported ? estimated ? 'mixed' : 'provider-reported' : estimated ? 'catalog-estimate' : 'none';
}

function dollars(observations, role = 'task') {
  const costs = observations.filter(value => value.state === 'admitted').map(value => value.costs[role]);
  const incomplete = observations.filter(value => value.state !== 'admitted').flatMap(value => value.reasons ?? ['UNOBSERVED_TRIAL']);
  incomplete.push(...costs.filter(value => value.state !== 'complete').flatMap(value => value.reasons));
  if (!observations.length) incomplete.push('NO_TRIALS');
  const knownTotal = costs.reduce((sum, value) => sum + value.knownTotal, 0);
  const fields = { knownTotal, providerReported: costs.reduce((sum, value) => sum + value.providerReported, 0),
    estimated: costs.reduce((sum, value) => sum + value.estimated, 0), provenance: provenance(costs.map(value => value.provenance)),
    completeTrials: costs.filter(value => value.state === 'complete').length, expectedTrials: observations.length };
  return incomplete.length ? { state: 'incomplete', ...fields, reasons: reasons(incomplete) } :
    { state: 'complete', total: knownTotal, ...fields };
}

function contexts(observations) {
  const values = observations.filter(value => value.state === 'admitted').map(value => value.context);
  const complete = observations.length > 0 && values.length === observations.length && values.every(value => value.state === 'complete');
  const input = values.reduce((sum, value) => sum + (value.inputSum ?? value.knownInputSum), 0);
  const responses = values.reduce((sum, value) => sum + (value.responses ?? value.observedResponses), 0);
  const peaks = values.map(value => value.peakInput ?? value.observedPeak).filter(value => value !== undefined);
  return complete ? { state: 'complete', inputSum: input, peakInput: Math.max(...peaks), responses } :
    { state: 'incomplete', knownInputSum: input, observedResponses: responses,
      ...(peaks.length && { observedPeak: Math.max(...peaks) }),
      reasons: reasons(observations.flatMap(value => value.state === 'admitted' ? value.context.reasons ?? [] : value.reasons ?? ['UNOBSERVED_TRIAL'])) };
}

function measurements(rows) {
  const observations = rows.map(row => row.fresh === false ? { state: 'unavailable', reasons: [row.reason] } :
    piMeasurementsForReport(row.accounting));
  return { expectedTrials: rows.length, freshTrials: rows.filter(row => row.fresh).length,
    scope: 'participant-tasks-including-model-helpers',
    dollars: dollars(observations), context: contexts(observations) };
}

export function buildCorpusReport(paths) {
  const rounds = paths.map(path => {
    const directory = resolve(path);
    if (existsSync(join(directory, 'results.json')) || (suites.includes(basename(directory)) &&
        (existsSync(join(directory, 'coordinator.json')) || existsSync(join(directory, 'receipt.json'))))) {
      return [record(dirname(directory), basename(directory))];
    }
    return suites.map(workspace => record(directory, workspace));
  });
  const outcomes = { bothCorrect: 0, bothIncorrect: 0, sourceOnlyCorrect: 0, kastOnlyCorrect: 0, incomplete: 0 };
  const jointlyCorrectTokens = { source: 0, kast: 0 };
  const skillDigests = new Set();
  for (const suite of rounds.flat()) {
    const pairs = new Map();
    for (const row of suite.rows) {
      if (row.skillSha256) skillDigests.add(row.skillSha256);
      const key = JSON.stringify([row.caseId, row.model]);
      if (!pairs.has(key)) pairs.set(key, {});
      pairs.get(key)[row.arm] = row;
    }
    for (const { source, kast } of pairs.values()) {
      if ([source, kast].some(row => !['correct', 'incorrect'].includes(row?.outcome))) outcomes.incomplete++;
      else if (source.outcome === 'correct' && kast.outcome === 'correct') {
        outcomes.bothCorrect++;
        for (const row of [source, kast]) jointlyCorrectTokens[row.arm] = jointlyCorrectTokens[row.arm] === null || row.totalTokens === null ?
          null : jointlyCorrectTokens[row.arm] + row.totalTokens;
      } else if (source.outcome === 'correct') outcomes.sourceOnlyCorrect++;
      else if (kast.outcome === 'correct') outcomes.kastOnlyCorrect++;
      else outcomes.bothIncorrect++;
    }
  }
  const allRows = rounds.flat().flatMap(suite => suite.rows);
  const unavailableSuites = rounds.flat().filter(suite => suite.plannedTrials === null).map(suite => suite.suite);
  const measured = measurements(allRows);
  const byArm = Object.fromEntries(['source', 'kast'].map(arm => [arm, measurements(allRows.filter(row => row.arm === arm))]));
  if (unavailableSuites.length) {
    for (const totals of [measured, ...Object.values(byArm)]) {
      totals.expectedTrials = null;
      totals.dollars = { ...totals.dollars, state: 'incomplete', expectedTrials: null,
        reasons: reasons([...(totals.dollars.reasons ?? []), 'MISSING_SUITE_PLAN']) };
      delete totals.dollars.total;
      totals.context = { state: 'incomplete', knownInputSum: totals.context.inputSum ?? totals.context.knownInputSum,
        observedResponses: totals.context.responses ?? totals.context.observedResponses,
        observedPeak: totals.context.peakInput ?? totals.context.observedPeak,
        reasons: reasons([...(totals.context.reasons ?? []), 'MISSING_SUITE_PLAN']) };
    }
  }
  const overhead = rounds.flat().map(suite => piMeasurementsForReport(suite.coordinator?.metadata?.accounting));
  return { outcomes, jointlyCorrectTokens, measurements: { ...measured,
    byArm,
    coordinatorOverhead: { role: 'coordinator', dollars: dollars(overhead, 'participant'),
      nestedDispatch: 'excluded; participant task receipts are counted once above' } },
    skillIdentity: { state: skillDigests.size === 1 ? 'matched' : 'unmatched-or-missing', digests: [...skillDigests] },
    unavailableSuites, rounds };
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  if (process.argv.length < 3) throw Error('Usage: node corpus-report.mjs RUN_DIRECTORY [RUN_DIRECTORY ...]');
  console.log(JSON.stringify(buildCorpusReport(process.argv.slice(2)), null, 2));
}
