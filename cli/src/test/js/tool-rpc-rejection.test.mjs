import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { SourceTextModule, createContext } from 'node:vm';
import test from 'node:test';

// Independent wire oracle, shared with the Kotlin ingress encoder tests.
const constraintEncoded = await readFile(new URL('../resources/rpc/runner-controlled-work-limit-rejected.json', import.meta.url), 'utf8');
const constraintRejection = JSON.parse(constraintEncoded);
const result = 'result:v1:00000000-0000-0000-0000-000000000001';
const continuation = 'query-output:v1:00000000-0000-0000-0000-000000000002';
const transition = 'QUERY_SESSION_TRANSITION_REJECTED';
const next = 'START_NEW_QUERY_SESSION';
const details = [
  ['INVALID_ARGUMENTS', constraintRejection.detail],
  [transition, { type: 'NEW_RUN_REQUIRES_NEW_SESSION', next_action: next }],
  [transition, { type: 'SOURCE_READ_REQUIRES_NEW_SESSION', next_action: next }],
  ...[null, result].map(expected => [transition, { type: 'RESULT_NOT_ISSUED_BY_PRECEDING_REPLY',
    expected_result: expected, requested_result: result, next_action: next }]),
  ...[null, continuation].map(expected => [transition, { type: 'CONTINUATION_NOT_ISSUED_BY_PRECEDING_REPLY',
    expected_continuation: expected, requested_continuation: continuation, next_action: next }]),
  ...['UNKNOWN_TOKEN_FAMILY', 'INVALID_TOKEN_STRUCTURE', 'INVALID_PAYLOAD_ENCODING', 'PAYLOAD_DIGEST_MISMATCH']
    .map(cause => ['INVALID_ARGUMENTS', { type: 'SOURCE_ANCHOR_REJECTED', cause, next_action: 'USE_ISSUED_CANDIDATE_SELECTOR' }]),
];

