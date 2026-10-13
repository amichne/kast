import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { SourceTextModule, createContext } from 'node:vm';

// Kotlin sends fresh documents from the production projector through typed RPC replies.
const input = JSON.parse(readFileSync(0, 'utf8'));
const start = '// Generated query delivery; owned by cli/src/main/js/query-delivery.mjs.';
const end = '// End generated query delivery.';
const paths = ['src/main/js/query-delivery.mjs', '../pi/extension.ts', '../copilot/extension.mjs'];
const owners = [];
let importAttempts = 0;
for (const path of paths) {
  const source = readFileSync(path, 'utf8');
  const body = path.endsWith('query-delivery.mjs') ? source : source.slice(source.indexOf(start) + start.length, source.indexOf(end));
  assert.ok(body.includes('function queryPresentation('));
  const module = new SourceTextModule(body + '\nexport { queryPresentation };', { context: createContext({ Buffer }) });
  await module.link(() => { importAttempts++; throw new Error('Presentation cannot import effects'); });
  await module.evaluate();
  owners.push(module.namespace.queryPresentation);
}

const measurements = ['examined_work_units', 'inventory_files', 'reacquired_files', 'examined_leaves',
  'inventory_nanoseconds', 'declaration_scan_nanoseconds', 'projection_nanoseconds'];
for (const [index, { compact, verbose }] of input.samples.entries()) {
  const originalCompact = JSON.stringify(compact), originalVerbose = JSON.stringify(verbose);
  assert.ok(Buffer.byteLength(originalVerbose) < 262144, 'Primary fixtures stay below delivery limits');
  for (const observation of compact.document.discovery_observations ?? []) {
    assert.deepEqual(Object.keys(observation).sort(), ['observed_stops', 'ordering', 'progress', 'universe']);
  }
  for (const observation of verbose.document.discovery_observations ?? []) {
    for (const field of measurements) assert.equal(observation[field], 100);
  }
  const expectedText = owners[0](compact);
  for (const [ownerIndex, present] of owners.entries()) {
    assert.equal(present(verbose, true), originalVerbose);
    assert.equal(present(compact), expectedText, 'All bundled consumers use the same presentation owner');
    assert.equal(present(verbose, true), originalVerbose, 'Repeated rendering keeps the original full document');
    assert.equal(JSON.stringify(compact), originalCompact);
    assert.equal(JSON.stringify(verbose), originalVerbose);
    for (const field of measurements) assert.equal(expectedText.includes('"' + field + '"'), false);
    console.log(JSON.stringify({ fixture: index, owner: paths[ownerIndex], compactDocumentBytes: Buffer.byteLength(originalCompact),
      verboseDocumentBytes: Buffer.byteLength(originalVerbose), presentationTextBytes: Buffer.byteLength(expectedText),
      importAttempts }));
  }
}

// This child admits no transport, clock, logger, or native semantic capability.
// Existing delivery tests separately prove caps, continuation, and failed detail reads.

if (process.env.KAST_PI_PACKAGE) {
  const { pathToFileURL } = await import('node:url');
  const { join } = await import('node:path');
  const { createHash } = await import('node:crypto');
  const pi = process.env.KAST_PI_PACKAGE;
  const packageInfo = JSON.parse(readFileSync(join(pi, 'package.json')));
  const messagesPath = join(pi, 'dist/core/messages.js');
  const converterPath = join(pi, '../pi-ai/dist/api/openai-responses-shared.js');
  const { convertToLlm } = await import(pathToFileURL(messagesPath));
  const { convertResponsesMessages } = await import(pathToFileURL(converterPath));
  const model = { id: 'gpt-6.1-sol', provider: 'openai-codex', api: 'openai-codex-responses',
    input: ['text', 'image'], reasoning: true, compat: {} };
  for (const [index, { compact, verbose }] of input.samples.entries()) {
    for (const [detail, result] of [['compact', compact], ['verbose', verbose]]) {
      const text = owners[1](result, detail === 'verbose');
      const assistant = { role: 'assistant', provider: model.provider, api: model.api, model: model.id,
        content: [{ type: 'toolCall', id: 'fixture_call', name: 'query_symbols', arguments: { request: { type: 'RUN' } } }],
        stopReason: 'toolUse', timestamp: 0 };
      const tool = { role: 'toolResult', toolCallId: 'fixture_call', toolName: 'query_symbols',
        content: [{ type: 'text', text }], details: result, isError: result.type === 'rejected_document', timestamp: 0 };
      const convert = details => convertResponsesMessages(model,
        { messages: convertToLlm([assistant, { ...tool, details }]) }, new Set(['openai-codex']),
        { includeSystemPrompt: false });
      const providerInput = convert(result);
      const outputs = providerInput.filter(item => item.type === 'function_call_output');
      assert.equal(outputs.length, 1);
      assert.equal(outputs[0].output, text);
      assert.deepEqual(convert({ privateCanonicalMarker: 'SDK_DETAILS_MUST_NOT_REACH_PROVIDER' }), providerInput);
      console.log(JSON.stringify({ fixture: index, owner: packageInfo.name, version: packageInfo.version,
        detail, functionCallOutputs: outputs.length, providerTextBytes: Buffer.byteLength(outputs[0].output),
        providerInputBytes: Buffer.byteLength(JSON.stringify(providerInput)) }));
    }
  }
  console.log(JSON.stringify({ pureInstalledConversion: 'passed', sourceSha256:
    Object.fromEntries([messagesPath, converterPath].map(path =>
      [path, createHash('sha256').update(readFileSync(path)).digest('hex')])) }));
} else {
  console.log(JSON.stringify({ pureInstalledConversion: 'skipped', reason: 'KAST_PI_PACKAGE is absent' }));
}
