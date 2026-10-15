import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { test } from 'node:test';

const source = readFileSync(new URL('../../../../pi/mcp-extension.ts', import.meta.url), 'utf8');
const { default: register } = await import('data:text/javascript;base64,' +
  Buffer.from(stripTypeScriptTypes(source)).toString('base64'));

function fixture(result = { code: 0, stdout: 'Kast shared context\n', stderr: '', killed: false }) {
  const handlers = new Map(), servers = [], calls = [];
  register({
    registerMcpServer: (...args) => servers.push(args),
    on: (name, handler) => handlers.set(name, handler),
    exec: async (...args) => { calls.push(args); return result; },
  });
  return { handlers, servers, calls };
}

test('Pi registers native MCP without RPC or factory process effects', async () => {
  const { handlers, servers, calls } = fixture();
  assert.equal(calls.length, 0);
  assert.equal(servers.length, 1);
  assert.equal(servers[0][0], 'kast');
  assert.ok(servers[0][1].command.endsWith('/installation/bin/kast-mcp-complete'));
  await handlers.get('session_start')({}, { cwd: '/fixture/worktree $ literal' });
  assert.deepEqual(calls[0][1].slice(-2), ['pi', '/fixture/worktree $ literal']);
  for (let turn = 0; turn < 2; turn++) {
    const prompt = handlers.get('before_agent_start')({ systemPrompt: 'Other extension context' });
    assert.equal(prompt.systemPrompt, 'Other extension context\n\nKast shared context');
  }
  assert.equal(calls.length, 1, 'Context is read once per session, without semantic tools');
});

test('Pi rejects absent, failed, killed, and oversized context before a model request', async () => {
  for (const result of [
    { code: 1, stdout: 'partial' }, { code: 0, stdout: '' },
    { code: 0, stdout: 'partial', killed: true }, { code: 0, stdout: 'x'.repeat(8194) },
  ]) {
    const { handlers } = fixture(result);
    await assert.rejects(handlers.get('session_start')({}, { cwd: '/fixture' }), /context is unavailable/);
    assert.throws(() => handlers.get('before_agent_start')({ systemPrompt: 'base' }), /context is unavailable/);
  }
});
