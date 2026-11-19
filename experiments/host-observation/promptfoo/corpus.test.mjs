import assert from 'node:assert/strict';
import test from 'node:test';
import { exactAnswer } from './corpus-grading.mjs';

test('grades unordered answers and rejects omissions, extras, duplicates, and malformed output', () => {
  const expected = ['A.kt#f', 'B.java#g'];
  assert.equal(exactAnswer('["B.java#g","A.kt#f"]', expected), true);
  for (const answer of ['["A.kt#f"]', '["A.kt#f","B.java#g","A.kt#g"]',
    '["A.kt#f","B.java#g","A.kt#f"]', '{"answer":["A.kt#f","B.java#g"]}', 'unknown']) {
    assert.equal(exactAnswer(answer, expected), false);
  }
});

test('each published task has unique answers and at least one distinct distractor', async () => {
  const { casesForSuite, suites } = await import('./corpus-cases.mjs');
  const ids = new Set();
  for (const name of suites) {
    const tasks = casesForSuite(name);
    assert.match(tasks[0].revision, /^[0-9a-f]{40}$/);
    for (const { id, expected, nearMiss, sources } of tasks) {
      assert.ok(id.startsWith(name + '-') && !ids.has(id));
      ids.add(id);
      assert.ok(expected.length > 0 && expected.every(x => typeof x === 'string' && x.trim()));
      assert.equal(expected.length, new Set(expected).size);
      assert.ok(nearMiss.some(value => !expected.includes(value)));
      assert.ok(sources.length > 0 && sources.every(path => /^[^:]+:[1-9][0-9]*$/.test(path)));
    }
  }
});

test('a failed Pi terminal preserves its finite cause without copying provider text', async () => {
  const { settlePiTrial } = await import('./skill-provider.mjs');
  for (const [stopReason, errorMessage, cause] of [
    ['aborted', 'private provider detail', 'PI_PROVIDER_ABORTED'],
    ['error', 'private provider detail', 'PI_PROVIDER_ERROR'],
    ['error', 'You have hit your ChatGPT usage limit. private provider detail', 'PI_PROVIDER_USAGE_LIMIT'],
  ]) {
    const message = { role: 'assistant', provider: 'openai-codex', model: 'test-model', stopReason, errorMessage, content: [] };
    const stdout = JSON.stringify({ type: 'message_end', message });
    const result = settlePiTrial({ status: 0, stdout }, 'openai-codex/test-model', [], true);
    assert.equal(result.terminalCause, cause);
    assert.equal(result.usage, null);
    assert.ok(!JSON.stringify(result).includes('private provider detail'));
  }
});
