import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { SourceTextModule, createContext } from 'node:vm';

// Canonical documents come from the typed production producer or pinned native receipts.
// The expected identities and failure vocabulary below are independent of presentation code.
const captured = JSON.parse(readFileSync(new URL('../../../../experiments/host-observation/pi-fixtures/query-delivery-cases.json', import.meta.url)));
const native = JSON.parse(readFileSync(new URL('../resources/query-presentation-native.json', import.meta.url)));
const measurements = JSON.parse(readFileSync(new URL('../../../docs/query-delivery-baseline.json', import.meta.url)));
const owner = new SourceTextModule(readFileSync(new URL('../../main/js/query-delivery.mjs', import.meta.url), 'utf8') +
  '\nexport { queryPresentation, awaitQueryDelivery, createQueryDeliveryEnvelope };', { context: createContext({ Buffer }) });
await owner.link(() => { throw new Error('Pure delivery presentation must not import effects'); });
await owner.evaluate();
const { queryPresentation, awaitQueryDelivery, createQueryDeliveryEnvelope } = owner.namespace;
const refs = Array.from({ length: 43 }, (_, index) => `exact:v2:fixture-${index}`);
const nativeDocumentHashes = {
  empty: 'c7190ca0d4fd02d841ecd544b4ef5a4bbf4c2d126554b8dac4d890499aa1480f',
  occurrences: '6d7bf4a03ef43ac7f1e4821b5db64391608f1de345c6b8f562dc51c4f7da25e7',
  retainedEvidence: '0206cb66788f2b67f2980c974f628a924c76e398df6ae25263dfdd5bb5cffb73',
};
const expectedFailures = [0, 1].map(index => ({ type: 'exact-reference', ref: `exact:v2:fixture-${index}`, reason: 'compiler-identity-unavailable' }));
const parsed = text => {
  const lines = text.split('\n'), section = heading => {
    const start = lines.findIndex(line => line === heading || heading === 'Rows' && /^Rows \(\d+\):$/.test(line));
    if (start < 0) return [];
    const values = [];
    for (const line of lines.slice(start + 1)) {
      if (!line.startsWith('{')) break;
      values.push(JSON.parse(line));
    }
    return values;
  };
  return { lines, rows: section('Rows'), summary: section('Summary:')[0], details: section('Details:')[0],
    evidence: Object.assign({}, ...section('Evidence:')), shared: Object.assign({}, ...section('Shared descriptions:')) };
};
const expand = (value, shared) => {
  if (Array.isArray(value)) return value.map(item => expand(item, shared));
  if (value && typeof value === 'object') {
    if (Object.hasOwn(value, '$description')) {
      assert.deepEqual(Object.keys(value), ['$description']);
      assert.ok(Object.hasOwn(shared, value.$description), 'Every description reference must resolve');
      return shared[value.$description];
    }
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, expand(item, shared)]));
  }
  return value;
};
const projected = result => {
  const before = JSON.stringify(result), text = queryPresentation(result);
  assert.equal(JSON.stringify(result), before, 'Presentation cannot mutate authoritative results');
  assert.equal(queryPresentation(result, true), before, 'Verbose remains byte-for-byte canonical JSON');
  return { text, ...parsed(text) };
};

for (const [name, entry] of Object.entries(native.cases)) test(`pinned ${name} fixture is an intact canonical document`, () => {
  const digest = createHash('sha256').update(JSON.stringify(entry.reply.document)).digest('hex');
  assert.equal(digest, nativeDocumentHashes[name]);
  assert.equal(entry.documentSha256, nativeDocumentHashes[name]);
});

test('complete projection preserves every exact reference, first authority, coverage and finite failure', () => {
  const result = captured.cases.complete.result, view = projected(result);
  assert.equal(view.lines[0], 'Kast query: complete; delivery: DELIVERED');
  assert.deepEqual(view.rows.map(row => row.ref), refs);
  assert.deepEqual(view.rows.map(row => row.name), Array.from({ length: 43 }, (_, i) => `function${i}`));
  assert.deepEqual(view.evidence.failures, expectedFailures);
  assert.deepEqual(view.summary.coverage, { exhaustive: true });
  assert.equal(view.summary.invocation.accumulated_row_count, 43);
  assert.deepEqual(view.summary.invocation.stop, { type: 'COMPLETED' });
  assert.deepEqual(view.summary.live, { root: '/workspace', host: '00000000-0000-0000-0000-000000000003', epoch: 7, contentView: 'SAVED_PSI_COMMITTED', version: 1 });
  assert.ok(view.text.includes('result:v1:00000000-0000-0000-0000-000000000001'));
  assert.ok(view.text.includes('"verbose":true'));
  assert.equal(view.details.request.type, 'READ_RESULT');
  if (view.details.request.output) assert.deepEqual(view.details.request.output, { type: 'SYMBOLS', fields: ['NAME'] });
  assert.ok(view.rows.every(row => !Object.hasOwn(row, 'location') && !Object.hasOwn(row, 'signature') && !Object.hasOwn(row, 'connections')));
  assert.ok(Buffer.byteLength(view.text) < Buffer.byteLength(JSON.stringify(result)));
});

