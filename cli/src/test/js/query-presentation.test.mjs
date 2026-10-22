import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { SourceTextModule, createContext } from 'node:vm';

// Canonical documents come from the typed production producer or pinned native receipts.
// The expected identities and failure vocabulary below are independent of presentation code.
const captured = JSON.parse(readFileSync(new URL('../../../../experiments/host-observation/pi-fixtures/query-delivery-cases.json', import.meta.url)));
const native = JSON.parse(readFileSync(new URL('../resources/query-presentation-native.json', import.meta.url)));
// Captured by RetainedGraphDeliveryMeasurementTest from typed production serializers at the 20,000-byte wire cap.
const graphPages = JSON.parse(readFileSync(new URL('../resources/query-compact-graph-pages.json', import.meta.url)));
const symbolGraphPages = JSON.parse(readFileSync(new URL('../resources/query-compact-symbol-graph-pages.json', import.meta.url)));
const measurements = JSON.parse(readFileSync(new URL('../../../docs/query-delivery-baseline.json', import.meta.url)));
const owner = new SourceTextModule(readFileSync(new URL('../../main/js/query-delivery.mjs', import.meta.url), 'utf8') +
  '\nexport { queryPresentation, awaitQueryDelivery, createQueryDeliveryEnvelope };', { context: createContext({ Buffer }) });
