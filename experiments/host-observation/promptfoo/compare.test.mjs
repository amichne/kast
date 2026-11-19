import assert from 'node:assert/strict';
import test from 'node:test';
import { compare } from './compare.mjs';

const row = (arm, pass, total, toolCalls, extra = {}) => ({
  vars: { caseId: 'overload', arm },
  success: pass,
  failureReason: pass ? 0 : 1,
  namedScores: { correctness: Number(pass) },
  gradingResult: { pass },
  response: { tokenUsage: { total }, metadata: { toolCalls, model: 'same-model', effort: 'low' } },
  ...extra,
});
const report = (...rows) => compare({ results: { results: rows } }, 'source', 'kast');

test('a cheaper wrong answer loses on correctness', () => {
  const result = report(row('source', false, 3, 1), row('kast', true, 20, 4));
  assert.deepEqual(result.preference, { arm: 'kast', criterion: 'correctness' });
  assert.equal(result.jointlyCorrect, 0);
});
test('tokens precede calls, even if calls favor the other arm', () => {
  assert.deepEqual(report(row('source', true, 8, 12), row('kast', true, 9, 1)).preference, {
    arm: 'source',
    criterion: 'tokens',
  });
  assert.deepEqual(report(row('source', true, 9, 12), row('kast', true, 9, 1)).preference, {
    arm: 'kast',
    criterion: 'toolCalls',
  });
});
test('a missing meter, an unpaired trial or an infrastructure failure blocks cost ranking', () => {
  const baseline = row('source', true, 10, 3);
  assert.equal(report(baseline, row('kast', true, undefined, 1)).preference, null);
  assert.equal(report(baseline).preference, null);
  assert.equal(report(baseline, row('kast', false, 10, 1, { failureReason: 2 })).preference, null);
  assert.equal(
    report(baseline, row('kast', false, 10, 1, { response: { error: 'unavailable' } })).preference,
    null,
  );
  assert.throws(() => report(baseline, baseline), /duplicate/i);
});
test('equal scores on different cases never authorize a cost winner', () => {
  const at = (arm, pass, tokens, caseId) => row(arm, pass, tokens, 1, { vars: { arm, caseId } });
  const result = report(
    at('kast', true, 10, 'a'),
    at('source', false, 100, 'a'),
    at('kast', false, 1, 'b'),
    at('source', true, 100, 'b'),
  );
  assert.equal(result.jointlyCorrect, 0);
  assert.equal(result.preference, null);
});

test('policy-only assertions and different models cannot rank agent capabilities', () => {
  const a = row('source', true, 10, 2);
  assert.equal(report(a, row('kast', true, 9, 1, { namedScores: { policy: 1 } })).preference, null);
  assert.equal(
    report(
      a,
      row('kast', true, 9, 1, {
        response: { tokenUsage: { total: 9 }, metadata: { model: 'other', toolCalls: 1 } },
      }),
    ).preference,
    null,
  );
});

test('inconsistent correctness and unrecorded effort block a preference', () => {
  const a = row('source', true, 9, 1);
  const b = row('kast', true, 1, 1);
  assert.equal(report(a, { ...b, namedScores: { correctness: 0 } }).preference, null);
  delete a.response.metadata.effort;
  delete b.response.metadata.effort;
  assert.equal(report(a, b).preference, null);
});

test('developer task comparisons need the same initial source and requirement in both arms', () => {
  const baseline = row('source', true, 12, 1);
  const treatment = row('kast', true, 10, 2);
  for (const item of [baseline, treatment]) {
    item.vars.comparison = 'developer-tasks';
    Object.assign(item.response.metadata, { taskSha256: 'a'.repeat(64), sourceSha256: 'b'.repeat(64) });
  }
  assert.deepEqual(report(baseline, treatment).preference, { arm: 'kast', criterion: 'tokens' });
  for (const key of ['taskSha256', 'sourceSha256']) {
    const original = treatment.response.metadata[key];
    for (const invalid of [undefined, 'broken', 'c'.repeat(64)]) {
      treatment.response.metadata[key] = invalid;
      const comparison = report(baseline, treatment);
      assert.equal(comparison.preference, null);
      assert.equal(comparison.reason, 'UNMATCHED_DEVELOPER_INPUTS');
    }
    treatment.response.metadata[key] = original;
  }
});
