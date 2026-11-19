import assert from 'node:assert/strict';
import test from 'node:test';
import { decodePiTrial, settlePiTrial } from './skill-provider.mjs';

const model = 'openai-codex/accounting-test';
const reported = (total = 0.004) => ({ input: 100, output: 40, cacheRead: 60, cacheWrite: 20,
  cacheWrite1h: 5, reasoning: 10, totalTokens: 220,
  cost: { input: 0.001, output: 0.002, cacheRead: 0.0003, cacheWrite: 0.0004, total, totalFromProvider: true } });
const assistant = (overrides = {}) => ({ role: 'assistant', provider: 'openai-codex', model: 'accounting-test',
  responseModel: 'accounting-test-resolved', responseId: 'response-1', providerThinkingLevel: 'low', thinkingLevel: 'low',
  timestamp: 1000, durationMs: 17, stopReason: 'stop', content: [{ type: 'text', text: '  final answer\n' }],
  usage: reported(), ...overrides });
const end = message => ({ type: 'message_end', message });
const stream = (...events) => events.map(event => JSON.stringify(event)).join('\n') + '\n';
const decode = (...events) => decodePiTrial(stream(...events), model, ['helper']);

test('native Pi accounting and delivered answer survive the actual decoder and projection', async () => {
  const trial = decode(end(assistant()));
  assert.equal(trial.accounting.schemaVersion, 1);
  const record = trial.accounting.records[0];
  assert.deepEqual(record.usage.value, { input: 100, output: 40, cacheRead: 60, cacheWrite: 20,
    cacheWrite1h: 5, reasoning: 10, totalTokens: 220 });
  assert.deepEqual(record.cost.value, reported().cost);
  assert.equal(record.cost.provenance, 'provider-reported');
  assert.equal(record.identity.responseModel, 'accounting-test-resolved');
  assert.equal(record.identity.responseId, 'response-1');
  assert.equal(record.identity.providerThinkingLevel, 'low');
  assert.equal(record.identity.durationMs, 17);
  assert.equal(trial.accounting.costs.task.total, 0.004);
  assert.equal(trial.context.input.state, 'complete');
  assert.deepEqual(trial.context.input, { state: 'complete', sum: 180, peak: 180, final: 180 });
  assert.equal(trial.evidence.final.text, '  final answer\n');
  assert.equal(trial.evidence.final.state, 'complete');
  const { projectPiTrial, taskCostForComparison } = await import('./pi-accounting.mjs');
  const projected = projectPiTrial(trial);
  assert.deepEqual(projected.tokenUsage, { total: 220, prompt: 180, completion: 40, cached: 60, numRequests: 1 });
  assert.equal(projected.cost, 0.004);
  assert.equal(projected.metadata.accounting, trial.accounting);
  assert.deepEqual(taskCostForComparison(trial.accounting), {
    state: 'ready', total: 0.004, provenance: 'provider-reported', pricingBasis: [] });
});

test('a later unmetered provider error preserves known charges and tokens without inventing a full total', async () => {
  const unknown = assistant({ responseId: 'response-2', stopReason: 'error', usage: undefined, content: [] });
  const trial = settlePiTrial({ status: 0, stdout: stream(end(assistant({ stopReason: 'toolUse' })), end(unknown)) }, model, [], true);
  assert.equal(trial.usage, null);
  assert.equal(trial.finalizedUsage.total, 220);
  assert.equal(trial.accounting.records.length, 2);
  assert.equal(trial.accounting.costs.task.state, 'incomplete');
  assert.equal(trial.accounting.costs.task.knownTotal, 0.004);
  assert.equal(Object.hasOwn(trial.accounting.costs.task, 'total'), false);
  assert.equal(trial.context.input.state, 'incomplete');
  const { projectPiTrial, taskCostForComparison } = await import('./pi-accounting.mjs');
  const projected = projectPiTrial(trial);
  assert.equal(Object.hasOwn(projected, 'tokenUsage'), false);
  assert.equal(Object.hasOwn(projected, 'cost'), false);
  assert.equal(projected.metadata.finalizedUsage.total, 220);
  assert.equal(taskCostForComparison(trial.accounting).state, 'unavailable');
});

test('helper charges survive both Pi event representations exactly once and belong to task cost', () => {
  const tool = { role: 'toolResult', toolCallId: 'helper-1', toolName: 'helper',
    content: [{ type: 'text', text: 'exact delivered helper output' }],
    usage: reported(0.006), isError: false };
  const trial = decode(
    { type: 'tool_execution_start', toolCallId: 'helper-1', toolName: 'helper', args: { query: 'safe source query' } },
    { type: 'tool_execution_end', toolCallId: 'helper-1', toolName: 'helper', result: tool },
    end(tool), end(assistant()), end(assistant()));
  assert.equal(trial.accounting.costs.participant.total, 0.004);
  assert.equal(trial.accounting.costs.helper.total, 0.006);
  assert.equal(trial.accounting.costs.task.total, 0.01);
  assert.equal(trial.accounting.records.length, 2);
  assert.equal(trial.providerResponses, 1);
  assert.equal(trial.usage.total, 220);
  assert.equal(trial.context.responses, 1);
  assert.equal(trial.accounting.duplicateRecords, 2);
  assert.match(trial.evidence.tools[0].result.text, /exact delivered helper output/);
});