await owner.link(() => { throw new Error('Pure delivery presentation must not import effects'); });
await owner.evaluate();
const { queryPresentation, awaitQueryDelivery, createQueryDeliveryEnvelope } = owner.namespace;
for (const executionMode of [undefined, null, 'RUNNER_CONTROLLED', 'BOUNDED'])
test(`RUN executionMode=${executionMode} selects the normalized transport lifetime`, async () => {
  let time = 0;
  const modes = [];
  const initial = native.cases.retainedEvidence.reply;
  const result = await awaitQueryDelivery({ request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
    terminalStep: { type: 'EMIT' }, ...(executionMode === undefined ? {} : { executionMode }) } },
    async (request, policy) => {
      modes.push(policy.executionMode); time = 10000;
      return request.request.type === 'RUN' ? initial : native.cases.empty.reply;
    },
    { callTimeoutMillis: 100 }, undefined, () => time);
  assert.equal(modes[0], executionMode === 'BOUNDED' ? undefined : 'RUNNER_CONTROLLED');
  assert.equal(modes.length, executionMode === 'BOUNDED' ? 1 : 2);
  if (modes.length === 2) assert.equal(modes[1], 'RUNNER_CONTROLLED');
  assert.equal(result.delivery.stop === 'TIME_LIMIT', executionMode === 'BOUNDED');
});
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
  return { lines, rows: section('Rows'), overview: section('Overview:')[0], summary: section('Summary:')[0], details: section('Details:')[0],
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
// The allowed default omission is a signature contract. All other pinned fields remain exact.
const withoutSignatures = rows => JSON.parse(JSON.stringify(rows, (key, value) => key === 'signature' ? undefined : value));
const projected = result => {
  const before = JSON.stringify(result), text = queryPresentation(result);
  assert.equal(JSON.stringify(result), before, 'Presentation cannot mutate authoritative results');
  assert.equal(queryPresentation(result, true), before, 'Verbose remains byte-for-byte canonical JSON');
  return { text, ...parsed(text) };
};

test('overview distinguishes received counts from the accumulated total before contract details', () => {
  const reply = native.cases.retainedEvidence.reply;
  const result = createQueryDeliveryEnvelope(reply, [], { stop: 'BYTE_LIMIT', rpc_count: 1,
    request_bytes: 0, response_bytes: Buffer.byteLength(JSON.stringify(reply)), result: reply.document.retention.reference });
  const view = projected(result);
  assert.equal(view.lines[1], 'Overview:');
  assert.deepEqual(view.overview.rows, { received: 16, accumulated: 185 });
  assert.deepEqual(view.overview.relations, [{ relation: 'references', received_connections: 16 }]);
  assert.equal(view.overview.files.reduce((total, entry) => total + entry.received_rows, 0), 16);
  assert.equal(view.overview.rows_without_file, 0);
  assert.deepEqual(view.overview.coverage, { exhaustive: true });
  assert.equal(view.overview.completion.type, 'COMPLETE_ONLY');
  assert.equal(view.overview.blockers.delivery, 'BYTE_LIMIT');
  assert.ok(view.lines.indexOf('Overview:') < view.lines.indexOf('Summary:'));
});

test('overview preserves rejection and unknown file counts rather than inferring absence', () => {
  const result = captured.cases.rejectedProof.result;
  const view = projected(result);
  assert.deepEqual(view.overview.rows, { received: 43 });
  assert.deepEqual(view.overview.files, []);
  assert.equal(view.overview.rows_without_file, 43);
  assert.deepEqual(view.overview.relations, []);
  assert.equal(view.overview.rows_without_relation, 43);
  assert.deepEqual(view.overview.coverage, result.initial.document.rejection.detail.originalCoverage);
  assert.equal(view.overview.blockers.rejection.type, 'COMPLETION_UNPROVEN');
  assert.deepEqual(view.overview.blockers.rejection.cause, { type: 'INCOMPLETE_EXECUTION' });
  assert.equal(view.overview.blockers.failures, 2);
});

test('failed refinement advice keeps the exact location and reason without issuing source authority', () => {
  for (const [reason, next_action] of [
    ['workspace-not-ready', 'wait_for_workspace'],
    ['workspace-root-mismatch', 'reacquire_authority'],
    ['stale-generation', 'reacquire_authority'],
    ['stale-location', 'reacquire_authority'],
    ['declaration-moved-or-changed', 'reacquire_authority'],
    ['scope-rejected', 'correct_request'],
    ['outside-scope', 'correct_request'],
    ['ambiguous-declaration', 'correct_request'],
    ['unsupported-declaration', 'correct_request'],
    ['workspace-index-unavailable', 'report_failure'],
    ['compiler-identity-unavailable', 'report_failure'],
    ['compiler-contract-violation', 'report_failure'],
  ]) {
    const result = structuredClone(captured.cases.rejectedProof.result);
    const failure = { type: 'refinement', location: { file: '/workspace/Search.kt', offset: 1217 }, reason };
    result.pages[0].document.failures[0] = failure;
    const view = projected(result);
    assert.deepEqual(view.overview.blockers.refinement_recovery, [{
      location: failure.location, reason, read_source_eligibility: 'NOT_ISSUED', next_action,
    }]);
    assert.deepEqual(view.evidence.failures[0], failure);
    assert.equal(view.lines[0], 'Kast query: rejected_document; delivery: DELIVERED');
    assert.equal(view.overview.completion.type, 'COMPLETE_ONLY');
    assert.deepEqual(view.summary.rejection, projected(captured.cases.rejectedProof.result).summary.rejection);
    assert.equal(view.text.includes('"type":"READ_SOURCE"'), false);
    assert.equal(view.text.includes('"candidateSelector"'), false);
    assert.equal(view.text.includes('"expected_epoch"'), false);
    assert.equal(view.text.includes('"observed_epoch"'), false);
    assert.ok(view.text.includes('Recovery advice does not issue or retry a query.'));
  }
});

test('refinement recovery consumes received failures without semantic retry or changing exact-reference failures', async () => {
  const original = captured.cases.rejectedProof.result;
  const replies = structuredClone([original.initial, ...original.pages]);
  const failures = [
    { type: 'refinement', location: { file: '/workspace/Compiler.kt', offset: 3 }, reason: 'compiler-identity-unavailable' },
    { type: 'refinement', location: { file: '/workspace/Moved.kt', offset: 19 }, reason: 'stale-location' },
  ];
  replies[1].document.failures[0] = failures[0];
  replies[2].document.failures[0] = failures[1];
  const requests = [];
  const result = await awaitQueryDelivery({ request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
    terminalStep: { type: 'EMIT' } } }, async request => {
    requests.push(request);
    assert.ok(requests.length <= replies.length, 'Recovery advice cannot cause another request');
    return replies[requests.length - 1];
  }, { callTimeoutMillis: 1000 }, undefined, () => 0);
  assert.deepEqual(requests.map(request => request.request.type), ['RUN', 'READ_RESULT', 'READ_RESULT']);
  const view = projected(result);
  assert.deepEqual(view.evidence.failures, failures);
  assert.deepEqual(view.overview.blockers.refinement_recovery.map(entry => entry.location), failures.map(failure => failure.location));
  assert.equal(projected(original).overview.blockers.refinement_recovery, undefined,
    'An exact-reference failure does not prove that its selector was never issued');
});

test('an unknown refinement reason preserves the canonical result instead of inventing recovery advice', () => {
  const result = structuredClone(captured.cases.rejectedProof.result);
  result.pages[0].document.failures[0] = { type: 'refinement',
    location: { file: '/workspace/Search.kt', offset: 1217 }, reason: 'future-unknown' };
  assert.equal(queryPresentation(result), JSON.stringify(result));
});

