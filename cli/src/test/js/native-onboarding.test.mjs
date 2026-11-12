import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';

const source = readFileSync(new URL('../../../../pi/mcp-extension.ts', import.meta.url), 'utf8');
const resources = ['onboarding.md', 'query-schema.json', 'query-examples.json'];

test('Pi CLI hook preserves workspace arguments and reports preparation failure without MCP', async () => {
  const hook = readFileSync(new URL('../../../../agent-tools/hooks/pi.ts', import.meta.url), 'utf8');
  const { default: register } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(hook)).toString('base64'));
  for (const code of [0, 4]) {
    const calls = [], notices = [], handlers = new Map();
    register({
      on: (event, handler) => handlers.set(event, handler),
      exec: async (...args) => { calls.push(args); return { code, stdout: 'PREPARATION_REJECTED', stderr: '' }; },
    });
    const cwd = '/project with spaces/$literal;value';
    await handlers.get('session_start')({}, { cwd, ui: { notify: (...args) => notices.push(args) } });
    assert.deepEqual(calls, [['kast', ['--internal-entrypoint=query', 'hook', 'session-start', '--workspace', cwd], { timeout: 90_000 }]]);
    assert.equal(notices.length, code === 0 ? 0 : 1);
    assert.deepEqual([...handlers.keys()], ['session_start', 'before_agent_start']);
  }
});

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

async function fixture(t, brief = 'Kast shared context\n', exec) {
  const home = realpathSync(mkdtempSync(join(tmpdir(), 'kast pi $ literal ')));
  t.after(() => rmSync(home, { recursive: true, force: true }));
  const installation = join(home, '.local/share/kast/installation');
  const references = join(installation, 'share/kast/agent-tools/compositions/mcp/plugins/kast/references');
  const workspace = join(home, 'workspace $ literal');
  mkdirSync(references, { recursive: true });
  mkdirSync(workspace);
  mkdirSync(join(references, "../skills/kast"), { recursive: true });
  writeFileSync(join(references, "../skills/kast/SKILL.md"), "Kast skill");
  writeFileSync(join(references, 'onboarding.md'), brief);
  writeFileSync(join(references, 'query-schema.json'), '{}\n');
  writeFileSync(join(references, 'query-examples.json'), '[]\n');

  // Replace only the OS home lookup. Resource admission uses the real Node filesystem.
  const osModule = 'data:text/javascript;base64,' + Buffer.from(
    'export const homedir = () => ' + JSON.stringify(home) + ';').toString('base64');
  const { default: register } = await import('data:text/javascript;base64,' + Buffer.from(
    stripTypeScriptTypes(source.replace('from "node:os"', 'from "' + osModule + '"'))).toString('base64'));
  const handlers = new Map(), servers = [], notifications = [], preparations = [];
  register({
    registerMcpServer: (...args) => servers.push(args),
    on: (name, handler) => handlers.set(name, handler),
    exec: (...args) => {
      preparations.push(args);
      return exec ? exec(...args) : Promise.resolve({ code: 0, stdout: '{}', stderr: '' });
    },
  });
  const ctx = { cwd: workspace, ui: { notify: (...args) => notifications.push(args) } };
  return { handlers, servers, notifications, preparations, ctx, references, workspace, installation };
}

