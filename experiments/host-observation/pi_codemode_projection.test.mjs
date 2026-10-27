import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { installedPiRendering, declarationOnlyMcpTool } from './capture_pi_codemode_payload.mjs';

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