test('retained default descriptions omit signature contracts and keep exact identity and qualifiers', () => {
  const reply = native.cases.occurrences.reply;
  const result = createQueryDeliveryEnvelope(reply, [], { stop: 'BYTE_LIMIT', rpc_count: 1,
    request_bytes: 0, response_bytes: Buffer.byteLength(JSON.stringify(reply)), result: reply.document.retention.reference });
  const view = projected(result), row = expand(view.rows[0], view.shared), original = reply.document.items[0];
  assert.equal(Object.hasOwn(row.occurrence.target.compilerEvidence, 'signature'), false);
  assert.equal(row.occurrence.target.selector, original.occurrence.target.selector);
  assert.equal(row.occurrence.target.compilerEvidence.identity, original.occurrence.target.compilerEvidence.identity);
  assert.deepEqual(row.occurrence.occurrence, original.occurrence.occurrence);
  assert.equal(row.occurrence.coverage, 'exact-compiler-confirmed');
  assert.equal(view.details.request.type, 'READ_RESULT');
  assert.equal(view.details.verbose, true);
  assert.deepEqual(expand(parsed(queryPresentation(result, false, true)).rows[0],
    parsed(queryPresentation(result, false, true)).shared), original);
});

test('missing, stale and transport-lost detail authority keep received signature contracts inline', () => {
  const reply = structuredClone(native.cases.occurrences.reply);
  const originalRows = structuredClone(reply.document.items);
  delete reply.document.retention;
  const inline = projected(reply);
  assert.deepEqual(inline.rows.map(row => expand(row, inline.shared)), originalRows);
  for (const [stop, pages] of [
    ['DELIVERY_UNAVAILABLE', []],
    ['DELIVERY_UNAVAILABLE', captured.cases.expired.result.pages],
  ]) {
    const first = native.cases.occurrences.reply;
    const result = createQueryDeliveryEnvelope(first, pages, { stop, rpc_count: pages.length + 2,
      request_bytes: 0, response_bytes: 1, result: first.document.retention.reference });
    const view = projected(result);
    assert.deepEqual(view.rows.map(row => expand(row, view.shared)), originalRows);
  }
});

test('full symbol connections keep orientation, occurrence and compiler identity beside a detail request', () => {
  const reply = structuredClone(captured.cases.complete.result.initial);
  const occurrence = native.cases.occurrences.reply.document.items[0].occurrence;
  const connection = { meaning: occurrence.meaning, source: occurrence.ownership.declaration,
    target: occurrence.target, occurrence: occurrence.occurrence,
    provenance: occurrence.provenance, coverage: occurrence.coverage };
  reply.document.items[0].connections = [connection];
  reply.document.items[0].signature = occurrence.ownership.declaration.compilerEvidence.signature;
  reply.document.items[0].source = { text: 'fun requestedSource() = Unit', lines: { startInclusive: 1, endInclusive: 1 } };
  reply.document.question.output.fields = ['name', 'signature', 'source'];
  const view = projected(reply);
  assert.deepEqual(view.rows[0].signature, occurrence.ownership.declaration.compilerEvidence.signature);
  assert.deepEqual(view.rows[0].source, reply.document.items[0].source);
  assert.deepEqual(view.details.request.output.fields, ['NAME', 'SIGNATURE', 'SOURCE']);
  assert.deepEqual(view.rows[0].connections[0], withoutSignatures(connection));
  assert.deepEqual(view.overview.relations, [{ relation: 'references', received_connections: 1 }]);
  assert.equal(view.details.request.type, 'READ_RESULT');
  delete reply.document.retention;
  const inline = projected(reply);
  assert.deepEqual(inline.rows[0].connections, [connection]);
  assert.deepEqual(inline.rows[0].signature, occurrence.ownership.declaration.compilerEvidence.signature);
});

test('compact retained graph pages preserve evidence progress and deliver all 53 occurrences', async () => {
  const requests = [];
  const replies = graphPages.map(({ document }) => ({ ...structuredClone(native.cases.occurrences.reply), document }));
  const result = await awaitQueryDelivery({ request: { type: 'READ_RESULT',
    result: replies[0].document.retention.reference, cursor: 0, evidence_cursor: 0,
    output: { type: 'OCCURRENCES' } } }, async (request, policy) => {
    requests.push(request);
    assert.ok(requests.length <= replies.length, 'Delivery cannot invoke semantics or invent another page');
    const reply = replies[requests.length - 1];
    policy.onResponseBytes(Buffer.byteLength(JSON.stringify(reply)));
    return reply;
  }, { callTimeoutMillis: 1000 }, undefined, () => 0);
  assert.equal(result.delivery.stop, 'DELIVERED');
  assert.equal(requests.length, 27);
  assert.ok(requests.every(request => request.request.type === 'READ_RESULT'));
  const documents = [result.initial, ...result.pages].map(reply => reply.document);
  assert.equal(documents.flatMap(document => document.items).length, 53);
  assert.equal(new Set(documents.flatMap(document => document.items.map(item => item.row_id))).size, 53);
  assert.equal(documents.reduce((sum, document) => sum + document.relation_graph.reference_observations.length, 0), 53);
  assert.ok(Buffer.byteLength(JSON.stringify(result)) <= 262144);
  for (const document of documents) {
    const graph = document.relation_graph;
    const witnesses = new Set(graph.witnesses.map(witness => witness.id));
    assert.ok(document.items.every(item => witnesses.has(item.witness)));
    assert.equal(graph.basis, document.retention.reference);
    assert.equal(graph.detail.request.type, 'READ_RESULT');
    assert.equal(document.coverage.exhaustive, true);
  }
  const rendered = projected(result);
  assert.deepEqual(rendered.overview.relations, [{ relation: 'references', received_connections: 53 }]);
  assert.deepEqual(rendered.overview.files, [{ file: '/workspace/Subject.kt', received_rows: 53 }]);
  const evidenceLine = rendered.lines[rendered.lines.indexOf('Evidence delivery:') + 1];
  assert.equal(JSON.parse(evidenceLine).delivered_entries, 53);
  const graphLine = rendered.lines[rendered.lines.indexOf('Relation graph:') + 1];
  const receivedGraph = JSON.parse(graphLine);
  assert.equal(receivedGraph.basis, documents[0].retention.reference);
  assert.equal(receivedGraph.nodes.length, 3);
  assert.equal(receivedGraph.witnesses.length, 53);
  assert.equal(receivedGraph.reference_observations.length, 53);
  assert.ok(rendered.rows.every(row => receivedGraph.witnesses.some(witness => witness.id === row.witness)));
  assert.equal(rendered.summary.coverage.exhaustive, true);
  assert.ok(Buffer.byteLength(rendered.text) < Buffer.byteLength(JSON.stringify(result)));
});

