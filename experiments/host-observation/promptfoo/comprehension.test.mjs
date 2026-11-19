import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { access, readdir, rm } from 'node:fs/promises';
import test from 'node:test';
import Ajv2020 from 'ajv/dist/2020.js';
import * as piConfig from './pi.config.mjs';
import { comprehensionCases, comprehensionSystem, participantPacket } from './comprehension-cases.mjs';
import { admitParticipantSurface } from './participant-surface.mjs';
import { gradeComprehension } from './comprehension-grading.mjs';
import { replayCase } from '../pi_evaluation_replay_cases.mjs';
import { settlePiTrial } from './skill-provider.mjs';

test('the frozen ktfmt packet reaches the real Pi config assertion through offline controls', async () => {
  assert.equal(typeof piConfig.createPiComprehensionConfig, 'function');
  const config = piConfig.createPiComprehensionConfig({ mode: 'controls', caseIds: ['ktfmt-constant-indent-negation-evidence-v1'] });
  const tests = config.scenarios.flatMap(scenario => scenario.tests);
  assert.ok(tests.length >= 6);
  for (const sample of tests) {
    const response = await config.providers[0].callApi(sample.vars.rowId, { vars: sample.vars });
    const grade = sample.assert[0].value('', { providerResponse: response });
    assert.equal(grade.pass, true, sample.description);
  }
});

test('all four supplied-evidence diagnostics use the same offline config path', async () => {
  assert.deepEqual(comprehensionCases().map(item => item.id), [
    'ktfmt-constant-indent-negation-evidence-v1', 'C5',
    'source-freshness-after-edit-v1', 'readiness-failure-no-absence-v1',
  ]);
  const config = piConfig.createPiComprehensionConfig();
  for (const sample of config.scenarios.flatMap(scenario => scenario.tests)) {
    const response = await config.providers[0].callApi(sample.vars.rowId, { vars: sample.vars });
    assert.equal(sample.assert[0].value('', { providerResponse: response }).pass, true, sample.description);
    assert.equal(response.metadata.panel, 'B');
  }
});

test('frozen supplied evidence still agrees with the existing source, replay and packaged schema owners', () => {
  const packet = id => comprehensionCases([id])[0].packet;
  const scope = packet('C5');
  const existing = replayCase('C5');
  assert.equal(scope.canonicalResponse, existing.canonicalResponse);
  assert.equal(scope.canonicalSha256, existing.canonicalSha256);
  const freshness = packet('source-freshness-after-edit-v1');
  const source = readFileSync(new URL('../interface-replay/fixtures/Orders.kt', import.meta.url), 'utf8');
  assert.equal(freshness.sourceBefore, source);
  const digest = text => createHash('sha256').update(text).digest('hex');
  assert.equal(digest(freshness.sourceBefore), freshness.sourceBeforeSha256);
  assert.equal(digest(freshness.sourceAfter), freshness.sourceAfterSha256);
  assert.equal(freshness.sourceBefore.split('\n')[4], 'fun submit(id: Long): Long = id + 1');
  assert.equal(freshness.sourceAfter.split('\n')[5], 'fun submit(id: Long): Long = id + 1');
  const readiness = packet('readiness-failure-no-absence-v1');
  for (const [schemaName, rejection] of [['hosted-query', freshness.finalReadRejection], ['hosted-endpoint', readiness.finalQueryRejection]]) {
    const schema = JSON.parse(readFileSync(new URL(`../../../protocol/contract/src/main/resources/ide-hosted/${schemaName}.schema.json`, import.meta.url), 'utf8'));
    const parse = new Ajv2020({ strict: false }).compile(schema);
    assert.equal(parse(rejection), true, schemaName);
    assert.equal(parse({ ...rejection, failure: 'INVENTED_FAILURE' }), false, schemaName);
    assert.equal(parse({ ...rejection, items: [] }), false, schemaName);
  }
});

