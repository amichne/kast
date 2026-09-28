import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { EventEmitter } from 'node:events';
import { stripTypeScriptTypes } from 'node:module';
import { SourceTextModule, SyntheticModule, createContext } from 'node:vm';
import { fileURLToPath } from 'node:url';

// The catalog is serialized by the production Kotlin RPC bridge, never reconstructed here.
const catalog = JSON.parse(readFileSync(process.env.KAST_ADAPTER_TEST_CATALOG, 'utf8'));
const root = new URL('../../../../', import.meta.url);
async function load(harness, reply = catalog, outcome = { type: 'complete', document: {} }) {
  const registered = [], calls = [];
  const context = createContext({ Buffer, setTimeout, clearTimeout, process: { env: { KAST_TOOL_RPC_COMMAND: '/fixture/kast-tool-rpc' }, cwd: () => '/fixture' } });
  const spawn = (command, args) => {
    calls.push({ command, args });
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    child.stderr.resume = () => {};
    child.kill = () => {};
    child.stdin = { end: () => queueMicrotask(() => {
      child.stdout.emit('data', Buffer.from(JSON.stringify(args[0] === 'catalog' ? reply : outcome)));
      child.emit('close', 0);
    }) };
    return child;
  };
  const modules = {
    'node:child_process': { spawn }, 'node:fs': { existsSync: () => true },
    'node:os': { homedir: () => '/fixture' }, 'node:path': { join: (...parts) => parts.join('/') },
    '@github/copilot-sdk/extension': { joinSession: async ({tools}) => registered.push(...tools) },
    typebox: { Type: { Unsafe: (value) => value } },
  };
  const path = new URL(harness === 'pi' ? 'pi/extension.ts' : 'copilot/extension.mjs', root);
  let source = readFileSync(path, 'utf8');
  if (harness === 'pi') source = stripTypeScriptTypes(source);
  const module = new SourceTextModule(source, { context, identifier: fileURLToPath(path) });
  await module.link(async (name) => {
    assert.ok(modules[name], `unexpected import ${name}`);
    return new SyntheticModule(Object.keys(modules[name]), function () {
      for (const [key, value] of Object.entries(modules[name])) this.setExport(key, value);
    }, { context });
  });
  let error;
  try {
    await module.evaluate();
    if (harness === 'pi') await module.namespace.default({ registerTool: (tool) => registered.push(tool) });
  } catch (failure) { error = failure; }
  return { registered, calls, error };
}
for (const harness of ['copilot', 'pi']) {
  test(`${harness} admits the complete generated catalog and invokes query unchanged`, async () => {
    const loaded = await load(harness);
    assert.ifError(loaded.error);
    assert.deepEqual(loaded.registered.map(t => t.name), catalog.catalog.tools.map(t => t.name));
    for (const tool of loaded.registered) {
      const original = catalog.catalog.tools.find(t => t.name === tool.name);
      assert.deepEqual(JSON.parse(JSON.stringify(tool.parameters)), original.inputSchema);
      if (harness === 'copilot') assert.equal(tool.skipPermission, original.effect === 'READ' ? true : undefined);
    }
    const query = loaded.registered.find(t => t.name === 'query_symbols');
    const result = harness === 'pi'
      ? await query.execute('call', {request: {type: 'RUN'}}, undefined, undefined, {cwd:'/fixture'})
      : await query.handler({request: {type: 'RUN'}}, {});
    assert.equal(loaded.calls.length, 2);
    assert.deepEqual(Array.from(loaded.calls[1].args), ['call', 'query_symbols']);
    assert.ok(JSON.stringify(result).includes('complete'));
  });
  test(`${harness} rejects version drift before any registration with bounded context`, async () => {
    const reply = structuredClone(catalog); reply.catalog.schemaVersion = 999;
    const loaded = await load(harness, reply);
    assert.equal(loaded.registered.length, 0);
    assert.match(loaded.error?.message ?? '', /catalog.*expected=3.*observed=999.*executable=\/fixture\/kast-tool-rpc/);
  });
  test(`${harness} validates the last entry before registering the first`, async () => {
    const reply = structuredClone(catalog); reply.catalog.tools.at(-1).inputSchema = null;
    const loaded = await load(harness, reply);
    assert.ok(loaded.error);
    assert.equal(loaded.registered.length, 0);
    assert.equal(loaded.calls.length, 1);
  });
}
