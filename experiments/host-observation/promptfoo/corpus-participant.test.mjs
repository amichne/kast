import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import test from 'node:test';
import { participate } from './corpus-participant.mjs';
import { settlePiTrial } from './skill-provider.mjs';

const request = { workspace: tmpdir(), model: 'openai-codex/test-model', arm: 'source', prompt: 'Return the locations.', revision: '1'.repeat(40) };
const receipt = () => ({ reply: '["A.kt:1"]', calls: [], toolCalls: 0, toolProposals: 0, providerResponses: 1, elapsedMillis: 1, usage: null });
const usage = { input: 12, output: 3, cacheRead: 4, cacheWrite: 2, totalTokens: 21,
  cost: { input: 0.012, output: 0.003, cacheRead: 0.004, cacheWrite: 0.002, total: 0.021, totalFromProvider: true } };
const message = { role: 'assistant', provider: 'openai-codex', model: 'test-model', responseId: 'synthetic-response',
  stopReason: 'stop', content: [{ type: 'text', text: '  ["A.kt:1"]\n' }], usage };
const stream = (...events) => events.map(event => JSON.stringify(event)).join('\n') + '\n';

test('corpus provider preserves read-only source tools and destroys its isolated participant directory', async () => {
  let invocation;
  const response = await participate(request, {
    getBinary: async (_override, name) => name,
    runPi: async input => { invocation = input; return receipt(); },
  });
  assert.equal(response.output, '["A.kt:1"]');
  assert.equal(response.error, undefined);
  assert.deepEqual(invocation.toolNames, ['list_files', 'read_file', 'search_source']);
  assert.equal(invocation.extensionEnvironment.KAST_CORPUS_REVISION, request.revision);
  assert.equal(existsSync(invocation.directory), false);
  assert.equal(response.metadata.kastCalls, 0);
  assert.equal(response.tokenUsage, undefined);
});

test('Panel A admission rejects restricted corpus tools before resolving executables or invoking Pi', async () => {
  let effectCount = 0;
  const response = await participate({ ...request, requestedPanel: 'A' }, {
    getBinary: async (_override, name) => { effectCount++; return name; },
    runPi: async () => { effectCount++; return receipt(); },
  });
  assert.equal(response.error, 'PANEL_A_BASELINE_UNQUALIFIED');
  assert.equal(effectCount, 0);
  assert.equal(response.metadata.requestedPanel, 'A');
  assert.equal(response.metadata.panel, 'RESTRICTED');
  assert.equal(response.metadata.admission.reason, 'ORDINARY_COMPILER_TEST_INSPECTION_UNAVAILABLE');
  assert.equal(response.metadata.accounting.costs.task.state, 'incomplete');
  assert.equal(response.cost, undefined);
  assert.equal(response.metadata.timing.phases.find(phase => phase.phase === 'participant').outcome, 'NOT_RUN');
});

test('legacy corpus trials remain executable with explicit restricted capability and budget provenance', async () => {
  let invocation;
  const response = await participate(request, {
    getBinary: async (_override, name) => name,
    runPi: async input => { invocation = input; return receipt(); },
  });
  assert.equal(response.metadata.panel, 'RESTRICTED');
  assert.equal(response.metadata.baseline.id, 'tracked-source-v2');
  assert.equal(response.metadata.baseline.panelA.admitted, false);
  assert.equal(response.metadata.budgets.participantMillis, 240_000);
  assert.equal(invocation.toolUsagePolicy, 'no-model-helpers');
  assert.equal(response.metadata.timing.phases.find(phase => phase.phase === 'cleanup').outcome, 'COMPLETED');
});

test('corpus receipts retain native charges and the exact delivered source text and final answer', async () => {
  const delivered = [{ type: 'text', text: '{"path":"A.kt","rows":[{"line":1,"text":"fun exact() = 1"}]}' }];
  const trial = settlePiTrial({ status: 0, stdout: stream(
    { type: 'tool_execution_start', toolCallId: 'read-1', toolName: 'read_file', args: { path: 'A.kt' } },
    { type: 'tool_execution_end', toolCallId: 'read-1', toolName: 'read_file', result: { content: delivered, details: {} } },
    { type: 'message_end', message },
  ) }, request.model, ['read_file'], true, { toolUsagePolicy: 'no-model-helpers' });
  const response = await participate(request, { getBinary: async (_override, name) => name,
    runPi: async () => ({ ...trial, elapsedMillis: 1 }) });
  assert.equal(response.cost, 0.021);
  assert.equal(response.metadata.accounting.costs.task.provenance, 'provider-reported');
  assert.deepEqual(response.tokenUsage, { total: 21, prompt: 18, completion: 3, cached: 4, numRequests: 1 });
  assert.equal(response.metadata.evidence.final.text, message.content[0].text);
  assert.equal(response.metadata.evidence.tools[0].result.text, JSON.stringify(delivered));
  assert.equal(response.metadata.evidence.tools[0].result.state, 'complete');
});

test('a rejected Pi tail retains the prior observed charge as an incomplete receipt', async () => {
  const partial = settlePiTrial({ status: null, termination: 'PARTICIPANT_DEADLINE', stdout: stream(
    { type: 'message_end', message: { ...message, stopReason: 'toolUse' } },
    { type: 'message_start', message: { ...message, responseId: 'pending', usage: undefined, stopReason: 'pending' } },
  ) }, request.model, [], true, { toolUsagePolicy: 'no-model-helpers' });
  const response = await participate(request, {
    getBinary: async (_override, name) => name,
    runPi: async () => { throw Object.assign(Error('rejected tail'), { code: 'PI_EVENT_PARSE_FAILED', trial: partial }); },
  });
  assert.equal(response.error, 'PARTICIPANT_RUNTIME_FAILED');
  assert.equal(response.metadata.terminalCause, 'PI_EVENT_PARSE_FAILED');
  assert.equal(response.metadata.accounting.costs.task.state, 'incomplete');
  assert.equal(response.metadata.accounting.costs.task.knownTotal, 0.021);
  assert.equal(response.metadata.finalizedUsage.total, 21);
  assert.equal(response.cost, undefined);
  assert.equal(response.tokenUsage, undefined);
});

test('cleanup is recorded as failed and the workspace is retained when participant reaping is unverified', async t => {
  let directory;
  t.after(async () => { if (directory) await rm(directory, { recursive: true, force: true }); });
  const response = await participate(request, {
    getBinary: async (_override, name) => name,
    runPi: async input => { directory = input.directory; throw Object.assign(Error('unreaped'), { code: 'PI_CHILD_REAP_UNAVAILABLE' }); },
    cleanup: async () => assert.fail('removed an unverified participant workspace'),
  });
  assert.equal(response.error, 'PI_CHILD_REAP_UNAVAILABLE');
  assert.equal(response.metadata.cleanup, 'PARTICIPANT_CLEANUP_FAILED');
  assert.equal(response.metadata.timing.phases.find(phase => phase.phase === 'cleanup').outcome, 'FAILED');
  assert.equal(existsSync(directory), true);
});
