import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { installedPiRendering, declarationOnlyMcpTool } from './capture_pi_codemode_payload.mjs';
import { createEfficiencyCodemodeExtension } from './agent_efficiency_session.mjs';

const sdkRoot = process.env.KAST_PI_SDK_ROOT;
const options = { skip: sdkRoot ? false : 'Set KAST_PI_SDK_ROOT to run the actual installed Pi renderer. No SDK is installed by this test.' };

test('installed Pi renders every COMMON action without losing request typing', options, async () => {
  const pi = await installedPiRendering(sdkRoot);
  const schema = JSON.parse(fs.readFileSync(new URL('../../app-server/src/main/resources/io/github/amichne/kast/appserver/query/query_symbols.common.parameters.json', import.meta.url)));
  const tool = declarationOnlyMcpTool(pi, { name: 'query_symbols', description: 'COMMON query', inputSchema: schema });
  const input = pi.schemaToType(pi.toCodemodeDeclaration(tool).inputSchema, { maxChars: pi.DEFAULT_INPUT_SCHEMA_MAX_CHARS });
  assert.doesNotMatch(input, /\bunknown\b/, 'Every COMMON field needs its declared type in native Pi discovery');
  for (const tag of ['RUN', 'RESUME', 'READ_RESULT', 'READ_SOURCE', 'SEARCH_DECLARATIONS', 'AT_LOCATION',
    'SYMBOL_REFS', 'EXPAND_RELATION', 'EMIT', 'TRACE', 'NAME', 'LOCATION', 'SIGNATURE'])
    assert.ok(input.includes(`"${tag}"`), `Missing independent expected tag: ${tag}`);
  for (const field of ['symbolRefs:', 'candidateRef:', 'sha256:', 'maxResults?', 'maxReturnedBytes?'])
    assert.ok(input.includes(field), `Missing expected callable field: ${field}`);
  const missingDefinition = structuredClone(schema);
  delete missingDefinition.$defs.Action;
  assert.match(pi.schemaToType(missingDefinition), /request: unknown/,
    'The renderer oracle must reject missing request authority');
});

// This is a local capability test, not a substitute for an agent or tool-pipeline replay.
test('matched replay sandbox cannot reach nested model APIs outside the request guard', options, async () => {
  const pi = await installedPiRendering(sdkRoot);
  let definition;
  createEfficiencyCodemodeExtension(pi)({
    registerTool: tool => { assert.equal(definition, undefined); definition = tool; },
    getAllTools: () => [],
    getSettings: () => ({codemode:{mode:'on'}}),
    appendEntry: () => { throw Error('No store write is permitted'); },
  });
  assert.ok(definition, 'The production replay factory must register its real SDK tool');
  let registryAccesses = 0;
  const context = {tools:[], sessionManager:{getBranch:()=>[]},
    modelRegistry:new Proxy({}, {get(){registryAccesses++;throw Error('No model operation is permitted');}}),
    executeTool:()=>{throw Error('No nested tool operation is permitted');}};
  const script = '// @options: {"timeout_ms": 5000}\nreturn typeof models;';
  const actual = await definition.execute('disabled-model-capability', {code:script}, undefined, undefined, context);
  assert.notEqual(actual.isError, true);
  assert.ok(actual.content.some(item=>item.type==='text' && item.text==='undefined'),
    'The real replay sandbox must not expose the models global');
  assert.equal(registryAccesses, 0);
  const denied = await definition.execute('denied-nested-model',
    {code:'// @options: {"timeout_ms": 5000}\nreturn await models.classify({});'}, undefined, undefined, context);
  assert.equal(denied.isError, true, 'A forbidden nested model call must reject inside the sandbox');
  assert.equal(registryAccesses, 0, 'Rejection must precede model registry access');
  const control = await pi.createCodemodeToolDefinition({models:true}).execute(
    'enabled-model-control', {code:script}, undefined, undefined, context);
  assert.notEqual(control.isError, true);
  assert.ok(control.content.some(item=>item.type==='text' && item.text==='object'),
    'The nearest enabled control must expose the models global without invoking it');
  assert.equal(registryAccesses, 0, 'Neither arm may invoke a model registry');
});
