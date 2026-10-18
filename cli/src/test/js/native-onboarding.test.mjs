import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const source = readFileSync(new URL('../../../../pi/mcp-extension.ts', import.meta.url), 'utf8');
const resources = ['onboarding.md', 'query-schema.json', 'query-examples.json'];

test('registry fixture rejects unexpected, duplicate, and unconsumed protocol messages', t => {
  const directory = mkdtempSync(join(tmpdir(), 'kast registry protocol '));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const catalog = join(directory, 'catalog.json'), log = join(directory, 'requests.jsonl');
  writeFileSync(catalog, '[]');
  const script = fileURLToPath(new URL('./pi-registry-fixture.mjs', import.meta.url));
  const initialize = { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2024-11-05' } };
  const initialized = { jsonrpc: '2.0', method: 'notifications/initialized' };
  const list = { jsonrpc: '2.0', id: 2, method: 'tools/list' };
  const cases = [
    ['expected', [initialize, initialized, list], true],
    ['unknown notification', [initialize, { jsonrpc: '2.0', method: 'notifications/unexpected' }, list], false],
    ['duplicate notification', [initialize, initialized, initialized, list], false],
    ['request without id', [initialize, initialized, { jsonrpc: '2.0', method: 'tools/list' }], false],
    ['unconsumed request', [initialize, initialized], false],
    ['excess request', [initialize, initialized, list, list], false],
  ];
  for (const [name, messages, accepted] of cases) {
    const result = spawnSync(process.execPath, [script, '--server', catalog, log, '0'], {
      input: messages.map(m => JSON.stringify(m) + '\n').join(''), encoding: 'utf8', timeout: 5_000,
    });
    assert.ifError(result.error);
    assert.equal(result.signal, null, name);
    assert.equal(result.status === 0, accepted, name + ': ' + result.stderr);
  }
});

async function fixture(t, brief = 'Kast shared context\n') {
  const home = realpathSync(mkdtempSync(join(tmpdir(), 'kast pi $ literal ')));
  t.after(() => rmSync(home, { recursive: true, force: true }));
  const installation = join(home, '.local/share/kast/installation');
  const references = join(installation, 'share/kast/agent-tools/plugins/kast/references');
  const workspace = join(home, 'workspace $ literal');
  mkdirSync(references, { recursive: true });
  mkdirSync(workspace);
  writeFileSync(join(references, 'onboarding.md'), brief);
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

test('Pi delivers the generated semantic routing policy without probing readiness', async t => {
  const brief = readFileSync(new URL('../../../../agent-tools/references/onboarding.md', import.meta.url), 'utf8');
  const f = await fixture(t, brief);
  await f.handlers.get('session_start')({}, f.ctx);
  const prompt = f.handlers.get('before_agent_start')({ systemPrompt: 'base' }).systemPrompt;
  assert.ok(prompt.startsWith('base\n\n' + brief.trimEnd()));
  // These are instruction-delivery checks, not a simulated agent or semantic oracle.
  for (const clause of [
    'first semantic step when its tools are reachable and support the task',
    'tasks without a Kotlin semantic question need no Kast call',
    'If the needed tool schema is already available, use it directly',
    'discover the needed Kast tool once',
    '`codemode` exposure uses `searchTools` and `describeTool`',
    'deferred exposure uses `tool_search`',
    'do not add a probe call',
    'workspace is not ready, or the requested scope is unsupported',
    'continue with ordinary reading and state the limitation',
    'Do not repeat discovery or retry the same failed request without new evidence',
    'Preserve incomplete results and scope qualifications',
    'For direct callers or references, use the matching relation',
    'reuse paths from returned evidence or file listings',
    'If a needed path is unknown, find it within the known scope before reading',
    'Batch independent source reads with bounded output that retains the evidence needed for the answer',
    'filter large metadata before `store()`; keep only small identifiers or summaries',
  ]) assert.ok(prompt.includes(clause), clause);
  assert.ok(Buffer.byteLength(prompt.slice('base\n\n'.length) + '\n') <= 8192);
  assert.deepEqual(f.notifications, []);
});

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
