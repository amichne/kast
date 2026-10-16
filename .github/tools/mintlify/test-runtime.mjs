import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('.', import.meta.url));
const probe = fileURLToPath(new URL('fixtures/online-probe.mjs', import.meta.url));

function observe(mode) {
  const child = spawnSync(process.execPath, [probe, root, mode], {
    encoding: 'utf8', timeout: 10_000, maxBuffer: 16_384,
  });
  assert.ifError(child.error);
  assert.equal(child.status, 0, child.stderr + child.stdout);
  const observation = JSON.parse(child.stdout);
  assert.equal(observation.failure, null);
  assert.deepEqual(observation.violations, []);
  assert.ok(observation.udpOpened > 0);
  assert.equal(observation.udpClosed, observation.udpOpened);
  return observation;
}

test('network failures yield an offline result without a late retry crash', () => {
  const observation = observe('deny');
  assert.equal(observation.result, false);
  assert.ok(observation.lookups.length > 0);
});

test('reachable HTTP observations keep the online predicate successful', () => {
  const observation = observe('allow');
  assert.equal(observation.result, true);
  assert.ok(observation.fetches.length > 0);
});