test('graph text keeps received witnesses when later delivery loses transport', () => {
  const replies = graphPages.slice(0, 2).map(({ document }) =>
    ({ ...structuredClone(native.cases.occurrences.reply), document: structuredClone(document) }));
  const result = createQueryDeliveryEnvelope(replies[0], replies.slice(1), { stop: 'DELIVERY_UNAVAILABLE',
    rpc_count: 3, request_bytes: 30, response_bytes: 100, result: replies[0].document.retention.reference });
  const rendered = projected(result);
  const graph = JSON.parse(rendered.lines[rendered.lines.indexOf('Relation graph:') + 1]);
  assert.equal(graph.witnesses.length, 4);
  assert.equal(rendered.rows.length, 4);
  assert.equal(rendered.summary.coverage.exhaustive, true);
  assert.ok(rendered.lines.some(line => line.includes('delivery: DELIVERY_UNAVAILABLE')));
  assert.equal(rendered.details.verbose, true);
});

test('minimal SYMBOLS delivers all 53 exact rows under the unchanged aggregate allowance', async () => {
  const replies = structuredClone(symbolGraphPages);
  const requests = [];
  const result = await awaitQueryDelivery({ request: { type: 'READ_RESULT',
    result: replies[0].document.retention.reference, cursor: 0, evidence_cursor: 0,
    output: { type: 'SYMBOLS', fields: [] } } }, async (request, policy) => {
    requests.push(request);
    assert.ok(requests.length <= replies.length, 'Delivery only consumes issued retained pages');
    const reply = replies[requests.length - 1];
    policy.onResponseBytes(Buffer.byteLength(JSON.stringify(reply)));
    return reply;
  }, { callTimeoutMillis: 1000 }, undefined, () => 0);
  assert.equal(result.delivery.stop, 'DELIVERED');
  assert.equal(requests.length, 27);
  assert.ok(requests.every(request => request.request.type === 'READ_RESULT'));
  assert.ok(requests.every(request => request.request.output.type === 'SYMBOLS' && request.request.output.fields.length === 0));
  const documents = [result.initial, ...result.pages].map(reply => reply.document);
  assert.equal(documents.flatMap(document => document.items).length, 53);
  assert.equal(new Set(documents.flatMap(document => document.items.map(item => item.row_id))).size, 53);
  assert.ok(Buffer.byteLength(JSON.stringify(result)) <= 262144 - 16384);
  for (const document of documents) {
    const graph = document.relation_graph;
    const nodes = new Set(graph.nodes.map(node => node.id));
    const witnesses = new Map(graph.witnesses.map(witness => [witness.id, witness]));
    const connections = new Map(graph.connection_witnesses.map(connection => [connection.id, connection]));
    for (const row of document.items) {
      assert.equal(row.type, 'exact-symbol-node');
      assert.ok(nodes.has(row.node));
      assert.equal(row.connections.length, 1);
      const connection = connections.get(row.connections[0]);
      assert.equal(connection.type, 'reference-backed');
      assert.equal(witnesses.get(connection.witness).ownership.type, 'declaration-owned');
    }
    assert.deepEqual(graph.detail.request.output, { type: 'SYMBOLS', fields: [] });
    assert.equal(graph.detail.request.result, graph.basis);
    assert.equal(graph.detail.verbose, true);
    assert.equal(document.coverage.exhaustive, true);
  }
  const rendered = projected(result);
  const graph = JSON.parse(rendered.lines[rendered.lines.indexOf('Relation graph:') + 1]);
  assert.equal(graph.nodes.length, 3);
  assert.equal(graph.witnesses.length, 53);
  assert.equal(graph.connection_witnesses.length, 53);
  assert.equal(rendered.rows.length, 53);
  assert.deepEqual(rendered.overview.relations, [{ relation: 'references', received_connections: 53 }]);
  assert.deepEqual(rendered.overview.files, [{ file: '/workspace/Subject.kt', received_rows: 53 }]);
  assert.equal(rendered.summary.coverage.exhaustive, true);
  assert.deepEqual(rendered.details.request.output, { type: 'SYMBOLS', fields: [] });
  assert.ok(Buffer.byteLength(rendered.text) < Buffer.byteLength(JSON.stringify(result)));
  console.log('D3 client SYMBOLS logical_calls=' + requests.length + ' response_bytes=' + result.delivery.response_bytes +
    ' request_bytes=' + result.delivery.request_bytes + ' envelope_bytes=' + Buffer.byteLength(JSON.stringify(result)));
});

