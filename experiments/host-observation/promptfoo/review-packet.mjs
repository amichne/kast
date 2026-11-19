// Blinded review of ONE Promptfoo JSON export (pstack eval playbook, steps 5-7).
// The executable oracle stays the authority for comparisons. This owner lets a
// reviewer read every delivered answer under a neutral label, record a verdict
// against the reviewer-only frame, and then see where that verdict disagrees
// with the oracle. A disagreement means the oracle, the frame or the review
// needs attention; it never changes a recorded outcome.
import { createHash, createHmac, randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { outcome, pairRows } from './compare.mjs';
import { portfolioFrames } from './portfolio.mjs';

export const reviewVersion = 1;
export const reviewVerdicts = Object.freeze(['correct', 'incorrect', 'unsure']);
const labels = Object.freeze(['A', 'B']);
const productName = /\bkast\b/i;
const instructions = [
  'Each item is one task with two delivered answers, labeled A and B.',
  'Judge both answers in one pass on the same scale, using only the task, the criteria and the answers.',
  'Record correct, incorrect or unsure for every label before opening the key.',
].join(' ');

const digest = (key, value) => createHmac('sha256', key).update(value).digest();
const text = value => value === undefined || value === null ? null : typeof value === 'string' ? value : JSON.stringify(value);

function taskText(row) {
  return text(row?.vars?.task ?? row?.prompt?.raw);
}

// Bounded tool evidence for chain review after verdicts: names and outcome
// codes only, never arguments, inputs or source payloads.
function transcript(row) {
  const calls = row?.response?.metadata?.calls;
  if (!Array.isArray(calls)) return null;
  return calls.map(call => ({
    tool: typeof call?.tool === 'string' ? call.tool : null,
    outcome: typeof call?.type === 'string' ? call.type : typeof call?.reason === 'string' ? call.reason : null,
  }));
}

function delivered(row) {
  const response = row?.response;
  const answer = response && !response.error ? text(response.output) : null;
  const patch = response && !response.error && typeof response.metadata?.patch === 'string' ? response.metadata.patch : null;
  return { delivered: answer !== null || patch !== null, output: answer, patch };
}

export function buildReviewPacket(document, left, right, { seed = randomBytes(32).toString('hex') } = {}) {
  if (typeof seed !== 'string' || !/^[a-f0-9]{64}$/.test(seed)) throw Error('REVIEW_SEED_REJECTED');
  const cases = pairRows(document, left, right);
  const pairs = [...cases.values()].flatMap(item => [...item.trials.entries()].map(([repetition, pair]) => {
    const identity = JSON.stringify([item.comparison, item.cohort, item.caseId, repetition]);
    return { ...item, repetition, pair, identity, order: digest(seed, 'order:' + identity).toString('hex') };
  })).sort((a, b) => a.order.localeCompare(b.order));
  const width = String(pairs.length).length;
  const items = [], key = {};
  pairs.forEach((entry, index) => {
    const id = 'R' + String(index + 1).padStart(Math.max(2, width), '0');
    const swapped = (digest(seed, 'labels:' + entry.identity)[0] & 1) === 1;
    const arms = swapped ? [right, left] : [left, right];
    const rows = arms.map(arm => entry.pair.get(arm));
    const exposures = [];
    const tasks = [...new Set(rows.filter(Boolean).map(taskText))];
    // Identical task text cannot distinguish the labels. Differing text, such as a
    // row identifier that names the configuration, is withheld and recorded.
    const task = tasks.length === 1 ? tasks[0] : null;
    if (tasks.length > 1) exposures.push({ kind: 'TASK_TEXT_DIFFERS' });
    const outputs = rows.map((row, position) => {
      const value = delivered(row);
      if ([value.output, value.patch].some(part => part !== null && productName.test(part))) {
        exposures.push({ kind: 'PRODUCT_NAME_IN_OUTPUT', label: labels[position] });
      }
      return { label: labels[position], ...value };
    });
    const frame = portfolioFrames[entry.caseId];
    items.push({ id, caseId: entry.caseId, task, criteria: frame?.criteria ?? null, outputs });
    key[id] = {
      caseId: entry.caseId, comparison: entry.comparison, cohort: entry.cohort, repetition: entry.repetition,
      labels: Object.fromEntries(labels.map((label, position) => [label, arms[position]])),
      oracle: Object.fromEntries(labels.map((label, position) => [label, outcome(rows[position])])),
      transcripts: Object.fromEntries(labels.map((label, position) => [label, transcript(rows[position])])),
      exposures,
    };
  });
  const exportSha256 = createHash('sha256').update(JSON.stringify(document)).digest('hex');
  return {
    packet: { version: reviewVersion, exportSha256, instructions, items },
    key: { version: reviewVersion, exportSha256, seed, arms: [left, right], items: key },
  };
}

// Outcome per label: AGREE, DISAGREE, REVIEWER_UNSURE, ORACLE_UNASSESSED
// (the oracle recorded an error or missing row) or UNREVIEWED.
export function reconcileReview(key, reviews) {
  if (key?.version !== reviewVersion || !key.items || typeof key.items !== 'object') throw Error('REVIEW_KEY_REJECTED');
  if (reviews?.version !== reviewVersion || reviews.exportSha256 !== key.exportSha256 ||
      !reviews.verdicts || typeof reviews.verdicts !== 'object' || Array.isArray(reviews.verdicts)) {
    throw Error('REVIEW_VERDICTS_REJECTED');
  }
  for (const [id, verdict] of Object.entries(reviews.verdicts)) {
    if (!Object.hasOwn(key.items, id)) throw Error('REVIEW_ITEM_UNKNOWN');
    if (!verdict || typeof verdict !== 'object' || Object.keys(verdict).some(label => !labels.includes(label)) ||
        Object.values(verdict).some(value => !reviewVerdicts.includes(value))) throw Error('REVIEW_VERDICTS_REJECTED');
  }
  const results = [];
  for (const [id, item] of Object.entries(key.items)) {
    for (const label of labels) {
      const reviewer = reviews.verdicts[id]?.[label];
      const oracle = item.oracle[label];
      const status = reviewer === undefined ? 'UNREVIEWED'
        : !['correct', 'incorrect'].includes(oracle) ? 'ORACLE_UNASSESSED'
          : reviewer === 'unsure' ? 'REVIEWER_UNSURE'
            : reviewer === oracle ? 'AGREE' : 'DISAGREE';
      results.push({ id, label, arm: item.labels[label], caseId: item.caseId, oracle, reviewer: reviewer ?? null, status,
        blind: item.exposures.length === 0, ...(status === 'DISAGREE' && { transcript: item.transcripts[label] }) });
    }
  }
  const counts = Object.fromEntries(['AGREE', 'DISAGREE', 'REVIEWER_UNSURE', 'ORACLE_UNASSESSED', 'UNREVIEWED']
    .map(status => [status, results.filter(result => result.status === status).length]));
  return {
    complete: counts.UNREVIEWED === 0, blind: results.every(result => result.blind), counts,
    disagreements: results.filter(result => result.status === 'DISAGREE'),
    unblinded: [...new Set(results.filter(result => !result.blind).map(result => result.id))],
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'build' && args.length === 4 && isAbsolute(args[3])) {
    const [path, left, right, directory] = args;
    const { packet, key } = buildReviewPacket(JSON.parse(readFileSync(path, 'utf8')), left, right);
    // Never overwrite an earlier packet: its key may already be in use.
    writeFileSync(join(directory, 'packet.json'), JSON.stringify(packet, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    writeFileSync(join(directory, 'key.json'), JSON.stringify(key, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    console.log(JSON.stringify({ items: packet.items.length, exportSha256: packet.exportSha256,
      unblinded: Object.entries(key.items).filter(([, item]) => item.exposures.length).map(([id]) => id) }, null, 2));
  } else if (command === 'reconcile' && args.length === 2) {
    const [keyPath, reviewsPath] = args;
    console.log(JSON.stringify(reconcileReview(JSON.parse(readFileSync(keyPath, 'utf8')),
      JSON.parse(readFileSync(reviewsPath, 'utf8'))), null, 2));
  } else {
    throw Error('Usage: npm run review -- build EXPORT.json ARM_A ARM_B /absolute/output-directory | reconcile KEY.json REVIEWS.json');
  }
}
