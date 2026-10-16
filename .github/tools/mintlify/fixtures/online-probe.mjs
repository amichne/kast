// The real consumer runs in an isolated child with bounded DNS/HTTP observations.
// Node 24.20+ exposes the old Got retry settlement bug: microsoft/vscode-js-debug#2417.
import assert from 'node:assert/strict';
import dns from 'node:dns';
import dgram from 'node:dgram';
import os from 'node:os';
import { EventEmitter } from 'node:events';
import { createRequire, syncBuiltinESMExports } from 'node:module';
import { pathToFileURL } from 'node:url';

const prefix = process.argv[2];
const mode = process.argv[3] ?? 'deny';
assert.ok(['deny', 'allow'].includes(mode));
const observation = { node: process.version, lookups: [], fetches: [], udpQueries: 0, udpOpened: 0, udpClosed: 0 };
const allowedHosts = new Set(['captive.apple.com', 'icanhazip.com', 'api.ipify.org', 'cloudflare.com']);
const allowedUrls = new Set([
  'https://captive.apple.com/hotspot-detect.html',
  'https://icanhazip.com/',
  'https://api.ipify.org/',
  'https://cloudflare.com/',
]);
const violations = [];
const sockets = [];
let packetCodec;
let result;

process.on('uncaughtException', error => {
  console.log(JSON.stringify({ ...observation, result, violations,
    failure: error.message, name: error.name }));
  process.exit(error.message.includes('onCancel') ? 29 : 30);
});

dns.lookup = (hostname, options, callback) => {
  const done = typeof options === 'function' ? options : callback;
  observation.lookups.push(allowedHosts.has(hostname) ? hostname : 'UNEXPECTED');
  if (!allowedHosts.has(hostname) || observation.lookups.length > 16) {
    violations.push('Unexpected or excess hostname lookup');
  }
  const error = Object.assign(new Error('Case-owned DNS rejection'), {
    code: 'ENOTFOUND', syscall: 'getaddrinfo', hostname,
  });
  queueMicrotask(() => done(error));
};

class SilentDnsSocket extends EventEmitter {
  bound = false;
  closed = false;
  pending = [];
  constructor() { super(); observation.udpOpened++; sockets.push(this); }
  address() {
    if (!this.bound) throw Object.assign(new Error('Not bound'), { code: 'EBADF' });
    return { address: '127.0.0.1', family: 'IPv4', port: 12345 };
  }
  bind() { this.bound = true; queueMicrotask(() => this.emit('listening')); return this; }
  send(...args) {
    if (!this.bound) this.bind();
    observation.udpQueries++;
    if (args[3] !== 53 || observation.udpQueries > 16) violations.push('Unexpected DNS datagram');
    let query;
    try {
      query = packetCodec.decode(args[0].subarray(args[1], args[1] + args[2]));
    } catch {
      violations.push('Invalid DNS datagram');
      throw new Error('Invalid DNS datagram');
    }
    if (query.questions.length !== 1 || !['myip.opendns.com', 'o-o.myaddr.l.google.com'].includes(query.questions[0].name)) {
      violations.push('Unexpected DNS question');
    }
    this.pending.push({ query, address: args[4] });
    return this;
  }
  rejectPending() {
    for (const { query, address } of this.pending.splice(0)) {
      this.emit('message', packetCodec.encode({
        type: 'response', id: query.id, flags: 3, questions: query.questions, answers: [],
      }), { address, port: 53 });
    }
  }
  close() {
    if (!this.closed) {
      this.closed = true;
      observation.udpClosed++;
      queueMicrotask(() => this.emit('close'));
    }
    return this;
  }
  ref() { return this; }
  unref() { return this; }
}
dgram.createSocket = () => new SilentDnsSocket();
os.networkInterfaces = () => ({ fixture: [{
  address: '192.0.2.2', netmask: '255.255.255.0', family: 'IPv4',
  mac: '00:00:00:00:00:00', internal: false, cidr: '192.0.2.2/24',
}] });
syncBuiltinESMExports();
if (mode === 'allow') {
  globalThis.fetch = async (input, options) => {
    let url;
    try {
      url = new URL(typeof input === 'string' ? input : input.url ?? input.toString());
    } catch {
      violations.push('Invalid HTTP observation');
      throw new Error('Invalid HTTP observation');
    }
    const known = allowedUrls.has(url.href);
    observation.fetches.push(known ? url.hostname : 'UNEXPECTED');
    if (!known || observation.fetches.length > 8 || options?.body != null
      || !['GET', 'HEAD'].includes(options?.method ?? 'GET')) {
      violations.push('Unexpected HTTP observation');
      throw new Error('Unexpected HTTP observation');
    }
    return new Response(url.hostname === 'captive.apple.com' ? 'Success' : '203.0.113.5', { status: 200 });
  };
}

const consumer = createRequire(prefix + '/node_modules/@mintlify/previewing/package.json');
const entry = consumer.resolve('is-online');
packetCodec = createRequire(prefix + '/package.json')('dns-packet');
const { default: isOnline } = await import(pathToFileURL(entry));
result = await isOnline({ timeout: 1500 });
// After the online decision, release the independently controlled pending DNS observations.
for (const socket of sockets) socket.rejectPending();
process.once('beforeExit', () => {
  assert.equal(result, mode === 'allow');
  assert.deepEqual(violations, []);
  assert.ok(mode === 'allow' ? observation.fetches.length > 0 : observation.lookups.length > 0);
  assert.ok(observation.udpOpened > 0);
  assert.equal(observation.udpClosed, observation.udpOpened);
  console.log(JSON.stringify({ ...observation, result, violations, failure: null }));
});
