import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { spawn as spawnChild } from 'node:child_process';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { EventEmitter } from 'node:events';
import { stripTypeScriptTypes } from 'node:module';
import { SourceTextModule, SyntheticModule, createContext } from 'node:vm';
import { fileURLToPath, pathToFileURL } from 'node:url';

// The catalog is serialized by the production Kotlin RPC bridge, never reconstructed here.
const catalog = JSON.parse(readFileSync(process.env.KAST_ADAPTER_TEST_CATALOG, 'utf8'));
// Retain the older catalog compatibility lane; session capability has separate real-child checks.
delete catalog.catalog.querySession;
const failures = JSON.parse(readFileSync(process.env.KAST_ADAPTER_TEST_FAILURES, 'utf8'));
const root = process.env.KAST_PACKAGED_ADAPTER_ROOT ? pathToFileURL(process.env.KAST_PACKAGED_ADAPTER_ROOT + '/share/kast/adapters/') : new URL('../../../../', import.meta.url);
async function load(harness, reply = catalog, outcome = { type: 'complete', document: {} }, failure, env = {KAST_TOOL_RPC_COMMAND: '/fixture/kast-tool-rpc'}, effects = {}) {
  const registered = [], calls = [], handlers = new Map();
  const context = createContext({ Buffer, TextDecoder, setTimeout: (callback, millis) => {
    if (failure === 'timeout' && calls.length > 1) { queueMicrotask(callback); return 0; }
    return setTimeout(callback, millis);
  }, clearTimeout, process: { env, cwd: () => effects.cwd ?? '/fixture' } });
  const spawn = (command, args, options) => {
    calls.push({ command, args, options });
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    child.stderr.resume = () => {};
    child.kill = () => queueMicrotask(() => child.emit('close', null, 'SIGKILL'));
    child.stdin = { on: () => {}, end: (input) => { calls.at(-1).input = input; queueMicrotask(() => {
      if (args[0] === 'call' && Array.isArray(outcome?.sequence)) assert.ok(calls.length - 2 < outcome.sequence.length, 'unexpected excess physical RPC');
      if (args[0] === 'call' && outcome?.onCall?.(calls.length - 1, child) === false) return;
      if (args[0] === 'call' && ['timeout', 'cancel'].includes(failure)) return;
      if (args[0] === 'call' && failure === 'overflow') {
        child.stdout.emit('data', Buffer.alloc(reply.catalog.maxResponseBytes + 1));
      } else if (args[0] === 'call' && failure) {
        child.stderr.emit('data', Buffer.from('kast_change stage=APPLICATION outcome=STARTED\nexact:v3:private-source-token\n'));
        child.emit('close', 7);
      } else {
        child.stdout.emit('data', args[0] === 'catalog' ? Buffer.from(JSON.stringify(reply)) : Buffer.isBuffer(outcome) ? outcome : Buffer.from(JSON.stringify(Array.isArray(outcome?.sequence) ? outcome.sequence[calls.length - 2] : outcome)));
        child.emit('close', 0);
      }
    }); } };
    return child;
  };
  const modules = {
    'node:child_process': { spawn: effects.spawn ? (command, args, options) => {
      calls.push({ command, args, options });return effects.spawn(command, args, options);
    } : spawn }, 'node:fs': { existsSync: () => true },
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
    if (harness === 'pi') await module.namespace.default({
      registerTool: (tool) => registered.push(tool),
      on: (name, handler) => handlers.set(name, handler),
      getAllTools: () => effects.availableTools ?? [...registered, {name:'read'}, {name:'bash'}, {name:'tool_search'}],
      getActiveTools: () => effects.activeTools ?? ['read', 'bash'],
      setActiveTools: names => effects.onActiveTools?.(names),
    });
  } catch (failure) { error = failure; }
  return { registered, calls, error, handlers };
}

test('Pi defers query and write schemas while diagnostic and health tools stay direct', async () => {
  const loaded = await load('pi');
  assert.ifError(loaded.error);
  assert.deepEqual(loaded.registered.map(tool => tool.name), catalog.catalog.tools.map(tool => tool.name));
  for (const tool of loaded.registered) {
    const deferred = ['query_symbols', 'add_declaration', 'replace_body'].includes(tool.name);
    assert.equal(tool.exposure, deferred ? 'deferred' : 'direct', tool.name);
    assert.deepEqual(JSON.parse(JSON.stringify(tool.parameters)), catalog.catalog.tools.find(entry => entry.name === tool.name).inputSchema);
  }
  assert.equal(loaded.calls.length, 1, 'Exposure must not invoke a semantic tool');
});

test('Pi enables native discovery without loading deferred schemas or replacing active tools', async () => {
  const changes = [];
  const loaded = await load('pi', catalog, undefined, undefined, undefined, {
    activeTools:['read', 'bash'], onActiveTools:names => changes.push([...names]),
  });
  assert.ifError(loaded.error);
  loaded.handlers.get('session_start')();
  assert.deepEqual(changes, [['read', 'bash', 'tool_search']]);
  assert.equal(loaded.calls.length, 1);
  const alreadyActive = await load('pi', catalog, undefined, undefined, undefined, {
    activeTools:['read', 'tool_search'], onActiveTools:() => assert.fail('Discovery is already active'),
  });
  assert.ifError(alreadyActive.error);
  alreadyActive.handlers.get('session_start')();
});

