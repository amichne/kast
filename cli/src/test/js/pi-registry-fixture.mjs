// Optional real-Pi composition oracle. No model, credentials, IDE, or installed Kast.
// node cli/src/test/js/pi-registry-fixture.mjs /absolute/path/to/pi-coding-agent
import assert from 'node:assert/strict';
import { appendFileSync, cpSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const file = fileURLToPath(import.meta.url);
const root = resolve(dirname(file), '../../../..');
const [mode, ...args] = process.argv.slice(2);
const target = 'mcp__kast__query_symbols';
const expectedMethods = calls => ['initialize', 'notifications/initialized', 'tools/list',
  ...(calls === 1 ? ['tools/call'] : [])];

if (mode === '--server') {
  const [catalog, log, calls] = args;
  assert.ok(calls === '0' || calls === '1');
  const expected = expectedMethods(Number(calls));
  let consumed = 0;
  const tools = JSON.parse(readFileSync(catalog, 'utf8'));
  for await (const line of createInterface({ input: process.stdin })) {
    const request = JSON.parse(line);
    appendFileSync(log, JSON.stringify({ method: request.method, name: request.params?.name }) + '\n');
    assert.equal(request.jsonrpc, '2.0');
    assert.equal(request.method, expected[consumed++], 'Unexpected or excess MCP message');
    if (request.method === 'notifications/initialized') {
      assert.equal(request.id, undefined);
      continue;
    }
    assert.ok(typeof request.id === 'number' || typeof request.id === 'string', 'MCP request needs an id');
    let result;
    if (request.method === 'initialize') result = {
      protocolVersion: request.params.protocolVersion, capabilities: { tools: {} },
      serverInfo: { name: 'kast-registry-fixture', version: '1' },
      instructions: 'Offline registry fixture. No semantic readiness or compiler evidence.',
    };
    else if (request.method === 'tools/list') result = { tools };
    else if (request.method === 'tools/call') {
      assert.equal(request.params.name, 'query_symbols');
      result = { isError: true, content: [{ type: 'text', text: 'FIXTURE_UNSUPPORTED_NO_SEMANTIC_EVIDENCE' }] };
    }
    else throw new Error('Unexpected fixture request: ' + request.method);
    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) + '\n');
  }
  assert.equal(consumed, expected.length, 'Unconsumed MCP expectations');
} else if (mode === '--case') {
  const [packageRoot, variant, home] = args;
  assert.equal(realpathSync(process.env.HOME), home);
  globalThis.fetch = () => { throw new Error('NETWORK_FORBIDDEN'); };
  const load = path => import(pathToFileURL(join(packageRoot, 'dist', path)));
  const { createAgentSession } = await load('core/sdk.js');
  const { ModelRuntime } = await load('core/model-runtime.js');
  const { SessionManager } = await load('core/session-manager.js');
  const { SettingsManager } = await load('core/settings-manager.js');
  const { DefaultResourceLoader } = await load('core/resource-loader.js');
  const { builtInExtensions } = await load('extensions/index.js');
  const cwd = join(home, 'workspace');
  const agentDir = join(home, '.pi/agent');
  const installation = join(home, '.local/share/kast/installation');
  const references = join(installation, 'share/kast/agent-tools/plugins/kast/references');
  mkdirSync(cwd, { recursive: true });
  mkdirSync(agentDir, { recursive: true });
  mkdirSync(dirname(references), { recursive: true });
  cpSync(join(root, 'agent-tools/references'), references, { recursive: true });
  const schema = JSON.parse(readFileSync(join(references, 'query-schema.json'), 'utf8'));
  const authority = JSON.parse(readFileSync(join(root,
    'app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json'), 'utf8'));
  const catalog = join(home, 'catalog.json'), log = join(home, 'requests.jsonl');
  writeFileSync(catalog, JSON.stringify([{
    name: 'query_symbols', description: authority.tools.find(t => t.name === 'query_symbols').description,
    inputSchema: schema,
  }]));
  writeFileSync(log, '');
  const binary = join(installation, 'bin/kast-mcp-complete');
  mkdirSync(dirname(binary), { recursive: true });
  writeFileSync(binary, '#!' + process.execPath + '\n' +
    'import { spawnSync } from "node:child_process";\n' +
    'const r = spawnSync(' + JSON.stringify(process.execPath) + ', ' +
    JSON.stringify([file, '--server', catalog, log, variant === 'disabled' ? '0' : '1']) + ', { stdio: "inherit" });\n' +
    'process.exit(r.status ?? 1);\n', { mode: 0o700 });
  if (variant === 'deferred') writeFileSync(join(agentDir, 'mcp.json'), JSON.stringify({
    mcpServers: { kast: { command: binary, exposure: 'deferred' } },
  }));
  const settingsManager = SettingsManager.inMemory({ cacheWarming: 'off' }, { projectTrusted: false });
  const resourceLoader = new DefaultResourceLoader({
    cwd, agentDir, settingsManager, noExtensions: true, noSkills: true,
    noPromptTemplates: true, noThemes: true, noContextFiles: true,
    additionalExtensionPaths: ['builtin:mcp',
      ...(variant === 'disabled' ? [] : ['builtin:codemode', 'builtin:tool-search']),
      join(root, 'pi/mcp-extension.ts')],
    extensionFactories: builtInExtensions,
  });
  await resourceLoader.reload();
  const modelRuntime = await ModelRuntime.create({
    authPath: join(agentDir, 'auth.json'), modelsPath: null,
    modelsStorePath: join(agentDir, 'models-store.json'),
    allowModelNetwork: false, refreshOnCreate: false,
  });
  const { session, extensionsResult } = await createAgentSession({
    cwd, agentDir, modelRuntime, settingsManager, resourceLoader,
    sessionManager: SessionManager.inMemory(cwd),
  });
  let modelCalls = 0;
  session.agent.streamFunction = () => { modelCalls++; throw new Error('INFERENCE_FORBIDDEN'); };
  const errors = [];
  const records = () => readFileSync(log, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  let receipt;
  try {
    assert.deepEqual(extensionsResult.errors, []);
    await session.bindExtensions({ mode: 'print', onError: error => errors.push(error) });
    const deadline = Date.now() + 10_000;
    while (!session.getAllTools().some(t => t.name === target) && Date.now() < deadline)
      await new Promise(done => setTimeout(done, 20));
    assert.deepEqual(errors, []);
    const registered = session.getAllTools().find(t => t.name === target);
    assert.ok(registered, 'MCP schema must actually reach the registry');
    assert.deepEqual(registered.parameters, schema);
    const prepared = await session.extensionRunner.emitBeforeAgentStart(
      { messageType: 'user', prompt: 'Offline instruction delivery check.' }, session._baseSystemPromptOptions);
    const prompt = prepared.systemPromptOptions.forceSystemPrompt ?? session.systemPrompt;
    assert.ok(prompt.includes('first semantic step when its tools are reachable and support the task'));
    assert.ok(prompt.includes('tasks without a Kotlin semantic question need no Kast call'));
    const active = session.getActiveToolNames();
    assert.equal(records().filter(r => r.method === 'tools/call').length, 0, 'Passive setup must not probe semantics');
    let fixtureAssistantCalls = 0;
    if (variant === 'disabled') {
      assert.ok(!active.includes('codemode') && !active.includes('tool_search'));
      assert.ok(!active.includes(target), 'Registration without a discovery surface is not reachability');
    } else {
      const invoke = (name, input) => {
        const tool = session.agent.state.tools.find(t => t.name === name);
        assert.ok(tool, name + ' must be model reachable');
        // The real nested-call pipeline requires an issuing assistant message.
        // This scripted fixture message is not a sampled model decision.
        fixtureAssistantCalls++;
        session.agent.state.messages = [...session.agent.state.messages, { role: 'assistant',
          content: [{ type: 'toolCall', id: 'registry-fixture-' + name, name, arguments: input }],
          provider: 'fixture', api: 'openai-responses', model: 'fixture', stopReason: 'toolUse', timestamp: 0,
          usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
        }];
        return tool.execute('registry-fixture-' + name, input, new AbortController().signal);
      };
      if (variant === 'deferred') {
        const found = await invoke('tool_search', { query: 'kast query_symbols', limit: 1 });
        assert.deepEqual(found.details.loaded, [target]);
      } else {
        const found = await invoke('codemode', { code:
          'text(await searchTools("kast query_symbols", { limit: 1, namespace: "mcp__kast" }));\n' +
          'text(await describeTool("' + target + '"));' });
        assert.ok(JSON.stringify(found).includes(target), 'Real codemode discovery must return the tool');
        assert.ok(!found.isError, JSON.stringify(found));
      }
      const example = authority.tools.find(t => t.name === 'query_symbols').examples.scopedInterfaceDeclaration.value;
      const rejected = variant === 'deferred' ? await invoke(target, example) :
        await invoke('codemode', { code: 'text(await tools.' + target + '(' + JSON.stringify(example) + '));' });
      assert.ok(JSON.stringify(rejected).includes('FIXTURE_UNSUPPORTED_NO_SEMANTIC_EVIDENCE'), JSON.stringify(rejected));
      assert.equal(records().filter(r => r.method === 'tools/call').length, 1, 'No blind retry after fixture rejection');
    }
    assert.equal(modelCalls, 0);
    assert.equal(session.messages.filter(m => m.role === 'assistant').length, fixtureAssistantCalls);
    assert.equal(session.messages.filter(m => m.role === 'user').length, 0);
    receipt = { variant, pi: JSON.parse(readFileSync(join(packageRoot, 'package.json'))).version,
      registered: registered.name, exposure: registered.exposure, activeTools: active,
      modelCalls, fixtureAssistantCalls, semanticCalls: 0, fixtureCalls: records().filter(r => r.method === 'tools/call').length,
      outcome: variant === 'disabled' ? 'REGISTERED_BUT_UNREACHABLE' : 'DISCOVERY_AND_REJECTION_REACHABLE' };
  } finally {
    await session.extensionRunner.emit({ type: 'session_shutdown' });
    session.dispose();
  }
  // Recheck after shutdown so caught child failures or late traffic cannot manufacture success.
  assert.deepEqual(records().map(r => r.method), expectedMethods(variant === 'disabled' ? 0 : 1));
  console.log(JSON.stringify(receipt));
} else {
  assert.ok(mode && mode.startsWith('/'), 'Supply an absolute Pi package root');
  const temporary = realpathSync(mkdtempSync(join(tmpdir(), 'kast-pi-registry-')));
  try {
    for (const variant of ['codemode', 'deferred', 'disabled']) {
      const home = join(temporary, variant);
      mkdirSync(home);
      const result = spawnSync(process.execPath, [file, '--case', mode, variant, home], {
        env: { HOME: home, PATH: dirname(process.execPath) + ':/usr/bin:/bin', TMPDIR: home,
          XDG_CONFIG_HOME: join(home, 'config'), XDG_DATA_HOME: join(home, 'data'),
          XDG_CACHE_HOME: join(home, 'cache'), XDG_STATE_HOME: join(home, 'state') },
        encoding: 'utf8', timeout: 30_000,
      });
      process.stdout.write(result.stdout ?? '');
      if (result.status !== 0) process.stderr.write(result.stderr ?? '');
      assert.equal(result.status, 0, variant + ': ' + (result.error?.message ?? result.stderr));
    }
  } finally { rmSync(temporary, { recursive: true, force: true }); }
}