test('participant packets keep curator provenance, owner paths and archived run identities out of the prompt', () => {
  for (const item of comprehensionCases()) {
    const surface = admitParticipantSurface({ system: comprehensionSystem, prompt: item.prompt, directory: '/tmp/project-Ab12Cd' });
    assert.equal(surface.state, 'ADMITTED', item.id);
    const participant = JSON.parse(item.prompt);
    for (const hidden of ['provenance', 'evidenceKind', 'sourceOwner', 'historicalProducer', 'canonicalSha256', 'kastRevision', 'packetId']) {
      assert.equal(Object.hasOwn(participant, hidden), false, `${item.id} ${hidden}`);
    }
    assert.doesNotMatch(item.prompt, /experiments\/host-observation|history\/|eval-i9W|gpt-6-luna|kast arm/, item.id);
    assert.equal(createHash('sha256').update(item.prompt).digest('hex'), item.participantSha256, item.id);
  }
  const ktfmt = JSON.parse(comprehensionCases(['ktfmt-constant-indent-negation-evidence-v1'])[0].prompt);
  assert.equal(ktfmt.priorQueryReceipt.summary.itemCount, 7);
  assert.equal(Object.hasOwn(ktfmt.priorQueryReceipt, 'provenance'), false);
  assert.equal(JSON.parse(comprehensionCases(['C5'])[0].prompt).result, replayCase('C5').canonicalResponse);
});

test('a projection fails closed on an absent field, a duplicate key or an absent omitted member', () => {
  const packet = { task: 'Question?', receipt: { summary: 1, provenance: 'archived' } };
  assert.equal(participantPacket(packet, [{ key: 'task', from: 'task' }, { key: 'receipt', from: 'receipt', omit: ['provenance'] }]),
    JSON.stringify({ task: 'Question?', receipt: { summary: 1 } }, null, 2) + '\n');
  for (const fields of [[], [{ key: 'task', from: 'missing' }], [{ key: 'task', from: 'task' }, { key: 'task', from: 'receipt' }],
    [{ key: 'receipt', from: 'receipt', omit: ['absent'] }], [{ key: 'task', from: 'task', omit: ['x'] }],
    [{ key: 'task', from: 'task', rename: true }]]) {
    assert.throws(() => participantPacket(packet, fields), /COMPREHENSION_PROJECTION_REJECTED/, JSON.stringify(fields));
  }
});

test('freshness decisions preserve identity, current text location and unproved compiler authority', () => {
  const correct = { target: 'fixture.orders.submit(Long)', scope: { directory: 'services/orders', sourceSets: ['main'] },
    canUsePriorLocation: false, currentLineFromText: 6, compilerStatus: 'unproven', nextAction: 'reacquire-current-source' };
  const grade = value => gradeComprehension('source-freshness-after-edit-v1', JSON.stringify(value)).correct;
  assert.equal(grade(correct), true);
  for (const changed of [{ target: 'fixture.orders.submit(String)' }, { currentLineFromText: 5 }, { currentLineFromText: '6' },
    { canUsePriorLocation: true }, { compilerStatus: 'proven' }, { nextAction: 'repeat-identical-request' },
    { scope: { directory: '.', sourceSets: ['main'] } }, { scope: { directory: 'services/orders', sourceSets: ['main', 'main'] } },
    { extraEvidence: 'invented' }]) assert.equal(grade({ ...correct, ...changed }), false, JSON.stringify(changed));
});

test('readiness decisions reject absence, automatic retry, wrong cause and extra or missing fields', () => {
  const correct = { scope: { directory: 'services/orders', sourceSets: ['main'] }, cause: 'MODEL_REFRESH_REQUIRED',
    compilerStatus: 'unavailable', absenceEstablished: false, nextAction: 'report-readiness-blocker', automaticRetry: false };
  const grade = value => gradeComprehension('readiness-failure-no-absence-v1', JSON.stringify(value)).correct;
  assert.equal(grade(correct), true);
  for (const changed of [{ cause: 'DEADLINE_EXCEEDED' }, { compilerStatus: 'available' }, { absenceEstablished: true },
    { automaticRetry: true }, { automaticRetry: 'false' }, { nextAction: 'repeat-identical-request' },
    { scope: { directory: '.', sourceSets: ['main', 'test'] } }, { recovered: true }]) {
    assert.equal(grade({ ...correct, ...changed }), false, JSON.stringify(changed));
  }
  const { cause: _cause, ...missingCause } = correct;
  assert.equal(grade(missingCause), false);
});

