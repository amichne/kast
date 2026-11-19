import assert from 'node:assert/strict';
import test from 'node:test';
import { buildReviewPacket, reconcileReview } from './review-packet.mjs';
import { portfolioFrames } from './portfolio.mjs';

const seed = 'a'.repeat(64);
const row = (caseId, arm, pass, output, extra = {}) => ({
  vars: { caseId, arm, task: `Repair ${caseId}.` },
  success: pass, failureReason: pass ? 0 : 1, namedScores: { correctness: Number(pass) }, gradingResult: { pass },
  response: { output, metadata: { model: 'secret-model', calls: [{ tool: arm === 'kast' ? 'kast' : 'read_file', type: 'COMPLETE', argv: ['--secret'] }] } },
  ...extra,
});
const document = rows => ({ results: { results: rows } });
const exported = document([
  row('coroutine-delay-rounding', 'source', false, 'Rounded down.'),
  row('coroutine-delay-rounding', 'kast', true, 'Rounded up.'),
  row('picnic-ansi', 'source', true, 'Escapes are zero width.'),
  row('picnic-ansi', 'kast', true, 'Escapes are zero width too.'),
]);

test('the reviewer packet hides configurations, models, costs and oracle outcomes', () => {
  const { packet, key } = buildReviewPacket(exported, 'source', 'kast', { seed });
  const visible = JSON.stringify(packet);
  for (const hidden of ['source', 'kast', 'secret-model', 'read_file', '--secret', 'gradingResult', 'COMPLETE']) {
    assert.doesNotMatch(visible, new RegExp(`\\b${hidden}\\b`), hidden);
  }
  assert.equal(packet.items.length, 2);
  for (const item of packet.items) {
    assert.deepEqual(item.outputs.map(output => output.label), ['A', 'B']);
    assert.deepEqual(item.criteria, portfolioFrames[item.caseId].criteria);
    assert.deepEqual(key.items[item.id].exposures, []);
    assert.deepEqual(Object.values(key.items[item.id].labels).sort(), ['kast', 'source']);
  }
  // The key retains bounded chain evidence for later review, never call arguments.
  assert.doesNotMatch(JSON.stringify(key), /--secret/);
});

test('label assignment and item order are reproducible from the seed and vary with it', () => {
  const first = buildReviewPacket(exported, 'source', 'kast', { seed });
  assert.deepEqual(buildReviewPacket(exported, 'source', 'kast', { seed }), first);
  const assignments = new Set(['0', '1', '2', '3', '4', '5', '6', '7'].map(digit => {
    const { key } = buildReviewPacket(exported, 'source', 'kast', { seed: digit.repeat(64) });
    return JSON.stringify(Object.values(key.items).map(item => [item.caseId, item.labels.A]));
  }));
  assert.ok(assignments.size > 1);
  assert.throws(() => buildReviewPacket(exported, 'source', 'kast', { seed: 'short' }), /REVIEW_SEED_REJECTED/);
});

test('arm-revealing task text, failures and product mentions are recorded as exposures, not shown', () => {
  const leaking = document([
    row('C5', 'evidence', true, 'insufficient', { vars: { caseId: 'C5', arm: 'evidence', task: 'C5-evidence' } }),
    row('C5', 'evidence-with-kast-guidance', true, 'Kast says insufficient',
      { vars: { caseId: 'C5', arm: 'evidence-with-kast-guidance', task: 'C5-evidence-with-kast-guidance' } }),
    row('picnic-ansi', 'evidence', false, null, { response: { error: 'DEVELOPER_HOST_PREFLIGHT_FAILED', metadata: {} } }),
    row('picnic-ansi', 'evidence-with-kast-guidance', true, 'Done.'),
  ]);
  const { packet, key } = buildReviewPacket(leaking, 'evidence', 'evidence-with-kast-guidance', { seed });
  assert.doesNotMatch(JSON.stringify(packet), /evidence-with|HOST_PREFLIGHT/);
  const scope = packet.items.find(item => item.caseId === 'C5');
  assert.equal(scope.task, null);
  const kinds = key.items[scope.id].exposures.map(item => item.kind);
  assert.ok(kinds.includes('TASK_TEXT_DIFFERS') && kinds.includes('PRODUCT_NAME_IN_OUTPUT'));
  const failed = packet.items.find(item => item.caseId === 'picnic-ansi').outputs.find(output => !output.delivered);
  assert.deepEqual(failed, { label: failed.label, delivered: false, output: null, patch: null });
});

