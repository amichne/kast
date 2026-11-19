import assert from 'node:assert/strict';
import test from 'node:test';
import { compare } from './compare.mjs';
import { decodePiTrial, settlePiTrial } from './skill-provider.mjs';

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
const metered = (arm, dollars, tokens = 100, pass = true) => {
  const trial = decodePiTrial(JSON.stringify({ type: 'message_end', message: {
    role: 'assistant', provider: 'test', model: 'same-model', responseModel: 'resolved-model',
    responseId: `response-${arm}`, stopReason: 'stop', content: [{ type: 'text', text: 'answer' }],
    usage: { input: tokens - 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: tokens,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: dollars, totalFromProvider: true } },
  } }), 'test/same-model', []);
  const value = row(arm, pass, tokens, 0);
  Object.assign(value.response.metadata, { accounting: trial.accounting, context: trial.context });
  return value;
};

test('a cheaper wrong answer loses on correctness', () => {
  const result = report(row('source', false, 3, 1), row('kast', true, 20, 4));
  assert.deepEqual(result.preference, { arm: 'kast', criterion: 'correctness' });
  assert.equal(result.jointlyCorrect, 0);
});
test('missing dollar accounting blocks efficiency even when tokens favor one correct arm', () => {
  const result = report(row('source', true, 8, 12), row('kast', true, 9, 1));
  assert.equal(result.preference, null);
  assert.equal(result.reason, 'INCOMPLETE_DOLLAR_ACCOUNTING');
});

test('natural tasks and supplied-evidence diagnostics never form one comparison panel', () => {
  const baseline = row('source', true, 8, 12);
  const treatment = row('kast', true, 9, 1);
  baseline.vars.panel = 'A';
  treatment.vars.panel = 'B';
  const result = report(baseline, treatment);
  assert.equal(result.preference, null);
  assert.equal(result.reason, 'PANEL_MISMATCH');
});
test('complete Pi dollars precede tokens when both engineering outcomes are correct', () => {
  const result = report(metered('source', 10, 10), metered('kast', 1, 20));
  assert.deepEqual(result.preference, { arm: 'kast', criterion: 'dollars' });
  assert.equal(result.arms.source.dollars, 10);
  assert.equal(result.arms.kast.dollars, 1);
  assert.equal(result.reason, 'DIFFERENT_OBSERVED_DOLLARS');
});

test('a lower fully accounted charge cannot compensate for an incorrect answer', () => {
  const result = report(metered('source', 0.01, 10, false), metered('kast', 100, 100));
  assert.deepEqual(result.preference, { arm: 'kast', criterion: 'correctness' });
});

test('a requested model alias cannot conceal a different resolved participant model', () => {
  const baseline = metered('source', 2), treatment = metered('kast', 1);
  treatment.response.metadata.accounting.records[0].identity.responseModel = 'different-resolved-model';
  const result = report(baseline, treatment);
  assert.equal(result.preference, null);
  assert.equal(result.reason, 'RESOLVED_MODEL_OR_EFFORT_MISMATCH');
});

test('a cutoff preserves known model identity even when context and costs are incomplete', () => {
  const baseline = metered('source', 2), treatment = metered('kast', 1, 100, false);
  const trial = settlePiTrial({ status: null, termination: 'PARTICIPANT_DEADLINE', stdout: JSON.stringify({
    type: 'message_end', message: { role: 'assistant', provider: 'test', model: 'same-model',
      responseModel: 'different-resolved-model', responseId: 'cutoff-response', stopReason: 'stop',
      content: [{ type: 'text', text: 'answer' }], usage: { input: 99, output: 1, cacheRead: 0, cacheWrite: 0,
        totalTokens: 100, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 2, totalFromProvider: true } } },
  }) + '\n' }, 'test/same-model', [], true);
  baseline.response.metadata.accounting = trial.accounting;
  baseline.response.metadata.context = trial.context;
  const result = report(baseline, treatment);
  assert.equal(result.preference, null);
  assert.equal(result.reason, 'RESOLVED_MODEL_OR_EFFORT_MISMATCH');
});

test('empty matched permission and budget objects are not natural-trial admission evidence', () => {
  const baseline = metered('source', 2), treatment = metered('kast', 1);
  for (const value of [baseline, treatment]) {
    value.vars.panel = 'A';
    Object.assign(value.response.metadata, { panel: 'A', taskSha256: 'a'.repeat(64), sourceSha256: 'b'.repeat(64),
      permissions: {}, budgets: {}, admission: { state: 'ADMITTED' } });
  }
  const result = report(baseline, treatment);
  assert.equal(result.preference, null);
  assert.equal(result.reason, 'UNMATCHED_PANEL_INPUTS');
});

test('participant-only duration cannot become an end-to-end speed preference', () => {
  const baseline = metered('source', 1), treatment = metered('kast', 1);
  for (const [value, wallMillis] of [[baseline, 2], [treatment, 1]]) {
    value.response.metadata.timing = { clock: 'MONOTONIC', boundary: 'CORPUS_PARTICIPANT', wallMillis, phases: [] };
  }
  const result = report(baseline, treatment);
  assert.equal(result.preference, null);
  assert.equal(result.reason, 'MISSING_END_TO_END_TIME');
});