for (const type of ['EMIT', 'TRACE']) test(`${type} retains explicit identity-only projection on output pages`, async () => {
  const requests = [], replies = structuredClone(symbolGraphPages);
  const result = await awaitQueryDelivery({ request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
    terminalStep: { type, output: { type: 'SYMBOLS', fields: [] } } } }, async (request) => {
    requests.push(request);
    assert.ok(requests.length <= replies.length, 'No extra semantic or presentation call');
    return replies[requests.length - 1];
  }, { callTimeoutMillis: 1000 }, undefined, () => 0);
  assert.equal(result.delivery.stop, 'DELIVERED');
  assert.equal(requests.length, replies.length);
  assert.equal(requests.filter(request => request.request.type === 'RUN').length, 1);
  for (const request of requests.slice(1)) {
    assert.equal(request.request.type, 'READ_RESULT');
    assert.equal(JSON.stringify(request.request.output), JSON.stringify({ type: 'SYMBOLS', fields: [] }));
    assert.equal(Object.hasOwn(request.request, 'terminalStep'), false);
  }
});

test('symbol text rejects unresolved connections and unknown connection variants', () => {
  for (const change of [
    document => { document.items[0].connections[0] = 'connection:v1:' + '0'.repeat(64); },
    document => { document.relation_graph.connection_witnesses[0].type = 'name-backed'; },
    document => { document.relation_graph.connection_witnesses[0].witness = 'witness:v1:' + '0'.repeat(64); },
  ]) {
    const reply = structuredClone(symbolGraphPages[0]);
    change(reply.document);
    const result = createQueryDeliveryEnvelope(reply, [], { stop: 'BYTE_LIMIT', rpc_count: 1,
      request_bytes: 1, response_bytes: Buffer.byteLength(JSON.stringify(reply)), result: reply.document.retention.reference });
    assert.equal(queryPresentation(result), JSON.stringify(result));
  }
});

test('conflicting received graph descriptions preserve the canonical output', () => {
  const replies = graphPages.slice(0, 2).map(({ document }) =>
    ({ ...structuredClone(native.cases.occurrences.reply), document: structuredClone(document) }));
  replies[1].document.relation_graph.nodes[0].symbol.name = 'different';
  const result = createQueryDeliveryEnvelope(replies[0], replies.slice(1), { stop: 'BYTE_LIMIT',
    rpc_count: 2, request_bytes: 20, response_bytes: 100, result: replies[0].document.retention.reference });
  assert.equal(queryPresentation(result), JSON.stringify(result));
});

test('compact graph delivery rejects missing evidence and a different retained basis', async () => {
  for (const [expected, change] of [
    ['MALFORMED_PAGE', document => document.relation_graph.reference_observations.pop()],
    ['IDENTITY_MISMATCH', document => { document.relation_graph.basis = 'result:v1:00000000-0000-0000-0000-000000000099'; }],
  ]) {
    const replies = graphPages.slice(0, 2).map(({ document }) =>
      ({ ...structuredClone(native.cases.occurrences.reply), document: structuredClone(document) }));
    change(replies[1].document);
    let calls = 0;
    const result = await awaitQueryDelivery({ request: { type: 'READ_RESULT',
      result: replies[0].document.retention.reference, cursor: 0, evidence_cursor: 0,
      output: { type: 'OCCURRENCES' } } }, async () => {
      assert.ok(calls < replies.length, 'A rejected page must stop before another call');
      return replies[calls++];
    }, { callTimeoutMillis: 1000 }, undefined, () => 0);
    assert.equal(result.delivery.stop, expected);
    assert.equal(calls, 2);
    assert.equal(result.initial.document.coverage.exhaustive, true);
    assert.equal(result.initial.document.items.length, 2);
  }
});

