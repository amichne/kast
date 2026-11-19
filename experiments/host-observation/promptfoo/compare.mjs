// Descriptive comparison of two arms in ONE Promptfoo JSON export.
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { contextForComparison, piMeasurementsForReport, taskCostForComparison } from './pi-accounting.mjs';

export function outcome(row) {
  if (!row) return 'missing';
  if (
    row.response?.error ||
    row.response?.cached ||
    row.failureReason === 2 ||
    !Object.hasOwn(row.namedScores ?? {}, 'correctness')
  )
    return 'error';
  if (row.namedScores.correctness !== Number(row.success)) return 'error';
  if (row.gradingResult?.pass === true && row.success === true && row.failureReason === 0) return 'correct';
  if (row.gradingResult?.pass === false && row.success === false && row.failureReason === 1)
    return 'incorrect';
  return 'error';
}

function matchedDeveloperInputs(pair, left, right) {
  const a = pair.get(left);
  if (a?.vars.comparison !== 'developer-tasks') return true;
  const b = pair.get(right);
  return ['taskSha256', 'sourceSha256'].every(key => {
    const digest = a.response?.metadata?.[key];
    return typeof digest === 'string' && /^[a-f0-9]{64}$/.test(digest) && digest === b?.response?.metadata?.[key];
  });
}

function matchedPanelInputs(pair, left, right) {
  const a = pair.get(left), b = pair.get(right);
  const panel = a?.vars?.panel ?? a?.response?.metadata?.panel;
  if (!['A', 'B'].includes(panel)) return true;
  const first = a.response?.metadata, second = b?.response?.metadata;
  const identity = panel === 'B' ? 'inputSha256' : 'sourceSha256';
  if (!first || !second || !['taskSha256', identity].every(key =>
    typeof first[key] === 'string' && /^[a-f0-9]{64}$/.test(first[key]) && first[key] === second[key])) return false;
  const meaningful = metadata => {
    const permission = metadata.permissions, budget = metadata.budgets;
    if (!permission || !budget) return false;
    if (panel === 'B') return Array.isArray(permission.tools) && permission.tools.length === 0 &&
      Number.isSafeInteger(budget.wallMillis) && budget.wallMillis > 0 && budget.maximumProviderResponses === 1 &&
      budget.maximumToolProposals === 0 && budget.maximumToolExecutions === 0;
    return permission.sourceRead === 'ISOLATED_FIXTURE' &&
      permission.sourceWrite === 'KOTLIN_PRODUCTION_EXISTING_DIRECTORIES' && permission.arbitraryCommands === false &&
      Array.isArray(permission.gradedEditPaths) && Array.isArray(permission.gradedNewSourceDirs) &&
      Array.isArray(permission.toolNames) && ['list_files', 'read_file', 'search_source', 'compile_project']
        .every(name => permission.toolNames.includes(name)) &&
      Number.isSafeInteger(budget.participantMillis) && budget.participantMillis > 0 &&
      Number.isSafeInteger(budget.hostPreflightMillis) && budget.hostPreflightMillis > 0;
  };
  if (!meaningful(first) || !meaningful(second)) return false;
  // Kast access is the treatment. Ordinary read/edit permissions must stay matched.
  const permissions = value => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    const { toolNames, ...common } = value;
    return { ...common, ...(Array.isArray(toolNames) && {
      toolNames: toolNames.filter(name => !['kast', 'read_kast_guidance'].includes(name)).toSorted(),
    }) };
  };
  return first.budgets && second.budgets && isDeepStrictEqual(first.budgets, second.budgets) &&
    permissions(first.permissions) !== null && isDeepStrictEqual(permissions(first.permissions), permissions(second.permissions)) &&
    (panel !== 'A' || first.admission?.state === 'ADMITTED' && second.admission?.state === 'ADMITTED');
}

