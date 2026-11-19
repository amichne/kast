import assert from 'node:assert/strict';
import { readFile, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import { casesForSuite, delegateModels, suites } from './corpus-cases.mjs';
import { createCorpusProvider } from './corpus.config.mjs';
import { decodePiTrial } from './skill-provider.mjs';
import { projectPiTrial } from './pi-accounting.mjs';

const task = suites.flatMap(casesForSuite)[0];
const context = { vars: { caseId: task.id, model: delegateModels[0], arm: 'source' } };
const environment = { KAST_CORPUS_ROOT: '/tmp' };
const reply = JSON.stringify(task.expected);

function receipt() {
  const message = { role: 'assistant', provider: 'openai-codex', model: delegateModels[0],
    responseModel: delegateModels[0], stopReason: 'stop', content: [{ type: 'text', text: reply }],
    usage: { input: 10, output: 2, cacheRead: 0, cacheWrite: 0, totalTokens: 12,
      cost: { input: 0.01, output: 0.02, cacheRead: 0, cacheWrite: 0, total: 0.03, totalFromProvider: true } } };
  return projectPiTrial(decodePiTrial(JSON.stringify({ type: 'message_end', message }),
    'openai-codex/' + delegateModels[0], [], true, { toolUsagePolicy: 'no-model-helpers' }));
}

test('corpus provider preserves its pinned request and exact-set answer', async () => {
  let calls = 0, directory;
  const provider = createCorpusProvider({ cases: [task] }, { environment,
    async runChild(command, args, options) {
      calls++;
      assert.equal(command, 'uv');
      assert.ok(args.includes('corpus_trial.py::test_delegate'));
      directory = options.env.KAST_CORPUS_TRIAL;
      const request = JSON.parse(await readFile(join(directory, 'request.json'), 'utf8'));
      assert.equal(request.caseId, task.id);
      assert.equal(request.revision, task.revision);
      assert.equal(request.prompt, task.task);
      assert.equal(request.requestedPanel, 'RESTRICTED');
      await writeFile(join(directory, 'response.json'), JSON.stringify({ output: reply, metadata: { participant: 'observed' } }));
      return { status: 0, stdout: '' };
    } });
  const result = await provider.callApi(task.task, context);
  assert.equal(calls, 1);
  assert.equal(result.output, reply);
  assert.equal(result.metadata.revision, task.revision);
  assert.equal(result.metadata.participant, 'observed');
  await assert.rejects(stat(directory), { code: 'ENOENT' });
});

test('a failed post-run workspace check retains the delivered answer and known receipt', async () => {
  const known = receipt();
  let calls = 0;
  const provider = createCorpusProvider({ cases: [task] }, { environment,
    async runChild(_command, _args, options) {
      calls++;
      await writeFile(join(options.env.KAST_CORPUS_TRIAL, 'response.json'),
        JSON.stringify({ ...known, output: reply, metadata: { ...known.metadata,
          timing: { boundary: 'CORPUS_PARTICIPANT', clock: 'MONOTONIC', wallMillis: 5, phases: [] } } }));
      return { status: 1, stdout: 'E ValueError: workspace changed\n' };
    } });
  const result = await provider.callApi(task.task, context);
  assert.equal(calls, 1);
  assert.equal(result.output, reply);
  assert.deepEqual(result.metadata.accounting, known.metadata.accounting);
  assert.equal(result.cost, 0.03);
  assert.match(result.error, /workspace changed/);
  assert.equal(result.metadata.grade.pass, false);
  assert.equal(result.metadata.timing.boundary, 'CORPUS_PROVIDER');
  assert.equal(result.metadata.participantTiming.boundary, 'CORPUS_PARTICIPANT');
  assert.equal(result.metadata.timing.phases.find(row => row.phase === 'participant').outcome, 'FAILED');
});

test('a provider keeps its owned directory when child reaping is unproven', async () => {
  let directory;
  const provider = createCorpusProvider({ cases: [task] }, { environment,
    async runChild(_command, _args, options) {
      directory = options.env.KAST_CORPUS_TRIAL;
      throw Object.assign(Error('unsettled'), { code: 'PI_CHILD_REAP_UNAVAILABLE' });
    } });
  try {
    const result = await provider.callApi(task.task, context);
    assert.equal(result.error, 'PI_CHILD_REAP_UNAVAILABLE');
    assert.equal(result.metadata.cleanup, 'PARTICIPANT_CLEANUP_FAILED');
    assert.equal((await stat(directory)).isDirectory(), true);
    assert.equal(result.cost, undefined);
  } finally {
    // The injected process never ran; the fixture owns and can remove this directory.
    if (directory) await rm(directory, { recursive: true, force: true });
  }
});
