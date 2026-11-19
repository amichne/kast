import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { expandReplayPlan, prepareReplayRun } from '../pi_evaluation_replay.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
const cli = path.join(here, 'node_modules/promptfoo/dist/src/entrypoint.js');

function temporary(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'kast-pi-promptfoo-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return root;
}

function evaluate(root, inputs, extraArgs = []) {
  const file = path.join(root, 'results.json');
  const env = { ...process.env, PROMPTFOO_DISABLE_TELEMETRY: '1', PROMPTFOO_CONFIG_DIR: root };
  delete env.KAST_PI_EVAL_PLAN;
  delete env.KAST_PI_EVAL_TARGET;
  const child = spawnSync(process.execPath, [cli, 'eval', '-c', 'pi.config.mjs', '--no-cache', '--no-write', '--no-share',
    '--no-progress-bar', '--no-table', '-o', file, ...extraArgs], { cwd: here, env: { ...env, ...inputs }, encoding: 'utf8', timeout: 20000 });
  assert.equal(child.error, undefined);
  assert.equal(fs.existsSync(file), true, child.stdout + child.stderr);
  return { status: child.status, rows: JSON.parse(fs.readFileSync(file, 'utf8')).results.results };
}

function writePiPlan(root, cases) {
  const outputRoot = path.join(root, 'private-run');
  const input = path.join(root, 'plan.json');
  const phase = { reportedTokens: 100, requests: 1, outputReserve: 20 };
  const plan = {
    piPackageRoot: path.join(root, 'sdk'), piVersion: '1.4.0',
    kastAdapter: path.join(root, 'missing-adapter'), kastAdapterSha256: 'a'.repeat(64),
    workspaceRoot: root, outputRoot, authPath: path.join(root, 'auth.json'), modelsStorePath: path.join(root, 'models.json'),
    cases: cases.map(item => ({ mode: 'fresh', prompt: 'Unavailable installation control', wallSeconds: 3,
      work: { ...phase, tools: 1 }, delivery: phase, expectedSemanticCalls: 1,
      inputTokenCeiling: 100, declarationByteCeiling: 1024, maximumToolTextBytes: 1024, ...item })),
  };
  fs.writeFileSync(input, JSON.stringify(plan));
  return { outputRoot, input };
}

test('legacy Pi commands cannot launch or grade an evaluation', () => {
  const entry = fileURLToPath(new URL('../run_pi_evaluation.mjs', import.meta.url));
  for (const flag of ['--execute', '--assess-target']) {
    const result = spawnSync(process.execPath, [entry, flag, '/unused/plan.json'], { encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /npm run eval -- -c pi\.config\.mjs/);
  }
});

test('a direct replay worker invocation cannot create trial output', t => {
  const root = temporary(t);
  const file = path.join(root, 'plan.json');
  fs.writeFileSync(file, JSON.stringify({
    mode: 'replay', workers: 1, caseIds: ['C1'], repetitions: 2, wallSeconds: 60, maximumProviderRequests: 4,
    outputRoot: root, adapterManifest: path.join(root, 'unavailable-manifest.json'),
  }));
  const worker = fileURLToPath(new URL('../pi_evaluation_replay_worker.mjs', import.meta.url));
  const child = spawnSync(process.execPath, [worker, '--trial', file, 'C1-r1-TOON'], { encoding: 'utf8' });
  assert.notEqual(child.status, 0);
  assert.deepEqual(fs.readdirSync(root), ['plan.json']);
});

test('Promptfoo grades accepted decisions and their nearest counterexamples in all six scenarios', t => {
  const { status, rows } = evaluate(temporary(t), {});
  assert.equal(status, 0);
  assert.equal(rows.length, 33);
  assert.ok(rows.every(row => row.success && row.namedScores.oracle === 1));
  for (const caseId of ['C1', 'C2', 'C3', 'C4', 'C5', 'C6']) {
    const cases = rows.filter(row => row.vars.caseId === caseId);
    assert.ok(cases.some(row => row.vars.arm === 'accept' && row.response.output.correct));
    assert.ok(cases.some(row => row.vars.arm === 'reject' && row.response.output.correct === false));
  }
});

test('Promptfoo reports incomplete target evidence without passing the performance target', t => {
  const root = temporary(t);
  const targetPath = path.join(root, 'target.json');
  fs.writeFileSync(targetPath, JSON.stringify({
    target: { factor: 3, dimensions: 'TOKENS_AND_LATENCY', caseIds: ['C5', 'C6'], repetitions: 2 },
    baseline: [], candidate: [],
  }));
  const { status, rows } = evaluate(root, { KAST_PI_EVAL_TARGET: targetPath });
  assert.notEqual(status, 0);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].success, false);
  assert.equal(rows[0].response.output.status, 'UNMEASURED');
  assert.ok(rows[0].response.output.reasons.includes('MISSING_TRIAL'));
});

