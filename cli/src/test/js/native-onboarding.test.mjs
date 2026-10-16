import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

const source = readFileSync(new URL('../../../../pi/mcp-extension.ts', import.meta.url), 'utf8');
const resources = ['onboarding.md', 'query-schema.json', 'query-examples.json'];

async function fixture(t) {
  const home = realpathSync(mkdtempSync(join(tmpdir(), 'kast pi $ literal ')));
  t.after(() => rmSync(home, { recursive: true, force: true }));
  const installation = join(home, '.local/share/kast/installation');
  const references = join(installation, 'share/kast/agent-tools/plugins/kast/references');
  const workspace = join(home, 'workspace $ literal');
  mkdirSync(references, { recursive: true });
  mkdirSync(workspace);
  writeFileSync(join(references, 'onboarding.md'), 'Kast shared context\n');
  writeFileSync(join(references, 'query-schema.json'), '{}\n');
  writeFileSync(join(references, 'query-examples.json'), '[]\n');

  // Replace only the OS home lookup. Resource admission uses the real Node filesystem.
  const osModule = 'data:text/javascript;base64,' + Buffer.from(
    'export const homedir = () => ' + JSON.stringify(home) + ';').toString('base64');
  const { default: register } = await import('data:text/javascript;base64,' + Buffer.from(
    stripTypeScriptTypes(source.replace('from "node:os"', 'from "' + osModule + '"'))).toString('base64'));
  const handlers = new Map(), servers = [], notifications = [];
  register({
    registerMcpServer: (...args) => servers.push(args),
    on: (name, handler) => handlers.set(name, handler),
    exec: () => assert.fail('Pi context must not start a child process'),
  });
  const ctx = { cwd: workspace, ui: { notify: (...args) => notifications.push(args) } };
  return { handlers, servers, notifications, ctx, references, workspace, installation };
}

function assertContinuesWithoutContext(f, reason) {
  assert.equal(f.servers.length, 1, 'Native MCP stays registered');
  assert.deepEqual(f.notifications, [[
    'Kast session context is unavailable (' + reason + '). Native MCP remains registered.', 'warning',
  ]]);
  for (let turn = 0; turn < 2; turn++) {
    assert.equal(f.handlers.get('before_agent_start')({ systemPrompt: 'Other extension context' }), undefined);
  }
  assert.equal(f.notifications.length, 1, 'A context failure does not repeat on every turn');
}

test('Pi registers native MCP and reads shared context without a child process', async t => {
  const f = await fixture(t);
  assert.deepEqual(f.servers, [['kast', {
    command: join(f.installation, 'bin/kast-mcp-complete'), timeout: 180,
  }]]);
  assert.equal(f.handlers.get('before_agent_start')({ systemPrompt: 'base' }), undefined);
  await f.handlers.get('session_start')({}, f.ctx);
  const expected = 'Other extension context\n\nKast shared context' +
    '\nWorkspace: ' + JSON.stringify(f.workspace) +
    '\nQuery schema: ' + JSON.stringify(join(f.references, 'query-schema.json')) +
    '\nQuery examples: ' + JSON.stringify(join(f.references, 'query-examples.json'));
  unlinkSync(join(f.references, 'onboarding.md'));
  for (let turn = 0; turn < 2; turn++) {
    assert.equal(f.handlers.get('before_agent_start')({ systemPrompt: 'Other extension context' }).systemPrompt, expected);
  }
  assert.deepEqual(f.notifications, []);

  await f.handlers.get('session_start')({}, f.ctx);
  assertContinuesWithoutContext(f, 'RESOURCE_UNAVAILABLE');
});

test('Pi keeps normal turns available when a bundled resource is absent', async t => {
  for (const name of resources) {
    const f = await fixture(t);
    unlinkSync(join(f.references, name));
    await f.handlers.get('session_start')({}, f.ctx);
    assertContinuesWithoutContext(f, 'RESOURCE_UNAVAILABLE');
  }
});

test('Pi rejects oversized resources and oversized UTF-8 context without partial context', async t => {
  for (const name of resources) {
    const f = await fixture(t);
    writeFileSync(join(f.references, name), Buffer.alloc(262_145, 120));
    await f.handlers.get('session_start')({}, f.ctx);
    assertContinuesWithoutContext(f, 'RESOURCE_REJECTED');
  }
  const f = await fixture(t);
  writeFileSync(join(f.references, 'onboarding.md'), 'é'.repeat(4096));
  await f.handlers.get('session_start')({}, f.ctx);
  assertContinuesWithoutContext(f, 'CONTEXT_TOO_LARGE');
});

test('Pi admits exactly 8192 context bytes and rejects the next byte', async t => {
  const f = await fixture(t);
  const suffix = '\nWorkspace: ' + JSON.stringify(f.workspace) +
    '\nQuery schema: ' + JSON.stringify(join(f.references, 'query-schema.json')) +
    '\nQuery examples: ' + JSON.stringify(join(f.references, 'query-examples.json')) + '\n';
  const brief = 'x'.repeat(8192 - Buffer.byteLength(suffix));
  writeFileSync(join(f.references, 'onboarding.md'), brief);
  await f.handlers.get('session_start')({}, f.ctx);
  assert.equal(f.handlers.get('before_agent_start')({ systemPrompt: 'base' }).systemPrompt,
    'base\n\n' + brief + suffix.trimEnd());
  assert.deepEqual(f.notifications, []);
  writeFileSync(join(f.references, 'onboarding.md'), brief + 'x');
  await f.handlers.get('session_start')({}, f.ctx);
  assertContinuesWithoutContext(f, 'CONTEXT_TOO_LARGE');
});

test('Pi rejects linked, non-file, malformed, and unresolved resources', async t => {
  for (const replacement of ['symlink', 'directory', 'invalid-utf8', 'empty', 'template']) {
    const f = await fixture(t);
    const brief = join(f.references, 'onboarding.md');
    unlinkSync(brief);
    if (replacement === 'symlink') symlinkSync(join(f.references, 'query-schema.json'), brief);
    if (replacement === 'directory') mkdirSync(brief);
    if (replacement === 'invalid-utf8') writeFileSync(brief, Buffer.from([0xc3, 0x28]));
    if (replacement === 'empty') writeFileSync(brief, ' \n');
    if (replacement === 'template') writeFileSync(brief, '{{UNRESOLVED}}');
    await f.handlers.get('session_start')({}, f.ctx);
    assertContinuesWithoutContext(f, 'RESOURCE_REJECTED');
  }
});

test('Pi rejects a linked references directory and an invalid workspace', async t => {
  const linked = await fixture(t);
  const resourcesCopy = join(linked.workspace, 'resources');
  mkdirSync(resourcesCopy);
  for (const name of resources) writeFileSync(join(resourcesCopy, name), 'fixture');
  rmSync(linked.references, { recursive: true });
  symlinkSync(resourcesCopy, linked.references);
  await linked.handlers.get('session_start')({}, linked.ctx);
  assertContinuesWithoutContext(linked, 'RESOURCE_REJECTED');

  const f = await fixture(t);
  await f.handlers.get('session_start')({}, { ...f.ctx, cwd: 'relative' });
  assertContinuesWithoutContext(f, 'INVALID_WORKSPACE');
});