for (const composition of ['CLI', 'MCP']) {
  for (const outcome of ['complete', 'rejected', 'launch-failed']) {
    test('Pi ' + composition + ' starts preparation eagerly without blocking startup: ' + outcome, async t => {
      const preparation = Promise.withResolvers();
      const calls = [], handlers = new Map();
      let notifications = [];
      const exec = (...args) => { calls.push(args); return preparation.promise; };
      let ctx, session;
      if (composition === 'MCP') {
        const f = await fixture(t, 'Kast shared context\n', exec);
        ctx = f.ctx;
        session = f.handlers.get('session_start');
        notifications = f.notifications;
        handlers.set('before_agent_start', f.handlers.get('before_agent_start'));
      } else {
        const hook = readFileSync(new URL('../../../../agent-tools/hooks/pi.ts', import.meta.url), 'utf8');
        const { default: register } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(hook)).toString('base64'));
        register({ on: (event, handler) => handlers.set(event, handler), exec });
        ctx = { cwd: '/project with spaces/$literal;value', ui: { notify: (...args) => notifications.push(args) } };
        session = handlers.get('session_start');
      }
      const startup = session({}, ctx);
      assert.equal(startup, undefined, 'The session handler must return while preparation is pending');
      assert.deepEqual(calls, [['kast', ['--internal-entrypoint=query', 'hook', 'session-start', '--workspace', ctx.cwd], { timeout: 90_000 }]]);
      assert.deepEqual(notifications, []);
      if (composition === 'MCP') {
        assert.ok(handlers.get('before_agent_start')({ systemPrompt: 'base' }).systemPrompt.includes('Kast shared context'));
      }
      if (outcome === 'launch-failed') preparation.reject(new Error('private launch details'));
      else preparation.resolve({ code: outcome === 'complete' ? 0 : 4, stdout: 'x'.repeat(4096), stderr: '' });
      await preparation.promise.catch(() => {});
      assert.deepEqual(notifications, outcome === 'complete' ? [] : [[
        outcome === 'launch-failed'
          ? 'Kast session context could not be observed. Inspect kast status for passive evidence.'
          : 'Kast session context was rejected. ' + 'x'.repeat(4096),
        'warning',
      ]]);
    });
  }
}

test('Pi delivers the generated semantic routing policy without probing readiness', async t => {
  const brief = readFileSync(new URL('../../../../agent-tools/references/onboarding.md', import.meta.url), 'utf8');
  const f = await fixture(t, brief);
  await f.handlers.get('session_start')({}, f.ctx);
  const prompt = f.handlers.get('before_agent_start')({ systemPrompt: 'base' }).systemPrompt;
  assert.ok(prompt.startsWith('base\n\n' + brief.trimEnd()));
  // These are instruction-delivery checks, not a simulated agent or semantic oracle.
  for (const clause of [
    'Use reachable Kast tools first for supported Kotlin semantic tasks in this exact workspace',
    'The first explicit semantic request trusts the exact workspace root and prepares its private Host',
    'Tasks without a Kotlin semantic question need no Kast call',
    'Use an available schema directly; otherwise discover the tool once',
    'For `codemode` exposure, use `searchTools` and `describeTool`',
    'For deferred exposure, use `tool_search`',
    'Use the first useful operation, not a readiness probe',
    'If tools, readiness, or scope block a query, use ordinary reads and state the limitation',
    'Do not repeat discovery or failures without new evidence or changed prerequisites',
    'Incomplete results and scope qualifications do not prove absence. Preserve them',
    'Use a relation for direct callers or references',
    'Reuse the issued `ref` unchanged',
    'Reuse returned or listed paths',
    'Do not enumerate the repository for supplied paths',
    'Find unknown paths within the known scope before reads',
    'Batch independent reads within the output and remaining tool allowances',
    'filter large structured results before `text()` or `store()`',
    'Keep needed evidence, original line numbers, and qualifications',
    'Chain discovery, exact selection, and reference expansion inside one supported host code call',
    'Keep intermediate results inside it',
    'Expose task findings with scope, completion, omissions, and recovery facts',
    'Keep requested matches and failures',
    'Remove duplicate paths and reuse unchanged source reads',
    'Discard cached source evidence after a source change or freshness rejection',
    'Read only the needed section or named example for unfamiliar shapes',
    'Compact and canonical rows can differ',
    'Use `find` with a simple `name`',
    'Use `source` with its default `ANCHOR`',
    'Read the bundled Kast skill',
  ]) assert.ok(prompt.includes(clause), clause);
  assert.ok(Buffer.byteLength(prompt.slice('base\n\n'.length) + '\n') <= 8192);
  assert.deepEqual(f.notifications, []);
});