test('Pi rejects unavailable native discovery without changing active tools', async () => {
  const loaded = await load('pi', catalog, undefined, undefined, undefined, {
    availableTools:[], onActiveTools:() => assert.fail('Missing discovery must not change active tools'),
  });
  assert.ifError(loaded.error);
  assert.throws(() => loaded.handlers.get('session_start')(), /Kast deferred tools need Pi's native tool_search/);
  assert.equal(loaded.calls.length, 1);
});

test('upstream Pi loader and native tool search discover the deferred query schema', {
  skip: !process.env.KAST_UPSTREAM_PI_PACKAGE,
}, async t => {
  const pi = process.env.KAST_UPSTREAM_PI_PACKAGE;
  const { loadExtensions } = await import(pathToFileURL(join(pi, 'dist/core/extensions/loader.js')));
  const { createToolSearchToolDefinition } = await import(pathToFileURL(join(pi, 'dist/extensions/tool-search/tool.js')));
  const directory = mkdtempSync(join(tmpdir(), 'kast-pi-discovery-'));
  t.after(() => rmSync(directory, {recursive:true, force:true}));
  const command = join(directory, 'catalog-rpc');
  writeFileSync(command, `#!/usr/bin/env node\nconst fs=require('node:fs');\nif(process.argv[2]!=='catalog')process.exit(9);\nprocess.stdout.write(fs.readFileSync(process.env.KAST_ADAPTER_TEST_CATALOG));\n`, {mode:0o700});
  const prior = process.env.KAST_TOOL_RPC_COMMAND;
  process.env.KAST_TOOL_RPC_COMMAND = command;
  try {
    const loaded = await loadExtensions([fileURLToPath(new URL('pi/extension.ts', root))], directory);
    assert.deepEqual(loaded.errors, []);
    const extension = loaded.extensions[0];
    const definitions = [...extension.tools.values()].map(tool => tool.definition);
    let active = ['read', 'check_diagnostics', 'health_check'];
    const changes = [];
    const tools = {
      getAllTools: () => [...definitions, {name:'read',exposure:'direct'}, {name:'tool_search',exposure:'model-only'}],
      getActiveTools: () => [...active],
      setActiveTools: names => {
        assert.ok(names.every(name => tools.getAllTools().some(tool => tool.name === name)), 'Unknown tool activation');
        active = [...names]; changes.push([...names]);
      },
    };
    Object.assign(loaded.runtime, tools);
    for (const handler of extension.handlers.get('session_start')) await handler({}, {});
    assert.deepEqual(active, ['read', 'check_diagnostics', 'health_check', 'tool_search']);
    assert.equal(definitions.find(tool => tool.name === 'query_symbols').exposure, 'deferred');
    const search = createToolSearchToolDefinition({tools});
    const result = await search.execute('discovery', {query:'query_symbols',limit:1});
    assert.deepEqual(result.details.loaded, ['query_symbols']);
    assert.deepEqual(active, ['read', 'check_diagnostics', 'health_check', 'tool_search', 'query_symbols']);
    const absent = await search.execute('absent', {query:'nonexistentqjxvkpt',limit:1});
    assert.deepEqual(absent.details.loaded, []);
    assert.equal(changes.length, 2);
    console.log(`upstream Pi=${JSON.parse(readFileSync(join(pi, 'package.json'))).version}; native loader/search passed with a case-owned registry; no model or semantic invocation`);
  } finally {
    if (prior === undefined) delete process.env.KAST_TOOL_RPC_COMMAND; else process.env.KAST_TOOL_RPC_COMMAND = prior;
  }
});

test('Pi private orchestration deadline stays outside actual tool subprocess environment', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'kast-pi-environment-'));
  t.after(() => rmSync(directory, {recursive:true, force:true}));
  const command = join(directory, 'kast-tool-rpc'),proof = join(directory, 'environment.jsonl');
  // The real child records only this case's explicit synthetic environment.
  // Catalog bytes remain the production Kotlin RPC serialization fixture.
  writeFileSync(command, String.raw`#!/usr/bin/env node
const fs=require('node:fs');
const phase=process.argv[2];
fs.appendFileSync(process.env.ADAPTER_ENVIRONMENT_PROOF_FILE,JSON.stringify({phase,
deadlinePresent:Object.hasOwn(process.env,'KAST_EVALUATION_DEADLINE_MS'),
selectorPresent:Object.hasOwn(process.env,'KAST_TOOL_RPC_COMMAND'),
ideaHome:process.env.KAST_INSTALL_IDEA_HOME,javaHome:process.env.JAVA_HOME,javaOptions:process.env.JAVA_OPTS,
path:process.env.PATH,unknownConfiguration:process.env.KAST_UNKNOWN_CONFIGURATION})+'\n');
if(phase==='catalog'){const catalog=JSON.parse(fs.readFileSync(process.env.ADAPTER_CATALOG_FIXTURE_FILE));delete catalog.catalog.querySession;process.stdout.write(JSON.stringify(catalog));}
else if(phase==='call'){process.stdin.resume();process.stdin.on('end',()=>process.stdout.write(JSON.stringify({type:'complete',document:{status:'complete'}})));}
else process.exitCode=9;
`, {mode:0o700});
  const environment = { KAST_TOOL_RPC_COMMAND:command,KAST_EVALUATION_DEADLINE_MS:String(Date.now()+5000),
    KAST_INSTALL_IDEA_HOME:'/owned/idea',JAVA_HOME:'/owned/jbr',JAVA_OPTS:'-Duser.home=/owned/home',
    PATH:`${dirname(process.execPath)}:${process.env.PATH}`,KAST_UNKNOWN_CONFIGURATION:'must-remain-for-strict-admission',
    ADAPTER_ENVIRONMENT_PROOF_FILE:proof,ADAPTER_CATALOG_FIXTURE_FILE:process.env.KAST_ADAPTER_TEST_CATALOG };
  const original = {...environment},children=[];
  const loaded = await load('pi', catalog, undefined, undefined, environment, {cwd:directory,
    spawn:(command,args,options)=>{const child=spawnChild(command,args,options);children.push(child);return child;}});
  assert.ifError(loaded.error);
  await loaded.registered.find(tool=>tool.name==='query_symbols').execute('call',{request:{type:'RUN',source:{type:'ALL_DECLARATIONS'},terminalStep:{type:'EMIT'}}},undefined,undefined,{cwd:directory});
  const observations=readFileSync(proof,'utf8').trim().split('\n').map(line=>JSON.parse(line));
  assert.deepEqual(observations.map(value=>value.phase),['catalog','call']);
  for(const observed of observations) {
    assert.equal(observed.deadlinePresent,false);assert.equal(observed.selectorPresent,false);
    assert.equal(observed.ideaHome,environment.KAST_INSTALL_IDEA_HOME);assert.equal(observed.javaHome,environment.JAVA_HOME);
    assert.equal(observed.javaOptions,environment.JAVA_OPTS);assert.equal(observed.path,environment.PATH);
    assert.equal(observed.unknownConfiguration,environment.KAST_UNKNOWN_CONFIGURATION);
  }
  assert.equal(loaded.calls.length,2);assert.equal(children.length,2);
  assert.ok(children.every(child=>Number.isSafeInteger(child.pid)&&child.pid>0&&child.exitCode===0));
  assert.deepEqual(environment,original,'Parent/worker deadline and required environment remain unchanged');
});
for (const harness of ['copilot', 'pi']) {
  test(`${harness} keeps its RPC selector outside product configuration`, async () => {
    const environment = { KAST_TOOL_RPC_COMMAND: '/selected/kast-tool-rpc',
      KAST_INSTALL_IDEA_HOME: '/owned/idea', JAVA_OPTS: '-Duser.home=/owned/home', PATH: '/owned/bin' };
    const loaded = await load(harness, catalog, undefined, undefined, environment);
    assert.ifError(loaded.error);
    const query = loaded.registered.find(tool => tool.name === 'query_symbols');
    if (harness === 'pi') await query.execute('call', {request: {type: 'RUN', source: {type: 'ALL_DECLARATIONS'}, terminalStep: {type: 'EMIT'}}}, undefined, undefined, {cwd:'/fixture'});
    else await query.handler({request: {type: 'RUN', source: {type: 'ALL_DECLARATIONS'}, terminalStep: {type: 'EMIT'}}}, {});
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
      assert.ok(tool.description.startsWith(original.description), `${tool.name} keeps the source-owned description`);
      if (harness === 'copilot') assert.equal(tool.skipPermission, undefined, `${original.effect} ${tool.name} must retain host permission policy`);
    }
    const query = loaded.registered.find(t => t.name === 'query_symbols');
    const result = harness === 'pi'
      ? await query.execute('call', {request: {type: 'RUN', source: {type: 'ALL_DECLARATIONS'}, terminalStep: {type: 'EMIT'}}}, undefined, undefined, {cwd:'/fixture'})
      : await query.handler({request: {type: 'RUN', source: {type: 'ALL_DECLARATIONS'}, terminalStep: {type: 'EMIT'}}}, {});
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
    assert.match(loaded.error?.message ?? '', /catalog.*expected=17.*observed=999.*executable=\/fixture\/kast-tool-rpc/);
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
  test(`${harness} preserves a typed recovery detail as a host failure`, async () => {
    const outcomes = process.env.KAST_ADAPTER_TEST_RECOVERY
      ? JSON.parse(readFileSync(process.env.KAST_ADAPTER_TEST_RECOVERY, 'utf8')) : [
      { type: 'rejected', failure: 'QUERY_SESSION_TRANSITION_REJECTED', detail: {
        type: 'RESULT_NOT_ISSUED_BY_PRECEDING_REPLY', expected_result: null,
        requested_result: 'result:v1:00000000-0000-0000-0000-000000000001', next_action: 'START_NEW_QUERY_SESSION' } },
      { type: 'rejected', failure: 'INVALID_ARGUMENTS', detail: {
        type: 'SOURCE_ANCHOR_REJECTED', cause: 'UNKNOWN_TOKEN_FAMILY', next_action: 'USE_ISSUED_CANDIDATE_SELECTOR' } },
    ];
    if (process.env.KAST_ADAPTER_TEST_RECOVERY) {
      assert.equal(outcomes.length, 10, 'Kotlin serializes every detail and both expected-authority states');
      assert.equal(new Set(outcomes.map(outcome => outcome.detail.type)).size, 5);
    }
    for (const outcome of outcomes) {
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
    const result = await tools.get('query_symbols').definition.execute('smoke', {request: {type:'RUN', source:{type:'ALL_DECLARATIONS'}, steps:[], terminalStep:{type:'EMIT',output:{type:'SYMBOLS',fields:['NAME']}}}}, undefined, undefined, {cwd:temporary, hasUI:false});
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

// Fixtures are production-serialized by QueryDeliveryFixtures, then traverse the real subprocess adapter.
if (process.env.KAST_ADAPTER_TEST_DELIVERY) {
  const fixture = JSON.parse(readFileSync(process.env.KAST_ADAPTER_TEST_DELIVERY, 'utf8'));
  const params = { verbose: true, request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
    terminalStep: { type: 'EMIT', output: { type: 'SYMBOLS', fields: ['NAME'] } }, executionBudget: { maxResults: 43 } } };
  for (const harness of ['pi', 'copilot']) {
    const execute = async (outcomes, signal, arguments_ = params) => {
      const loaded = await load(harness, catalog, {sequence:outcomes});
      assert.ifError(loaded.error);
      const tool = loaded.registered.find(t => t.name === 'query_symbols');
      const result = harness === 'pi'
        ? await tool.execute('one-logical-invocation', arguments_, signal, undefined, { cwd: '/fixture' })
        : await tool.handler(arguments_, { signal });
      assert.equal(loaded.calls.length - 1, outcomes.length, 'unconsumed fixture responses');
      const delivered = JSON.parse(harness === 'pi' ? result.content[0].text : result.textResultForLlm);
      return { loaded, result, delivered };
    };
    test(`${harness} INLINE remains one physical call and unchanged canonical evidence`, async () => {
      const {loaded, delivered} = await execute([fixture.inline]);
      assert.deepEqual(delivered, fixture.inline);
      assert.equal(loaded.calls.length - 1, 1);
    });
    for (const [name, outcomes, expectedCursors] of [
      ['PREFIX with independent evidence-only tail', [fixture.prefix, fixture.rows, fixture.tail], [[1, 0], [43, 1]]],
      ['empty first prefix', [fixture.emptyPrefix, fixture.allRows, fixture.tail], [[0, 0], [43, 1]]],
      ['empty advancing evidence page', [fixture.emptyPrefix, fixture.emptyAdvancing, fixture.rowsAfterEvidence], [[0, 0], [0, 1]]],
    ]) {
      test(`${harness} owns ${name} with exact rows and proof in one tool turn`, async () => {
        const {loaded, delivered} = await execute(outcomes);
        assert.equal(delivered.delivery.stop, 'DELIVERED');
        assert.equal(delivered.delivery.rpc_count, 3);
        assert.equal(loaded.calls.length - 1, 3);
        assert.deepEqual(delivered.initial, outcomes[0]);
        assert.deepEqual(delivered.pages, outcomes.slice(1));
        assert.equal(delivered.pages.every(r => JSON.stringify(r.document.live) === JSON.stringify(delivered.initial.document.live)), true);
        assert.deepEqual([delivered.initial, ...delivered.pages].flatMap(r => r.document.items).map(r => r.ref),
          fixture.inline.document.items.map(r => r.ref));
        assert.deepEqual(delivered.pages.flatMap(r => r.document.failures ?? []),
          [fixture.rows, fixture.tail].flatMap(r => r.document.failures ?? []));
        for (const [index, [cursor, evidence_cursor]] of expectedCursors.entries()) {
          const input = JSON.parse(loaded.calls[index + 2].input);
          assert.deepEqual(input, {verbose:true,request:{ type:'READ_RESULT', result:fixture.prefix.document.retention.reference,
            cursor, evidence_cursor, output:params.request.terminalStep.output, executionBudget:params.request.executionBudget }});
          assert.equal(Object.hasOwn(input.request, 'source'), false);
        }
        const visibleBytes = Buffer.byteLength(JSON.stringify(delivered), 'utf8');
        assert.ok(visibleBytes <= 262144);
        console.log(JSON.stringify({contract:name, harness, logicalInvocations:1, toolTurns:1,
          physicalRpcs:delivered.delivery.rpc_count, requestBytes:delivered.delivery.request_bytes, responseBytes:delivered.delivery.response_bytes,
          visibleBytes, rows:43, evidence:2, semanticReruns:0}));
      });
    }
    test(`${harness} initial READ_RESULT at a nonzero row cursor drains its independent evidence tail`, async () => {
      const request = {verbose:true,request:{type:'READ_RESULT',result:fixture.prefix.document.retention.reference,
        cursor:1,evidence_cursor:0,output:params.request.terminalStep.output,executionBudget:params.request.executionBudget}};
      assert.equal(Object.hasOwn(fixture.rows.document, 'next_cursor'), false);
      assert.equal(Object.hasOwn(fixture.rows.document, 'invocation'), false);
      assert.equal(fixture.rows.document.evidence_window.type, 'MORE');
      const {loaded, delivered} = await execute([fixture.rows,fixture.tail], undefined, request);
      assert.equal(delivered.delivery.stop, 'DELIVERED');
      assert.equal(delivered.delivery.rpc_count, 2);
      assert.deepEqual(delivered.initial, fixture.rows);
      assert.deepEqual(delivered.pages, [fixture.tail]);
      assert.deepEqual(JSON.parse(loaded.calls[1].input), request);
      assert.deepEqual(JSON.parse(loaded.calls[2].input), {verbose:true,request:{...request.request,cursor:43,evidence_cursor:1}});
      assert.deepEqual([delivered.initial,...delivered.pages].flatMap(r=>r.document.items), fixture.inline.document.items.slice(1));
      assert.deepEqual([delivered.initial,...delivered.pages].flatMap(r=>r.document.failures ?? []),
        [fixture.rows,fixture.tail].flatMap(r=>r.document.failures ?? []));
      assert.deepEqual(loaded.calls.slice(1).map(call=>JSON.parse(call.input).request.type), ['READ_RESULT','READ_RESULT']);
    });
    test(`${harness} drains retained rejection proof and preserves the original blocker`, async () => {
      const {loaded, result, delivered} = await execute([fixture.rejected, fixture.proof, fixture.proofTail]);
      assert.equal(delivered.delivery.stop, 'DELIVERED');
      assert.deepEqual(delivered.initial, fixture.rejected);
      assert.equal(delivered.initial.document.rejection.type, 'COMPLETION_UNPROVEN');
      assert.equal(harness === 'pi' ? result.isError : result.resultType === 'failure', true);
      assert.deepEqual(delivered.pages.flatMap(r => r.document.items).map(r => r.ref), fixture.inline.document.items.map(r => r.ref));
      assert.equal(delivered.pages.every(r => r.document.interpretation.type === 'POLICY_REJECTED_EVIDENCE'), true);
      assert.deepEqual(loaded.calls.slice(2).map(call => JSON.parse(call.input).request.type), ['READ_RESULT','READ_RESULT']);
    });
    test(`${harness} RESUME is output-only and preserves the original grant`, async () => {
      const {loaded, delivered} = await execute([fixture.outputPrefix, fixture.outputFinal]);
      assert.equal(delivered.delivery.stop, 'DELIVERED');
      assert.deepEqual(JSON.parse(loaded.calls[2].input), {verbose:true,request:{type:'RESUME',
        continuation:fixture.outputPrefix.document.qualification.progress.checkpoint.token,
        executionBudget:params.request.executionBudget}});
      assert.equal(delivered.delivery.rpc_count, 2);
    });
    for (const [name, blocker, stop] of [
      ['suffix retention unavailable', fixture.outputUnavailable, 'DELIVERY_UNAVAILABLE'],
      ['increased allowance required', fixture.outputBudget, 'BUDGET_INCREASE_REQUIRED'],
    ]) {
      for (const initial of [true, false]) {
        test(`${harness} ${initial ? 'initial' : 'RESUME'} ${name} preserves its qualification and stops`, async () => {
          const outcomes = initial ? [blocker] : [fixture.outputPrefix, blocker];
          const {loaded, result, delivered} = await execute(outcomes);
          assert.equal(delivered.delivery.stop, stop);
          assert.deepEqual(delivered.initial, outcomes[0]);
          assert.deepEqual(delivered.pages, outcomes.slice(1));
          assert.equal(delivered.delivery.rpc_count, outcomes.length);
          assert.equal(harness === 'pi' ? result.isError : result.resultType === 'failure', true);
          if (!initial) assert.deepEqual(JSON.parse(loaded.calls[2].input), {verbose:true,request:{type:'RESUME',
            continuation:fixture.outputPrefix.document.qualification.progress.checkpoint.token,
            executionBudget:params.request.executionBudget}});
          if (stop === 'DELIVERY_UNAVAILABLE') assert.equal(Object.hasOwn(blocker.document, 'continuation'), false);
          else assert.equal(blocker.document.qualification.progress.next_action, 'increase_execution_budget');
        });
      }
    }
    test(`${harness} later READ_RESULT budget blocker stops before any cursor progression`, async () => {
      const blocker = fixture.readBudget;
      const {loaded, result, delivered} = await execute([fixture.prefix, blocker]);
      assert.equal(delivered.delivery.stop, 'BUDGET_INCREASE_REQUIRED');
      assert.deepEqual(delivered.pages, [blocker]);
      assert.equal(delivered.delivery.rpc_count, 2);
      assert.equal(harness === 'pi' ? result.isError : result.resultType === 'failure', true);
      assert.deepEqual(JSON.parse(loaded.calls[2].input), {verbose:true,request:{type:'READ_RESULT',
        result:fixture.prefix.document.retention.reference,cursor:1,evidence_cursor:0,
        output:params.request.terminalStep.output,executionBudget:params.request.executionBudget}});
    });
    test(`${harness} stale handles terminate with their exact canonical rejection`, async () => {
      const {loaded, result, delivered} = await execute([fixture.prefix, fixture.stale]);
      assert.equal(delivered.delivery.stop, 'DELIVERY_UNAVAILABLE');
      assert.deepEqual(delivered.pages, [fixture.stale]);
      assert.equal(loaded.calls.length - 1, 2);
      assert.equal(harness === 'pi' ? result.isError : result.resultType === 'failure', true);
    });
    for (const [name, initial, received, unavailable, action, reason] of [
      ['result expires before first read', fixture.prefix, [], fixture.expiredResult, 'READ_RESULT', 'result-unavailable'],
      ['result disposed while waiting for evidence tail', fixture.prefix, [fixture.rows], fixture.disposedResult, 'READ_RESULT', 'result-unavailable'],
      ['output token expires before RESUME', fixture.outputPrefix, [], fixture.expiredOutput, 'RESUME', 'continuation-unavailable'],
      ['output owner disposed before RESUME', fixture.outputPrefix, [], fixture.disposedOutput, 'RESUME', 'continuation-unavailable'],
      ['rejected proof owner disposed during evidence tail', fixture.rejected, [fixture.proof], fixture.disposedResult, 'READ_RESULT', 'result-unavailable'],
    ]) {
      test(`${harness} ${name} preserves proof and stops without revival`, async () => {
        const {loaded, result, delivered} = await execute([initial, ...received, unavailable]);
        assert.equal(delivered.delivery.stop, 'DELIVERY_UNAVAILABLE');
        assert.deepEqual(delivered.initial, initial);
        assert.deepEqual(delivered.pages, [...received, unavailable]);
        assert.equal(delivered.pages.at(-1).document.rejection.reason, reason);
        assert.deepEqual(loaded.calls.slice(2).map(call => JSON.parse(call.input).request.type),
          Array(received.length + 1).fill(action));
        assert.equal(harness === 'pi' ? result.isError : result.resultType === 'failure', true);
        if (received.length) {
          const last = JSON.parse(loaded.calls.at(-1).input).request;
          assert.equal(last.cursor, 43);
          assert.equal(last.evidence_cursor, 1);
          assert.equal(received[0].document.items.length + (initial.document.items?.length ?? 0), 43);
        }
      });
    }
    test(`${harness} cancellation of an in-flight delivery child preserves the initial reply`, async () => {
      const controller = new AbortController();
      const loaded = await load(harness, catalog, {sequence:[fixture.prefix, fixture.rows],
        onCall: ordinal => {if (ordinal === 2) {controller.abort();return false;}}});
      assert.ifError(loaded.error);
      const tool = loaded.registered.find(t => t.name === 'query_symbols');
      const result = harness === 'pi'
        ? await tool.execute('one-invocation', params, controller.signal, undefined, {cwd:'/fixture'})
        : await tool.handler(params, {signal:controller.signal});
      const delivered = JSON.parse(harness === 'pi' ? result.content[0].text : result.textResultForLlm);
      assert.equal(delivered.delivery.stop, 'CANCELLED');
      assert.equal(delivered.delivery.rpc_count, 2);
      assert.equal(loaded.calls.length - 1, 2);
      assert.deepEqual(delivered.initial, fixture.prefix);
      assert.deepEqual(delivered.pages, []);
      assert.equal(harness === 'pi' ? result.isError : result.resultType === 'failure', true);
    });
    test(`${harness} transient delivery loss never retries or reexecutes RUN`, async () => {
      const {loaded, delivered} = await execute([fixture.prefix, {type:'invalid-response'}]);
      assert.equal(delivered.delivery.stop, 'DELIVERY_UNAVAILABLE');
      assert.equal(loaded.calls.length - 1, 2);
      assert.deepEqual(delivered.initial, fixture.prefix);
    });
    for (const [name, mutate, stop] of [
      ['nonadvancing', page => {page.items=[]; page.next_cursor=1; page.evidence_window={type:'MORE',start:0,end:0,total:2};page.failures=[];}, 'NON_ADVANCING'],
      ['malformed row cursor', page => {page.next_cursor=9;}, 'MALFORMED_PAGE'],
      ['malformed evidence window', page => {page.evidence_window.start=1;}, 'MALFORMED_PAGE'],
      ['changed question', page => {page.question.from={type:'invented'};}, 'IDENTITY_MISMATCH'],
      ['changed live epoch', page => {page.live.epoch=8;}, 'IDENTITY_MISMATCH'],
      ['wrong retained owner', page => {page.retention.reference='result:v1:00000000-0000-0000-0000-000000000009';}, 'IDENTITY_MISMATCH'],
    ]) {
      test(`${harness} ${name} terminates without accepting the page`, async () => {
        const page = structuredClone(fixture.rows); mutate(page.document);
        const {loaded, delivered} = await execute([fixture.prefix, page]);
        assert.equal(delivered.delivery.stop, stop);
        assert.deepEqual(delivered.pages, []);
        assert.equal(loaded.calls.length - 1, 2);
      });
    }
    test(`${harness} repeated output continuation terminates`, async () => {
      const {loaded, delivered} = await execute([fixture.outputPrefix, fixture.outputPrefix]);
      assert.equal(delivered.delivery.stop, 'NON_ADVANCING');
      assert.equal(loaded.calls.length - 1, 2);
    });
  }
  const deliveryModule = new SourceTextModule(
    readFileSync(new URL('../' + '../main/js/query-delivery.mjs', import.meta.url), 'utf8') +
      '\nexport { awaitQueryDelivery };', {context:createContext({Buffer, Date, JSON, Error})});
  await deliveryModule.link(() => { throw new Error('Delivery core must have no external imports'); });
  await deliveryModule.evaluate();
  const awaitDelivery = deliveryModule.namespace.awaitQueryDelivery;
  const policy = {callTimeoutMillis:100, maxResponseBytes:4194304};
  test('shared production delivery cancels after submission without any continuation effect', async () => {
    const controller = new AbortController(); let calls = 0;
    const result = await awaitDelivery(params, async () => {calls++;controller.abort();return fixture.prefix;}, policy, controller.signal);
    assert.equal(result.delivery.stop, 'CANCELLED');
    assert.equal(calls, 1);
    assert.deepEqual(result.initial, fixture.prefix);
  });
  test('shared production delivery cancellation before submission has zero physical calls', async () => {
    const controller = new AbortController();controller.abort();let calls = 0;
    await assert.rejects(() => awaitDelivery(params, async () => {calls++;}, policy, controller.signal), /CANCELLED/);
    assert.equal(calls, 0);
  });
  test('shared production delivery deadline is one aggregate allowance', async () => {
    let time = 0; const allowances = []; const replies = [fixture.prefix,fixture.rows];
    const result = await awaitDelivery({...params, request:{...params.request, executionMode:'BOUNDED'}}, async (_, budget) => {
      allowances.push(budget.callTimeoutMillis);time += allowances.length === 1 ? 30 : 70;
      return replies.shift();
    }, policy, undefined, () => time);
    assert.equal(result.delivery.stop, 'TIME_LIMIT');
    assert.deepEqual(allowances, [100,70]);
    assert.equal(result.delivery.rpc_count, 2);
    assert.equal(result.pages.length, 1);
  });
  for (const phase of ['disconnected before reply','submission succeeded but reply lost']) {
    test(`shared production delivery ${phase} never blindly resubmits`, async () => {
      let calls = 0;
      await assert.rejects(() => awaitDelivery(params, async () => {calls++;throw new Error('AMBIGUOUS_SUBMISSION');}, policy), /AMBIGUOUS_SUBMISSION/);
      assert.equal(calls, 1);
    });
  }
  test('shared production delivery bounds aggregate bytes and exposes the existing retained reference', async () => {
    const huge = structuredClone(fixture.prefix);
    huge.document.items[0].name = 'x'.repeat(262145);
    let calls = 0;
    const result = await awaitDelivery(params, async () => {calls++;return huge;}, policy);
    assert.equal(result.delivery.stop, 'BYTE_LIMIT');
    assert.equal(result.initial, null);
    assert.equal(result.delivery.original_outcome, 'complete');
    assert.equal(result.delivery.result, fixture.prefix.document.retention.reference);
    assert.ok(Buffer.byteLength(JSON.stringify(result),'utf8') <= 4096);
    assert.equal(result.semantic.original_outcome, 'complete');
    assert.equal(result.semantic.authority.type, 'RETAINED_RESULT');
    assert.equal(result.semantic.presentation.rows, 0);
    assert.equal(calls, 1);
  });
  test('shared production delivery bounds physical output RPCs even when every page advances', async () => {
    let calls = 0;
    const result = await awaitDelivery(params, async () => {
      const reply = structuredClone(fixture.outputPrefix);
      reply.document.qualification.progress.checkpoint.token =
        'query-output:v1:00000000-0000-0000-0000-' + String(++calls).padStart(12,'0');
      delete reply.document.continuation;
      return reply;
    }, policy);
    assert.equal(result.delivery.stop, 'PAGE_LIMIT');
    assert.equal(calls, 64);
    assert.equal(result.delivery.rpc_count, 64);
  });

}

// Invocation-scoped transport checks use the real adapter and an owned child, never a live IDE.
if (process.env.KAST_ADAPTER_TEST_DELIVERY) {
  const fixture = JSON.parse(readFileSync(process.env.KAST_ADAPTER_TEST_DELIVERY, 'utf8'));
  for (const harness of ['pi', 'copilot']) {
    for (const [name, keys, first] of [
      ['complete', ['prefix','rows','tail'], {type:'RUN',source:{type:'ALL_DECLARATIONS'},terminalStep:{type:'EMIT',output:{type:'SYMBOLS',fields:['NAME']}}}],
      ['retained rejection', ['rejected','proof','proofTail'], {type:'RUN',source:{type:'ALL_DECLARATIONS'},terminalStep:{type:'EMIT',output:{type:'SYMBOLS',fields:['NAME']}}}],
      ['detail', ['rows','tail'], {type:'READ_RESULT',result:fixture.prefix.document.retention.reference,cursor:1,evidence_cursor:0,output:{type:'SYMBOLS',fields:['NAME']}}],
      ['stale detail', ['expiredResult'], {type:'READ_RESULT',result:fixture.prefix.document.retention.reference,cursor:0,evidence_cursor:0}],
    ]) {
      for (const transport of (name==='complete'?['one-shot','session']:['session'])) test(`${harness} ${transport} delivers ${name} without semantic replay`, async t => {
        const directory = mkdtempSync(join(tmpdir(), 'kast-query-session-'));
        t.after(() => rmSync(directory, {recursive:true, force:true}));
        const command = join(directory,'kast-tool-rpc'), proof = join(directory,'proof.jsonl');
        const sessionCatalog = structuredClone(catalog);if(transport==='session')sessionCatalog.catalog.querySession = true;
        const catalogFile = join(directory,'catalog.json'), repliesFile = join(directory,'replies.json');
        writeFileSync(catalogFile,JSON.stringify(sessionCatalog));
        writeFileSync(repliesFile,JSON.stringify(keys.map(key=>fixture[key])));
        writeFileSync(command, String.raw`#!/usr/bin/env node
const fs=require('node:fs'),readline=require('node:readline');
const directory=process.cwd(),phase=process.argv[2],proof=directory+'/proof.jsonl';
const record=value=>fs.appendFileSync(proof,JSON.stringify(value)+'\n');
record({phase,catalogCalls:phase==='catalog'?1:0,selectorPresent:Object.hasOwn(process.env,'KAST_TOOL_RPC_COMMAND'),deadlinePresent:Object.hasOwn(process.env,'KAST_EVALUATION_DEADLINE_MS')});
if(phase==='catalog')process.stdout.write(fs.readFileSync(directory+'/catalog.json'));
else if(phase==='query-session'){
 const replies=JSON.parse(fs.readFileSync(directory+'/replies.json','utf8'));let index=0;
 readline.createInterface({input:process.stdin}).on('line',line=>{
  const request=JSON.parse(line).request,reply=replies[index++];
  if(!reply){process.exitCode=7;return;}
  record({type:request.type,semanticExecutions:request.type==='RUN'?1:0,describeCalls:1,
    requestBytes:Buffer.byteLength(line,'utf8'),responseBytes:Buffer.byteLength(JSON.stringify(reply),'utf8')+1});
  process.stdout.write(JSON.stringify(reply)+'\n');
  }).on('close',()=>{record({closed:true,consumed:index});if(index!==replies.length)process.exitCode=9;});
}else if(phase==='call'){
 const replies=JSON.parse(fs.readFileSync(directory+'/replies.json','utf8')),cursor=directory+'/cursor';
 const index=fs.existsSync(cursor)?Number(fs.readFileSync(cursor,'utf8')):0;
 let input='';process.stdin.on('data',chunk=>input+=chunk.toString('utf8'));
 process.stdin.on('end',()=>{
  const request=JSON.parse(input).request,reply=replies[index];
  if(!reply){process.exitCode=7;return;}
  fs.writeFileSync(cursor,String(index+1));
  record({type:request.type,semanticExecutions:request.type==='RUN'?1:0,describeCalls:1,
    requestBytes:Buffer.byteLength(input,'utf8'),responseBytes:Buffer.byteLength(JSON.stringify(reply),'utf8')+1});
  process.stdout.write(JSON.stringify(reply)+'\n');record({closed:true,consumed:1});
 });
}else process.exitCode=8;
`,{mode:0o700});
        const children=[],processObservations=[];
        const environment={KAST_TOOL_RPC_COMMAND:command,KAST_EVALUATION_DEADLINE_MS:'private-controller-deadline',PATH:process.env.PATH};
        const loaded=await load(harness,sessionCatalog,undefined,undefined,environment,{cwd:directory,
          spawn:(command,args,options)=>{
            const started=process.hrtime.bigint(),child=spawnChild(command,args,options);
            const observation={phase:args[0],stdoutReadBytes:0,spawnReturnMillis:Number(process.hrtime.bigint()-started)/1e6};
            child.stdout.on('data',chunk=>{observation.stdoutReadBytes+=chunk.length;
              observation.firstByteMillis??=Number(process.hrtime.bigint()-started)/1e6;});
            child.on('close',()=>observation.closedMillis=Number(process.hrtime.bigint()-started)/1e6);
            processObservations.push(observation);children.push(child);return child;}});
        assert.ifError(loaded.error);
        const query=loaded.registered.find(tool=>tool.name==='query_symbols');
        const params={request:first,...(name.includes('detail')?{verbose:true}:{})};
        const reply=harness==='pi'?await query.execute('one',params,undefined,undefined,{cwd:directory}):await query.handler(params,{});
        const text=harness==='pi'?reply.content[0].text:reply.textResultForLlm;
        const trace=readFileSync(proof,'utf8').trim().split('\n').map(line=>JSON.parse(line));
        assert.deepEqual(trace.filter(row=>row.phase).map(row=>row.phase),['catalog',...(transport==='session'?['query-session']:keys.map(()=> 'call'))]);
        assert.ok(trace.filter(row=>row.phase).every(row=>!row.selectorPresent&&!row.deadlinePresent));
        assert.equal(environment.KAST_EVALUATION_DEADLINE_MS,'private-controller-deadline');
        const requests=trace.filter(row=>row.type);
        assert.deepEqual(requests.map(row=>row.type),[first.type,...keys.slice(1).map(()=> 'READ_RESULT')]);
        assert.equal(requests.reduce((n,row)=>n+row.semanticExecutions,0),first.type==='RUN'?1:0);
        assert.equal(requests.reduce((n,row)=>n+row.describeCalls,0),keys.length,'simulated authoritative describe remains per RPC');
        assert.equal(children.length,transport==='session'?2:keys.length+1,'one catalog and bounded invocation children');
        assert.ok(children.every(child=>child.exitCode===0));
        assert.equal(trace.filter(row=>row.closed).reduce((sum,row)=>sum+row.consumed,0),keys.length);
        if(name.includes('detail')){
          const result=JSON.parse(text);
          if(keys.length===1)assert.deepEqual(result,fixture[keys[0]]);
          else {assert.deepEqual(result.initial,fixture.rows);assert.deepEqual(result.pages,[fixture.tail]);}
        }else{
          assert.match(text,/Rows \(43\):/);
          assert.equal(harness==='pi'?reply.isError:reply.resultType==='failure',name==='retained rejection');
          for(let index=0;index<43;index++)assert.ok(text.includes(`"ref":"exact:v2:fixture-${index}"`));
          if(harness==='pi')assert.deepEqual(JSON.parse(JSON.stringify(reply.details.initial)),fixture[keys[0]]);
        }
        console.log(JSON.stringify({harness,transport,case:name,childProcesses:children.length,catalogCalls:1,
          simulatedDescribeCalls:requests.length,physicalRpcs:requests.length,
          requestBytes:requests.reduce((n,row)=>n+row.requestBytes,0),responseBytes:requests.reduce((n,row)=>n+row.responseBytes,0),
          modelVisibleBytes:Buffer.byteLength(text,'utf8'),semanticExecutions:first.type==='RUN'?1:0,semanticReruns:0,
          requestFramingBytes:transport==='session'?requests.length:0,
          catalogReadBytes:processObservations.find(row=>row.phase==='catalog').stdoutReadBytes,processObservations}));
      });
    }
  }
}

for(const harness of ['pi','copilot'])for(const capability of [null,'true',1,{},[]])
  test(`${harness} rejects an unproven query session capability ${JSON.stringify(capability)}`,async()=>{
    const invalid=structuredClone(catalog);invalid.catalog.querySession=capability;
    const loaded=await load(harness,invalid);
    assert.match(String(loaded.error),/catalog admission failed/);assert.equal(loaded.registered.length,0);
    assert.equal(loaded.calls.length,1);
  });
