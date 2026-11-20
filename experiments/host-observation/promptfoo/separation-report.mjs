// Separation report from one Promptfoo export. It regrades each delivered answer
// against the admitted expected set, rechecks that the source and Kast prompts
// were identical across worlds, and counts every planned trial.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { arms, gradeAnswer, outcomes, separationReport } from './separation.mjs';

const blindArms = ['source', 'kast'];

function exportRows(document) {
  const rows = document?.results?.results ?? document?.results;
  if (!Array.isArray(rows)) throw Error('SEPARATION_EXPORT_UNREADABLE');
  return rows;
}

function sum(values) {
  return values.reduce((total, value) => total + (Number.isFinite(value) ? value : 0), 0);
}

export function reportExport(document) {
  const admissions = new Map();
  const rows = [];
  const descriptive = new Map();
  for (const row of exportRows(document)) {
    const response = row.response ?? {};
    const separation = response.metadata?.separation;
    const admission = separation?.admission;
    if (!separation || !admission || row.vars?.caseId !== separation.caseId || row.vars?.world !== separation.world ||
        row.vars?.arm !== separation.arm) {
      return { verdict: 'BLOCKED', failure: 'SEPARATION_ROW_UNKNOWN' };
    }
    const previous = admissions.get(admission.id);
    if (previous && !isDeepStrictEqual(previous, admission)) return { verdict: 'BLOCKED', failure: 'SEPARATION_ADMISSION_DIVERGED' };
    admissions.set(admission.id, admission);
    const world = admission.worlds.find(item => item.world === separation.world);
    if (!world) return { verdict: 'BLOCKED', failure: 'SEPARATION_ROW_UNKNOWN' };
    // Promptfoo writes a failed assertion's message to row.error; only the provider
    // response records a failed or replayed trial.
    const failed = Boolean(response.error || response.cached);
    const outcome = failed ? 'ERROR' : gradeAnswer(response.output, world.oracle);
    if (outcome !== separation.outcome) return { verdict: 'BLOCKED', failure: 'SEPARATION_OUTCOME_DIVERGED' };
    rows.push({ caseId: separation.caseId, world: separation.world, arm: separation.arm, outcome, promptSha256: separation.promptSha256 });
    const key = `${separation.caseId}\u0000${separation.arm}`;
    const entry = descriptive.get(key) ?? { tokens: [], toolCalls: [], completeKastCalls: [], answers: {},
      consulted: Object.fromEntries(outcomes.map(item => [item, 0])), unconsulted: Object.fromEntries(outcomes.map(item => [item, 0])) };
    const successfulKastCalls = (response.metadata?.calls ?? []).filter(call => call.tool === 'kast' && call.status === 0).length;
    entry.tokens.push(response.metadata?.usage?.total ?? response.tokenUsage?.total);
    entry.toolCalls.push(response.metadata?.toolCalls);
    entry.completeKastCalls.push(successfulKastCalls);
    entry[successfulKastCalls > 0 ? 'consulted' : 'unconsulted'][outcome]++;
    const answer = failed ? `ERROR:${response.error ?? 'cached'}` : String(response.output);
    entry.answers[`${separation.world}: ${answer}`] = (entry.answers[`${separation.world}: ${answer}`] ?? 0) + 1;
    descriptive.set(key, entry);
  }
  for (const admission of admissions.values()) {
    for (const arm of blindArms) {
      if (new Set(admission.worlds.map(world => world.promptSha256[arm])).size !== 1) {
        return { verdict: 'BLOCKED', failure: 'SEPARATION_TWIN_NOT_BLIND' };
      }
    }
  }
  const counts = new Map();
  for (const row of rows) {
    const key = `${row.caseId}\u0000${row.world}\u0000${row.arm}`;
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const repetitions = Math.max(0, ...counts.values());
  if (repetitions === 0) return { verdict: 'BLOCKED', failure: 'SEPARATION_EXPORT_EMPTY' };
  const report = separationReport([...admissions.values()].map(admission => ({ ...admission, state: 'ADMITTED' })), rows,
    { repetitions });
  if (report.verdict === 'BLOCKED') return report;
  for (const item of report.cases) {
    for (const arm of arms) {
      const entry = descriptive.get(`${item.id}\u0000${arm}`);
      if (!entry) continue;
      Object.assign(item.arms[arm], {
        totalTokens: sum(entry.tokens), toolCalls: sum(entry.toolCalls),
        successfulKastCalls: sum(entry.completeKastCalls), answers: entry.answers,
        // Descriptive split of the Kast configuration: what its answers were when the
        // participant ran Kast, and when it answered without running it.
        ...(arm === 'kast' && { byConsultation: { consulted: entry.consulted, unconsulted: entry.unconsulted } }),
      });
    }
  }
  return report;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [path] = process.argv.slice(2);
  if (!path) throw Error('Usage: node separation-report.mjs /absolute/results.json');
  const report = reportExport(JSON.parse(readFileSync(path, 'utf8')));
  console.log(JSON.stringify(report, null, 2));
  if (!['SEPARATED', 'NOT_SEPARATED'].includes(report.verdict)) process.exitCode = 2;
}