test('Pi inventory reference projects a large structured Bash result before display', () => {
  const reference = readFileSync(new URL('../../../../agent-tools/README.md', import.meta.url), 'utf8');
  const example = reference.match(/```javascript\n([\s\S]*?)\n```/);
  assert.ok(example, 'missing executable inventory projection example');
  const directoryPrefix = 'selected/src/main/';
  const selected = Array.from({ length: 21 }, (_, i) => directoryPrefix + 'File' + i + '.kt');
  const unrelated = Array.from({ length: 8000 }, (_, i) => 'unrelated/module' + i + '/src/main/Noise.kt');
  const cases = [
    { name: 'exact paths', matches: [selected[0], selected[1]], expectedPaths: [selected[0], selected[1]], truncated: false, exit_code: 0 },
    { name: 'no matching paths', matches: [], expectedPaths: [], truncated: false, exit_code: 0 },
    { name: 'bounded paths keep count', matches: selected, expectedPaths: selected.slice(0, 20), omittedCount: 1, truncated: false, exit_code: 0 },
    { name: 'sibling prefix excluded', matches: [selected[0]], extraPaths: ['selected/src/main-sibling/Other.kt'], expectedPaths: [selected[0]], truncated: false, exit_code: 0 },
    { name: 'partial inventory withholds claims', matches: [selected[0]], unavailable: true, truncated: true, exit_code: 0 },
    { name: 'failed Bash withholds matching-looking paths', matches: [selected[0]], unavailable: true, truncated: false, exit_code: 1 },
  ];
  for (const c of cases) {
    // This is the structured value of an already-completed Bash call, not a model simulation.
    const result = { output: [...unrelated, ...c.matches, ...(c.extraPaths ?? [])].join('\n'), truncated: c.truncated, exit_code: c.exit_code };
    assert.ok(Buffer.byteLength(result.output) > 262144);
    const displayed = [];
    runInNewContext(example[1], { result, directoryPrefix, text: value => displayed.push(JSON.stringify(value)) }, { timeout: 1000 });
    const expected = c.unavailable
      ? { exit_code: c.exit_code, truncated: c.truncated, counts: 'UNAVAILABLE' }
      : { exit_code: c.exit_code, truncated: c.truncated, matchCount: c.matches.length, omittedCount: c.omittedCount ?? 0, paths: c.expectedPaths };
    assert.deepEqual(displayed, [JSON.stringify(expected)], c.name);
  }
});

test('Pi inventory reference withholds paths cut by upstream byte clipping', () => {
  const reference = readFileSync(new URL('../../../../agent-tools/README.md', import.meta.url), 'utf8');
  const example = reference.match(/```javascript\n([\s\S]*?)\n```/);
  assert.ok(example);
  const directoryPrefix = 'selected/src/main/';
  const exactPath = directoryPrefix + 'BoundaryCrossingName.kt';
  const partialPath = directoryPrefix + 'Bound';
  const halfBytes = 524288;
  const noise = 'unrelated/module/src/main/Noise.kt\n';
  const beforeLength = halfBytes - Buffer.byteLength(partialPath);
  const remainder = beforeLength % noise.length;
  const before = noise.repeat(Math.floor(beforeLength / noise.length)) +
    (remainder === 0 ? '' : 'x'.repeat(remainder - 1) + '\n');
  const inventory = before + exactPath + '\n' + noise.repeat(40000);
  const bytes = Buffer.from(inventory);
  assert.ok(bytes.length > 2 * halfBytes);
  assert.ok(inventory.split('\n').every(line => line.length < 100));
  assert.ok(inventory.split('\n').includes(exactPath));
  assert.ok(!inventory.split('\n').includes(partialPath));
  // Pi keeps the first/last 512 KiB of oversized Bash output. ASCII preserves exact byte cuts here.
  const output = bytes.subarray(0, halfBytes).toString() +
    '\n\n[... ' + (bytes.length - 2 * halfBytes) + ' bytes omitted ...]\n\n' +
    bytes.subarray(bytes.length - halfBytes).toString();
  assert.ok(output.split('\n').includes(partialPath), 'the clipped record resembles a different path');
  const result = { output, truncated: true, exit_code: 0 };
  const displayed = [];
  runInNewContext(example[1], { result, directoryPrefix, text: value => displayed.push(JSON.stringify(value)) }, { timeout: 1000 });
  assert.deepEqual(displayed, [JSON.stringify({ exit_code: 0, truncated: true, counts: 'UNAVAILABLE' })]);
});

