import assert from 'node:assert/strict';
import test from 'node:test';
import * as coordinators from './corpus-coordinators.mjs';
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { settlePiTrial } from './skill-provider.mjs';
import { casesForSuite, delegateModels } from './corpus-cases.mjs';

test('coordinator preserves selected executables and catalog without forwarding unrelated credentials', () => {
  assert.equal(typeof coordinators.corpusRuntimeOptions, 'function');
  const selected = {
    KAST_EVAL_KAST: '/candidate/kast', KAST_EVAL_PI: '/candidate/pi',
    KAST_EVAL_PI_MODELS: '/candidate/models.json', KAST_CORPUS_ROOT: '/corpus',
    KAST_CORPUS_SKILL: '/candidate/SKILL.md', KAST_EVAL_PORTFOLIO: 'curated',
  };
  assert.deepEqual(coordinators.corpusRuntimeOptions({ ...selected, UNRELATED_SECRET: 'must-not-forward' }), selected);
});

test('curated coordinator admission stops before filesystem, credential, executable or model effects', async () => {
  const result = await coordinators.runCoordinator('spring', '/not-an-existing-output', {
    environment: { KAST_EVAL_PORTFOLIO: 'curated' }, runPi: () => assert.fail('blocked coordinator invoked a model'),
  });
  assert.equal(result.coordinator, 'BLOCKED');
  assert.equal(result.evaluation.reason, 'NATURAL_BASELINE_UNQUALIFIED');
  assert.equal(result.role, 'coordinator');
  assert.equal(result.metadata.accounting.costs.task.state, 'incomplete');
});

test('export assessment requires canonical answers and coherent per-row grade evidence', () => {
  const task = casesForSuite('spring')[0], model = delegateModels[0];
  const tests = ['source', 'kast'].map(arm => ({ vars: { caseId: task.id, suite: 'spring', arm, model } }));
  const rows = tests.map(({ vars }) => ({ vars, prompt: { raw: task.task },
    response: { output: JSON.stringify(task.expected) }, success: true, failureReason: 0,
    gradingResult: { pass: true }, namedScores: { correctness: 1 } }));
  const document = { config: { tests }, results: { results: rows, stats: { successes: 2, failures: 0, errors: 0 } } };
  assert.equal(coordinators.assessCorpusExport(document, 'spring', 0).status, 'PASSED');
  const absent = structuredClone(document);
  absent.results.results = tests.map(({ vars }) => ({ vars }));
  assert.equal(coordinators.assessCorpusExport(absent, 'spring', 0).status, 'INFRASTRUCTURE_FAILURE');
  for (const change of [row => delete row.response, row => delete row.response.output,
    row => delete row.success, row => delete row.gradingResult, row => delete row.failureReason,
    row => { row.response.output = '[]'; }, row => { row.gradingResult.pass = false; },
    row => { row.namedScores.correctness = 0; }]) {
    const invalid = structuredClone(document);
    change(invalid.results.results[0]);
    assert.equal(coordinators.assessCorpusExport(invalid, 'spring', 0).status, 'INFRASTRUCTURE_FAILURE');
  }
  const wrong = structuredClone(document);
  Object.assign(wrong.results.results[0], { response: { output: '[]' }, success: false, failureReason: 1,
    gradingResult: { pass: false }, namedScores: { correctness: 0 } });
  assert.equal(coordinators.assessCorpusExport(wrong, 'spring', 0).status, 'INFRASTRUCTURE_FAILURE');
  wrong.results.stats = { successes: 1, failures: 1, errors: 0 };
  assert.equal(coordinators.assessCorpusExport(wrong, 'spring', 100).status, 'ASSESSED_WRONG_ANSWERS');
});