test('explicit final view retains all received observation evidence inline without a live handle dependency',()=>{
  for(const name of ['retainedEvidence','occurrences']) {
    const reply=native.cases[name].reply;
    const result=createQueryDeliveryEnvelope(reply,[],{stop:'BYTE_LIMIT',rpc_count:1,request_bytes:0,
      response_bytes:Buffer.byteLength(JSON.stringify(reply)),result:reply.document.retention.reference});
    const before=JSON.stringify(result),text=queryPresentation(result,false,true),view=parsed(text);
    assert.equal(JSON.stringify(result),before);
    assert.equal(queryPresentation(result,true,true),before,'Explicit final view never changes verbose serialization');
    for(const field of ['failures','omissions','walk_observations','reference_observations','discovery_observations','relation_observations'])
      if(reply.document[field]?.length)assert.deepEqual(view.evidence[field],reply.document[field]);
    assert.deepEqual(view.rows.map(row=>expand(row,view.shared)),reply.document.items);
    assert.equal(text.includes('"available_entries"'),false);
    assert.equal(text.includes('"request":{"type":"READ_RESULT"'),false);
    assert.ok(text.includes(reply.document.retention.reference));
    assert.ok(text.includes('current usability is unverified'));
    assert.equal(view.details,undefined);
  }
});

test('explicit final view preserves a known unavailable handle and its received failure',()=>{
  const result=captured.cases.expired.result,before=JSON.stringify(result),text=queryPresentation(result,false,true);
  assert.equal(JSON.stringify(result),before);
  assert.ok(text.includes('"reason":"result-unavailable"'));
  assert.ok(text.includes('canonical detail authority was rejected; unavailable'));
  assert.equal(text.includes('current usability is unverified'),false);
  assert.equal(text.includes('"request":{"type":"READ_RESULT"'),false);
});

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
  assert.deepEqual(rows, withoutSignatures(reply.document.items));
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

test('fixed result oracle preserves evidence and accounts for terminal request bytes without semantic replay', async () => {
  // Only the fresh RUN shape changes. Preserve the historical capture and its measured evidence.
  const terminalBytes = Buffer.byteLength(JSON.stringify({ terminalStep: { type: 'EMIT', output: null } }))
    - Buffer.byteLength(JSON.stringify({ output: null }));
  for (const measurement of measurements.cases) {
    const frozen = captured.cases[measurement.name].result;
    const replies = [frozen.initial, ...frozen.pages], requests = [];
    let responseBytes = 0;
    const result = await awaitQueryDelivery({ request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
      terminalStep: { type: 'EMIT', output: { type: 'SYMBOLS', fields: ['NAME'] } }, executionBudget: { maxResults: 43 } } },
    async (request, policy) => {
      requests.push(request);
      assert.ok(requests.length <= replies.length, 'Unexpected or excess physical request');
      const reply = replies[requests.length - 1], bytes = Buffer.byteLength(JSON.stringify(reply));
      responseBytes += bytes;
      policy.onResponseBytes(bytes);
      return reply;
    }, { callTimeoutMillis: 1000 }, undefined, () => 0);
    assert.equal(requests.length, replies.length, 'All fixture replies consumed');
    const expected = structuredClone(frozen);
    expected.delivery.request_bytes += terminalBytes;
    assert.equal(JSON.stringify(result), JSON.stringify(expected));
    const metrics = {
      logicalQueries: 1, physicalRpcs: requests.length,
      semanticExecutions: requests.filter(request => request.request.type === 'RUN').length,
      requestBytes: requests.reduce((bytes, request) => bytes + Buffer.byteLength(JSON.stringify(request)), 0),
      responseBytes, modelVisibleBytes: Buffer.byteLength(queryPresentation(result)),
    };
    assert.deepEqual(metrics, { ...measurement.after,
      requestBytes: measurement.after.requestBytes + terminalBytes,
      modelVisibleBytes: measurements.summaryFollowUp.cases[measurement.name].modelVisibleBytes });
    assert.deepEqual({ ...metrics, modelVisibleBytes: Buffer.byteLength(JSON.stringify(frozen)) },
      { ...measurement.before, requestBytes: measurement.before.requestBytes + terminalBytes });
    assert.equal(metrics.semanticExecutions, 1);
    assert.equal(metrics.requestBytes, result.delivery.request_bytes);
    assert.equal(metrics.responseBytes, result.delivery.response_bytes);
  }
});

test('presentation serialization avoids full declaration keys while the summary preserves exact evidence', async () => {
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
    if (measurement.name === 'occurrencesPrefix')
      assert.equal(createHash('sha256').update(queryPresentation(result)).digest('hex'),
        measurements.summaryFollowUp.cases.occurrencesPrefix.textSha256,
        'The fixed compact presentation remains reproducible');
    assert.deepEqual({ calls, bytes }, measurements.summaryFollowUp.cases[measurement.name].serialization);
    if (measurement.dictionaryKeyConstruction)
      assert.deepEqual({ calls: dictionaryCalls, bytes: dictionaryBytes }, measurement.dictionaryKeyConstruction);
    assert.deepEqual({ calls: 1, bytes: Buffer.byteLength(JSON.stringify(result)) }, measurement.before);
  }
});

