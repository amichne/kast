import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { SourceTextModule, createContext } from 'node:vm';
import test from 'node:test';

const result = 'result:v1:00000000-0000-0000-0000-000000000001';
const continuation = 'query-output:v1:00000000-0000-0000-0000-000000000002';
const transition = 'QUERY_SESSION_TRANSITION_REJECTED';
const next = 'START_NEW_QUERY_SESSION';
const details = [
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
  let source = readFileSync(new URL(`../../../../${harness}/extension.${harness === 'pi' ? 'ts' : 'mjs'}`, import.meta.url), 'utf8');
  if (harness === 'pi') source = stripTypeScriptTypes(source);
  const failures = source.match(/const TOOL_RPC_FAILURES = \[[\s\S]+?\]\s*;/)[0];
  const admission = source.match(/function isToolResult\([^\n]+\n[\s\S]+?\n}/)[0];
  const module = new SourceTextModule(failures + '\nconst isObject = value => value !== null && typeof value === "object" && !Array.isArray(value);\n' +
    admission + '\nexport { isToolResult };', { context: createContext({}) });
  await module.link(() => { throw new Error('Reply admission cannot import effects'); });
  await module.evaluate();
  const accepts = module.namespace.isToolResult;

  test(`${harness} admits legacy failures and each closed recovery detail without changing data`, () => {
    assert.equal(accepts({ type: 'rejected', failure: transition }), true);
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