test('qualified first result cannot become complete after a complete output suffix', () => {
  const result = captured.cases.qualified.result, view = projected(result);
  assert.equal(view.lines[0], 'Kast query: qualified; delivery: DELIVERED');
  assert.deepEqual(view.rows.map(row => row.ref), refs);
  assert.deepEqual(view.summary.qualification, result.initial.document.qualification);
  assert.equal(view.summary.qualification.knownMinimum, 43);
  assert.deepEqual(view.summary.qualification.limitations, ['time-limit-reached']);
  assert.ok(view.text.includes('no retained handle; all available evidence is inline'));
});

test('policy rejection keeps its coverage and failure while duplicate preview is delivered once', () => {
  const result = captured.cases.rejectedProof.result, view = projected(result);
  assert.equal(view.lines[0], 'Kast query: rejected_document; delivery: DELIVERED');
  assert.equal(view.summary.rejection.type, 'COMPLETION_UNPROVEN');
  assert.deepEqual(view.summary.rejection.detail.originalCoverage, result.initial.document.rejection.detail.originalCoverage);
  assert.equal(view.summary.rejection.detail.stop, 'TIME_LIMIT');
  assert.deepEqual(view.rows.map(row => row.ref), refs);
  assert.deepEqual(view.evidence.failures, expectedFailures);
  assert.equal(Object.hasOwn(view.summary.rejection.detail.evidence, 'preview'), false);
  assert.ok(view.text.includes('POLICY_REJECTED_EVIDENCE'));
});

test('undrained rejection preview stays usable without claiming complete output', () => {
  const initial = captured.cases.rejectedProof.result.initial;
  const result = createQueryDeliveryEnvelope(initial, [], { stop: 'CANCELLED', rpc_count: 1,
    request_bytes: 0, response_bytes: Buffer.byteLength(JSON.stringify(initial)),
    result: 'result:v1:00000000-0000-0000-0000-000000000001' });
  const view = projected(result);
  if (view.text.startsWith('{')) {
    // A small canonical rejection may be shorter than the explanatory projection.
    const fallback = JSON.parse(view.text);
    assert.equal(JSON.stringify(fallback), JSON.stringify(result));
    assert.deepEqual(fallback.initial.document.rejection.detail.evidence.preview.map(row => row.ref), ['exact:v2:fixture-0']);
    assert.equal(fallback.delivery.stop, 'CANCELLED');
    return;
  }
  assert.deepEqual(view.rows.map(row => row.ref), ['exact:v2:fixture-0']);
  assert.equal(view.summary.rejection.type, 'COMPLETION_UNPROVEN');
  assert.equal(view.summary.rejection.detail.originalCoverage.knownMinimum, 43);
  assert.ok(view.text.includes('delivery: CANCELLED'));
  assert.ok(view.text.includes('Output is incomplete'));
});

test('empty result preserves exact scoped absence and all evidence without a retained handle', () => {
  const result = native.cases.empty.reply, view = projected(result);
  assert.deepEqual(view.rows, []);
  assert.deepEqual(view.summary.question, result.document.question);
  assert.deepEqual(view.summary.coverage, { exhaustive: true });
  assert.deepEqual(view.evidence.discovery_observations, result.document.discovery_observations);
  assert.ok(view.text.includes('no retained handle; all available evidence is inline'));
});

