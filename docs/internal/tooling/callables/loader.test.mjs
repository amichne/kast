import assert from 'node:assert/strict';
import { createHash, webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { gzipSync } from 'node:zlib';

// Run the actual loader before its JSX render functions. Native gzip, streams,
// UTF-8, and crypto remain real; effects, timers, and pending body settlement
// are controlled. A network request is always a test failure.
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
  const deadline = performance.now() + 5000;
  while (!predicate()) {
    assert.ok(performance.now() < deadline, 'The bounded loader observation did not settle.');
    await new Promise(resolve => setImmediate(resolve));
  }
}

function observe(value = graph(), {
  expectedDigest,
  rawBody,
  compressedBytes,
  holdBody = false,
  ignoreAbort = false,
  unsupportedDecompressor = false,
} = {}) {
  const bytes = new TextEncoder().encode(rawBody ?? JSON.stringify(value));
  const updates = [];
  const effects = [];
  const signals = [];
  const timers = new Map();
  let cleared = 0;
  let networkCalls = 0;
  let releaseBody;
  let bodyPending = false;
  let nativeBodies = 0;

  class ObservedAbortController extends AbortController {
    constructor() {
      super();
      signals.push(this.signal);
    }
  }

  class ControlledResponse extends Response {
    async arrayBuffer() {
      // Consume the real decompression stream first. The optional hold models
      // an in-flight completion boundary after bytes exist but before admission.
      const result = await super.arrayBuffer();
      nativeBodies++;
      if (!holdBody) return result;
      return new Promise((resolve, reject) => {
        const signal = signals[0];
        releaseBody = () => resolve(result);
        bodyPending = true;
        if (!ignoreAbort) {
          if (signal.aborted) reject(signal.reason);
          else signal.addEventListener('abort', () => reject(signal.reason), { once: true });
        }
      });
    }
  }

  const context = {
    AbortController: ObservedAbortController,
    atob,
    Blob,
    DecompressionStream: unsupportedDecompressor ? undefined : DecompressionStream,
    Response: ControlledResponse,
    TextDecoder,
    Uint8Array,
    crypto: webcrypto,
    compressedGraphBase64: Buffer.from(compressedBytes ?? gzipSync(bytes)).toString('base64'),
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
    fetch: () => {
      networkCalls++;
      throw new Error('Bundled schema loading must not request a hosted resource.');
    },
  };
  vm.runInNewContext(loader, context, { timeout: 1000 });
  assert.equal(signals.length, 1);
  assert.equal(signals[0].aborted, false);
  assert.equal(effects.length, 1);
  assert.equal(typeof effects[0], 'function');
  return {
    updates,
    signal: signals[0],
    pending: () => waitFor(() => bodyPending),
    complete: () => {
      assert.equal(typeof releaseBody, 'function', 'The controlled body must be pending.');
      releaseBody();
    },
    expire: () => timers.get(1)(),
    unmount: () => effects[0](),
    settled: async (clears = 1) => {
      await waitFor(() => cleared >= clears);
      assert.equal(networkCalls, 0, 'No loader branch may request a hosted schema asset.');
    },
    nativeBodies: () => nativeBodies,
  };
}

function assertFailed(observation) {
  assert.equal(observation.updates.length, 1);
  assert.equal(observation.updates[0].kind, 'failed');
}

test('valid bundled gzip preserves self references and exact integer text without network access', async () => {
  const observation = observe();
  await observation.settled();
  assert.equal(observation.nativeBodies(), 1);
  assert.equal(observation.updates.length, 1);
  const ready = observation.updates[0];
  assert.equal(ready.kind, 'ready');
  assert.equal(ready.pointers.get(0), '#/components/schemas/Value');
  assert.equal(ready.graph.nodes[0].children[0].node, 0);
  assert.equal(ready.graph.nodes[0].facts[0].value, '9223372036854775807');
  observation.unmount();
});

test('corrupt gzip settles as an explicit failure', async () => {
  const observation = observe(graph(), { compressedBytes: Buffer.from('not a gzip stream') });
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('truncated gzip cannot become a ready graph', async () => {
  const observation = observe(graph(), { compressedBytes: gzipSync('partial body').subarray(0, 12) });
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('changed decoded bytes cannot pass the injected build digest', async () => {
  const observation = observe(graph(), { expectedDigest: '0'.repeat(64) });
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('invalid JSON rejects after gzip and resource integrity succeed', async () => {
  const observation = observe(graph(), { rawBody: '{' });
  await observation.settled();
  assert.equal(observation.nativeBodies(), 1);
  assertFailed(observation);
  observation.unmount();
});

test('malformed graph facts reject after matching resource integrity', async () => {
  const document = graph();
  document.nodes[0].facts[0].value = 9223372036854776000;
  const observation = observe(document);
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('pointer ancestry cycles reject while recursive schema edges remain valid', async () => {
  const document = graph();
  document.nodes[0].parent = 0;
  const observation = observe(document);
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('unknown graph versions reject even with a matching build digest', async () => {
  const document = graph();
  document.schemaVersion = 2;
  const observation = observe(document);
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('unsupported browser decompression leaves a finite failure', async () => {
  const observation = observe(graph(), { unsupportedDecompressor: true });
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('unmount aborts pending decoding and prevents late state updates', async () => {
  const observation = observe(graph(), { holdBody: true });
  await observation.pending();
  observation.unmount();
  assert.equal(observation.signal.aborted, true);
  await observation.settled(2);
  assert.deepEqual(observation.updates, []);
});

test('a decoding boundary resolving after unmount cannot publish a state', async () => {
  const observation = observe(graph(), { holdBody: true, ignoreAbort: true });
  await observation.pending();
  observation.unmount();
  assert.equal(observation.signal.aborted, true);
  observation.complete();
  await observation.settled(2);
  assert.deepEqual(observation.updates, []);
});

test('timeout aborts pending decoding and settles as failed', async () => {
  const observation = observe(graph(), { holdBody: true });
  await observation.pending();
  observation.expire();
  assert.equal(observation.signal.aborted, true);
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});

test('timeout fails immediately even when the pending decoding boundary ignores abort', async () => {
  const observation = observe(graph(), { holdBody: true, ignoreAbort: true });
  await observation.pending();
  observation.expire();
  assertFailed(observation);
  observation.complete();
  await observation.settled();
  assertFailed(observation);
  observation.unmount();
});
