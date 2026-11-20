// Separation owner. A twin case holds two or more worlds whose participant-visible
// input is byte-identical and whose independently derived answers differ. Any
// participant restricted to that input sees the same request in every world, so
// its answer distribution cannot depend on the world: over k worlds with pairwise
// distinct answers its expected accuracy is at most 1/k. That ceiling holds for
// every model, effort, prompt and text-only tool set; it needs no baseline trial.
// A configuration separates on a case when the one-sided lower confidence bound
// of its observed accuracy exceeds that ceiling. This module is pure.
import { createHash } from 'node:crypto';

export const arms = Object.freeze(['source', 'kast', 'materialized']);
export const outcomes = Object.freeze(['CORRECT', 'ABSTAINED', 'WRONG', 'ERROR']);
export const confidence = Object.freeze({ level: 0.95, method: 'CLOPPER_PEARSON_ONE_SIDED' });
const none = 'NONE';
const unknown = 'UNKNOWN';
const sha256 = text => createHash('sha256').update(text).digest('hex');

// The single answer contract for every case: one member per line, NONE for an
// empty set, UNKNOWN to abstain. Order is free; nothing else is normalized.
export const answerInstruction = `Return one per line with no other text. Return ${none} if there are none. ` +
  `Return ${unknown} if the available evidence does not decide it.`;

export function canonicalSet(members) {
  if (!Array.isArray(members) || members.some(member => typeof member !== 'string' || member === '' ||
      member !== member.trim() || member.includes('\n') || member === none || member === unknown)) {
    throw Error('SEPARATION_ORACLE_INVALID');
  }
  const sorted = [...new Set(members)].sort();
  if (sorted.length !== members.length) throw Error('SEPARATION_ORACLE_INVALID');
  return sorted;
}

export function readAnswer(output) {
  if (typeof output !== 'string') return { type: 'UNREADABLE' };
  const lines = output.trim().split('\n').map(line => line.trim()).filter(Boolean);
  if (lines.length === 1 && lines[0] === unknown) return { type: 'ABSTAINED' };
  if (lines.length === 1 && lines[0] === none) return { type: 'SET', members: [] };
  if (lines.length === 0 || lines.some(line => line === none || line === unknown) ||
      new Set(lines).size !== lines.length) return { type: 'UNREADABLE' };
  return { type: 'SET', members: lines.sort() };
}

export function gradeAnswer(output, oracle) {
  const expected = canonicalSet(oracle);
  const answer = readAnswer(output);
  if (answer.type === 'ABSTAINED') return 'ABSTAINED';
  if (answer.type !== 'SET') return 'WRONG';
  return answer.members.length === expected.length && answer.members.every((member, index) => member === expected[index])
    ? 'CORRECT' : 'WRONG';
}

// Admission happens before any model call. It proves the two properties the
// ceiling depends on from the bytes that will actually be delivered.
export function admitTwin({ id, request, visible, worlds }) {
  const reject = failure => ({ state: 'REJECTED', id, failure });
  if (typeof id !== 'string' || !/^[a-z][a-z0-9-]*$/.test(id)) return reject('TWIN_ID_INVALID');
  if (typeof request !== 'string' || !request.trim()) return reject('TWIN_REQUEST_MISSING');
  if (!Array.isArray(worlds) || worlds.length < 2) return reject('TWIN_TOO_FEW_WORLDS');
  const names = worlds.map(world => world?.world);
  if (names.some(name => typeof name !== 'string' || !name) || new Set(names).size !== names.length) {
    return reject('TWIN_WORLD_NAMES_INVALID');
  }
  if (worlds.some(world => typeof world.visible !== 'string' || !world.visible)) return reject('TWIN_VISIBLE_INPUT_MISSING');
  if (new Set(worlds.map(world => world.visible)).size !== 1 || worlds[0].visible !== visible) {
    return reject('TWIN_VISIBLE_INPUT_DIFFERS');
  }
  let oracles;
  try { oracles = worlds.map(world => canonicalSet(world.oracle)); } catch { return reject('TWIN_ORACLE_INVALID'); }
  if (new Set(oracles.map(oracle => JSON.stringify(oracle))).size !== worlds.length) return reject('TWIN_ORACLES_NOT_DISTINCT');
  if (worlds.some(world => typeof world.fact !== 'string' || !world.fact.trim()) ||
      new Set(worlds.map(world => world.fact)).size !== worlds.length) return reject('TWIN_FACTS_NOT_DISTINCT');
  const prompts = Object.fromEntries(worlds.map(world => [world.world,
    Object.fromEntries(arms.map(arm => [arm, renderPrompt(request, visible, arm === 'materialized' ? world.fact : undefined)]))]));
  const blind = ['source', 'kast'].map(arm => new Set(names.map(name => prompts[name][arm])).size);
  if (blind.some(size => size !== 1)) return reject('TWIN_VISIBLE_INPUT_DIFFERS');
  return {
    state: 'ADMITTED', id,
    visibleSha256: sha256(visible),
    ceiling: 1 / worlds.length,
    worlds: worlds.map((world, index) => ({
      world: world.world, oracle: oracles[index], factSha256: sha256(world.fact),
      prompts: prompts[world.world], promptSha256: Object.fromEntries(arms.map(arm => [arm, sha256(prompts[world.world][arm])])),
    })),
  };
}