test('shared declaration identity is only a candidate bucket, never proof of equal descriptions', () => {
  const initial = structuredClone(native.cases.occurrences.reply);
  const original = initial.document.items[0];
  const variants = [
    target => { target.name += 'Different'; },
    target => { target.range.endExclusive++; },
    target => { target.compilerEvidence.identity += '-different'; },
    target => { target.compilerEvidence.signature.qualifiedIdentity += '.Different'; },
    target => { target.file += '.other.kt'; },
    target => { const name = target.name; delete target.name; target.name = name; },
  ];
  const rows = [original, structuredClone(original)];
  for (const change of variants) {
    const row = structuredClone(original);
    change(row.occurrence.target);
    rows.push(row, structuredClone(row));
  }
  rows.forEach((row, index) => { row.row_id = `result-row:v1:fixture-${index}`; });
  initial.document.items = rows;
  const view = projected(initial);
  assert.equal(view.rows.length, rows.length, 'Distinct occurrence rows are never deduplicated');
  assert.deepEqual(view.rows.map(row => expand(row, view.shared)), withoutSignatures(rows));
  assert.equal(Object.keys(view.shared).length, variants.length + 2,
    'Every different target shape/order and the shared owner have separate exact descriptions');
  assert.equal(new Set(view.rows.map(row => row.row_id)).size, rows.length);
});

test('smaller description-key work cannot buy extra canonical envelope drainage', async () => {
  // Synthetic admitted-provider pages reuse pinned declaration shapes; this is not a native capacity claim.
  const source = native.cases.occurrences.reply;
  const oracle = Array.from({ length: 185 }, (_, index) => {
    const row = structuredClone(source.document.items[index % 20]);
    row.row_id = `result-row:v1:00000000-0000-0000-0000-${String(index).padStart(12, '0')}`;
    return row;
  });
  const replies = [];
  for (let start = 0; start < oracle.length; start += 20) {
    const reply = structuredClone(source), end = Math.min(start + 20, oracle.length);
    reply.document.items = oracle.slice(start, end);
    reply.document.evidence_window = { type: 'FINAL', start: 0, end: 0, total: 0 };
    if (end < oracle.length) reply.document.next_cursor = end;
    else delete reply.document.next_cursor;
    if (start === 0) reply.document.invocation = {
      accumulated_row_count: oracle.length, stop: { type: 'COMPLETED' },
      preview: { type: 'PREFIX', row_count: 20 },
    };
    replies.push(reply);
  }
  const requests = [];
  let readBytes = 0;
  const result = await awaitQueryDelivery({ request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
    terminalStep: { type: 'EMIT', output: { type: 'OCCURRENCES' } } } }, async (request, policy) => {
    requests.push(request);
    assert.ok(requests.length <= replies.length, 'No unexpected semantic or output work');
    const reply = replies[requests.length - 1], bytes = Buffer.byteLength(JSON.stringify(reply));
    readBytes += bytes; policy.onResponseBytes(bytes);
    return reply;
  }, { callTimeoutMillis: 1000 }, undefined, () => 0);
  assert.equal(result.delivery.stop, 'BYTE_LIMIT');
  assert.equal(result.delivery.rpc_count, 7);
  assert.deepEqual(requests.slice(1).map(request => request.request.cursor), [20, 40, 60, 80, 100, 120]);
  assert.equal(requests.filter(request => request.request.type === 'RUN').length, 1);
  assert.equal(result.delivery.response_bytes, readBytes, 'Read-but-excluded page bytes remain accounted');
  assert.equal(result.delivery.rpc_count, result.pages.length + 2, 'One received page did not fit');
  const view = projected(result), received = [result.initial, ...result.pages].flatMap(reply => reply.document.items);
  assert.equal(received.length, 120);
  assert.deepEqual(received, oracle.slice(0, received.length));
  assert.deepEqual(view.rows.map(row => expand(row, view.shared)), withoutSignatures(received));
  assert.equal(view.summary.invocation.accumulated_row_count, oracle.length);
  assert.equal(view.summary.invocation.stop.type, 'COMPLETED');
  assert.ok(received.length < oracle.length);
  assert.ok(view.text.includes('Output is incomplete'));
  assert.equal(view.details.request.result, source.document.retention.reference);
  assert.equal(queryPresentation(result, true), JSON.stringify(result));
  assert.ok(Buffer.byteLength(JSON.stringify(result)) <= 262144);
  assert.equal(Buffer.byteLength(JSON.stringify(result)), 239645);
  assert.equal(Buffer.byteLength(view.text), measurements.summaryFollowUp.synthetic185.visibleBytes);
  assert.equal(readBytes, 279333);
  console.log(JSON.stringify({ boundary: 'Synthetic pure canonical-envelope drainage',
    rpcCount: result.delivery.rpc_count, includedPages: result.pages.length + 1, rows: received.length,
    originalRows: oracle.length, canonicalBytes: Buffer.byteLength(JSON.stringify(result)),
    visibleBytes: Buffer.byteLength(view.text), readBytes, semanticExecutions: 1, semanticReruns: 0 }));
});