test('matched live rows supply only frozen evidence and ordinary guidance to an isolated no-tools boundary', async () => {
  const calls = [];
  const answer = JSON.stringify({ scope: { directory: 'services/orders', sourceSets: ['main'] }, cause: 'MODEL_REFRESH_REQUIRED',
    compilerStatus: 'unavailable', absenceEstablished: false, nextAction: 'report-readiness-blocker', automaticRetry: false });
  const config = piConfig.createPiComprehensionConfig({ mode: 'live', caseIds: ['readiness-failure-no-absence-v1'],
    model: 'test/model', effort: 'low',
    async runPi(options) {
      assert.deepEqual(await readdir(options.directory), []);
      calls.push(options);
      return { reply: answer, providerResponses: 1, toolProposals: 0, toolCalls: 0, calls: [], elapsedMillis: 1 };
    },
  });
  const rows = config.scenarios.flatMap(scenario => scenario.tests);
  assert.deepEqual(rows.map(row => row.vars.arm), ['evidence', 'evidence-with-kast-guidance']);
  for (const row of rows) {
    const response = await config.providers[0].callApi(row.vars.rowId, { vars: row.vars });
    assert.equal(row.assert[0].value('', { providerResponse: response }).pass, true);
    assert.equal(response.metadata.panel, 'B');
    assert.equal(response.metadata.budgets.responseLimitEnforcement, 'POST_HOC');
    assert.equal(response.metadata.budgets.outputTokenLimitEnforcement, 'UNENFORCED');
    assert.equal(response.metadata.taskSha256, comprehensionCases(['readiness-failure-no-absence-v1'])[0].participantSha256);
    assert.notEqual(response.metadata.taskSha256, response.metadata.inputSha256);
    assert.deepEqual(response.metadata.permissions, { tools: [] });
    assert.equal(response.metadata.timing.clock, 'MONOTONIC');
    assert.equal(response.metadata.timing.boundary, 'COMPREHENSION_PROVIDER');
    assert.ok(response.metadata.timing.wallMillis >= 0);
    assert.deepEqual(response.metadata.timing.phases.filter(phase => phase.outcome !== 'NOT_RUN').map(phase => phase.phase), ['preparation', 'participant', 'cleanup']);
    assert.equal(response.metadata.accounting.costs.task.state, 'incomplete');
    assert.equal(response.cost, undefined);
  }
  assert.equal(calls.length, 2);
  const [baseline, treatment] = calls;
  assert.equal(baseline.prompt, comprehensionCases(['readiness-failure-no-absence-v1'])[0].prompt);
  assert.equal(baseline.prompt, treatment.prompt);
  for (const key of ['system', 'model', 'effort', 'timeout']) assert.equal(baseline[key], treatment[key], key);
  assert.notEqual(baseline.directory, treatment.directory);
  assert.equal(baseline.inlineSkill, undefined);
  assert.match(treatment.inlineSkill, /name: kast/);
  assert.deepEqual(baseline.toolNames, []);
  assert.deepEqual(treatment.toolNames, []);
  assert.equal(baseline.extension, undefined);
  assert.equal(treatment.extension, undefined);
  assert.equal(baseline.timeout, 60_000);
  assert.equal(baseline.captureLimits, true);
  const repeated = await config.providers[0].callApi(rows[0].vars.rowId, { vars: rows[0].vars });
  assert.equal(repeated.error, 'PI_EVALUATION_DUPLICATE_ROW');
  assert.equal(calls.length, 2);
});