export function renderPrompt(request, visible, fact) {
  return `${request}\n${answerInstruction}\n\n${visible}` + (fact === undefined ? '' : `\n${fact}`);
}

// Exact binomial tail P(X >= x) for X ~ Binomial(n, p), in log space.
function upperTail(x, n, p) {
  if (x <= 0) return 1;
  if (p <= 0) return 0;
  if (p >= 1) return 1;
  let logChoose = 0;
  const terms = [];
  for (let k = 0; k <= n; k++) {
    if (k > 0) logChoose += Math.log(n - k + 1) - Math.log(k);
    if (k >= x) terms.push(logChoose + k * Math.log(p) + (n - k) * Math.log1p(-p));
  }
  const peak = Math.max(...terms);
  return Math.min(1, Math.exp(peak) * terms.reduce((sum, term) => sum + Math.exp(term - peak), 0));
}

export function lowerConfidenceBound(successes, trials, level = confidence.level) {
  if (!Number.isSafeInteger(trials) || trials < 1 || !Number.isSafeInteger(successes) || successes < 0 || successes > trials) {
    throw Error('SEPARATION_COUNTS_INVALID');
  }
  if (successes === 0) return 0;
  const alpha = 1 - level;
  if (successes === trials) return alpha ** (1 / trials);
  let low = 0;
  let high = successes / trials;
  for (let step = 0; step < 200; step++) {
    const middle = (low + high) / 2;
    if (upperTail(successes, trials, middle) < alpha) low = middle;
    else high = middle;
  }
  return low;
}

function tally(rows) {
  const counts = Object.fromEntries(outcomes.map(outcome => [outcome, 0]));
  for (const row of rows) counts[row.outcome]++;
  return { trials: rows.length, ...counts };
}

// Every planned trial counts. A provider error is a failure of the configuration
// that produced it, never an exclusion. A missing or unbalanced plan has no verdict.
export function separationReport(admissions, rows, { repetitions }) {
  if (!Number.isSafeInteger(repetitions) || repetitions < 1) throw Error('SEPARATION_REPETITIONS_INVALID');
  const known = new Map(admissions.map(admission => [admission.id, admission]));
  if (admissions.some(admission => admission.state !== 'ADMITTED') || known.size !== admissions.length) {
    return { verdict: 'BLOCKED', failure: 'SEPARATION_CASE_UNADMITTED' };
  }
  for (const row of rows) {
    const admission = known.get(row.caseId);
    const world = admission?.worlds.find(candidate => candidate.world === row.world);
    if (!world || !arms.includes(row.arm) || !outcomes.includes(row.outcome)) {
      return { verdict: 'BLOCKED', failure: 'SEPARATION_ROW_UNKNOWN' };
    }
    if (row.promptSha256 !== world.promptSha256[row.arm]) return { verdict: 'BLOCKED', failure: 'SEPARATION_PROMPT_DIVERGED' };
  }
  const cases = admissions.map(admission => {
    const byArm = Object.fromEntries(arms.map(arm => {
      const armRows = rows.filter(row => row.caseId === admission.id && row.arm === arm);
      const perWorld = admission.worlds.map(world => armRows.filter(row => row.world === world.world).length);
      const balanced = perWorld.every(count => count === repetitions);
      const counts = tally(armRows);
      return [arm, {
        ...counts, balanced,
        accuracy: counts.trials ? counts.CORRECT / counts.trials : null,
        lowerBound: counts.trials ? lowerConfidenceBound(counts.CORRECT, counts.trials) : null,
      }];
    }));
    return { id: admission.id, worlds: admission.worlds.length, ceiling: admission.ceiling, arms: byArm,
      verdict: verdictFor(byArm.kast, admission.ceiling) };
  });
  const pooled = Object.fromEntries(arms.map(arm => {
    const armRows = rows.filter(row => row.arm === arm);
    const counts = tally(armRows);
    return [arm, { ...counts, accuracy: counts.trials ? counts.CORRECT / counts.trials : null,
      lowerBound: counts.trials ? lowerConfidenceBound(counts.CORRECT, counts.trials) : null }];
  }));
  // Every case contributes the same number of trials per world, so the pooled
  // ceiling is the trial-weighted mean of the case ceilings.
  const weights = admissions.map(admission => admission.worlds.length * repetitions);
  const ceiling = admissions.reduce((sum, admission, index) => sum + admission.ceiling * weights[index], 0) /
    weights.reduce((sum, weight) => sum + weight, 0);
  const complete = cases.every(item => arms.every(arm => item.arms[arm].balanced));
  return {
    confidence, repetitions, ceiling,
    verdict: complete ? verdictFor({ ...pooled.kast, balanced: true }, ceiling) : 'INCOMPLETE',
    pooled, cases,
  };
}

function verdictFor(counts, ceiling) {
  if (!counts.balanced) return 'INCOMPLETE';
  return counts.lowerBound > ceiling ? 'SEPARATED' : 'NOT_SEPARATED';
}