test('expired detail authority preserves every already-received observation without semantic replay', async () => {
  const initial = native.cases.retainedEvidence.reply;
  const expired = captured.cases.expired.result.pages[0];
  const replies = [initial, expired], requests = [];
  const result = await awaitQueryDelivery({ request: { type: 'RUN', source: { type: 'ALL_DECLARATIONS' },
    terminalStep: { type: 'EMIT', output: { type: 'OCCURRENCES' } } } }, async (request, policy) => {
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
  assert.ok(view.text.includes('canonical retained detail authority unavailable'));
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
        content: [{ type: 'toolCall', id: 'fixture_call', name: 'query_symbols', arguments: { request: {
          type: 'RUN', source: { type: 'ALL_DECLARATIONS' }, terminalStep: { type: 'EMIT' } } } }],
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
    terminalStep: { type: 'EMIT', output: { type: 'OCCURRENCES' } } } }, async (request, policy) => {
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
  assert.equal(Array.isArray(result.pages), true);
  assert.equal(result.pages.length, 0);
  const view = projected(result);
  assert.deepEqual(view.evidence.discovery_observations, initial.document.discovery_observations);
  assert.deepEqual(view.evidence.relation_observations, initial.document.relation_observations);
  assert.equal(view.summary.invocation.accumulated_row_count, 185);
  assert.deepEqual(view.summary.coverage, initial.document.coverage);
  assert.deepEqual(view.details, { request: { type: 'READ_RESULT', result: initial.document.retention.reference,
    cursor: 0, evidence_cursor: 0, output: { type: 'OCCURRENCES' } }, verbose: true });
  assert.ok(view.text.includes('Detail delivery unavailable; authority was not rejected'));
  assert.equal(view.text.includes('canonical retained detail authority unavailable'), false);
  assert.equal(view.text.includes('recovery action above'), false);
  assert.equal(view.text.includes('fixture transient transport loss'), false);
});


test('explicit result-stale-basis removes detail authority while a field rejection retains it', () => {
  const initial = native.cases.retainedEvidence.reply;
  for (const [reason, authorityRejected] of [['result-stale-basis', true], ['result-field-unavailable', false]]) {
    const rejection = { type: 'rejected_document', document: { status: 'rejected',
      rejection: { type: 'execution-rejected', reason } } };
    const result = createQueryDeliveryEnvelope(initial, [rejection], { stop: 'DELIVERY_UNAVAILABLE', rpc_count: 2,
      request_bytes: 0, response_bytes: Buffer.byteLength(JSON.stringify(initial)) + Buffer.byteLength(JSON.stringify(rejection)),
      result: initial.document.retention.reference });
    const view = projected(result);
    assert.deepEqual(view.evidence.discovery_observations, initial.document.discovery_observations);
    assert.deepEqual(view.evidence.relation_observations, initial.document.relation_observations);
    assert.equal(view.summary.invocation.accumulated_row_count, 185);
    assert.deepEqual(view.summary.coverage, initial.document.coverage);
    assert.ok(view.text.includes(reason));
    assert.equal(view.text.includes('recovery action above'), false, 'No recovery action can be invented');
    if (authorityRejected) assert.equal(view.details, undefined);
    else assert.equal(view.details.request.result, initial.document.retention.reference);
  }
});


test('canonical retention-unavailable removes the detail request while preserving prior received evidence', () => {
  const initial = native.cases.retainedEvidence.reply;
  const unavailable = captured.cases.unavailable.result.pages[0];
  assert.equal(unavailable.document.qualification.progress.type, 'retention_unavailable');
  const result = createQueryDeliveryEnvelope(initial, [unavailable], { stop: 'DELIVERY_UNAVAILABLE', rpc_count: 2,
    request_bytes: 0, response_bytes: Buffer.byteLength(JSON.stringify(initial)) + Buffer.byteLength(JSON.stringify(unavailable)),
    result: initial.document.retention.reference });
  const view = projected(result);
  assert.deepEqual(view.evidence.discovery_observations, initial.document.discovery_observations);
  assert.deepEqual(view.evidence.relation_observations, initial.document.relation_observations);
  assert.equal(view.details, undefined);
  assert.ok(view.text.includes('canonical retained detail authority unavailable'));
});