test('shared descriptions preserve distinct occurrence rows, exact handles and source ranges', () => {
  const reply = native.cases.occurrences.reply;
  const result = createQueryDeliveryEnvelope(reply, [], { stop: 'BYTE_LIMIT', rpc_count: 1,
    request_bytes: 0, response_bytes: Buffer.byteLength(JSON.stringify(reply)), result: reply.document.retention.reference });
  const view = projected(result), rows = view.rows.map(row => expand(row, view.shared));
  assert.equal(rows.length, 20);
  assert.equal(new Set(rows.map(row => row.row_id)).size, 20);
  assert.deepEqual(rows, reply.document.items);
  assert.equal(rows[0].row_id, 'result-row:v1:57a91d5f-0b80-432b-a3c0-96a5f993a922');
  assert.equal(rows[1].row_id, 'result-row:v1:e9e3a4c8-9d0b-4c79-91fe-0c328e55fd25');
  assert.deepEqual(rows[0].occurrence.occurrence.range, { startInclusive: 3542, endExclusive: 3557 });
  assert.deepEqual(rows[1].occurrence.occurrence.range, { startInclusive: 3646, endExclusive: 3661 });
  assert.ok(Object.keys(view.shared).length > 0);
  assert.ok(view.text.includes('Output is incomplete'));
  assert.ok(Buffer.byteLength(view.text) < Buffer.byteLength(JSON.stringify(result)));
});

test('retained observations expose exact available counts and existing READ_RESULT vocabulary', () => {
  const reply = native.cases.retainedEvidence.reply;
  const result = createQueryDeliveryEnvelope(reply, [], { stop: 'BYTE_LIMIT', rpc_count: 1,
    request_bytes: 0, response_bytes: Buffer.byteLength(JSON.stringify(reply)), result: reply.document.retention.reference });
  const view = projected(result);
  assert.equal(view.rows.length, 16);
  assert.equal(view.summary.invocation.accumulated_row_count, 185);
  assert.deepEqual(view.summary.invocation.stop, { type: 'COMPLETED' });
  assert.deepEqual(view.summary.invocation.preview, { type: 'PREFIX', row_count: 16 });
  assert.deepEqual(view.evidence.discovery_observations, { available_entries: 1, detail: 'READ_RESULT' });
  assert.deepEqual(view.evidence.relation_observations, { available_entries: 10, detail: 'READ_RESULT' });
  assert.deepEqual(view.details, { request: { type: 'READ_RESULT', result: reply.document.retention.reference,
    cursor: 0, evidence_cursor: 0, output: { type: 'OCCURRENCES' } }, verbose: true });
  assert.equal(queryPresentation(result, true), JSON.stringify(result));
  assert.ok(view.text.includes('original live authority and may expire or become stale'));
});

for (const [name, stop] of [['expired', 'DELIVERY_UNAVAILABLE'], ['cancelled', 'CANCELLED'],
  ['cancelledAfterPage', 'CANCELLED'], ['deadline', 'TIME_LIMIT'], ['budgetIncrease', 'BUDGET_INCREASE_REQUIRED']]) {
  test(`${name} preserves the first terminal result and honest delivery blocker`, () => {
    const result = captured.cases[name].result, view = projected(result);
    assert.equal(view.lines[0], `Kast query: ${result.initial.type}; delivery: ${stop}`);
    assert.ok(view.text.includes('Output is incomplete'));
    assert.deepEqual(view.summary.coverage, result.initial.document.coverage);
    if (name === 'expired') {
      assert.ok(view.text.includes('"reason":"result-unavailable"'));
      assert.ok(view.text.includes('"next_action":"restart_read"'));
      assert.ok(view.text.includes('Delivery does not retry RUN'));
    }
  });
}

test('oversized first result remains an honest omitted canonical outcome without invented rows', () => {
  const result = captured.cases.byteLimitWithoutInitial.result;
  assert.equal(queryPresentation(result), JSON.stringify(result));
  assert.equal(result.initial, null);
  assert.equal(result.delivery.original_outcome, 'complete');
  assert.equal(result.delivery.stop, 'BYTE_LIMIT');
});

test('existing READ_RESULT detail interface returns canonical failures with zero semantic execution', async () => {
  const replies = captured.cases.complete.result.pages, requests = [];
  const result = await awaitQueryDelivery({ request: { type: 'READ_RESULT',
    result: 'result:v1:00000000-0000-0000-0000-000000000001', cursor: 1, evidence_cursor: 0 }, verbose: true },
  async request => {
    requests.push(request);
    assert.ok(requests.length <= 2, 'Unexpected or excess physical detail request');
    return replies[requests.length - 1];
  }, { callTimeoutMillis: 1000 }, undefined, () => 0);
  assert.equal(requests.length, 2, 'All fixture replies consumed');
  assert.deepEqual(requests.map(value => value.request.type), ['READ_RESULT', 'READ_RESULT']);
  assert.deepEqual(requests.map(value => value.request.cursor), [1, 43]);
  assert.deepEqual(requests.map(value => value.request.evidence_cursor), [0, 1]);
  assert.deepEqual([result.initial, ...result.pages].flatMap(reply => reply.document.failures), expectedFailures);
  assert.equal(queryPresentation(result, true), JSON.stringify(result));
});

