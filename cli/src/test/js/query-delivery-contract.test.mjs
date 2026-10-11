import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { decodeQueryDelivery } from '../../../../experiments/host-observation/pi_evaluation_delivery.mjs';
import { admitQueryDeliveryEnvelope, createQueryDeliveryEnvelope, encodeQueryDeliveryEnvelope } from '../../main/js/query-delivery-contract.mjs';

const captured = JSON.parse(readFileSync(new URL('../../../../experiments/host-observation/pi-fixtures/query-delivery-cases.json', import.meta.url)));
const initial = captured.cases.complete.result.initial;
const page = captured.cases.complete.result.pages[0];
const admitCanonical = value => ['complete', 'qualified', 'rejected_document', 'rejected'].includes(value?.type)
  ? value : { type: 'invalid' };
const envelope = stop => ({
  type: 'query_delivery', initial, pages: [page],
  delivery: { stop, rpc_count: 2, request_bytes: 7, response_bytes: 11, result: null },
});

test('unknown outer fields cannot be admitted as delivery evidence', () => {
  const value = envelope('DELIVERED');
  value.invented = true;
  assert.equal(decodeQueryDelivery(value, admitCanonical).type, 'invalid');
});

// This finite expected vocabulary is authored independently of the schema and generator.
const stops = ['DELIVERED', 'CANCELLED', 'TIME_LIMIT', 'PAGE_LIMIT', 'BYTE_LIMIT',
  'DELIVERY_UNAVAILABLE', 'BUDGET_INCREASE_REQUIRED', 'MALFORMED_PAGE', 'IDENTITY_MISMATCH', 'NON_ADVANCING'];
for (const stop of stops) {
  test(`${stop} preserves admitted canonical initial and pages exactly`, () => {
    const value = envelope(stop);
    const received = [];
    const admitted = decodeQueryDelivery(value, reply => { received.push(reply); return admitCanonical(reply); });
    assert.strictEqual(admitted, value);
    assert.strictEqual(admitted.initial, initial);
    assert.strictEqual(admitted.pages[0], page);
    assert.deepEqual(received, [initial, page]);
    const created = createQueryDeliveryEnvelope(initial, [page], value.delivery);
    assert.strictEqual(created.initial, initial);
    assert.strictEqual(created.pages[0], page);
    const encoded = encodeQueryDeliveryEnvelope(created);
    assert.equal(encoded.type, 'encoded');
    assert.deepEqual(JSON.parse(encoded.json), value);
  });
  if (stop !== 'BYTE_LIMIT') test(`${stop} excludes an omitted initial`, () => {
    const value = envelope(stop);
    value.initial = null; value.pages = []; value.delivery.original_outcome = 'complete';
    assert.equal(admitQueryDeliveryEnvelope(value).type, 'invalid');
  });
}

for (const original of ['complete', 'qualified', 'rejected_document', 'rejected']) {
  test(`BYTE_LIMIT alone can omit ${original} initial with no fabricated pages`, () => {
    const value = { type: 'query_delivery', initial: null, pages: [],
      delivery: { stop: 'BYTE_LIMIT', original_outcome: original, rpc_count: 1, request_bytes: 7, response_bytes: 11, result: null } };
    assert.strictEqual(decodeQueryDelivery(value, () => { throw new Error('Absent canonical reply must not be decoded'); }), value);
    assert.deepEqual(JSON.parse(encodeQueryDeliveryEnvelope(value).json), value);
  });
}

const malformed = [
  ['unknown stop', value => { value.delivery.stop = 'UNKNOWN'; }],
  ['unknown discriminator', value => { value.type = 'query'; }],
  ['extra metric', value => { value.delivery.invented = 0; }],
  ['original outcome with present initial', value => { value.delivery.original_outcome = 'complete'; }],
  ['unknown initial outcome', value => { value.initial = { type: 'unknown' }; }],
  ['unknown page outcome', value => { value.pages = [{ type: 'unknown' }]; }],
  ['null pages', value => { value.pages = null; }],
  ['null metric', value => { value.delivery.request_bytes = null; }],
  ['negative bytes', value => { value.delivery.response_bytes = -1; }],
  ['unsafe bytes', value => { value.delivery.response_bytes = Number.MAX_SAFE_INTEGER + 1; }],
  ['fractional count', value => { value.delivery.rpc_count = 1.5; }],
  ['zero calls', value => { value.delivery.rpc_count = 0; }],
  ['excess calls', value => { value.delivery.rpc_count = 65; }],
  ['too many received replies', value => { value.delivery.rpc_count = 1; }],
  ['nontext reference', value => { value.delivery.result = 4; }],
  ['omitted original outcome', value => { value.initial = null; value.pages = []; value.delivery.stop = 'BYTE_LIMIT'; }],
  ['unknown original outcome', value => { value.initial = null; value.pages = []; value.delivery.stop = 'BYTE_LIMIT'; value.delivery.original_outcome = 'unknown'; }],
  ['pages after omitted initial', value => { value.initial = null; value.delivery.stop = 'BYTE_LIMIT'; value.delivery.original_outcome = 'complete'; }],
];
for (const field of ['type', 'initial', 'pages', 'delivery']) malformed.push([`missing ${field}`, value => { delete value[field]; }]);
for (const field of ['stop', 'rpc_count', 'request_bytes', 'response_bytes', 'result']) malformed.push([`missing ${field}`, value => { delete value.delivery[field]; }]);
for (const [name, mutate] of malformed) test(`${name} is finite outer rejection`, () => {
  const value = structuredClone(envelope('DELIVERED')); mutate(value);
  assert.equal(decodeQueryDelivery(value, admitCanonical).type, 'invalid');
  assert.equal(encodeQueryDeliveryEnvelope(value).type, 'invalid');
});

test('failed physical calls need not manufacture reply pages', () => {
  const value = envelope('DELIVERY_UNAVAILABLE'); value.delivery.rpc_count = 3;
  assert.strictEqual(admitQueryDeliveryEnvelope(value), value);
});

test('outer admission cannot replace canonical inner rejection', () => {
  const value = envelope('DELIVERED');
  assert.equal(decodeQueryDelivery(value, () => ({ type: 'invalid' })).type, 'invalid');
});

test('invalid caller shape is a closed constructor failure', () => {
  assert.deepEqual(createQueryDeliveryEnvelope(null, [], envelope('DELIVERED').delivery),
    { type: 'invalid', reason: 'INVALID_OUTER_SHAPE' });
  const value = envelope('DELIVERED'); value.delivery.rpc_count = 1;
  assert.deepEqual(createQueryDeliveryEnvelope(initial, [page], value.delivery),
    { type: 'invalid', reason: 'INVALID_RPC_ACCOUNTING' });
});

test('canonical transport rejection remains a payload rather than an outer failure', () => {
  const rejected = { type: 'rejected', failure: 'OBSERVATION_UNAVAILABLE' };
  const value = createQueryDeliveryEnvelope(rejected, [],
    { stop: 'DELIVERY_UNAVAILABLE', rpc_count: 1, request_bytes: 7, response_bytes: 11, result: null });
  assert.strictEqual(value.initial, rejected);
  assert.equal(value.type, 'query_delivery');
});