// Panel trials are measurements only when both participants saw an admitted,
// measurement-free surface under the same vocabulary. Legacy rows carry no panel.
function blindedPanelInputs(pair, left, right) {
  const a = pair.get(left), b = pair.get(right);
  const panel = a?.vars?.panel ?? a?.response?.metadata?.panel;
  if (!['A', 'B'].includes(panel)) return true;
  const surfaces = [a, b].map(row => row?.response?.metadata?.participantSurface);
  return surfaces.every(surface => surface?.state === 'ADMITTED' && Number.isSafeInteger(surface.vocabularyVersion)) &&
    surfaces[0].vocabularyVersion === surfaces[1].vocabularyVersion;
}

function count(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function sum(values) {
  return values.includes(null) ? null : values.reduce((a, b) => a + b, 0);
}

function nativeIdentity(row) {
  const accounting = row?.response?.metadata?.accounting;
  // Incomplete context must not erase a known model mismatch on a failed or cutoff attempt.
  if (piMeasurementsForReport(accounting).state !== 'admitted') return { state: 'unavailable' };
  const identities = accounting.records.filter(record => record.role === 'participant').map(record => record.identity);
  if (!identities.length) return { state: 'unavailable' };
  const fields = ['provider', 'model', 'responseModel', 'thinkingLevel', 'providerThinkingLevel'];
  if (identities.some(identity => fields.some(key => identity[key] !== undefined &&
    (typeof identity[key] !== 'string' || !identity[key])))) return { state: 'unavailable' };
  const fingerprints = [...new Set(identities.map(identity => JSON.stringify(fields.map(key => identity[key] ?? null))))];
  return { state: fingerprints.length === 1 ? 'observed' : 'mixed', fingerprints,
    resolved: identities.length > 0 && identities.every(identity => typeof identity.responseModel === 'string') };
}

function endToEndTime(timing) {
  if (timing?.clock !== 'MONOTONIC' || !['DEVELOPER_PROVIDER', 'CORPUS_PROVIDER'].includes(timing.boundary) ||
      !Array.isArray(timing.phases) || new Set(timing.phases.map(phase => phase.phase)).size !== timing.phases.length ||
      !['preparation', 'participant', 'grading', 'cleanup'].every(name => timing.phases.some(phase =>
        phase.phase === name && phase.outcome === 'COMPLETED' && Number.isFinite(phase.elapsedMillis) && phase.elapsedMillis >= 0))) return null;
  return Number.isFinite(timing.wallMillis) && timing.wallMillis >= 0 ? timing.wallMillis : null;
}

function meters(row) {
  const metadata = row?.response?.metadata;
  const cost = taskCostForComparison(row?.response?.cached ? undefined : metadata?.accounting);
  const context = contextForComparison(row?.response?.cached ? undefined : metadata?.accounting);
  return { cost, dollars: cost.state === 'ready' ? cost.total : null,
    peakContext: context.state === 'ready' ? context.peakInput : null,
    modelTurns: context.state === 'ready' ? context.responses : null,
    endToEndMillis: endToEndTime(metadata?.timing) };
}

function summarize(rows) {
  const outcomes = rows.map(outcome);
  const measured = rows.map(meters);
  return {
    correct: outcomes.filter((x) => x === 'correct').length,
    incorrect: outcomes.filter((x) => x === 'incorrect').length,
    error: outcomes.filter((x) => x === 'error').length,
    missing: outcomes.filter((x) => x === 'missing').length,
    tokens: sum(rows.map((row) => count(row?.response?.tokenUsage?.total))),
    toolCalls: sum(rows.map((row) => count(row?.response?.metadata?.toolCalls))),
    ...Object.fromEntries(['dollars', 'peakContext', 'modelTurns', 'endToEndMillis']
      .map(key => [key, sum(measured.map(item => item[key]))])),
    accounting: { completeTrials: measured.filter(item => item.cost.state === 'ready').length,
      unavailableReasons: [...new Set(measured.flatMap(item => item.cost.reasons ?? []))] },
  };
}

// Pairs rows by suite, cohort, case and repetition. Review packets reuse this
// owner so a reviewed pair is exactly a compared pair.
export function pairRows(document, left, right) {
  if (!left || !right || left === right) throw Error('Select two distinct arm labels');
  const cases = new Map();
  const rows = document?.results?.results;
  if (!Array.isArray(rows)) throw Error('Expected a Promptfoo JSON export');
  for (const row of rows) {
    const { arm, caseId, comparison = '', cohort = '', repeat = 0 } = row.vars ?? {};
    if (arm !== left && arm !== right) continue;
    if (typeof caseId !== 'string' || !caseId.length) throw Error('Paired row lacks caseId');
    const caseKey = JSON.stringify([comparison, cohort, caseId]);
    if (!cases.has(caseKey)) cases.set(caseKey, { caseId, comparison, cohort, trials: new Map() });
    const trials = cases.get(caseKey).trials;
    const repetition = JSON.stringify([repeat, row.repeatIndex ?? 0]);
    if (!trials.has(repetition)) trials.set(repetition, new Map());
    const pair = trials.get(repetition);
    if (pair.has(arm)) throw Error('Duplicate arm in case ' + caseId + ', repetition ' + repetition);
    pair.set(arm, row);
  }
  if (!cases.size) throw Error('No results for the selected arm labels');
  return cases;
}

export function compare(document, left, right) {
  const cases = pairRows(document, left, right);
  const allPairs = [...cases.values()].flatMap((item) => [...item.trials.values()]);
  const arms = Object.fromEntries(
    [left, right].map((arm) => [arm, summarize(allPairs.map((pair) => pair.get(arm)))]),
  );
  const jointlyCorrect = allPairs.filter((pair) =>
    [left, right].every((arm) => outcome(pair.get(arm)) === 'correct'),
  ).length;
  const leftOnly = allPairs.filter(
    (pair) => outcome(pair.get(left)) === 'correct' && outcome(pair.get(right)) === 'incorrect',
  ).length;
  const rightOnly = allPairs.filter(
    (pair) => outcome(pair.get(right)) === 'correct' && outcome(pair.get(left)) === 'incorrect',
  ).length;
  const models = new Set(
    allPairs.flatMap((pair) => [...pair.values()].map((row) => row.response?.metadata?.model)),
  );
  const efforts = new Set(
    allPairs.flatMap((pair) => [...pair.values()].map((row) => row.response?.metadata?.effort)),
  );
  const panels = new Set(allPairs.flatMap(pair => [...pair.values()].map(row => row.vars?.panel ?? row.response?.metadata?.panel ?? 'legacy')));
  const identities = allPairs.map(pair => [nativeIdentity(pair.get(left)), nativeIdentity(pair.get(right))]);
  const sameModel =
    models.size === 1 && [...models].every((model) => typeof model === 'string' && model.length > 0);
  const perCase = [...cases.values()].map(({ caseId, comparison, cohort, trials }) => ({
    caseId,
    comparison,
    cohort,
    repetitions: trials.size,
    arms: Object.fromEntries(
      [left, right].map((arm) => [arm, summarize([...trials.values()].map((pair) => pair.get(arm)))]),
    ),
  }));

  // Each case has equal weight, even if a suite repeats the cases at different rates.
  function difference(field) {
    return perCase.reduce(
      (total, item) => total + (item.arms[left][field] - item.arms[right][field]) / item.repetitions,
      0,
    );
  }
  let preference = null;
  let reason = 'TIED_ON_MEASURED_CRITERIA';
  if (Object.values(arms).some((arm) => arm.missing || arm.error)) {
    reason = 'INCOMPLETE_OR_INVALID_PAIR';
  } else if (panels.size !== 1 || allPairs.some(pair => [...pair.values()].some(row =>
    row.vars?.panel && row.response?.metadata?.panel && row.vars.panel !== row.response.metadata.panel))) {
    reason = 'PANEL_MISMATCH';
  } else if (!allPairs.every(pair => matchedDeveloperInputs(pair, left, right))) {
    reason = 'UNMATCHED_DEVELOPER_INPUTS';
  } else if (!allPairs.every(pair => matchedPanelInputs(pair, left, right))) {
    reason = 'UNMATCHED_PANEL_INPUTS';
  } else if (!allPairs.every(pair => blindedPanelInputs(pair, left, right))) {
    reason = 'UNBLINDED_PARTICIPANT_SURFACE';
  } else if (
    !sameModel ||
    efforts.size !== 1 ||
    [...efforts].some((effort) => typeof effort !== 'string' || !effort)
  ) {
    reason = 'MODEL_OR_EFFORT_MISMATCH';
  } else if (identities.some(([a, b]) => a.state === 'mixed' || b.state === 'mixed' ||
    a.state === 'observed' && b.state === 'observed' && !isDeepStrictEqual(a.fingerprints, b.fingerprints))) {
    reason = 'RESOLVED_MODEL_OR_EFFORT_MISMATCH';
  } else if (leftOnly && rightOnly) {
    reason = 'MIXED_CORRECTNESS';
  } else if (leftOnly || rightOnly) {
    preference = { arm: leftOnly ? left : right, criterion: 'correctness' };
    reason = 'DIFFERENT_OBSERVED_CORRECTNESS';
  } else if (jointlyCorrect !== allPairs.length) {
    reason = 'CORRECTNESS_INCOMPLETE';
  } else {
    const costs = allPairs.map(pair => [meters(pair.get(left)).cost, meters(pair.get(right)).cost]);
    if (costs.some(pair => pair.some(cost => cost.state !== 'ready'))) {
      reason = 'INCOMPLETE_DOLLAR_ACCOUNTING';
    } else if (identities.some(pair => pair.some(identity => identity.state !== 'observed' || !identity.resolved))) {
      reason = 'UNRESOLVED_MODEL_IDENTITY';
    } else if (costs.some(([a, b]) => a.provenance !== b.provenance || !isDeepStrictEqual(a.pricingBasis, b.pricingBasis))) {
      reason = 'MISMATCHED_COST_BASIS';
    } else {
      const criteria = [
        ['dollars', 'INCOMPLETE_DOLLAR_ACCOUNTING', 'DIFFERENT_OBSERVED_DOLLARS'],
        ['peakContext', 'INCOMPLETE_CONTEXT_ACCOUNTING', 'DIFFERENT_OBSERVED_CONTEXT'],
        ['modelTurns', 'INCOMPLETE_CONTEXT_ACCOUNTING', 'DIFFERENT_OBSERVED_MODEL_TURNS'],
        ['endToEndMillis', 'MISSING_END_TO_END_TIME', 'DIFFERENT_OBSERVED_END_TO_END_TIME'],
      ];
      for (const [field, missing, changed] of criteria) {
        if (Object.values(arms).some(arm => arm[field] === null)) { reason = missing; break; }
        if (field === 'endToEndMillis' && allPairs.some(pair =>
          pair.get(left).response.metadata.timing.boundary !== pair.get(right).response.metadata.timing.boundary)) {
          reason = 'MISMATCHED_TIMING_BASIS'; break;
        }
        let delta = difference(field);
        if (field === 'dollars' && Math.abs(delta) <= Number.EPSILON * 8 *
          Math.max(1, Math.abs(arms[left].dollars), Math.abs(arms[right].dollars))) delta = 0;
        if (delta !== 0) {
          preference = { arm: delta < 0 ? left : right, criterion: field };
          reason = changed;
          break;
        }
      }
    }
  }
  return { panel: panels.size === 1 ? [...panels][0] : null,
    modelEvidence: identities.every(pair => pair.every(identity => identity.state === 'observed' && identity.resolved))
      ? 'RESOLVED_PARTICIPANT_IDENTITIES' : 'INCOMPLETE_NATIVE_IDENTITY',
    pairs: allPairs.length, jointlyCorrect, leftOnly, rightOnly, arms, perCase, preference, reason };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [path, left, right] = process.argv.slice(2);
  if (!path || !left || !right || process.argv.length !== 5)
    throw Error('Usage: npm run compare -- EXPORT.json ARM_A ARM_B');
  console.log(JSON.stringify(compare(JSON.parse(readFileSync(path, 'utf8')), left, right), null, 2));
}