test('expired detail handle returns the exact finite rejection without reviving semantics', async () => {
  const unavailable = captured.cases.expired.result.pages[0], requests = [];
  const result = await awaitQueryDelivery({ request: { type: 'READ_RESULT',
    result: 'result:v1:00000000-0000-0000-0000-000000000001', cursor: 0, evidence_cursor: 0 }, verbose: true },
  async request => {
    requests.push(request);
    assert.equal(requests.length, 1, 'An unavailable detail handle cannot trigger recovery execution');
    return unavailable;
  }, { callTimeoutMillis: 1000 }, undefined, () => 0);
  assert.deepEqual(result, unavailable);
  assert.deepEqual(requests.map(request => request.request.type), ['READ_RESULT']);
  assert.equal(result.document.rejection.reason, 'result-unavailable');
  assert.equal(queryPresentation(result), JSON.stringify(unavailable));
});

test('fixed result oracle reproduces saved work and byte measurements without semantic replay', async () => {
  for (const measurement of measurements.cases) {
    const frozen = captured.cases[measurement.name].result;
    const replies = [frozen.initial, ...frozen.pages], requests = [];
    let responseBytes = 0;
    const result = await awaitQueryDelivery({ request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
      output: { type: 'SYMBOLS', fields: ['NAME'] }, executionBudget: { maxResults: 43 } } },
    async (request, policy) => {
      requests.push(request);
      assert.ok(requests.length <= replies.length, 'Unexpected or excess physical request');
      const reply = replies[requests.length - 1], bytes = Buffer.byteLength(JSON.stringify(reply));
      responseBytes += bytes;
      policy.onResponseBytes(bytes);
      return reply;
    }, { callTimeoutMillis: 1000 }, undefined, () => 0);
    assert.equal(requests.length, replies.length, 'All fixture replies consumed');
    assert.equal(JSON.stringify(result), JSON.stringify(frozen));
    const metrics = {
      logicalQueries: 1, physicalRpcs: requests.length,
      semanticExecutions: requests.filter(request => request.request.type === 'RUN').length,
      requestBytes: requests.reduce((bytes, request) => bytes + Buffer.byteLength(JSON.stringify(request)), 0),
      responseBytes, modelVisibleBytes: Buffer.byteLength(queryPresentation(result)),
    };
    assert.deepEqual(metrics, measurement.after);
    assert.deepEqual({ ...metrics, modelVisibleBytes: Buffer.byteLength(JSON.stringify(frozen)) }, measurement.before);
    assert.equal(metrics.semanticExecutions, 1);
    assert.equal(metrics.requestBytes, result.delivery.request_bytes);
    assert.equal(metrics.responseBytes, result.delivery.response_bytes);
  }
});

test('presentation serialization counters expose both saved work and dense dictionary overhead', async () => {
  let calls = 0, bytes = 0, dictionaryCalls = 0, dictionaryBytes = 0;
  const dictionaryObjects = new Set(native.cases.occurrences.reply.document.items.flatMap(row =>
    [row.occurrence.target, row.occurrence.ownership.declaration]));
  const measuredJSON = { parse: JSON.parse, stringify(value, ...arguments_) {
    const encoded = JSON.stringify(value, ...arguments_);
    calls++; bytes += Buffer.byteLength(encoded);
    if (dictionaryObjects.has(value)) { dictionaryCalls++; dictionaryBytes += Buffer.byteLength(encoded); }
    return encoded;
  } };
  const instrumented = new SourceTextModule(readFileSync(new URL('../../main/js/query-delivery.mjs', import.meta.url), 'utf8') +
    '\nexport { queryPresentation };', { context: createContext({ Buffer, JSON: measuredJSON }) });
  await instrumented.link(() => { throw new Error('Presentation cannot import effects'); });
  await instrumented.evaluate();
  const reply = native.cases.occurrences.reply;
  const fixtures = { complete: captured.cases.complete.result,
    occurrencesPrefix: createQueryDeliveryEnvelope(reply, [], { stop: 'BYTE_LIMIT', rpc_count: 1,
      request_bytes: 0, response_bytes: Buffer.byteLength(JSON.stringify(reply)), result: reply.document.retention.reference }) };
  for (const measurement of measurements.presentationSerialization.cases) {
    const result = fixtures[measurement.name];
    calls = bytes = dictionaryCalls = dictionaryBytes = 0;
    assert.equal(instrumented.namespace.queryPresentation(result), queryPresentation(result));
    assert.deepEqual({ calls, bytes }, measurement.after);
    if (measurement.dictionaryKeyConstruction)
      assert.deepEqual({ calls: dictionaryCalls, bytes: dictionaryBytes }, measurement.dictionaryKeyConstruction);
    assert.deepEqual({ calls: 1, bytes: Buffer.byteLength(JSON.stringify(result)) }, measurement.before);
  }
});