test('conflicting duplicate response identities cannot authorize a dollar comparison', async () => {
  const trial = decode(end(assistant()), end(assistant({ usage: reported(0.009) })));
  assert.equal(trial.accounting.costs.task.state, 'incomplete');
  assert.ok(trial.accounting.costs.task.reasons.includes('CONFLICTING_RESPONSE_ID'));
  const { taskCostForComparison } = await import('./pi-accounting.mjs');
  assert.equal(taskCostForComparison(trial.accounting).state, 'unavailable');
});

test('estimates require matching copied catalog evidence and preserve rates without recalculation', async () => {
  const { snapshotPiCatalog, taskCostForComparison } = await import('./pi-accounting.mjs');
  const estimated = { ...reported(), cost: { ...reported().cost, total: 0.0037, totalFromProvider: false } };
  const message = assistant({ usage: estimated, responseModel: undefined });
  const missing = decode(end(message));
  assert.equal(missing.accounting.costs.task.knownTotal, 0.0037);
  assert.equal(taskCostForComparison(missing.accounting).state, 'unavailable');
  const copied = JSON.stringify({ 'openai-codex': { checkedAt: 1000,
    models: [{ id: 'accounting-test', provider: 'openai-codex', api: 'openai-codex-responses',
      headers: { Authorization: 'secret-must-never-survive' },
      cost: { input: 10, output: 50, cacheRead: 5, cacheWrite: 20 } }] } });
  const catalog = snapshotPiCatalog(copied, model);
  assert.equal(catalog.state, 'copied');
  assert.equal(catalog.selected.state, 'available');
  assert.doesNotMatch(JSON.stringify(catalog), /Authorization|secret-must-never-survive/);
  const trial = decodePiTrial(stream(end(message)), model, [], true, { runtime: { catalog } });
  const cost = taskCostForComparison(trial.accounting);
  assert.equal(cost.state, 'ready');
  assert.equal(cost.total, 0.0037);
  assert.deepEqual(cost.pricingBasis, [catalog.sha256]);
  assert.deepEqual(trial.accounting.records[0].cost.rates.cost, { input: 10, output: 50, cacheRead: 5, cacheWrite: 20 });
  const forged = structuredClone(trial.accounting);
  forged.costs.task.total = 0;
  assert.equal(taskCostForComparison(forged).state, 'unavailable');
});

test('unreported tool usage needs an explicit deterministic-tool effect contract', async () => {
  const events = stream(
    { type: 'tool_execution_start', toolCallId: 'read-1', toolName: 'helper', args: {} },
    { type: 'tool_execution_end', toolCallId: 'read-1', toolName: 'helper', result: { content: [] } }, end(assistant()));
  const unknown = decodePiTrial(events, model, ['helper']);
  assert.equal(unknown.accounting.costs.task.state, 'incomplete');
  const deterministic = decodePiTrial(events, model, ['helper'], true, { toolUsagePolicy: 'no-model-helpers' });
  assert.equal(deterministic.accounting.costs.task.total, 0.004);
  const { taskCostForComparison } = await import('./pi-accounting.mjs');
  assert.equal(taskCostForComparison(unknown.accounting).state, 'unavailable');
});

test('invalid reasoning or cache-write subsets cannot become validated usage', () => {
  for (const subset of [{ reasoning: 41 }, { cacheWrite1h: 21 }]) {
    assert.throws(() => decode(end(assistant({ usage: { ...reported(), ...subset } }))), /Pi.*usage/);
  }
});

test('cutoffs preserve known subtotals and bounded delivery marks omitted bytes', async () => {
  const text = 'x'.repeat(200000);
  const trial = settlePiTrial({ status: null, termination: 'PARTICIPANT_DEADLINE', stdout: stream(
    end(assistant({ stopReason: 'toolUse', content: [{ type: 'text', text }] })),
    { type: 'message_start', message: assistant({ responseId: 'response-2', stopReason: 'pending', usage: undefined }) }) }, model, [], true);
  assert.equal(trial.accounting.costs.task.state, 'incomplete');
  assert.equal(trial.accounting.costs.task.knownTotal, 0.004);
  assert.equal(trial.finalizedUsage.total, 220);
  assert.equal(trial.evidence.final.state, 'truncated');
  assert.equal(trial.evidence.final.bytes, 200000);
  assert.match(trial.evidence.final.sha256, /^[a-f0-9]{64}$/);
  assert.ok(Buffer.byteLength(trial.evidence.final.text) < trial.evidence.final.bytes);
  const { projectPiTrial, taskCostForComparison } = await import('./pi-accounting.mjs');
  const unobserved = projectPiTrial(undefined);
  assert.equal(unobserved.metadata.accounting.costs.task.state, 'incomplete');
  assert.equal(Object.hasOwn(unobserved, 'cost'), false);
  assert.equal(Object.hasOwn(unobserved, 'tokenUsage'), false);
  assert.equal(taskCostForComparison({ schemaVersion: 1, costs: { task: { state: 'complete', total: 0 } } }).state, 'unavailable');
});