function assertContinuesWithoutContext(f, reason) {
  assert.equal(f.servers.length, 1, 'Native MCP stays registered');
  assert.deepEqual(f.notifications, [[
    'Kast session context is unavailable (' + reason + '). Native MCP remains registered.', 'warning',
  ]]);
  for (let turn = 0; turn < 2; turn++) {
    assert.equal(f.handlers.get('before_agent_start')({ systemPrompt: 'Other extension context' }).systemPrompt, 'Other extension context\n\n{}');
  }
  assert.equal(f.notifications.length, 1, 'A context failure does not repeat on every turn');
}

test('Pi registers native MCP and delegates preparation to the public executable', async t => {
  const f = await fixture(t);
  assert.deepEqual(f.servers, [['kast', {
    command: join(f.installation, 'share/kast/private-python/bin/python3.12'),
    args: ['-I', '-S', '-B', join(f.installation, 'share/kast/mcp_environment.py'), 'serve', '--install-root', f.installation], timeout: 3780,
  }]]);
  assert.match(f.handlers.get('before_agent_start')({ systemPrompt: 'base' }).systemPrompt, /first semantic query/);
  await f.handlers.get('session_start')({}, f.ctx);
  assert.deepEqual(f.preparations, [['kast', ['--internal-entrypoint=query', 'hook', 'session-start', '--workspace', f.workspace], { timeout: 90_000 }]]);
  const expected = 'Other extension context\n\nKast shared context' +
    '\nWorkspace: ' + JSON.stringify(f.workspace) +
    '\nKast skill: ' + JSON.stringify(join(f.references, '../skills/kast/SKILL.md')) +
    '\nAdvanced query schema: ' + JSON.stringify(join(f.references, 'query-schema.json')) +
    '\nQuery examples: ' + JSON.stringify(join(f.references, 'query-examples.json')) + '\n\n{}';
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
    '\nKast skill: ' + JSON.stringify(join(f.references, '../skills/kast/SKILL.md')) +
    '\nAdvanced query schema: ' + JSON.stringify(join(f.references, 'query-schema.json')) +
    '\nQuery examples: ' + JSON.stringify(join(f.references, 'query-examples.json')) + '\n';
  const brief = 'x'.repeat(8192 - Buffer.byteLength(suffix));
  writeFileSync(join(f.references, 'onboarding.md'), brief);
  await f.handlers.get('session_start')({}, f.ctx);
  assert.equal(f.handlers.get('before_agent_start')({ systemPrompt: 'base' }).systemPrompt,
    'base\n\n' + brief + suffix.trimEnd() + '\n\n{}');
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

// The optional MCP composition keeps the task service contract.
test('optional plugin MCP entries select the bundled task server and skill', () => {
  const root = new URL('../../../../agent-tools/', import.meta.url);
  const mcp = JSON.parse(readFileSync(new URL('mcp.json', root), 'utf8'));
  assert.deepEqual(Object.keys(mcp.mcpServers), ['kast']);
  assert.match(mcp.mcpServers.kast.args.join(' '), /private-python\/bin\/python3.12/);
  assert.match(mcp.mcpServers.kast.args.join(' '), /mcp_environment.py" serve --install-root/);
  assert.doesNotMatch(mcp.mcpServers.kast.args.join(' '), /kast-mcp-complete|\buv\b|\bpip\b|prepare/);
  assert.match(readFileSync(new URL('skills/kast/SKILL.md', root), 'utf8'), /name: kast/);
});

test("background startup gives the model pending context before and after native admission", async () => {
  const handlers = new Map();
  const calls = [];
  let settle;
  const pi = {
    on: (event, handler) => handlers.set(event, handler),
    exec: (...args) => {
      calls.push(args);
      return new Promise(resolve => { settle = resolve; });
    },
  };
  const hook = readFileSync(new URL('../../../../agent-tools/hooks/pi.ts', import.meta.url), 'utf8');
  const { default: register } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(hook)).toString('base64'));
  register(pi);
  handlers.get("session_start")({}, { cwd: "/workspace with spaces", ui: { notify() { assert.fail("Pending is not failure"); } } });
  assert.deepEqual(calls, [["kast", ["--internal-entrypoint=query", "hook", "session-start", "--workspace", "/workspace with spaces"], { timeout: 90_000 }]]);
  const prompt = () => handlers.get("before_agent_start")({ systemPrompt: "original" }).systemPrompt;
  assert.match(prompt(), /first semantic query/);
  assert.doesNotMatch(prompt(), /--wait|starting in the background/);
  const nativeOutput = "Session hooks do not grant trust or start a Host.";
  settle({ code: 0, stdout: nativeOutput, stderr: "" });
  await Promise.resolve();
  assert.equal(prompt(), "original\n\n" + nativeOutput);
});

test("native rejection is kept in model context and produces a visible warning", async () => {
  const handlers = new Map();
  const notifications = [];
  const hook = readFileSync(new URL('../../../../agent-tools/hooks/pi.ts', import.meta.url), 'utf8');
  const { default: register } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(hook)).toString('base64'));
  register({
    on: (event, handler) => handlers.set(event, handler),
    exec: async () => ({ code: 4, stdout: "TRUST_REQUIRED", stderr: "" }),
  });
  handlers.get("session_start")({}, { cwd: "/workspace", ui: { notify: (...args) => notifications.push(args) } });
  await Promise.resolve();
  assert.match(handlers.get("before_agent_start")({ systemPrompt: "original" }).systemPrompt, /TRUST_REQUIRED/);
  assert.equal(notifications.length, 1);
  assert.equal(notifications[0][1], "warning");
});