test('expired detail authority preserves every already-received observation without semantic replay', async () => {
  const initial = native.cases.retainedEvidence.reply;
  const expired = captured.cases.expired.result.pages[0];
  const replies = [initial, expired], requests = [];
  const result = await awaitQueryDelivery({ request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
    output: { type: 'OCCURRENCES' } } }, async (request, policy) => {
    requests.push(request);
    assert.ok(requests.length <= replies.length, 'Authority failure cannot trigger recovery execution');
    const reply = replies[requests.length - 1];
    policy.onResponseBytes(Buffer.byteLength(JSON.stringify(reply)));
    return reply;
  }, { callTimeoutMillis: 1000 }, undefined, () => 0);
  assert.deepEqual(requests.map(request => request.request.type), ['RUN', 'READ_RESULT']);
  assert.equal(requests[1].request.result, initial.document.retention.reference);
  assert.equal(requests[1].request.cursor, 16);
  assert.equal(requests[1].request.evidence_cursor, 11);
  assert.equal(result.delivery.stop, 'DELIVERY_UNAVAILABLE');
  const view = projected(result);
  assert.equal(view.rows.length, 16);
  assert.deepEqual(view.rows.map(row => expand(row, view.shared)), initial.document.items);
  assert.equal(view.summary.invocation.accumulated_row_count, 185);
  assert.deepEqual(view.summary.coverage, initial.document.coverage);
  assert.deepEqual(view.evidence.discovery_observations, initial.document.discovery_observations);
  assert.deepEqual(view.evidence.relation_observations, initial.document.relation_observations);
  assert.ok(view.text.includes('"reason":"result-unavailable"'));
  assert.ok(view.text.includes('"next_action":"restart_read"'));
  assert.equal(view.details, undefined, 'A known-dead handle cannot be offered as usable detail authority');
  assert.ok(view.text.includes('detail delivery unavailable'));
  assert.equal(view.text.includes('known unavailable'), false);
  assert.equal(JSON.stringify(result.pages[0]), JSON.stringify(expired));
});