test('floating-point summation noise preserves a dollar tie', () => {
  const result = report(metered('source', 0.1 + 0.2), metered('kast', 0.3));
  assert.equal(result.preference, null);
  assert.equal(result.reason, 'MISSING_END_TO_END_TIME');
});

test('after a dollar tie context precedes turns and complete end-to-end time', () => {
  const baseline = metered('source', 1, 200), treatment = metered('kast', 1, 100);
  assert.deepEqual(report(baseline, treatment).preference, { arm: 'kast', criterion: 'peakContext' });
  const messages = [1, 2].map(index => ({ type: 'message_end', message: {
    role: 'assistant', provider: 'test', model: 'same-model', responseModel: 'resolved-model',
    responseId: 'turn-' + index, stopReason: 'stop', content: [{ type: 'text', text: 'answer' }],
    usage: { input: 99, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 100,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0.5, totalFromProvider: true } },
  } }));
  const trial = decodePiTrial(messages.map(event => JSON.stringify(event)).join('\n'), 'test/same-model', []);
  treatment.response.metadata.accounting = trial.accounting;
  treatment.response.metadata.context = trial.context;
  const oneTurn = metered('source', 1, 100);
  assert.deepEqual(report(oneTurn, treatment).preference, { arm: 'source', criterion: 'modelTurns' });
  const otherOneTurn = metered('kast', 1, 100);
  for (const [value, wallMillis] of [[oneTurn, 50], [otherOneTurn, 25]]) {
    value.response.metadata.timing = { clock: 'MONOTONIC', boundary: 'DEVELOPER_PROVIDER', wallMillis,
      phases: ['preparation', 'participant', 'grading', 'cleanup'].map(phase =>
        ({ phase, outcome: 'COMPLETED', elapsedMillis: 1 })) };
  }
  assert.deepEqual(report(oneTurn, otherOneTurn).preference, { arm: 'kast', criterion: 'endToEndMillis' });
  otherOneTurn.response.cached = true;
  const replay = report(oneTurn, otherOneTurn);
  assert.equal(replay.preference, null);
  assert.equal(replay.reason, 'INCOMPLETE_OR_INVALID_PAIR');
  assert.equal(replay.arms.kast.dollars, null);
});

test('matched admitted natural fixtures and supplied evidence have distinct valid comparisons', () => {
  for (const panel of ['A', 'B']) {
    const baseline = metered('source', 2), treatment = metered('kast', 1);
    for (const value of [baseline, treatment]) {
      value.vars.panel = panel;
      const metadata = value.response.metadata;
      Object.assign(metadata, { panel, taskSha256: 'a'.repeat(64), sourceSha256: 'b'.repeat(64),
        inputSha256: 'c'.repeat(64), admission: { state: 'ADMITTED' },
        permissions: panel === 'B' ? { tools: [] } : { sourceRead: 'ISOLATED_FIXTURE',
          sourceWrite: 'KOTLIN_PRODUCTION_EXISTING_DIRECTORIES', arbitraryCommands: false,
          toolNames: ['list_files', 'read_file', 'search_source', 'compile_project'],
          gradedEditPaths: ['src/Example.kt'], gradedNewSourceDirs: [] },
        budgets: panel === 'B' ? { wallMillis: 60_000, maximumProviderResponses: 1,
          maximumToolProposals: 0, maximumToolExecutions: 0 } : { participantMillis: 600_000, hostPreflightMillis: 900_000 },
        participantSurface: { state: 'ADMITTED', role: 'participant', vocabularyVersion: 1, surfaceSha256: 'd'.repeat(64) } });
    }
    if (panel === 'A') treatment.response.metadata.permissions.toolNames.push('kast', 'read_kast_guidance');
    assert.deepEqual(report(baseline, treatment).preference, { arm: 'kast', criterion: 'dollars' });
    assert.equal(report(baseline, treatment).panel, panel);
    // Blinding is admission, not an efficiency tiebreak: it blocks correctness as well.
    for (const surface of [null, { state: 'REJECTED', vocabularyVersion: 1, findings: [] },
      { state: 'NOT_APPLICABLE', role: 'coordinator', vocabularyVersion: 1 },
      { state: 'ADMITTED', role: 'participant', vocabularyVersion: 2, surfaceSha256: 'd'.repeat(64) }]) {
      const original = treatment.response.metadata.participantSurface;
      treatment.response.metadata.participantSurface = surface;
      treatment.success = false; treatment.failureReason = 1;
      treatment.namedScores.correctness = 0; treatment.gradingResult.pass = false;
      const blocked = report(baseline, treatment);
      assert.equal(blocked.preference, null, JSON.stringify(surface));
      assert.equal(blocked.reason, 'UNBLINDED_PARTICIPANT_SURFACE', JSON.stringify(surface));
      treatment.response.metadata.participantSurface = original;
      treatment.success = true; treatment.failureReason = 0;
      treatment.namedScores.correctness = 1; treatment.gradingResult.pass = true;
    }
  }
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
  const baseline = metered('source', 12, 12);
  const treatment = metered('kast', 10, 10);
  for (const item of [baseline, treatment]) {
    item.vars.comparison = 'developer-tasks';
    Object.assign(item.response.metadata, { taskSha256: 'a'.repeat(64), sourceSha256: 'b'.repeat(64) });
  }
  assert.deepEqual(report(baseline, treatment).preference, { arm: 'kast', criterion: 'dollars' });
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
