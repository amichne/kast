import assert from 'node:assert/strict';
import fs from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { pathToFileURL } from 'node:url';
import { CasePolicy } from './pi_evaluation_policy.mjs';
import { createEvaluationSession, disposeEvaluationSession } from './pi_evaluation_worker.mjs';
import { replaySha, replayWorker, syntheticRuntime } from './pi_evaluation_replay_worker.mjs';
import { loadKastGuidance, replayGuidanceIdentity } from './pi_evaluation_guidance.mjs';

const packageRoot = process.env.KAST_PI_PACKAGE;
const usage = { input: 0, cacheRead: 0, cacheWrite: 0, output: 0, totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } };

test('installed SDK serializes staged fresh guidance and preloaded continuation guidance before transport', { skip: !packageRoot }, async t => {
  const load = relative => import(pathToFileURL(join(packageRoot, relative)).href);
  const sdk = await load('dist/index.js');
  const guidance = loadKastGuidance();
  const root = fs.mkdtempSync(join(tmpdir(), 'kast-sdk-guidance-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const adapter = join(root, 'adapter.mjs');
  fs.writeFileSync(adapter, 'export default function() {}');
  let attempts = 0;
  t.mock.method(globalThis, 'fetch', async () => { attempts++; throw Error('Synthetic proof forbids transport'); });
  const runtime = await syntheticRuntime(sdk, load);

  for (const mode of ['fresh', 'received-result']) {
    const directory = join(root, mode);
    fs.mkdirSync(directory);
    const captured = [], records = [];
    const manager = sdk.SessionManager.inMemory(root);
    const allowance = mode === 'fresh' ? 2 : 1;
    const policy = new CasePolicy({ name: 'guidance-sdk', expectedSemanticCalls: 1, inputTokenCeiling: 20000, declarationByteCeiling: 100000,
      work: { reportedTokens: 30000, requests: allowance, tools: allowance, outputReserve: 2000 },
      delivery: { reportedTokens: 25000, requests: 1, outputReserve: 2000 }, maximumToolTextBytes: 1048576 });
    if (mode === 'received-result') {
      const document = JSON.parse(fs.readFileSync(new URL('./pi-fixtures/complete-document.json', import.meta.url), 'utf8'));
      const envelope = { type: 'complete', document };
      const text = JSON.stringify(envelope);
      assert.equal(policy.restoreReceivedResult(envelope, Buffer.byteLength(text), 'fixture').allow, true);
      manager.appendMessage({ role: 'user', content: 'Find the fixture', timestamp: 1 });
      manager.appendMessage({ role: 'assistant', provider: 'openai-codex', model: 'gpt-6.1-sol', api: 'openai-codex-responses',
        content: [{ type: 'toolCall', id: 'fixture', name: 'query_symbols', arguments: { request: { type: 'RUN' } } }],
        usage, stopReason: 'toolUse', timestamp: 2 });
      manager.appendMessage({ role: 'toolResult', toolCallId: 'fixture', toolName: 'query_symbols', content: [{ type: 'text', text }], isError: false, timestamp: 3 });
    }
    const observing = { ...sdk, createMcpExtension: () => () => {},
      DefaultResourceLoader: class extends sdk.DefaultResourceLoader {
        constructor(options) {
          super({ ...options, extensionFactories: [...options.extensionFactories, pi => {
            pi.on('before_provider_request', (event, ctx) => { captured.push(event.payload); ctx.abort(); });
          }] });
        }
      } };
    const created = await createEvaluationSession({ sdk: observing, plan: { workspaceRoot: root, kastAdapter: adapter }, directory,
      modelRuntime: runtime, policy, record: event => { records.push(event); }, manager, guidance, mode });
    assert.equal(created.type, 'session', created.type === 'session' ? undefined : JSON.stringify(created));
    const session = created.session;
    try {
      if (mode === 'fresh') await session.prompt('Find the fixture', { source: 'rpc', expandPromptTemplates: false });
      else { await session.agent.continue(); await session.agent.waitForIdle(); }
      assert.equal(captured.length, 1);
      const [body] = captured;
      assert.ok(body.instructions.includes(guidance.text));
      assert.equal(records.find(event => event.type === 'provider_admission').decision.allow, true);
      assert.equal(body.input.filter(value => value.role === 'user').length, 1);
      if (mode === 'fresh') {
        assert.equal(created.guidance.mode, 'STAGED');
        assert.equal(body.instructions.includes(guidance.skillText), false);
        const declared = [...(body.tools ?? []), ...body.input.flatMap(item => item.tools ?? [])];
        assert.ok(declared.some(tool => tool.name === 'read_kast_guidance'), JSON.stringify({ active: session.getActiveToolNames(), input: body.input.map(item => item.type ?? item.role) }));
      } else {
        assert.equal(created.guidance.mode, 'PRELOADED');
        assert.ok(body.instructions.includes(guidance.skillText));
        assert.equal(Object.hasOwn(body, 'tools'), false);
      }
    } finally { await disposeEvaluationSession(session); }
  }
  assert.equal(attempts, 0);
});

test('replay preflight projects canonical skill bytes on the installed SDK with no provider send', { skip: !packageRoot }, async t => {
  const root = fs.mkdtempSync(join(tmpdir(), 'kast-sdk-guided-replay-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const renderer = join(root, 'neutral-renderer.mjs');
  // The renderer is incidental here. Only one fixed JSON input enters the SDK.
  fs.writeFileSync(renderer, 'import fs from "node:fs"; process.stdout.write(fs.readFileSync(process.argv[3]));');
  const manifest = join(root, 'manifest.json');
  const manifestBytes = JSON.stringify({ command: [process.execPath, renderer], sha256: replaySha(fs.readFileSync(renderer)) });
  fs.writeFileSync(manifest, manifestBytes);
  const guidance = loadKastGuidance();
  const version = JSON.parse(fs.readFileSync(join(packageRoot, 'package.json'))).version;
  const trial = { trialId: 'C1-r1-JSON', pairId: 'C1-r1', caseId: 'C1', format: 'JSON', repetition: 1, armOrder: 0 };
  const base = { piPackageRoot: packageRoot, piVersion: version, adapterManifest: manifest, adapterManifestSha256: replaySha(manifestBytes) };
  const observed = {};
  for (const mode of ['historical', 'guided']) {
    const outputRoot = join(root, mode);
    fs.mkdirSync(outputRoot);
    const plan = { ...base, outputRoot, ...(mode === 'guided' && { kastGuidanceSha256: replayGuidanceIdentity(guidance) }) };
    const projection = await replayWorker('--preflight', plan, trial);
    assert.equal(projection.type, 'projected', JSON.stringify(projection));
    assert.equal(projection.presentationOccurrences, 1);
    const stage = join(outputRoot, 'preflight-' + trial.trialId);
    assert.equal(JSON.parse(fs.readFileSync(join(stage, 'ledger.json'))).providerSends, 0);
    observed[mode] = projection;
  }
  assert.equal(observed.historical.guidance, undefined);
  assert.deepEqual(observed.guided.guidance, { mode: 'PRELOADED', skillSha256: guidance.skillSha256,
    guideSha256: guidance.guideSha256, preloadedBytes: Buffer.byteLength(guidance.skillText) });
  assert.notEqual(observed.guided.payloadSha256, observed.historical.payloadSha256);
  assert.ok(observed.guided.payloadBytes > observed.historical.payloadBytes);
});
