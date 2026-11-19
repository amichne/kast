// Descriptive comparison of two arms in ONE Promptfoo JSON export.
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

function outcome(row) {
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

function count(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function sum(values) {
  return values.includes(null) ? null : values.reduce((a, b) => a + b, 0);
}

function summarize(rows) {
  const outcomes = rows.map(outcome);
  return {
    correct: outcomes.filter((x) => x === 'correct').length,
    incorrect: outcomes.filter((x) => x === 'incorrect').length,
    error: outcomes.filter((x) => x === 'error').length,
    missing: outcomes.filter((x) => x === 'missing').length,
    tokens: sum(rows.map((row) => count(row?.response?.tokenUsage?.total) || null)),
    toolCalls: sum(rows.map((row) => count(row?.response?.metadata?.toolCalls))),
  };
}

export function compare(document, left, right) {
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
  } else if (!allPairs.every(pair => matchedDeveloperInputs(pair, left, right))) {
    reason = 'UNMATCHED_DEVELOPER_INPUTS';
  } else if (
    !sameModel ||
    efforts.size !== 1 ||
    [...efforts].some((effort) => typeof effort !== 'string' || !effort)
  ) {
    reason = 'MODEL_OR_EFFORT_MISMATCH';
  } else if (leftOnly && rightOnly) {
    reason = 'MIXED_CORRECTNESS';
  } else if (leftOnly || rightOnly) {
    preference = { arm: leftOnly ? left : right, criterion: 'correctness' };
    reason = 'DIFFERENT_OBSERVED_CORRECTNESS';
  } else if (jointlyCorrect !== allPairs.length) {
    reason = 'CORRECTNESS_INCOMPLETE';
  } else {
    for (const field of ['tokens', 'toolCalls']) {
      if (Object.values(arms).some((arm) => arm[field] === null)) {
        reason = 'MISSING_' + (field === 'tokens' ? 'USAGE' : 'CALL_COUNT');
        break;
      }
      const delta = difference(field);
      if (delta !== 0) {
        preference = { arm: delta < 0 ? left : right, criterion: field };
        reason = 'DIFFERENT_OBSERVED_COST';
        break;
      }
    }
  }
  return { pairs: allPairs.length, jointlyCorrect, leftOnly, rightOnly, arms, perCase, preference, reason };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [path, left, right] = process.argv.slice(2);
  if (!path || !left || !right || process.argv.length !== 5)
    throw Error('Usage: npm run compare -- EXPORT.json ARM_A ARM_B');
  console.log(JSON.stringify(compare(JSON.parse(readFileSync(path, 'utf8')), left, right), null, 2));
}