test('Promptfoo invokes the real Pi controller once even with repeated rows', t => {
  const root = temporary(t);
  const { input, outputRoot } = writePiPlan(root, [{ name: 'fresh-proof' }]);
  const { status, rows } = evaluate(root, { KAST_PI_EVAL_PLAN: input }, ['--repeat', '2']);
  assert.notEqual(status, 0);
  assert.equal(rows.length, 2);
  assert.deepEqual(rows.map(row => row.response.error).sort(), ['EFFECT_FAILED', 'PI_EVALUATION_DUPLICATE_ROW']);
  const report = JSON.parse(fs.readFileSync(path.join(outputRoot, 'report.json'), 'utf8'));
  assert.equal(report.cases.length, 1);
  assert.equal(report.cases[0].failure.operation, 'ADAPTER_READ');
  assert.equal(fs.readFileSync(path.join(outputRoot, 'plan.json'), 'utf8'), fs.readFileSync(input, 'utf8'));
  assert.equal(fs.statSync(outputRoot).mode & 0o777, 0o700);
});

test('the configured correctness assertion requires an exact answer match on a qualified receipt', async t => {
  const { createPiConfig } = await import('./pi.config.mjs');
  const { input } = writePiPlan(temporary(t), [
    { name: 'expected', expectedAnswerSha256: 'a'.repeat(64), expectedOutcome: 'QUALIFIED_ANSWER' },
    { name: 'exploratory' },
  ]);
  const [expected, exploratory] = createPiConfig(input).scenarios.map(scenario => scenario.tests[0].assert[0]);
  assert.equal(expected.metric, 'correctness');
  assert.equal(exploratory.metric, 'policy');
  const valid = { type: 'report', workerExit: 0, protectedFilesUnchanged: true, outcome: 'QUALIFIED_ANSWER', finalAnswer: true, answerCheck: 'MATCHED' };
  const assertOutput = output => expected.value('', { providerResponse: { output } });
  assert.equal(assertOutput(valid).pass, true);
  for (const changed of [{ answerCheck: 'MISMATCH' }, { answerCheck: 'UNAVAILABLE' }, { workerExit: 1 }, { outcome: 'FINAL_ANSWER' }]) {
    assert.equal(assertOutput({ ...valid, ...changed }).pass, false);
  }
});

test('the paired replay admission rejects both arms before either live worker starts', async t => {
  const root = temporary(t);
  const adapterManifest = path.join(root, 'manifest.json');
  fs.writeFileSync(adapterManifest, JSON.stringify({ sha256: 'a'.repeat(64) }));
  const plan = { mode: 'replay', workers: 2, caseIds: ['C1'], repetitions: 2, maximumProviderRequests: 4, wallSeconds: 60,
    outputRoot: path.join(root, 'output'), adapterManifest, cases: [{ id: 'C1' }] };
  const bytes = Buffer.from(JSON.stringify(plan));
  const signal = new AbortController().signal;
  const preflights = [];
  const live = [];
  const run = await prepareReplayRun(path.join(root, 'plan.json'), bytes, plan, signal, {
    checkPins: () => {},
    async spawnWorker(_command, [_worker, mode, snapshot, trialId]) {
      assert.deepEqual(fs.readFileSync(snapshot), bytes);
      if (mode === '--preflight') {
        preflights.push(trialId);
        return { type: 'report', exitCode: 0, report: { type: 'projected', presentationOccurrences: 1, payloadBytes: 1024,
          complete: true, encodingEquivalent: trialId !== 'C1-r1-TOON' } };
      }
      assert.equal(mode, '--trial');
      assert.equal(preflights.length, 4);
      live.push(trialId);
      return { type: 'report', exitCode: 0, report: { outcome: { type: 'correct', reasons: [] }, providerSends: 1 } };
    },
  });
  const ids = expandReplayPlan(plan).map(trial => trial.trialId);
  const receipts = await Promise.all(ids.map(id => run.run(id, signal)));
  assert.deepEqual(live.sort(), ['C1-r2-JSON', 'C1-r2-TOON']);
  assert.ok(receipts.filter(row => row.repetition === 1).every(row => row.outcome.reasons[0] === 'PAIR_ENCODING_MISMATCH' && row.providerSends === 0));
  const report = JSON.parse(fs.readFileSync(path.join(plan.outputRoot, 'report.json'), 'utf8'));
  assert.equal(report.plannedTrials, 4);
  assert.equal(report.preflight.qualifiedPairs, 1);
  assert.equal(report.ledger.providerSends.knownTotal, 2);
  await assert.rejects(run.run('C1-r2-JSON', signal), /REPLAY_TRIAL_REJECTED/);
});