test('a correct answer cannot qualify after extra responses, tool attempts or terminal failures', async () => {
  const answer = JSON.stringify({ scope: { directory: 'services/orders', sourceSets: ['main'] }, cause: 'MODEL_REFRESH_REQUIRED',
    compilerStatus: 'unavailable', absenceEstablished: false, nextAction: 'report-readiness-blocker', automaticRetry: false });
  for (const changed of [{ providerResponses: 2 }, { toolProposals: 1 }, { toolCalls: 1 },
    { termination: 'MODEL_OUTPUT_LIMIT' }, { error: 'provider failed', terminalCause: 'PI_PROVIDER_ERROR' }]) {
    const config = piConfig.createPiComprehensionConfig({ mode: 'live', caseIds: ['readiness-failure-no-absence-v1'],
      async runPi() { return { reply: answer, providerResponses: 1, toolProposals: 0, toolCalls: 0, calls: [], ...changed }; },
    });
    const row = config.scenarios[0].tests[0];
    const response = await config.providers[0].callApi(row.vars.rowId, { vars: row.vars });
    assert.equal(row.assert[0].value('', { providerResponse: response }).pass, false, JSON.stringify(changed));
    assert.equal(response.metadata.diagnosticQualified, false);
    assert.equal(response.output, answer);
    assert.equal(response.metadata.accounting.costs.task.state, 'incomplete');
  }
});

test('a malformed attempted response retains known charges through the provider error path', async () => {
  const message = { type: 'message_end', message: { role: 'assistant', provider: 'test', model: 'model',
    responseId: 'diagnostic-failed-attempt', stopReason: 'stop', content: [{ type: 'text', text: 'partial delivered answer' }],
    usage: { input: 4, output: 2, cacheRead: 0, cacheWrite: 0, totalTokens: 6,
      cost: { input: 0.01, output: 0.02, cacheRead: 0, cacheWrite: 0, total: 0.03, totalFromProvider: true } } } };
  const config = piConfig.createPiComprehensionConfig({ mode: 'live', caseIds: ['readiness-failure-no-absence-v1'], model: 'test/model',
    async runPi() { return settlePiTrial({ status: 0, stdout: JSON.stringify(message) + '\nmalformed\n' }, 'test/model', [], true); },
  });
  const row = config.scenarios[0].tests[0];
  const response = await config.providers[0].callApi(row.vars.rowId, { vars: row.vars });
  assert.equal(row.assert[0].value('', { providerResponse: response }).pass, false);
  assert.equal(response.metadata.accounting.costs.task.state, 'incomplete');
  assert.equal(response.metadata.accounting.costs.task.knownTotal, 0.03);
  assert.equal(response.cost, undefined);
  assert.equal(response.tokenUsage, undefined);
  assert.equal(response.metadata.evidence.final.text, 'partial delivered answer');
});

test('unconfirmed child settlement preserves its owned directory and failed receipt', async t => {
  let directory;
  const config = piConfig.createPiComprehensionConfig({ mode: 'live', caseIds: ['readiness-failure-no-absence-v1'],
    async runPi(options) {
      directory = options.directory;
      throw Object.assign(Error('child settlement was not confirmed'), { code: 'PI_CHILD_REAP_UNAVAILABLE',
        trial: { reply: 'retained partial answer', providerResponses: 1, toolProposals: 0, toolCalls: 0 } });
    },
  });
  t.after(async () => { if (directory) await rm(directory, { recursive: true, force: true }); });
  const row = config.scenarios[0].tests[0];
  const response = await config.providers[0].callApi(row.vars.rowId, { vars: row.vars });
  assert.equal(response.error, 'PI_CHILD_REAP_UNAVAILABLE');
  assert.equal(response.output, 'retained partial answer');
  assert.equal(response.metadata.cleanup, 'WITHHELD_UNCONFIRMED_CHILD_SETTLEMENT');
  assert.equal(response.metadata.retainedDirectory, directory);
  assert.equal(response.metadata.timing.boundary, 'COMPREHENSION_PROVIDER');
  assert.equal(response.metadata.timing.phases.find(phase => phase.phase === 'cleanup').outcome, 'FAILED');
  assert.equal(row.assert[0].value('', { providerResponse: response }).pass, false);
  await access(directory);
});