test('coordinator persists its own incomplete charges and timing after a failed attempt', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'kast-coordinator-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const corpusRoot = join(directory, 'corpus'), output = join(directory, 'output'), skill = join(directory, 'SKILL.md');
  await Promise.all([mkdir(corpusRoot), mkdir(output), writeFile(skill, 'Deterministic fixture skill.')]);
  const model = 'openai-codex/gpt-6.2-sol';
  const first = { role: 'assistant', provider: 'openai-codex', model: 'gpt-6.2-sol', responseId: 'first', stopReason: 'toolUse', content: [],
    usage: { input: 10, output: 2, cacheRead: 0, cacheWrite: 0, totalTokens: 12,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0.004, totalFromProvider: true } } };
  const stdout = [first, { ...first, responseId: 'failed', stopReason: 'error', usage: undefined }]
    .map(message => JSON.stringify({ type: 'message_end', message })).join('\n');
  const trial = settlePiTrial({ status: 0, stdout }, model, [], true);
  let now = 100;
  const result = await coordinators.runCoordinator('spring', output, {
    environment: { KAST_CORPUS_ROOT: corpusRoot, KAST_CORPUS_SKILL: skill }, clock: () => now++,
    runPi: async () => { throw Object.assign(Error('fixture transport failure'), { trial }); },
  });
  const retained = JSON.parse(await readFile(join(output, 'spring', 'coordinator.json'), 'utf8'));
  assert.equal(retained.coordinator, 'FAILED');
  assert.equal(retained.role, 'coordinator');
  assert.equal(retained.metadata.accounting.costs.participant.knownTotal, 0.004);
  assert.equal(retained.metadata.accounting.costs.participant.state, 'incomplete');
  assert.equal(retained.metadata.timing.clock, 'MONOTONIC');
  assert.ok(retained.metadata.timing.wallMillis >= 0);
  assert.equal(result.metadata.accounting.costs.task.knownTotal, 0.004);
  assert.equal(result.evaluation.reason, 'MISSING_DISPATCH_RECEIPT');
});

for (const unsettled of [false, true]) test(`coordinator preserves its paid receipt when ${unsettled ? 'child settlement is unknown' : 'cleanup fails'}`, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'kast-coordinator-cleanup-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const corpusRoot = join(directory, 'corpus'), output = join(directory, 'output'), skill = join(directory, 'SKILL.md');
  await Promise.all([mkdir(corpusRoot), mkdir(output), writeFile(skill, 'Deterministic fixture skill.')]);
  const message = { role: 'assistant', provider: 'openai-codex', model: 'gpt-6.2-sol', stopReason: 'toolUse', content: [],
    usage: { input: 10, output: 2, cacheRead: 0, cacheWrite: 0, totalTokens: 12,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0.004, totalFromProvider: true } } };
  const trial = settlePiTrial({ status: null, termination: 'PARTICIPANT_DEADLINE',
    stdout: JSON.stringify({ type: 'message_end', message }) + '\n' }, 'openai-codex/gpt-6.2-sol', [], true);
  let trialDirectory, cleanupCalls = 0;
  t.after(async () => { if (trialDirectory) await rm(trialDirectory, { recursive: true, force: true }); });
  const result = await coordinators.runCoordinator('spring', output, {
    environment: { KAST_CORPUS_ROOT: corpusRoot, KAST_CORPUS_SKILL: skill },
    runPi: async ({ directory }) => {
      trialDirectory = directory;
      if (unsettled) throw Object.assign(Error('fixture unsettled child'), { code: 'PI_CHILD_REAP_UNAVAILABLE', trial });
      return trial;
    },
    cleanup: async () => { cleanupCalls++; throw Error('fixture cleanup failure'); },
  });
  assert.equal(cleanupCalls, unsettled ? 0 : 1);
  assert.equal(result.coordinator, 'FAILED');
  assert.equal(result.metadata.cleanup, unsettled ? 'WITHHELD_UNCONFIRMED_CHILD_SETTLEMENT' : 'FAILED');
  assert.equal(result.metadata.retainedDirectory, trialDirectory);
  assert.equal((await stat(trialDirectory)).isDirectory(), true);
  const retained = JSON.parse(await readFile(join(output, 'spring', 'coordinator.json'), 'utf8'));
  assert.equal(retained.metadata.accounting.costs.participant.knownTotal, 0.004);
  assert.equal(retained.metadata.cleanup, result.metadata.cleanup);
});
