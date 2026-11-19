import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { createDeveloperProvider, gradeDeveloperResponse } from './developer-provider.mjs';
import { child, settlePiTrial } from './skill-provider.mjs';
import { listWorkspaceFiles, readWorkspaceFile, replaceWorkspaceText, searchWorkspace } from './developer-workspace.mjs';
import * as workspaceTools from './developer-workspace.mjs';

const path = 'src/main/kotlin/Example.kt';
const referenceText = 'fun code() = 1\n';
const task = { id: 'example', kind: 'edit', prompt: 'Change the value to two.', warmup: { name: 'code', kind: 'function' } };

async function writeFixture(workspace) {
  await mkdir(dirname(join(workspace, path)), { recursive: true });
  await writeFile(join(workspace, path), referenceText);
  await writeFile(join(workspace, 'build.gradle.kts'), 'trusted build\n');
}

test('participant file tools limit edits to unique spans in existing Kotlin production sources', async t => {
  const root = await mkdtemp(join(tmpdir(), 'kast-developer-tool-control-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const workspace = join(root, 'workspace');
  await writeFixture(workspace);
  await writeFile(join(root, 'outside.kt'), 'hidden oracle data');
  await symlink(join(root, 'outside.kt'), join(workspace, 'src/main/kotlin/linked.kt'));
  assert.deepEqual(listWorkspaceFiles(workspace), ['build.gradle.kts', path]);
  assert.deepEqual(searchWorkspace(workspace, 'code()'), { matches: [{ path, line: 1, text: 'fun code() = 1' }], truncated: false });
  for (const candidate of ['../outside.kt', join(root, 'outside.kt'), 'src/main/kotlin/linked.kt']) {
    assert.throws(() => readWorkspaceFile(workspace, candidate), /PATH_REJECTED/);
  }
  assert.throws(() => replaceWorkspaceText(workspace, 'build.gradle.kts', 'trusted', 'modified'), /EDIT_REJECTED/);
  assert.throws(() => replaceWorkspaceText(workspace, path, 'missing', 'modified'), /EDIT_TARGET_NOT_UNIQUE/);
  assert.deepEqual(replaceWorkspaceText(workspace, path, '1', '2'), { path, changed: true });
  assert.equal(readWorkspaceFile(workspace, path), 'fun code() = 2\n');
  assert.equal(await readFile(join(root, 'outside.kt'), 'utf8'), 'hidden oracle data');
});

test('participant creation admits a new Kotlin file and preserves existing and external paths', async t => {
  const root = await mkdtemp(join(tmpdir(), 'kast-developer-create-control-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const workspace = join(root, 'workspace');
  await writeFixture(workspace);
  const newPath = 'src/main/kotlin/Added.kt';
  assert.equal(typeof workspaceTools.createWorkspaceFile, 'function');
  assert.deepEqual(workspaceTools.createWorkspaceFile(workspace, newPath, 'fun added() = 3\n'), { path: newPath });
  assert.equal(readWorkspaceFile(workspace, newPath), 'fun added() = 3\n');
  const outside = join(root, 'outside');
  await mkdir(outside);
  await symlink(outside, join(workspace, 'src/main/kotlin/linked'));
  await symlink(join(outside, 'Hidden.kt'), join(workspace, 'src/main/kotlin/Symlink.kt'));
  for (const candidate of [path, newPath, '../Outside.kt', 'build.gradle.kts',
    'src/main/kotlin/missing/New.kt', 'src/main/kotlin/linked/New.kt', 'src/main/kotlin/Symlink.kt']) {
    assert.throws(() => workspaceTools.createWorkspaceFile(workspace, candidate, 'overwrite'), /EDIT_REJECTED|EDIT_TARGET_EXISTS|PATH_REJECTED|ENOENT/);
  }
  assert.equal(readWorkspaceFile(workspace, path), referenceText);
  assert.equal(existsSync(join(outside, 'Hidden.kt')), false);
  assert.equal(existsSync(join(outside, 'New.kt')), false);
});

const participantModel = process.env.KAST_EVAL_PI_MODEL ?? 'openai-codex/gpt-6.1-sol';
const participantUsage = { input: 12, cacheRead: 4, cacheWrite: 2, output: 3, totalTokens: 21,
  cost: { input: 0.012, cacheRead: 0.004, cacheWrite: 0.002, output: 0.003, total: 0.021, totalFromProvider: true } };
const participantMessage = (overrides = {}) => ({ role: 'assistant', provider: participantModel.split('/')[0],
  model: participantModel.split('/')[1], responseId: 'synthetic-provider-response', stopReason: 'stop',
  content: [{ type: 'text', text: '{"pass":true}' }], usage: participantUsage, ...overrides });
const receipt = () => ({ ...settlePiTrial({ status: 0,
  stdout: JSON.stringify({ type: 'message_end', message: participantMessage() }) + '\n' }, participantModel, [], true,
  { toolUsagePolicy: 'no-model-helpers' }), toolProposals: 1, elapsedMillis: 1 });

const runCase = (provider, arm) => provider.callApi(task.prompt, { vars: { caseId: task.id, arm } });

test('paired provider gives both participants the same controls and independently scores work', async () => {
  const setups = [], stops = [];
  let gradings = 0;
  const provider = createDeveloperProvider({
    cases: [task], preflightOracle: async () => {}, prepareWorkspace: (_task, workspace) => writeFixture(workspace),
    async gradeWorkspace(_task, workspace) {
      gradings++;
      const pass = await readFile(join(workspace, path), 'utf8') === 'fun code() = 2\n';
      return { pass, reason: pass ? 'MATCHED' : 'UNCHANGED', patch: pass ? 'the observed patch' : '' };
    },
  }, {
    compile: () => ({ type: 'COMPILED' }), getKast: async () => 'owned-kast', getGradleHome: async () => '/gradle',
    warmup: async () => true,
    stop: async (_kast, workspace) => { stops.push(workspace); return true; },
    async runPi(input) {
      const workspace = input.extensionEnvironment.KAST_DEV_EVAL_WORKSPACE;
      setups.push(input);
      if (input.toolNames.includes('kast')) replaceWorkspaceText(workspace, path, '1', '2');
      return { ...receipt(), guidance: input.guidance };
    },
  });
  const source = await runCase(provider, 'source');
  const kast = await runCase(provider, 'kast');
  assert.equal(setups[0].prompt, setups[1].prompt);
  for (const [file, field] of [['SKILL.md', 'skillSha256'], ['public-cli.md', 'guideSha256']]) {
    const contents = await readFile(new URL('../../../agent-tools/skills/kast/' + file, import.meta.url), 'utf8');
    assert.equal(setups[1].guidance?.[file === 'SKILL.md' ? 'skillText' : 'guideText'], contents);
    assert.ok(!setups[0].system.includes(contents));
    assert.equal(kast.metadata[field], createHash('sha256').update(contents).digest('hex'));
    assert.equal(source.metadata[field], undefined);
  }
  assert.equal(setups[0].guidance, undefined);
  assert.match(setups[1].guidance.text, /Kast is available for compiler-backed analysis and symbol resolution in Kotlin code and Gradle projects\./);
  assert.match(setups[1].guidance.text, /a correct solution does not require a Kast call/);
  assert.doesNotMatch(setups[0].system, /Use Kast/);
  assert.equal(setups[0].timeout, setups[1].timeout);
  assert.deepEqual(setups[1].toolNames, [...setups[0].toolNames, 'kast']);
  assert.deepEqual(stops, [setups[1].extensionEnvironment.KAST_DEV_EVAL_WORKSPACE]);
  assert.ok(setups.every(input => !existsSync(input.directory)));
  assert.equal(source.metadata.sourceSha256, kast.metadata.sourceSha256);
  assert.equal(source.metadata.model, kast.metadata.model);
  assert.equal(source.metadata.effort, kast.metadata.effort);
  assert.equal(source.metadata.hostAvailable, null);
  assert.equal(kast.metadata.hostAvailable, true);
  assert.equal(kast.metadata.kastCalls, 0);
  assert.equal(gradings, 2);
  assert.equal(gradeDeveloperResponse(source).pass, false);
  assert.equal(gradeDeveloperResponse(kast).pass, true);
  assert.equal(kast.metadata.patch, 'the observed patch');
  assert.equal(source.metadata.patch, '');
  assert.deepEqual(source.tokenUsage, { total: 21, prompt: 18, completion: 3, cached: 4, numRequests: 1 });
  assert.equal(source.cost, 0.021);
  assert.equal(source.metadata.accounting.costs.task.provenance, 'provider-reported');
  assert.deepEqual(source.metadata.context.input, { state: 'complete', sum: 18, peak: 18, final: 18 });
  assert.equal(source.metadata.evidence.final.text, '{"pass":true}');
  assert.equal(source.metadata.panel, 'A');
  assert.equal(kast.metadata.panel, 'A');
  assert.equal(source.metadata.baseline.qualification, 'TASK_QUALIFIED');
  assert.equal(setups[0].toolUsagePolicy, 'no-model-helpers');
  assert.equal(source.metadata.toolCalls, 1);
  assert.equal(source.metadata.toolExecutions, 0);
});

test('Kast work cannot receive a grade if the launcher omits its skill receipt', async () => {
  const provider = createDeveloperProvider({
    cases: [task], preflightOracle: async () => {}, prepareWorkspace: (_task, workspace) => writeFixture(workspace),
    gradeWorkspace: async () => assert.fail('unqualified Kast trial reached the grader'),
  }, {
    compile: () => ({ type: 'COMPILED' }), getKast: async () => 'owned-kast', getGradleHome: async () => undefined,
    warmup: async () => true, stop: async () => true, runPi: async () => receipt(),
  });
  const result = await runCase(provider, 'kast');
  assert.equal(result.error, 'DEVELOPER_GUIDANCE_DELIVERY_FAILED');
  assert.equal(result.metadata.skillSha256, undefined);
});

test('an unavailable exact Host stops the trial before model use', async () => {
  let stopped;
  const provider = createDeveloperProvider({
    cases: [task], preflightOracle: async () => {}, prepareWorkspace: (_task, workspace) => writeFixture(workspace),
    gradeWorkspace: async () => assert.fail('grade without a participant'),
  }, {
    compile: () => ({ type: 'COMPILED' }), getKast: async () => 'owned-kast', getGradleHome: async () => '/gradle',
    warmup: async () => false, stop: async (_kast, workspace) => { stopped = workspace; return true; },
    runPi: async () => assert.fail('model use without a qualified Host'),
  });
  const response = await runCase(provider, 'kast');
  assert.equal(response.error, 'DEVELOPER_HOST_PREFLIGHT_FAILED');
  assert.equal(response.metadata.hostAvailable, false);
  assert.equal(Object.hasOwn(response, 'tokenUsage'), false);
  assert.equal(gradeDeveloperResponse(response).pass, false);
  assert.match(stopped, /project-[A-Za-z0-9]{6}\/workspace$/);
  assert.equal(existsSync(stopped), false);
});

test('failed developer preflight includes the entire boundary and role-separated phase timing', async () => {
  let tick = 0;
  const provider = createDeveloperProvider({
    cases: [task],
    prepareWorkspace: async (_task, workspace) => { tick += 10; await writeFixture(workspace); },
    preflightOracle: async () => { tick += 20; },
    gradeWorkspace: async () => assert.fail('grade without a qualified Host'),
  }, {
    clock: () => tick,
    compile: () => { tick += 30; return { type: 'COMPILED' }; },
    getKast: async () => { tick += 1; return 'owned-kast'; },
    getGradleHome: async () => { tick += 2; return '/gradle'; },
    warmup: async () => { tick += 40; return false; },
    stop: async () => { tick += 50; return true; },
    runPi: async () => assert.fail('model use without a qualified Host'),
  });
  const response = await runCase(provider, 'kast');
  assert.equal(response.error, 'DEVELOPER_HOST_PREFLIGHT_FAILED');
  assert.equal(response.metadata.panel, 'RESTRICTED');
  assert.equal(response.metadata.requestedPanel, 'A');
  assert.equal(response.metadata.admission.state, 'BLOCKED');
  assert.equal(response.metadata.timing.wallMillis, 153);
  assert.deepEqual(response.metadata.timing.phases.map(phase => [phase.phase, phase.role, phase.outcome, phase.elapsedMillis]), [
    ['preparation', 'coordinator', 'COMPLETED', 10],
    ['oracle-preflight', 'grader', 'COMPLETED', 20],
    ['compiler-preflight', 'coordinator', 'COMPLETED', 30],
    ['host-preflight', 'coordinator', 'FAILED', 43],
    ['participant', 'participant', 'NOT_RUN', undefined],
    ['grading', 'grader', 'NOT_RUN', undefined],
    ['cleanup', 'coordinator', 'COMPLETED', 50],
  ]);
});

test('a participant cutoff grades the observed workspace and preserves only known usage', async () => {
  let invocation = 0;
  let gradings = 0;
  const provider = createDeveloperProvider({
    cases: [task], preflightOracle: async () => {}, prepareWorkspace: (_task, workspace) => writeFixture(workspace),
    async gradeWorkspace(_task, workspace) {
      gradings++;
      const pass = readWorkspaceFile(workspace, path) === 'fun code() = 2\n';
      return { pass, reason: pass ? 'MATCHED' : 'INCOMPLETE', patch: pass ? 'complete edit' : '' };
    },
  }, {
    compile: () => ({ type: 'COMPILED' }),
    async runPi(input) {
      invocation++;
      if (invocation === 1) replaceWorkspaceText(input.extensionEnvironment.KAST_DEV_EVAL_WORKSPACE, path, '1', '2');
      if (invocation === 3) return { ...settlePiTrial({ status: 0, stdout: stream(
        { type: 'message_end', message: participantMessage({ stopReason: 'error', content: [] }) },
      ) }, participantModel, [], true, { toolUsagePolicy: 'no-model-helpers' }), elapsedMillis: 1 };
      return { ...settlePiTrial({ status: null, termination: 'PARTICIPANT_DEADLINE', stdout: stream(
        { type: 'message_end', message: participantMessage({ stopReason: 'toolUse', content: [] }) },
        { type: 'message_start', message: participantMessage({ responseId: 'pending-response', stopReason: 'pending', usage: undefined, content: [] }) },
        { type: 'message_update', assistantMessageEvent: { type: 'text_delta', contentIndex: 0, delta: 'partial answer' } },
      ) }, participantModel, [], true, { toolUsagePolicy: 'no-model-helpers' }), elapsedMillis: 1 };
    },
  });
  const completed = await runCase(provider, 'source');
  const incomplete = await runCase(provider, 'source');
  const unavailable = await runCase(provider, 'source');
  assert.equal(gradings, 2);
  assert.equal(gradeDeveloperResponse(completed).pass, true);
  assert.equal(completed.metadata.patch, 'complete edit');
  assert.equal(completed.metadata.termination, 'PARTICIPANT_DEADLINE');
  assert.deepEqual(completed.metadata.partialTranscript, { assistant: ['partial answer'], tools: [], truncated: false });
  assert.equal(Object.hasOwn(completed, 'tokenUsage'), false);
  assert.equal(completed.metadata.finalizedUsage.total, 21);
  assert.equal(completed.metadata.accounting.costs.task.state, 'incomplete');
  assert.equal(completed.metadata.accounting.costs.task.knownTotal, 0.021);
  assert.equal(completed.cost, undefined);
  assert.equal(gradeDeveloperResponse(incomplete).reason, 'INCOMPLETE');
  assert.equal(Object.hasOwn(incomplete, 'error'), false);
  assert.equal(unavailable.error, 'DEVELOPER_PARTICIPANT_EXECUTION_FAILED');
  assert.equal(unavailable.metadata.terminalCause, 'PI_PROVIDER_ERROR');
  assert.equal(unavailable.metadata.accounting.costs.task.knownTotal, 0.021);
});

test('a timed-out individual tool leaves normal participant work eligible for grading', async () => {
  let attempts = 0;
  let gradeCount = 0;
  const provider = createDeveloperProvider({
    cases: [task], preflightOracle: async () => {}, prepareWorkspace: (_task, workspace) => writeFixture(workspace),
    gradeWorkspace: async () => { gradeCount++; return { pass: true, reason: 'MATCHED' }; },
  }, {
    compile: () => ({ type: 'COMPILED' }),
    runPi: async () => ({ ...receipt(), toolCalls: 2,
      calls: [{ tool: 'compile_project', reason: attempts++ === 0 ? 'COMPILER_TIME_LIMIT' : 'COMPILER_UNAVAILABLE' }, { tool: 'read_file' }] }),
  });
  const response = await runCase(provider, 'source');
  assert.equal(gradeDeveloperResponse(response).pass, true);
  assert.equal(response.metadata.termination, 'COMPLETED');
  assert.equal(response.metadata.toolResourceLimits, 1);
  const unavailable = await runCase(provider, 'source');
  assert.equal(unavailable.error, 'DEVELOPER_PARTICIPANT_TOOL_UNAVAILABLE_FAILED');
  assert.equal(gradeCount, 1);
});

const testModel = 'openai-codex/test-model';
const message = (stopReason, text, usage) => ({ role: 'assistant', provider: 'openai-codex', model: 'test-model',
  stopReason, content: text ? [{ type: 'text', text }] : [], usage });
const modelUsage = { input: 12, cacheRead: 3, cacheWrite: 2, output: 4, totalTokens: 21 };
const stream = (...events) => events.map(event => JSON.stringify(event) + '\n').join('');

test('Pi cutoff decoder keeps complete events and available text without inventing usage', () => {
  const bounded = settlePiTrial({ status: null, termination: 'PARTICIPANT_DEADLINE', stdout: stream(
    { type: 'message_end', message: message('toolUse', '', modelUsage) },
    { type: 'message_start', message: message('pending') },
    { type: 'message_update', assistantMessageEvent: { type: 'text_delta', contentIndex: 0, delta: '{"trace":' } },
    { type: 'message_update', assistantMessageEvent: { type: 'text_delta', contentIndex: 0, delta: 'true}' } },
  ) + '{"partial' }, testModel, [], true);
  assert.equal(bounded.termination, 'PARTICIPANT_DEADLINE');
  assert.equal(bounded.reply, '{"trace":true}');
  assert.equal(bounded.usage, null);
  assert.equal(bounded.finalizedUsage.total, 21);

  const length = usage => settlePiTrial({ status: 0, stdout: stream(
    { type: 'message_end', message: message('length', 'partial answer', usage) },
  ) }, testModel, [], true);
  assert.equal(length(modelUsage).termination, 'MODEL_OUTPUT_LIMIT');
  assert.equal(length(modelUsage).usage.total, 21);
  assert.equal(length(undefined).usage, null);
  assert.equal(length({ ...modelUsage, totalTokens: 20 }).usage, null);

  const failed = stream({ type: 'message_end', message: message('error', '', modelUsage) });
  assert.ok(settlePiTrial({ status: 0, stdout: failed }, testModel, [], true).error);
  assert.throws(() => settlePiTrial({ status: null, termination: 'PARTICIPANT_DEADLINE', stdout: failed }, testModel, [], true), /admissible/);
  assert.throws(() => settlePiTrial({ status: null, termination: 'PARTICIPANT_DEADLINE', stdout: '' }, testModel, [], true), /admissible/);
});

test('an owned child cutoff preserves the stdout prefix after the child is reaped', async () => {
  const result = await child(process.execPath, ['-e', 'console.log(process.pid); setInterval(() => {}, 1000)'], {
    timeout: 500, captureLimits: true,
  });
  assert.equal(result.termination, 'PARTICIPANT_DEADLINE');
  const pid = Number(result.stdout);
  assert.ok(Number.isSafeInteger(pid) && pid > 0);
  assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
});