test('rejected event tails retain earlier validated native usage in an incomplete failure receipt', async () => {
  const { projectPiTrial, taskCostForComparison } = await import('./pi-accounting.mjs');
  for (const stdout of [stream(end(assistant())) + '{bad-json}\n',
    stream(end(assistant()), end(assistant({ responseId: 'wrong-model', model: 'unrequested' })))]) {
    assert.throws(() => settlePiTrial({ status: 0, stdout }, model, [], true), error => {
      assert.ok(error.trial);
      const projected = projectPiTrial(error.trial);
      assert.ok(projected.metadata.accounting.costs.task.knownTotal >= 0.004);
      assert.equal(taskCostForComparison(projected.metadata.accounting).state, 'unavailable');
      assert.equal(Object.hasOwn(projected, 'cost'), false);
      return true;
    });
  }
});

test('a failed zero-initialized estimate is unmetered rather than known free work', async () => {
  const zero = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } };
  const trial = settlePiTrial({ status: 0, stdout: stream(end(assistant({ stopReason: 'toolUse' })),
    end(assistant({ responseId: 'failed', stopReason: 'error', usage: zero }))) }, model, [], true);
  assert.equal(trial.accounting.records[1].usage.state, 'unobserved');
  assert.equal(trial.accounting.records[1].cost.state, 'unobserved');
  assert.equal(trial.accounting.costs.task.knownTotal, 0.004);
  assert.equal(trial.context.input.state, 'incomplete');
  assert.equal(trial.usage, null);
});

test('comparison context is rederived from native receipts and malformed rate lineage fails closed', async () => {
  const { contextForComparison, snapshotPiCatalog, taskCostForComparison } = await import('./pi-accounting.mjs');
  const trial = decode(end(assistant()), end(assistant({ responseId: 'second', usage: { ...reported(),
    input: 50, totalTokens: 170 } })));
  assert.deepEqual(contextForComparison(trial.accounting), {
    state: 'ready', inputSum: 310, peakInput: 180, finalInput: 130, responses: 2 });
  const invalid = structuredClone(trial.accounting);
  invalid.records[0].usage.value.reasoning = 41;
  assert.equal(contextForComparison(invalid).state, 'unavailable');
  const catalog = snapshotPiCatalog(JSON.stringify({ 'openai-codex': { models: [{ provider: 'openai-codex',
    id: 'accounting-test', cost: { input: 10, output: 50, cacheRead: 5, cacheWrite: 20 } }] } }), model);
  const estimated = { ...reported(), cost: { ...reported().cost, total: 0.0037, totalFromProvider: false } };
  const rateTrial = decodePiTrial(stream(end(assistant({ usage: estimated, responseModel: undefined }))), model, [], true, { runtime: { catalog } });
  const wrongLineage = structuredClone(rateTrial.accounting);
  wrongLineage.records[0].cost.rates.catalogSha256 = 'f'.repeat(64);
  assert.equal(taskCostForComparison(wrongLineage).state, 'unavailable');
});

test('accounting retention is byte-bounded with an explicit omitted-record digest', () => {
  const large = Array.from({ length: 150 }, (_, index) => end(assistant({
    responseId: `${index}-${'i'.repeat(1000)}`, responseModel: 'm'.repeat(1000),
    providerThinkingLevel: 'p'.repeat(1000), thinkingLevel: 't'.repeat(1000),
  })));
  const trial = decode(...large);
  assert.ok(trial.accounting.coverage.omittedRecords > 0);
  assert.ok(Buffer.byteLength(JSON.stringify(trial.accounting.records)) <= 524288);
  assert.equal(trial.accounting.costs.task.state, 'incomplete');
  assert.match(trial.accounting.retention.omittedSha256, /^[a-f0-9]{64}$/);
});

test('nested tool traces cannot supply missing helper charges', () => {
  const helper = { role: 'toolResult', toolCallId: 'nested', toolName: 'helper', usage: reported(0.006),
    nestedCalls: { complete: false, calls: [] } };
  const nested = decode({ type: 'tool_execution_start', toolCallId: 'nested', toolName: 'helper' },
    { type: 'tool_execution_end', toolCallId: 'nested', toolName: 'helper', result: helper }, end(assistant()));
  assert.equal(nested.accounting.costs.task.knownTotal, 0.01);
  assert.equal(nested.accounting.costs.task.state, 'incomplete');
});
