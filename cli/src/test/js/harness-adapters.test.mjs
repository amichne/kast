import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { EventEmitter } from 'node:events';
import { stripTypeScriptTypes } from 'node:module';
import { SourceTextModule, SyntheticModule, createContext } from 'node:vm';
import { fileURLToPath, pathToFileURL } from 'node:url';

// The catalog is serialized by the production Kotlin RPC bridge, never reconstructed here.
const catalog = JSON.parse(readFileSync(process.env.KAST_ADAPTER_TEST_CATALOG, 'utf8'));
const failures = JSON.parse(readFileSync(process.env.KAST_ADAPTER_TEST_FAILURES, 'utf8'));
const root = process.env.KAST_PACKAGED_ADAPTER_ROOT ? pathToFileURL(process.env.KAST_PACKAGED_ADAPTER_ROOT + '/share/kast/adapters/') : new URL('../../../../', import.meta.url);
async function load(harness, reply = catalog, outcome = { type: 'complete', document: {} }, failure, env = {KAST_TOOL_RPC_COMMAND: '/fixture/kast-tool-rpc'}) {
  const registered = [], calls = [];
  const context = createContext({ Buffer, TextDecoder, setTimeout: (callback, millis) => {
    if (failure === 'timeout' && calls.length > 1) { queueMicrotask(callback); return 0; }
    return setTimeout(callback, millis);
  }, clearTimeout, process: { env, cwd: () => '/fixture' } });
  const spawn = (command, args, options) => {
    calls.push({ command, args, options });
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    child.stderr.resume = () => {};
    child.kill = () => queueMicrotask(() => child.emit('close', null, 'SIGKILL'));
    child.stdin = { on: () => {}, end: () => queueMicrotask(() => {
      if (args[0] === 'call' && ['timeout', 'cancel'].includes(failure)) return;
      if (args[0] === 'call' && failure === 'overflow') {
        child.stdout.emit('data', Buffer.alloc(reply.catalog.maxResponseBytes + 1));
      } else if (args[0] === 'call' && failure) {
        child.stderr.emit('data', Buffer.from('kast_change stage=APPLICATION outcome=STARTED\nexact:v3:private-source-token\n'));
        child.emit('close', 7);
      } else {
        child.stdout.emit('data', args[0] === 'catalog' ? Buffer.from(JSON.stringify(reply)) : Buffer.isBuffer(outcome) ? outcome : Buffer.from(JSON.stringify(outcome)));
        child.emit('close', 0);
      }
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
  test(`${harness} keeps its RPC selector outside product configuration`, async () => {
    const environment = { KAST_TOOL_RPC_COMMAND: '/selected/kast-tool-rpc',
      KAST_INSTALL_IDEA_HOME: '/owned/idea', JAVA_OPTS: '-Duser.home=/owned/home', PATH: '/owned/bin' };
    const loaded = await load(harness, catalog, undefined, undefined, environment);
    assert.ifError(loaded.error);
    const query = loaded.registered.find(tool => tool.name === 'query_symbols');
    if (harness === 'pi') await query.execute('call', {request: {type: 'RUN'}}, undefined, undefined, {cwd:'/fixture'});
    else await query.handler({request: {type: 'RUN'}}, {});
    assert.equal(loaded.calls.length, 2);
    for (const call of loaded.calls) {
      assert.equal(call.command, '/selected/kast-tool-rpc');
      assert.ok(call.options.env, 'Explicit child environment required');
      assert.equal(Object.hasOwn(call.options.env, 'KAST_TOOL_RPC_COMMAND'), false);
      assert.equal(call.options.env.KAST_INSTALL_IDEA_HOME, '/owned/idea');
      assert.equal(call.options.env.JAVA_OPTS, '-Duser.home=/owned/home');
      assert.equal(call.options.env.PATH, '/owned/bin');
    }
    assert.equal(environment.KAST_TOOL_RPC_COMMAND, '/selected/kast-tool-rpc');
  });
  for (const [environment, expected] of [
    [{}, '/fixture/.local/share/kast/installation/bin/kast-tool-rpc-complete'],
    [{XDG_DATA_HOME: '/data'}, '/fixture/.local/share/kast/installation/bin/kast-tool-rpc-complete'],
    [{KAST_INSTALL_ROOT: '/owned/kast'}, '/fixture/.local/share/kast/installation/bin/kast-tool-rpc-complete'],
    [{KAST_TOOL_RPC_COMMAND: '/transport/kast-tool-rpc'}, '/transport/kast-tool-rpc'],
  ]) {
    test(`${harness} selects the ordinary payload launcher from ${JSON.stringify(environment)}`, async () => {
      const loaded = await load(harness, catalog, undefined, undefined, environment);
      assert.ifError(loaded.error);
      assert.equal(loaded.calls.length, 1);
      assert.equal(loaded.calls[0].command, expected);
    });
  }
  test(`${harness} admits the complete generated catalog and invokes query unchanged`, async () => {
    const loaded = await load(harness);
    assert.ifError(loaded.error);
    assert.deepEqual(loaded.registered.map(t => t.name), catalog.catalog.tools.map(t => t.name));
    if (harness === 'copilot') {
      assert.ok(catalog.catalog.tools.some(tool => tool.effect === 'READ'));
      assert.ok(catalog.catalog.tools.some(tool => tool.effect === 'WRITE'));
    }
    for (const tool of loaded.registered) {
      const original = catalog.catalog.tools.find(t => t.name === tool.name);
      assert.deepEqual(JSON.parse(JSON.stringify(tool.parameters)), original.inputSchema);
      if (harness === 'copilot') assert.equal(tool.skipPermission, undefined, `${original.effect} ${tool.name} must retain host permission policy`);
    }
    const query = loaded.registered.find(t => t.name === 'query_symbols');
    const result = harness === 'pi'
      ? await query.execute('call', {request: {type: 'RUN'}}, undefined, undefined, {cwd:'/fixture'})
      : await query.handler({request: {type: 'RUN'}}, {});
    assert.equal(loaded.calls.length, 2);
    assert.deepEqual(Array.from(loaded.calls[1].args), ['call', 'query_symbols']);
    assert.ok(JSON.stringify(result).includes('complete'));
  });
  test(`${harness} retains bounded phase evidence and uncertainty on child failure`, async () => {
    const loaded = await load(harness, catalog, undefined, true);
    assert.ifError(loaded.error);
    const tool = loaded.registered.find(t => t.name === 'replace_body');
    const invoke = () => harness === 'pi'
      ? tool.execute('call', {}, undefined, undefined, {cwd:'/fixture', hasUI:true, ui:{confirm:async()=>true}})
      : tool.handler({}, {});
    await assert.rejects(invoke, (error) => {
      assert.match(error.message, /NONZERO_EXIT/);
      assert.match(error.message, /APPLICATION/);
      assert.match(error.message, /mutation state is unknown/);
      assert.ok(!error.message.includes('private-source-token'));
      return true;
    });
    assert.equal(loaded.calls.length, 2);
  });
  for (const [mode, reason] of [['timeout', 'TIMEOUT'], ['cancel', 'CANCELLED'], ['overflow', 'OUTPUT_OVERFLOW']]) {
    test(`${harness} distinguishes ${mode} and never replays the call`, async () => {
      const reply = structuredClone(catalog);
      reply.catalog.maxResponseBytes = 64;
      const loaded = await load(harness, reply, undefined, mode);
      assert.ifError(loaded.error);
      const controller = new AbortController();
      const tool = loaded.registered.find(t => t.name === 'query_symbols');
      const pending = harness === 'pi'
        ? tool.execute('call', {}, controller.signal, undefined, {cwd:'/fixture'})
        : tool.handler({}, {signal:controller.signal});
      if (mode === 'cancel') controller.abort();
      await assert.rejects(pending, new RegExp(`outcome=${reason}.*mutation state is unknown`));
      assert.equal(loaded.calls.length, 2);
    });
  }
  test(`${harness} rejects version drift before any registration with bounded context`, async () => {
    const reply = structuredClone(catalog); reply.catalog.schemaVersion = 999;
    const loaded = await load(harness, reply);
    assert.equal(loaded.registered.length, 0);
    assert.match(loaded.error?.message ?? '', /catalog.*expected=14.*observed=999.*executable=\/fixture\/kast-tool-rpc/);
  });
  const invokeRead = async (outcome) => {
    const loaded = await load(harness, catalog, outcome);
    assert.ifError(loaded.error);
    const query = loaded.registered.find(t => t.name === 'query_symbols');
    return harness === 'pi'
      ? await query.execute('call', {}, undefined, undefined, {cwd:'/fixture'})
      : await query.handler({}, {});
  };
  test(`${harness} preserves every canonical boundary rejection as a host failure`, async () => {
    for (const failure of failures) {
      const outcome = {type:'rejected', failure};
      const result = await invokeRead(outcome);
      assert.deepEqual(JSON.parse(harness === 'pi' ? result.content[0].text : result.textResultForLlm), outcome);
      if (harness === 'pi') { assert.equal(result.isError, true); assert.deepEqual(JSON.parse(JSON.stringify(result.details)), outcome); }
      else assert.equal(result.resultType, 'failure');
    }
  });
  test(`${harness} preserves document rejection and qualified continuation`, async () => {
    for (const type of ['qualified', 'rejected_document']) {
      const outcome = {type, document:{status:type === 'qualified' ? 'qualified' : 'rejected', qualification:{type:'PARTIAL'}, continuation:'opaque:test'}};
      const result = await invokeRead(outcome);
      assert.deepEqual(JSON.parse(harness === 'pi' ? result.content[0].text : result.textResultForLlm), outcome);
      if (harness === 'pi') assert.equal(result.isError, type === 'rejected_document');
      else assert.equal(result.resultType, type === 'qualified' ? 'success' : 'failure');
    }
  });
  test(`${harness} rejects malformed closed reply envelopes before presentation`, async () => {
    for (const outcome of [null, [], {}, {type:'unknown'}, {type:'complete'}, {type:'qualified',document:null},
      {type:'complete',document:5}, {type:'rejected_document',document:[]}, {type:'rejected',failure:'MADE_UP'},
      {type:'rejected',failure:'OUT_OF_SCOPE',document:{}}, {type:'complete',document:{},extra:true}]) {
      await assert.rejects(() => invokeRead(outcome), /INVALID_RESULT/);
    }
  });
  test(`${harness} rejects invalid UTF-8 instead of changing identity bytes`, async () => {
    const bytes = Buffer.concat([Buffer.from('{"type":"complete","document":{"ref":"'), Buffer.from([0x80]), Buffer.from('"}}')]);
    await assert.rejects(() => invokeRead(bytes), /INVALID_UTF8/);
  });
  test(`${harness} validates the last entry before registering the first`, async () => {
    const reply = structuredClone(catalog); reply.catalog.tools.at(-1).inputSchema = null;
    const loaded = await load(harness, reply);
    assert.ok(loaded.error);
    assert.equal(loaded.registered.length, 0);
    assert.equal(loaded.calls.length, 1);
  });
}

// Opt-in upstream loader check. This invokes the actual packaged process in an
// unowned directory and asserts its finite admission rejection, not semantic success.
test('installed Pi loader registers the packaged catalog and invokes the production RPC', {skip: !process.env.KAST_PI_PACKAGE || !process.env.KAST_PACKAGED_ADAPTER_ROOT}, async () => {
  const {join} = await import('node:path');
  const {tmpdir} = await import('node:os');
  const pi = process.env.KAST_PI_PACKAGE;
  const {loadExtensions} = await import(pathToFileURL(join(pi, 'dist/core/extensions/loader.js')));
  const temporary = mkdtempSync(join(tmpdir(), 'kast-pi-admission-'));
  const prior = process.env.KAST_TOOL_RPC_COMMAND;
  process.env.KAST_TOOL_RPC_COMMAND = join(process.env.KAST_PACKAGED_ADAPTER_ROOT, 'bin/kast-tool-rpc');
  try {
    const loaded = await loadExtensions([fileURLToPath(new URL('pi/extension.ts', root))], temporary);
    assert.deepEqual(loaded.errors, []);
    assert.equal(loaded.extensions.length, 1);
    const tools = loaded.extensions[0].tools;
    assert.deepEqual([...tools.keys()].sort(), catalog.catalog.tools.map(t => t.name).sort());
    const result = await tools.get('query_symbols').definition.execute('smoke', {request: {type:'RUN', source:{type:'ALL_DECLARATIONS'}, steps:[], output:{type:'SYMBOLS',fields:['NAME']}}}, undefined, undefined, {cwd:temporary, hasUI:false});
    assert.deepEqual(JSON.parse(result.content[0].text), {type:'rejected',failure:'OUT_OF_SCOPE'});
    console.log(`upstream Pi=${JSON.parse(readFileSync(join(pi, 'package.json'))).version} Node=${process.version}; loader/registerTool/RPC invocation verified; compiler execution unverified`);
  } finally {
    if (prior === undefined) delete process.env.KAST_TOOL_RPC_COMMAND; else process.env.KAST_TOOL_RPC_COMMAND = prior;
    rmSync(temporary, {recursive:true, force:true});
  }
});

test('Pi write denial retains a closed failed outcome without dispatch', async () => {
  const loaded = await load('pi');
  assert.ifError(loaded.error);
  const write = loaded.registered.find(t => t.name === 'replace_body');
  for (const ctx of [{cwd:'/fixture',hasUI:false}, {cwd:'/fixture',hasUI:true,ui:{confirm:async()=>false}}]) {
    const result = await write.execute('call', {exactTarget:'exact:test',body:'{ return Unit }'}, undefined, undefined, ctx);
    assert.equal(result.isError, true);
    assert.deepEqual(JSON.parse(JSON.stringify(result.details)), {type:'APPROVAL_DENIED'});
    assert.deepEqual(JSON.parse(result.content[0].text), {type:'APPROVAL_DENIED'});
    assert.equal(loaded.calls.length, 1);
  }
});

test('Pi write approval shows bounded workspace target and intent with cancellation', async () => {
  const loaded = await load('pi');
  assert.ifError(loaded.error);
  const controller = new AbortController();
  const prompts = [];
  const ctx = {cwd:'/fixture/worktree',hasUI:true,ui:{confirm:async (...args)=>{prompts.push(args);return false;}}};
  for (const [name, params] of [['replace_body',{exactTarget:'exact:test',body:'{ return Unit }'}],
    ['add_declaration',{exactTarget:'exact:test',declaration:'fun added() = Unit'}]]) {
    const tool = loaded.registered.find(t => t.name === name);
    await tool.execute('call',params,controller.signal,undefined,ctx);
    const [title,message,options] = prompts.at(-1);
    assert.equal(title,'Apply Kast change?');
    assert.ok(message.includes('/fixture/worktree'));
    assert.ok(message.includes('exact:test'));
    assert.ok(message.includes(name));
    assert.ok(message.includes(params.body ?? params.declaration));
    assert.equal(options.signal,controller.signal);
    assert.ok(message.length <= 2048);
  }
  await loaded.registered.find(t=>t.name==='replace_body').execute('call',
    {exactTarget:'exact:test',body:'x'.repeat(10000)},controller.signal,undefined,ctx);
  assert.ok(prompts.at(-1)[1].length <= 2048);
  assert.ok(prompts.at(-1)[1].includes('truncated'));
  assert.equal(loaded.calls.length,1);
});