for (const composition of ['CLI', 'MCP']) {
  for (const staleOutcome of ['rejected', 'launch-failed']) {
    test('Pi ' + composition + ' ignores a prior session completion: ' + staleOutcome, async t => {
      const pending = [], handlers = new Map(), notices = [];
      const exec = () => {
        const result = Promise.withResolvers();
        pending.push(result);
        return result.promise;
      };
      let ctx;
      if (composition === 'MCP') {
        const f = await fixture(t, 'Kast shared context\n', exec);
        ctx = f.ctx;
        ctx.ui.notify = (...args) => notices.push(args);
        for (const event of ['session_start', 'before_agent_start']) handlers.set(event, f.handlers.get(event));
      } else {
        const hook = readFileSync(new URL('../../../../agent-tools/hooks/pi.ts', import.meta.url), 'utf8');
        const { default: register } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(hook)).toString('base64'));
        register({ on: (event, handler) => handlers.set(event, handler), exec });
        ctx = { cwd: '/workspace', ui: { notify: (...args) => notices.push(args) } };
      }
      handlers.get('session_start')({}, ctx);
      handlers.get('session_start')({}, ctx);
      pending[1].resolve({ code: 0, stdout: 'CURRENT_ADMISSION', stderr: '' });
      await Promise.resolve();
      if (staleOutcome === 'launch-failed') pending[0].reject(new Error('old launch failed'));
      else pending[0].resolve({ code: 4, stdout: 'STALE_REJECTION', stderr: '' });
      await Promise.resolve();
      const prompt = handlers.get('before_agent_start')({ systemPrompt: 'original' }).systemPrompt;
      assert.match(prompt, /CURRENT_ADMISSION/);
      assert.doesNotMatch(prompt, /STALE_REJECTION|could not be observed/);
      assert.equal(notices.length, 0);
    });
  }
}
