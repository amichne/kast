import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { casesForSuite, delegateModels } from './corpus-cases.mjs';
import { decodePiTrial, settlePiTrial } from './skill-provider.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
function fixture() {
  const tests = casesForSuite('spring').flatMap(task => delegateModels.flatMap(model => ['source', 'kast'].map(arm => ({
    vars: { caseId: task.id, suite: 'spring', arm, model },
  }))));
  const rows = tests.map(({ vars }) => {
    const task = casesForSuite('spring').find(item => item.id === vars.caseId);
    return { vars, prompt: { raw: task.task }, success: true, failureReason: 0,
      namedScores: { correctness: 1 }, gradingResult: { pass: true },
      response: { output: JSON.stringify(task.expected), tokenUsage: { total: 100 }, metadata: {
        revision: task.revision, taskSha256: sha(task.task), model: vars.model, effort: 'low', arm: vars.arm,
        skillSha256: vars.arm === 'source' ? null : 'a'.repeat(64),
        providerResponses: 2, usage: { input: 80, output: 20, cacheRead: 0, cacheWrite: 0, total: 100 },
        finalizedUsage: null, toolCalls: 1,
      } } };
  });
  return { config: { tests }, evalId: 'offline-fixture', results: { results: rows,
    stats: { successes: rows.length, failures: 0, errors: 0 } } };
}
function runReport(document, coordinator = { profile: { workspace: 'spring' }, coordinator: 'COMPLETED' }) {
  const directory = mkdtempSync(join(tmpdir(), 'kast-corpus-report-test-'));
  const workspace = join(directory, 'spring');
  mkdirSync(workspace);
  const stats = document.results.stats;
  const dispatch = stats.errors ? { status: 'INFRASTRUCTURE_FAILURE', reason: 'UNASSESSED_TRIAL', passed: stats.successes,
    failed: stats.failures, errors: stats.errors, exitCode: 100 } :
    { status: 'PASSED', passed: stats.successes, failed: stats.failures, errors: 0, exitCode: 0 };
  for (const [name, value] of [['results.json', document], ['receipt.json', dispatch], ['coordinator.json', coordinator]]) {
    writeFileSync(join(workspace, name), JSON.stringify(value));
  }
  try {
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./corpus-report.mjs', import.meta.url)), workspace],
      { encoding: 'utf8', timeout: 10_000 });
    assert.equal(result.status, 0, result.stderr);
    return JSON.parse(result.stdout);
  } finally { rmSync(directory, { recursive: true, force: true }); }
}

test('retained corpus report characterizes complete paired outcomes without new inference', () => {
  const report = runReport(fixture());
  assert.deepEqual(report.outcomes, { bothCorrect: 6, bothIncorrect: 0, sourceOnlyCorrect: 0, kastOnlyCorrect: 0, incomplete: 0 });
  assert.deepEqual(report.jointlyCorrectTokens, { source: 600, kast: 600 });
});

test('corpus report preserves per-response and partial accounting evidence instead of only token totals', () => {
  const document = fixture();
  const metadata = document.results.results[0].response.metadata;
  metadata.accounting = { schemaVersion: 1, state: 'audit-sentinel' };
  metadata.context = { state: 'incomplete', reasons: ['UNKNOWN_RESPONSE_INPUT'] };
  metadata.evidence = { final: { text: 'delivered', truncated: false } };
  metadata.timing = { clock: 'MONOTONIC', wallMillis: 123, phases: [] };
  metadata.participantTiming = { clock: 'MONOTONIC', wallMillis: 100, boundary: 'PARTICIPANT' };
  metadata.source = { revision: metadata.revision, state: 'PINNED' };
  metadata.permissions = { tools: ['source'] };
  metadata.budgets = { timeoutMillis: 1000 };
  const report = runReport(document);
  const actual = report.rounds[0][0].rows[0];
  assert.equal(actual.providerResponses, 2);
  for (const key of ['usage', 'finalizedUsage', 'accounting', 'context', 'evidence', 'timing', 'participantTiming',
    'source', 'permissions', 'budgets']) {
    assert.deepEqual(actual[key], metadata[key]);
  }
  assert.equal(report.measurements.dollars.state, 'incomplete');
});