// Optional installed SDK boundary, pure conversion only: no session, credentials, provider, or IDEA.
test('Pi provider input contains the exact compact projection and excludes canonical SDK details',
  { skip: !process.env.KAST_PI_PACKAGE }, async () => {
    const { pathToFileURL } = await import('node:url');
    const { join } = await import('node:path');
    const pi = process.env.KAST_PI_PACKAGE;
    const packageInfo = JSON.parse(readFileSync(join(pi, 'package.json')));
    const messagesPath = join(pi, 'dist/core/messages.js');
    const converterPath = join(pi, '../pi-ai/dist/api/openai-responses-shared.js');
    const { convertToLlm } = await import(pathToFileURL(messagesPath));
    const { convertResponsesMessages } = await import(pathToFileURL(converterPath));
    const model = { id: 'gpt-6.1-sol', provider: 'openai-codex', api: 'openai-codex-responses',
      input: ['text', 'image'], reasoning: true, compat: {} };
    const selected = ['complete', 'qualified', 'rejectedProof', 'expired'].map(name =>
      ({ name, result: captured.cases[name].result }));
    for (const name of ['occurrences', 'retainedEvidence']) {
      const reply = native.cases[name].reply;
      selected.push({ name, result: createQueryDeliveryEnvelope(reply, [], { stop: 'BYTE_LIMIT', rpc_count: 1,
        request_bytes: 0, response_bytes: Buffer.byteLength(JSON.stringify(reply)), result: reply.document.retention.reference }) });
    }
    const retainedReply = native.cases.retainedEvidence.reply;
    const unavailableReply = captured.cases.expired.result.pages[0];
    selected.push({ name: 'retainedEvidenceExpired', result: createQueryDeliveryEnvelope(retainedReply, [unavailableReply],
      { stop: 'DELIVERY_UNAVAILABLE', rpc_count: 2, request_bytes: 0,
        response_bytes: Buffer.byteLength(JSON.stringify(retainedReply)) + Buffer.byteLength(JSON.stringify(unavailableReply)),
        result: retainedReply.document.retention.reference }) });
    const observations = [];
    for (const { name, result } of selected) {
      const text = queryPresentation(result);
      const tool = { role: 'toolResult', toolCallId: 'fixture_call', toolName: 'query_symbols',
        content: [{ type: 'text', text }], details: result, isError: result.initial?.type === 'rejected_document', timestamp: 0 };
      const assistant = { role: 'assistant', provider: model.provider, api: model.api, model: model.id,
        content: [{ type: 'toolCall', id: 'fixture_call', name: 'query_symbols', arguments: { request: { type: 'RUN' } } }],
        stopReason: 'toolUse', timestamp: 0 };
      const sdkContext = convertToLlm([assistant, tool]);
      assert.equal(sdkContext[1].details, result, 'Details remain available to the local SDK');
      const convert = messages => convertResponsesMessages(model, { messages }, new Set(['openai-codex']),
        { includeSystemPrompt: false });
      const providerInput = convert(sdkContext);
      const output = providerInput.find(item => item.type === 'function_call_output');
      assert.equal(output.output, text, 'Provider receives every character of the independently verified projection');
      assert.deepEqual(parsed(output.output), parsed(text));
      if (name === 'retainedEvidenceExpired') {
        const view = parsed(output.output);
        assert.deepEqual(view.evidence.discovery_observations, retainedReply.document.discovery_observations);
        assert.deepEqual(view.evidence.relation_observations, retainedReply.document.relation_observations);
        assert.equal(view.details, undefined);
      }
      const alternateDetails = { privateCanonicalMarker: 'SDK_DETAILS_MUST_NOT_REACH_PROVIDER', full: result };
      const changedContext = convertToLlm([assistant, { ...tool, details: alternateDetails }]);
      assert.equal(JSON.stringify(convert(changedContext)), JSON.stringify(providerInput));
      assert.equal(JSON.stringify(providerInput).includes(alternateDetails.privateCanonicalMarker), false);
      observations.push({ name, textBytes: Buffer.byteLength(text), canonicalDetailsBytes: Buffer.byteLength(JSON.stringify(result)),
        serializedProviderInputBytes: Buffer.byteLength(JSON.stringify(providerInput)), detailsExcluded: true });
    }
    console.log(JSON.stringify({ boundary: 'Pure installed Pi SDK and OpenAI Codex provider conversion; no inference',
      package: packageInfo.name, version: packageInfo.version, intermediateDetailsRetained: true,
      sourceSha256: Object.fromEntries([messagesPath, converterPath].map(path =>
        [path, createHash('sha256').update(readFileSync(path)).digest('hex')])), observations }));
  });


test('a transient thrown detail read preserves received evidence without asserting handle invalidation', async () => {
  const initial = native.cases.retainedEvidence.reply, requests = [];
  const result = await awaitQueryDelivery({ request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
    output: { type: 'OCCURRENCES' } } }, async (request, policy) => {
    requests.push(request);
    if (requests.length === 1) {
      policy.onResponseBytes(Buffer.byteLength(JSON.stringify(initial)));
      return initial;
    }
    assert.equal(requests.length, 2, 'A lost detail read cannot retry RUN');
    throw new Error('fixture transient transport loss');
  }, { callTimeoutMillis: 1000 }, undefined, () => 0);
  assert.deepEqual(requests.map(request => request.request.type), ['RUN', 'READ_RESULT']);
  assert.equal(result.delivery.stop, 'DELIVERY_UNAVAILABLE');
  assert.deepEqual(result.pages, []);
  const view = projected(result);
  assert.deepEqual(view.evidence.discovery_observations, initial.document.discovery_observations);
  assert.deepEqual(view.evidence.relation_observations, initial.document.relation_observations);
  assert.equal(view.summary.invocation.accumulated_row_count, 185);
  assert.deepEqual(view.summary.coverage, initial.document.coverage);
  assert.equal(view.details, undefined);
  assert.ok(view.text.includes('detail delivery unavailable'));
  assert.equal(view.text.includes('known unavailable'), false);
  assert.equal(view.text.includes('fixture transient transport loss'), false);
});
