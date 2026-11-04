import assert from 'node:assert/strict';
import { createHash, webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

// Exercise the real loader, before the JSX-only render functions, with owned
// network/effect boundaries. No fetch reaches the network and no real timer runs.
const viewer = readFileSync(new URL('viewer.jsx', import.meta.url), 'utf8');
const marker = 'function SchemaNode';
assert.equal(viewer.split(marker).length, 2, 'The loader boundary must remain explicit.');
const loader = viewer.slice(0, viewer.indexOf(marker));

function graph() {
  return {
    schemaVersion: 1,
    nodes: [{
      parent: null,
      path: '#/components/schemas/Value',
      facts: [{ keyword: 'maximum', value: '9223372036854775807' }],
      children: [{ label: 'Recursive reference', node: 0 }],
    }],
    operations: { example: { roots: [{ label: 'Inputs', node: 0 }, { label: 'Response', node: 0 }] } },
  };
}

async function waitFor(predicate) {
  const deadline = performance.now() + 2000;
  while (!predicate()) {
    assert.ok(performance.now() < deadline, 'The bounded loader observation did not settle.');
    await new Promise(resolve => setImmediate(resolve));
  }
}

function observe(value = graph(), { ignoreAbort = false, expectedDigest, rawBody } = {}) {
  const bytes = new TextEncoder().encode(rawBody ?? JSON.stringify(value));
  const updates = [];
  const effects = [];
  const requests = [];
  const timers = new Map();
  let cleared = 0;
  let resolveFetch;
  let rejectFetch;
  const context = {
    AbortController,
    TextDecoder,
    Uint8Array,
    crypto: webcrypto,
    expectedDataSha: expectedDigest ?? createHash('sha256').update(bytes).digest('hex'),
    useState: initial => [initial, state => updates.push(state)],
    useEffect: effect => effects.push(effect()),
    setTimeout: (callback, delay) => {
      assert.equal(delay, 20000);
      const id = timers.size + 1;
      timers.set(id, callback);
      return id;
    },
    clearTimeout: id => {
      assert.equal(id, 1);
      cleared++;
    },
    fetch: (url, { signal }) => {
      requests.push({ url, signal });
      return new Promise((resolve, reject) => {
        resolveFetch = resolve;
        rejectFetch = reject;
        if (!ignoreAbort) {
          signal.addEventListener('abort', () => reject(signal.reason), { once: true });
        }
      });
    },
  };
  vm.runInNewContext(loader, context, { timeout: 1000 });
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/assets/callable-reference.txt');
  assert.equal(requests[0].signal.aborted, false);
  assert.equal(effects.length, 1);
  assert.equal(typeof effects[0], 'function');
  return {
    updates,
    signal: requests[0].signal,
    complete: (status = 200) => resolveFetch(new Response(bytes, { status })),
    reject: () => rejectFetch(new Error('Case-owned network rejection')),
    expire: () => timers.get(1)(),
    unmount: () => effects[0](),
    settled: (clears = 1) => waitFor(() => cleared >= clears),
  };
}

function assertFailed(observation) {
  assert.equal(observation.updates.length, 1);
  assert.equal(observation.updates[0].kind, 'failed');
}

test('valid recursive references load without eager reference traversal or integer loss', async () => {
  const observation = observe();
  observation.complete();
  await observation.settled();
  assert.equal(observation.updates.length, 1);
  const ready = observation.updates[0];
  assert.equal(ready.kind, 'ready');
  assert.equal(ready.pointers.get(0), '#/components/schemas/Value');
  assert.equal(ready.graph.nodes[0].children[0].node, 0);
  assert.equal(ready.graph.nodes[0].facts[0].value, '9223372036854775807');
  observation.unmount();
});

test('a rejected fetch settles as an explicit failure', async () => {
  const observation = observe();
  observation.reject();
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('an unsuccessful HTTP response cannot become a ready graph', async () => {
  const observation = observe();
  observation.complete(503);
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('malformed fact values reject after matching resource integrity', async () => {
  const document = graph();
  document.nodes[0].facts[0].value = 9223372036854776000;
  const observation = observe(document);
  observation.complete();
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('pointer ancestry cycles reject while schema reference cycles remain valid', async () => {
  const document = graph();
  document.nodes[0].parent = 0;
  const observation = observe(document);
  observation.complete();
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('a response resolving after unmount cannot publish a state', async () => {
  const observation = observe(graph(), { ignoreAbort: true });
  observation.unmount();
  assert.equal(observation.signal.aborted, true);
  observation.complete();
  // Cleanup clears once; the detached request must still reach its own finally.
  await observation.settled(2);
  assert.deepEqual(observation.updates, []);
});

test('the finite timeout aborts pending fetch and settles as failed', async () => {
  const observation = observe();
  observation.expire();
  assert.equal(observation.signal.aborted, true);
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('stale or changed response bytes cannot pass the injected build digest', async () => {
  const observation = observe(graph(), { expectedDigest: '0'.repeat(64) });
  observation.complete();
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('unknown graph versions reject even with a matching build digest', async () => {
  const document = graph();
  document.schemaVersion = 2;
  const observation = observe(document);
  observation.complete();
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('invalid JSON settles as failed after resource integrity succeeds', async () => {
  const observation = observe(graph(), { rawBody: '{' });
  observation.complete();
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('timeout fails immediately even when a pending boundary ignores abort', async () => {
  const observation = observe(graph(), { ignoreAbort: true });
  observation.expire();
  assertFailed(observation);
  observation.complete();
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});