for (const harness of ['copilot', 'pi']) {
  let source = await readFile(new URL(`../../../../${harness}/extension.${harness === 'pi' ? 'ts' : 'mjs'}`, import.meta.url), 'utf8');
  if (harness === 'pi') source = stripTypeScriptTypes(source);
  const failures = source.match(/const TOOL_RPC_FAILURES = \[[\s\S]+?\]\s*;/)[0];
  const admission = source.match(/function isToolResult\([^\n]+\n[\s\S]+?\n}/)[0];
  const sessionSource = source.match(/function createQuerySession\([^\n]+\n[\s\S]+?\n}/)[0];
  const caller = source.match(/function run\([^\n]+\n[\s\S]+?\n}/)[0];
  const delivery = source.slice(source.indexOf('// Generated query delivery;'), source.indexOf('// End generated query delivery.'));
  const adapterSource = [failures, admission, sessionSource, caller, delivery,
    'const command = "/selected";',
    'export { isToolResult, createQuerySession, run, awaitQueryDelivery, queryPresentation, queryDeliveryFailed };'].join('\n');
  async function loadAdapter(effects = {}) {
    const module = new SourceTextModule(adapterSource, { context: createContext({ Buffer, TextDecoder, ...effects }) });
    await module.link(() => { throw new Error('Adapter rules cannot import effects'); });
    await module.evaluate();
    return module.namespace;
  }
  const { isToolResult: accepts } = await loadAdapter();

  // Only child observations and timers are virtualized. Both transport readers,
  // their decoder, delivery caller and rejection presentation are production code.
  async function transportFixture(transport, encoded, arguments_) {
    const writes = [], launches = [], kills = [], timers = [];
    const policy = { callTimeoutMillis: 100, maxResponseBytes: 262_144 };
    const spawn = (command, args, options) => {
      launches.push({ command, args: Array.from(args), cwd: options.cwd });
      assert.equal(launches.length, 1, 'No transport may replay the request');
      assert.equal(command, '/selected');
      assert.deepEqual(Array.from(args), transport === 'query-session' ? ['query-session'] : ['call', 'query_symbols']);
      assert.equal(options.cwd, '/owned/workspace');
      const child = new EventEmitter();
      child.stdin = new EventEmitter();
      child.stdout = new EventEmitter();
      child.stderr = new EventEmitter();
      const respond = input => {
        writes.push(input);
        assert.equal(writes.length, 1, 'The scripted child accepts exactly one request');
        assert.deepEqual(JSON.parse(input), arguments_);
        const bytes = Buffer.from(encoded);
        child.stdout.emit('data', bytes.subarray(0, 50));
        child.stdout.emit('data', bytes.subarray(50));
      };
      child.stdin.write = respond;
      child.stdin.end = input => {
        if (input !== undefined) respond(input);
        queueMicrotask(() => child.emit('close', 0));
      };
      child.kill = signal => {
        kills.push(signal);
        queueMicrotask(() => child.emit('close', null));
        return true;
      };
      return child;
    };
    const adapter = await loadAdapter({
      spawn, process: { env: {}, cwd: () => '/owned/workspace' },
      setTimeout: (callback, millis) => { const timer = { callback, millis }; timers.push(timer); return timer; },
      clearTimeout: timer => { if (timer) timer.cleared = true; },
    });
    const session = transport === 'query-session'
      ? adapter.createQuerySession('/selected', '/owned/workspace', policy, undefined, spawn, {}) : null;
    const invoke = session?.invoke ?? ((args, deliveryPolicy) => harness === 'pi'
      ? adapter.run(['call', 'query_symbols'], JSON.stringify(args), '/owned/workspace', deliveryPolicy)
      : adapter.run(['call', 'query_symbols'], JSON.stringify(args), deliveryPolicy, null, '/owned/workspace'));
    return { adapter, policy, session, invoke, writes, launches, kills, timers };
  }

  test(`${harness} admits legacy failures and each closed recovery detail without changing data`, () => {
    assert.equal(accepts({ type: 'rejected', failure: transition }), true);
    assert.equal(accepts({ type: 'rejected', failure: 'INVALID_ARGUMENTS' }), true);
    for (const [failure, detail] of details) {
      const reply = { type: 'rejected', failure, detail }, before = JSON.stringify(reply);
      assert.equal(accepts(reply), true, detail.type);
      assert.equal(JSON.stringify(reply), before);
    }
    assert.equal(accepts({ type: 'rejected', failure: transition, detail: {
      type: 'CONTINUATION_NOT_ISSUED_BY_PRECEDING_REPLY', expected_continuation: null,
      requested_continuation: continuation.replace('query-output:', 'query:'), next_action: next } }), true);
  });

  test(`${harness} rejects missing fields, extra fields and incorrect failure bindings`, () => {
    for (const [failure, detail] of details) {
      const reply = { type: 'rejected', failure, detail };
      assert.equal(accepts({ ...reply, detail: null }), false);
      assert.equal(accepts({ ...reply, extra: true }), false);
      assert.equal(accepts({ ...reply, failure: failure === transition ? 'INVALID_ARGUMENTS' : transition }), false);
      assert.equal(accepts({ ...reply, detail: { ...detail, extra: true } }), false);
      for (const key of Object.keys(detail)) {
        const incomplete = structuredClone(detail);
        delete incomplete[key];
        assert.equal(accepts({ ...reply, detail: incomplete }), false, key);
      }
    }
    assert.equal(accepts({ type: 'rejected', failure: transition, detail: { type: 'UNKNOWN', next_action: next } }), false);
    assert.equal(accepts({ type: 'rejected', failure: 'INVALID_ARGUMENTS', detail: {
      type: 'SOURCE_ANCHOR_REJECTED', cause: 'STALE', next_action: 'USE_ISSUED_CANDIDATE_SELECTOR' } }), false);
    assert.equal(accepts({ type: 'complete', document: {}, detail: details[0][1] }), false);
  });

  test(`${harness} admits the complete constraint sets in either order without changing them`, () => {
    for (const parameters of [constraintRejection.detail.parameters, [...constraintRejection.detail.parameters].reverse()]) {
      for (const remedies of [constraintRejection.detail.remedies, [...constraintRejection.detail.remedies].reverse()]) {
        const reply = { ...constraintRejection, detail: { ...constraintRejection.detail, parameters, remedies } };
        const before = JSON.stringify(reply);
        assert.equal(accepts(reply), true);
        assert.equal(JSON.stringify(reply), before);
      }
    }
  });

  test(`${harness} rejects unknown constraint tags, rules, paths and remedies and malformed or incomplete sets`, () => {
    for (const [field, invalid] of [
      ['type', 'UNKNOWN_CONSTRAINT_REJECTED'], ['rule', 'SIMPLE_NAME'], ['rule', 'UNKNOWN'], ['rule', null],
    ]) {
      assert.equal(accepts({ ...constraintRejection, detail: { ...constraintRejection.detail, [field]: invalid } }), false, field);
    }
    for (const field of ['parameters', 'remedies']) {
      const [first, second] = constraintRejection.detail[field];
      for (const invalid of [
        null, first, 3, false, {}, { 0: first, 1: second, length: 2 }, [], [first], [second],
        [first, first], [second, second], [first, second, first], [first, 'UNKNOWN'], ['UNKNOWN', second],
        [first, null], [first, {}], [[first, second]], [first, second, 'UNKNOWN'], new Array(2),
        field === 'parameters' ? [first, 'executionBudget.maxUnits'] : [first, 'SELECT_RUNNER_CONTROLLED'],
      ]) {
        assert.equal(accepts({ ...constraintRejection, detail: { ...constraintRejection.detail, [field]: invalid } }), false,
          `${field}=${JSON.stringify(invalid)}`);
      }
    }
  });

  const arguments_ = { request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' }, terminalStep: { type: 'EMIT' },
    executionMode: 'RUNNER_CONTROLLED', executionBudget: { maxWorkUnits: 1 } } };
  for (const transport of ['query-session', 'call']) {
    test(`${harness} ${transport} preserves the encoded constraint rejection through the actual query caller without replay`, async () => {
      const f = await transportFixture(transport, constraintEncoded, arguments_);
      let reply;
      try { reply = await f.adapter.awaitQueryDelivery(arguments_, f.invoke, f.policy, undefined, () => 0); }
      finally { await f.session?.close(); }
      assert.deepEqual(JSON.parse(JSON.stringify(reply)), constraintRejection);
      assert.equal(f.adapter.queryDeliveryFailed(reply), true);
      assert.deepEqual(JSON.parse(f.adapter.queryPresentation(reply)), constraintRejection);
      assert.equal(f.writes.length, 1);
      assert.equal(f.launches.length, 1);
      assert.deepEqual(f.kills, []);
      assert.ok(f.timers.every(timer => timer.cleared));
    });

    for (const detail of [
      { ...constraintRejection.detail, rule: 'UNKNOWN' },
      { ...constraintRejection.detail, remedies: ['REMOVE_MAX_WORK_UNITS', 'REMOVE_MAX_WORK_UNITS'] },
    ]) {
      test(`${harness} ${transport} still rejects malformed constraint ${detail.rule === 'UNKNOWN' ? 'rule' : 'remedies'} without replay`, async () => {
        const encoded = JSON.stringify({ ...constraintRejection, detail }) + '\n';
        const f = await transportFixture(transport, encoded, arguments_);
        try { await assert.rejects(f.adapter.awaitQueryDelivery(arguments_, f.invoke, f.policy, undefined, () => 0), /outcome=INVALID_RESULT/); }
        finally { await f.session?.close(); }
        assert.equal(f.writes.length, 1);
        assert.equal(f.launches.length, 1);
        assert.ok(f.timers.every(timer => timer.cleared));
      });
    }
  }

  test(`${harness} preserves result and continuation token families and explicit expected nulls`, () => {
    for (const [type, field, token] of [
      ['RESULT_NOT_ISSUED_BY_PRECEDING_REPLY', 'result', result],
      ['CONTINUATION_NOT_ISSUED_BY_PRECEDING_REPLY', 'continuation', continuation],
    ]) {
      const detail = { type, [`expected_${field}`]: null, [`requested_${field}`]: token, next_action: next };
      for (const invalid of [null, '', 'opaque:test', token + '-suffix', token.toUpperCase(), 3])
        assert.equal(accepts({ type: 'rejected', failure: transition, detail: { ...detail, [`requested_${field}`]: invalid } }), false);
      assert.equal(accepts({ type: 'rejected', failure: transition, detail: {
        ...detail, [`expected_${field}`]: field === 'result' ? continuation : result } }), false);
      assert.equal(accepts({ type: 'rejected', failure: transition, detail: { ...detail, next_action: 'RETRY_RUN' } }), false);
    }
  });
}