test('a correct-looking answer without grade evidence remains an unassessed report row', () => {
  const document = fixture();
  delete document.results.results[0].gradingResult;
  const report = runReport(document);
  assert.equal(report.outcomes.bothCorrect, 5);
  assert.equal(report.outcomes.incomplete, 1);
  assert.equal(report.rounds[0][0].rows[0].outcome, 'unassessed');
  assert.equal(report.rounds[0][0].rows[0].reason, 'MISSING_ASSESSMENT');
  assert.equal(report.rounds[0][0].evaluation.status, 'INFRASTRUCTURE_FAILURE');
});

test('configured canonical subsets determine the denominator and missing rows stay unassessed', () => {
  const document = fixture();
  const caseId = document.config.tests[0].vars.caseId;
  document.config.tests = document.config.tests.filter(test => test.vars.caseId === caseId);
  document.results.results = document.results.results.filter(row => row.vars.caseId === caseId);
  document.results.results.pop();
  document.results.stats.successes = document.results.results.length;
  const report = runReport(document);
  assert.equal(report.rounds[0][0].rows.length, 6);
  assert.equal(report.outcomes.bothCorrect, 2);
  assert.equal(report.outcomes.incomplete, 1);
  const missing = report.rounds[0][0].rows.find(row => row.reason === 'MISSING_TRIAL');
  assert.equal(missing.outcome, 'unassessed');
  assert.equal(missing.totalTokens, null);
});

test('cached, failed and incomplete-meter rows survive without fresh-trial or zero-token claims', () => {
  const document = fixture();
  document.results.results[0].response.cached = true;
  const failed = document.results.results[2];
  Object.assign(failed, { success: false, failureReason: 2 });
  Object.assign(failed.response, { error: 'PI_PROVIDER_ERROR', output: 'partial delivered answer' });
  const first = { role: 'assistant', provider: 'openai-codex', model: failed.vars.model, responseId: 'first', stopReason: 'toolUse',
    content: [], usage: { input: 80, output: 20, cacheRead: 0, cacheWrite: 0, totalTokens: 100,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0.004, totalFromProvider: true } } };
  const stdout = [first, { ...first, responseId: 'failed', stopReason: 'error', usage: undefined }]
    .map(message => JSON.stringify({ type: 'message_end', message })).join('\n');
  failed.response.metadata.accounting = settlePiTrial({ status: 0, stdout }, 'openai-codex/' + failed.vars.model, [], true).accounting;
  delete document.results.results[4].response.tokenUsage;
  document.results.stats = { successes: 11, failures: 0, errors: 1 };
  const report = runReport(document, { profile: { workspace: 'spring' }, coordinator: 'FAILED' });
  assert.equal(report.outcomes.incomplete, 2);
  assert.equal(report.jointlyCorrectTokens.source, null);
  assert.equal(report.rounds[0][0].rows[0].outcome, 'unassessed');
  assert.equal(report.rounds[0][0].rows[0].reason, 'RESPONSE_CACHE_REPLAY');
  assert.equal(report.rounds[0][0].rows[2].answer, 'partial delivered answer');
  assert.equal(report.rounds[0][0].coordinator.coordinator, 'FAILED');
  assert.equal(report.measurements.dollars.knownTotal, 0.004);
  assert.equal(Object.hasOwn(report.measurements.dollars, 'total'), false);
});

test('corpus dollars use admitted native receipts while forged task and answer identities are rejected', () => {
  const document = fixture();
  for (const row of document.results.results) {
    const trial = decodePiTrial(JSON.stringify({ type: 'message_end', message: {
      role: 'assistant', provider: 'openai-codex', model: row.vars.model, stopReason: 'stop',
      content: [{ type: 'text', text: row.response.output }],
      usage: { input: 80, output: 20, cacheRead: 0, cacheWrite: 0, totalTokens: 100,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 1, totalFromProvider: true } },
    } }), 'openai-codex/' + row.vars.model, []);
    Object.assign(row.response.metadata, { accounting: trial.accounting, context: trial.context, evidence: trial.evidence });
  }
  const report = runReport(document);
  assert.equal(report.measurements.dollars.state, 'complete');
  assert.equal(report.measurements.dollars.total, 12);
  assert.equal(report.measurements.dollars.provenance, 'provider-reported');
  assert.equal(report.measurements.context.state, 'complete');
  const unknown = structuredClone(document);
  unknown.config.tests[0].vars.caseId = 'spring-forged';
  assert.throws(() => runReport(unknown), /canonical|plan|identity/i);
  const forged = structuredClone(document);
  forged.results.results[0].response.output = '["forged"]';
  assert.throws(() => runReport(forged), /evidence|answer|grade/i);
});