test('reconciliation reports every disagreement with its unblinded configuration and transcript', () => {
  const { packet, key } = buildReviewPacket(exported, 'source', 'kast', { seed });
  const delay = packet.items.find(item => item.caseId === 'coroutine-delay-rounding');
  const picnic = packet.items.find(item => item.caseId === 'picnic-ansi');
  const sourceLabel = Object.entries(key.items[delay.id].labels).find(([, arm]) => arm === 'source')[0];
  const kastLabel = sourceLabel === 'A' ? 'B' : 'A';
  const result = reconcileReview(key, { version: 1, exportSha256: key.exportSha256, verdicts: {
    [delay.id]: { [sourceLabel]: 'correct', [kastLabel]: 'correct' },
    [picnic.id]: { A: 'correct', B: 'unsure' },
  } });
  assert.equal(result.complete, true);
  assert.equal(result.blind, true);
  assert.deepEqual(result.counts, { AGREE: 2, DISAGREE: 1, REVIEWER_UNSURE: 1, ORACLE_UNASSESSED: 0, UNREVIEWED: 0 });
  assert.deepEqual(result.disagreements.map(item => [item.arm, item.oracle, item.reviewer]), [['source', 'incorrect', 'correct']]);
  assert.deepEqual(result.disagreements[0].transcript, [{ tool: 'read_file', outcome: 'COMPLETE' }]);
});

test('reconciliation fails closed on a foreign export, unknown item, extra label or verdict outside the scale', () => {
  const { key } = buildReviewPacket(exported, 'source', 'kast', { seed });
  const [id] = Object.keys(key.items);
  const base = { version: 1, exportSha256: key.exportSha256 };
  assert.throws(() => reconcileReview(key, { ...base, exportSha256: 'b'.repeat(64), verdicts: {} }), /REVIEW_VERDICTS_REJECTED/);
  assert.throws(() => reconcileReview(key, { ...base, verdicts: { R99: { A: 'correct' } } }), /REVIEW_ITEM_UNKNOWN/);
  assert.throws(() => reconcileReview(key, { ...base, verdicts: { [id]: { C: 'correct' } } }), /REVIEW_VERDICTS_REJECTED/);
  assert.throws(() => reconcileReview(key, { ...base, verdicts: { [id]: { A: 'mostly' } } }), /REVIEW_VERDICTS_REJECTED/);
  const partial = reconcileReview(key, { ...base, verdicts: {} });
  assert.equal(partial.complete, false);
  assert.equal(partial.counts.UNREVIEWED, 4);
});

test('an oracle error or missing row is never reconciled as agreement', () => {
  const infrastructure = document([
    row('picnic-ansi', 'source', true, 'Done.', { failureReason: 2 }),
    row('picnic-ansi', 'kast', true, 'Done.'),
    row('coroutine-delay-rounding', 'source', true, 'Done.'),
  ]);
  const { key } = buildReviewPacket(infrastructure, 'source', 'kast', { seed });
  const verdicts = Object.fromEntries(Object.keys(key.items).map(id => [id, { A: 'correct', B: 'correct' }]));
  const result = reconcileReview(key, { version: 1, exportSha256: key.exportSha256, verdicts });
  assert.equal(result.counts.ORACLE_UNASSESSED, 2);
  assert.equal(result.counts.AGREE, 2);
});
