import assert from 'node:assert/strict';
import test from 'node:test';
import { discoveryFailures } from './smoke-acceptance.mjs';

test('discovery fails for a missing model, loader failure or mismatched adapter ownership', () => {
  const base = { errors: [], model: {}, servers: [{ name: 'kast', extensionPath: '/adapter.ts' }], adapter: '/adapter.ts' };
  assert.deepEqual(discoveryFailures(base), []);
  assert.deepEqual(discoveryFailures({ ...base, model: undefined }), ['REQUESTED_MODEL_UNAVAILABLE']);
  assert.deepEqual(discoveryFailures({ ...base, errors: ['load failed'] }), ['EXTENSION_LOAD_FAILED']);
  assert.deepEqual(discoveryFailures({ ...base, servers: [{ name: 'kast', extensionPath: '/other.ts' }] }), ['KAST_MCP_REGISTRATION_UNAVAILABLE']);
  assert.deepEqual(discoveryFailures({ ...base, servers: [] }), ['KAST_MCP_REGISTRATION_UNAVAILABLE']);
  assert.deepEqual(discoveryFailures({ ...base, adapter: null, servers: [] }), []);
});
