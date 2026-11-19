import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { mkdtemp, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import test from 'node:test';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import config from './skill.config.mjs';
import { kastInstruction, loadKastGuidance } from './kast-guidance.mjs';
import { armCapabilities, completionFile, invocationFile, originalCases, skillCases } from './skill-cases.mjs';
import { gradeSkill, planSkillDraft } from './skill-grading.mjs';
import SkillProvider, { decodePiTrial, prepareFixtureWorkspace, runIsolatedPi } from './skill-provider.mjs';
import { permittedKast, runtimeEnvironment } from './skill-sandbox.mjs';

const workspace = '/fixture';
const live = { root: workspace, host: 'test-host', epoch: 1 };
const selection = (name, path, kind) => ({
  type: 'symbols', match: { type: 'name', matching: 'exact-name', text: name },
  scope: { sourceSets: ['main'], directory: { path, containment: 'descendants' }, packageName: null },
  declarationKinds: [kind],
});
const selected = (name, path, kind) => ({
  from: selection(name, path, kind), steps: [], output: { type: 'symbols' },
});
const document = (question, items) => ({
  status: 'complete', live, question, items, coverage: { exhaustive: true },
  original_evidence_summary: { failure_count: 0, omission_count: 0 },
  evidence_window: { type: 'FINAL' }, invocation: { accumulated_row_count: items.length },
});
const call = (response, argv = ['query'], input) => ({
  tool: 'kast', status: response.type === 'rejected_document' ? 1 : 0,
  argv, input, stdout: JSON.stringify(response),
});
const reference = token => ({ type: 'references', values: [{ token }] });
const completion = (question, items) => ({ type: 'complete', document: document(question, items) });

test('concurrent trials and a later process admit one shared fixture for this worktree', async () => {
  const scratch = await mkdtemp(join(tmpdir(), 'kast-fixture-admission-test-'));
  const env = { ...process.env };
  delete env.KAST_EVAL_WORKSPACE;
  const provider = new URL('./skill-provider.mjs', import.meta.url).href;
  const setup = `import { prepareWorkspace } from ${JSON.stringify(provider)};
    console.log(JSON.stringify(await prepareWorkspace({ workspace: 'fixture' }, process.argv[1])));`;
  const invoke = async (trialEnvironment = env) => {
    const directory = await mkdtemp(join(scratch, 'trial-'));
    const { stdout } = await promisify(execFile)(process.execPath, ['--input-type=module', '-e', setup, directory], {
      env: trialEnvironment,
    });
    return JSON.parse(stdout);
  };
  try {
    const [first, second] = await Promise.all([invoke(), invoke()]);
    assert.deepEqual(first, second);
    assert.deepEqual(await invoke(), first);
    assert.equal(first.root, await realpath(fileURLToPath(new URL('../../../build/projects/callback-service', import.meta.url))));
    const external = fileURLToPath(new URL('../static-callback-fixture', import.meta.url));
    assert.deepEqual(await invoke({ ...env, KAST_EVAL_WORKSPACE: external }), { root: await realpath(external) });
    await assert.rejects(invoke({ ...env, KAST_EVAL_WORKSPACE: scratch }), /Kotlin sources differ from the fixture/);
  } finally {
    await rm(scratch, { recursive: true, force: true });
  }
});

test('a managed fixture rejects changed build and wrapper inputs without replacing them', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'kast-fixture-input-test-'));
  try {
    const [workspace, concurrent] = await Promise.all([prepareFixtureWorkspace(directory), prepareFixtureWorkspace(directory)]);
    assert.equal(workspace, concurrent);
    assert.deepEqual(await readdir(directory), ['callback-service']);
    for (const [file, error] of [
      ['settings.gradle.kts', /Gradle scripts differ/],
      ['invocation/build.gradle.kts', /Gradle scripts differ/],
      ['gradle/wrapper/gradle-wrapper.properties', /wrapper differs/],
    ]) {
      const path = join(workspace, file);
      const original = await readFile(path);
      await writeFile(path, 'changed');
      await assert.rejects(prepareFixtureWorkspace(directory), error);
      assert.equal(await readFile(path, 'utf8'), 'changed');
      await writeFile(path, original);
    }
    assert.equal(await prepareFixtureWorkspace(directory), workspace);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('Promptfoo retires unguided Kast and keeps canonical, version, and source controls distinct', async () => {
  assert.deepEqual(new Set(config.scenarios.map(row => row.config[0].vars.caseId)), new Set(Object.keys(skillCases)));
  assert.equal(armCapabilities['without-skill'], undefined);
  assert.deepEqual(config.scenarios.filter(row => row.config[0].vars.comparison === 'skill-version')
    .map(row => row.config[0].vars.caseId).toSorted(), originalCases.toSorted());
  for (const { tests, config: [base] } of config.scenarios) {
    assert.equal(base.vars.task, skillCases[base.vars.caseId].prompt);
    const expectedArms = {
      'canonical-skill': ['canonical'],
      'skill-version': ['reference', 'delivered'],
      'guided-kast-access': ['with-kast', 'without-kast'],
    }[base.vars.comparison];
    assert.deepEqual(tests.map(row => row.vars.arm), expectedArms);
    for (const row of tests) {
      assert.equal(armCapabilities[row.vars.arm].kast, row.vars.arm !== 'without-kast');
      assert.deepEqual(row.assert.map(assertion => assertion.metric), ['correctness']);
    }
  }
  const archived = await new SkillProvider().callApi(skillCases.select.prompt, {
    vars: { caseId: 'select', arm: 'without-skill', comparison: 'skill' },
  });
  assert.deepEqual(archived, { error: 'Invalid skill evaluation case or arm' });
});

test('the Pi launcher stages exact Kast files and supplies only a short pointer to guided participants', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'kast-guidance-launch-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const fakePi = join(directory, 'pi.mjs');
  const auth = join(directory, 'auth.json');
  const catalog = join(directory, 'models.json');
  const identity = Buffer.from(JSON.stringify({ 'https://api.openai.com/auth': { chatgpt_account_id: 'test' } })).toString('base64url');
  await writeFile(auth, JSON.stringify({ 'openai-codex': {
    type: 'oauth', access: `test.${identity}.test`, refresh: 'test', expires: Date.now() + 3_600_000,
  } }));
  const modelStore = { 'openai-codex': { checkedAt: 1000, models: [{ provider: 'openai-codex', id: 'test',
    headers: { Authorization: 'catalog-secret' }, cost: { input: 1, output: 1, cacheRead: 1, cacheWrite: 1 } }] },
    unrelated: { secret: 'unrelated-secret' } };
  await writeFile(catalog, JSON.stringify(modelStore));
  await writeFile(fakePi, `#!${process.execPath}
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
const args = process.argv.slice(2);
const extensions = args.flatMap((arg, index) => arg === '-e' ? [args[index + 1]] : []);
const system = readFileSync(args[args.indexOf('--system-prompt') + 1], 'utf8');
const files = Object.fromEntries(['SKILL.md', 'public-cli.md'].flatMap(name => {
  const path = 'skills/kast/' + name;
  return existsSync(path) ? [[name, readFileSync(path, 'utf8')]] : [];
}));
writeFileSync('observed.json', JSON.stringify({ extensions, system, files }));
if (extensions.some(path => path.endsWith('/kast-guidance-tool.ts'))) {
  const call = { toolName: 'read_kast_guidance', toolCallId: 'guide' };
  console.log(JSON.stringify({ type: 'tool_execution_start', ...call }));
  console.log(JSON.stringify({ type: 'tool_execution_end', ...call, result: { details: { path: 'skills/kast/SKILL.md' } } }));
}
console.log(JSON.stringify({ type: 'message_end', message: { role: 'assistant', provider: 'openai-codex', model: 'test',
  stopReason: 'stop', usage: { input: 2, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 3 },
  content: [{ type: 'text', text: 'answer' }] } }));
`, { mode: 0o700 });
  const env = { ...process.env, KAST_EVAL_PI: fakePi, KAST_EVAL_PI_AUTH: auth, KAST_EVAL_PI_MODELS: catalog };
  delete env.KAST_EVAL_CODEX_AUTH;
  const provider = new URL('./skill-provider.mjs', import.meta.url).href;
  const helper = new URL('./kast-guidance.mjs', import.meta.url).href;
  const setup = `import { runIsolatedPi } from ${JSON.stringify(provider)};
    import { loadKastGuidance } from ${JSON.stringify(helper)};
    const [directory, guided] = process.argv.slice(1);
    const options = { directory, system: 'Shared instructions.', prompt: 'Shared question.', model: 'openai-codex/test' };
    if (guided) Object.assign(options, { extension: 'participant-extension.ts', guidance: loadKastGuidance(), toolNames: ['kast'] });
    console.log(JSON.stringify(await runIsolatedPi(options)));`;
  async function invoke(guided) {
    const trialDirectory = await mkdtemp(join(directory, 'project-'));
    const args = ['--input-type=module', '-e', setup, trialDirectory, ...(guided ? ['guided'] : [])];
    const { stdout } = await promisify(execFile)(process.execPath, args, { env });
    return { trial: JSON.parse(stdout), observed: JSON.parse(await readFile(join(trialDirectory, 'observed.json'), 'utf8')),
      copiedCatalog: await readFile(join(trialDirectory, 'pi', 'models-store.json')) };
  }
  const [kast, source] = await Promise.all([invoke(true), invoke(false)]);
  const { skillText, guideText, skillSha256, guideSha256 } = loadKastGuidance();
  assert.deepEqual(kast.observed.files, { 'SKILL.md': skillText, 'public-cli.md': guideText });
  assert.deepEqual(kast.observed.extensions.map(path => path.split('/').at(-1)), ['participant-extension.ts', 'kast-guidance-tool.ts']);
  assert.ok(kast.observed.system.includes('Use read_kast_guidance'));
  assert.ok(kast.observed.system.includes('skills/kast/SKILL.md'));
  assert.equal(kast.observed.system.split(kastInstruction).length, 2);
  assert.ok(!kast.observed.system.includes(skillText) && !kast.observed.system.includes(guideText));
  assert.deepEqual(kast.trial.guidance, { skillSha256, guideSha256 });
  assert.equal(kast.trial.calls[0].tool, 'read_kast_guidance');
  assert.deepEqual(source.observed, { extensions: [], system: 'Shared instructions.', files: {} });
  assert.equal(source.trial.guidance, undefined);
  const runtime = source.trial.accounting.runtime;
  assert.equal(runtime.catalog.sha256, createHash('sha256').update(source.copiedCatalog).digest('hex'));
  assert.deepEqual(runtime.catalog.selected.cost, modelStore['openai-codex'].models[0].cost);
  assert.equal(runtime.executable.sha256, createHash('sha256').update(await readFile(fakePi)).digest('hex'));
  assert.equal(runtime.sourceRevision, 'unknown');
  assert.doesNotMatch(JSON.stringify(runtime), /catalog-secret|unrelated-secret|Authorization|refresh/);
  await assert.rejects(runIsolatedPi({ directory, prompt: 'Rejected.', toolNames: ['kast'] }), /Kast access needs participant guidance/);
});

test('historical skill snapshots keep the reported bytes in both arms', () => {
  const history = name => JSON.parse(readFileSync(new URL(`./history/pi-skill-cli-${name}.json`, import.meta.url), 'utf8'));
  const snapshot = history('snapshots');
  const { skills, trials } = history('results');
  assert.equal(trials.length, 24);
  for (const arm of ['reference', 'delivered']) {
    for (const file of ['SKILL.md', 'public-cli.md']) {
      assert.equal(createHash('sha256').update(snapshot[arm][file]).digest('hex'), skills[arm][file]);
    }
  }
});

test('tool permissions allow discovery in drafts and keep outside authority inaccessible', () => {
  assert.equal(permittedKast(['diagnostic', 'check', '--help'], 'draft'), true);
  assert.equal(permittedKast(['describe', 'query', '--jq', '.content.flags'], 'draft'), true);
  assert.equal(permittedKast(['query', '--name', 'Widget'], 'draft'), false);
  assert.equal(permittedKast(['query', '--name', 'Widget', '--workspace=/outside'], 'repository'), false);
  assert.equal(permittedKast(['query', '--name', 'Widget', '--in', '../outside'], 'repository'), false);
  assert.equal(permittedKast(['query', '--name', 'Widget', '--request', '/outside/document'], 'repository'), false);
  assert.equal(permittedKast(['describe', 'query', '--jq', '$ENV.PATH'], 'draft'), false);
  assert.equal(permittedKast(['query', '--request', '-'], 'repository', '{"request":{"type":"RESUME"}}'), false);
  assert.equal(permittedKast(['query', '--request', '-'], 'repository', '{"request":{"type":"READ_SOURCE","candidateRef":"issued"}}'), true);
  assert.equal(runtimeEnvironment({ HOME: '/home', OPENAI_API_KEY: 'sentinel' }).OPENAI_API_KEY, undefined);
  assert.deepEqual(runtimeEnvironment({ KAST_WORKSPACE_DEPENDENCY_SHARING: 'SHARED', GRADLE_USER_HOME: '/gradle' }), {
    KAST_WORKSPACE_DEPENDENCY_SHARING: 'SHARED', GRADLE_USER_HOME: '/gradle', KAST_OUTPUT_TOON: '0',
  });
});

test('draft planning forwards valid input and rejects ambient workspace overrides before firing', async () => {
  const answer = '["kast","query","--name","Widget"]';
  const admitted = await planSkillDraft('select', answer, (args, input) => {
    assert.deepEqual(args, ['--name', 'Widget']);
    assert.equal(input, undefined);
    return { status: 0, stdout: '{"type":"planned","request":{"from":"real planner result"}}' };
  });
  assert.deepEqual(admitted, { from: 'real planner result' });
  const denied = await planSkillDraft('select', '["kast","query","--name","Widget","--workspace","/outside"]',
    () => assert.fail('unadmitted CLI arguments reached the planner'));
  assert.equal(denied, undefined);
});

test('draft oracles preserve exact scope, one hop, limits, placeholders and distinct output kinds', () => {
  const evidence = { workspace, calls: [call({}, ['describe', 'query'])] };
  const select = {
    ...selected('Widget', 'app/src', 'class'),
    output: { type: 'symbols', fields: ['name', 'signature', 'location'] },
  };
  const selectionAnswer = '["kast","query","--name","Widget","--in","app/src","--source-set","main","--kind","class","--fields","name,signature,location"]';
  assert.equal(gradeSkill('select', selectionAnswer, { ...evidence, planned: select }).pass, true);
  assert.equal(gradeSkill('select', selectionAnswer, {
    ...evidence, planned: { ...select, from: selection('Widget', 'app', 'class') },
  }).pass, false);
  const walk = {
    from: reference('EXACT_REF'),
    steps: [{ type: 'walk', relation: 'callers', extent: { type: 'THROUGH_DEPTH', maximum_depth: 2 } }],
    output: { type: 'traversal_records' }, execution_budget: { max_results: 20 },
  };
  assert.equal(gradeSkill('walk', '{}', { ...evidence, planned: walk }).pass, true);
  walk.steps[0].extent.maximum_depth = 1;
  assert.equal(gradeSkill('walk', '{}', { ...evidence, planned: walk }).pass, false);
  const callees = { from: reference('EXACT_REF'), steps: [{ type: 'related', relation: 'callees' }], output: { type: 'symbols' } };
  assert.equal(gradeSkill('callees', '[]', { ...evidence, planned: callees }).pass, true);
  callees.steps[0].relation = 'callers';
  assert.equal(gradeSkill('callees', '[]', { ...evidence, planned: callees }).pass, false);
  const diagnostic = '["kast","diagnostic","check","--path","app/src","--max-diagnostics","12"]';
  assert.equal(gradeSkill('diagnostics', diagnostic, evidence).pass, true);
  assert.equal(gradeSkill('diagnostics', diagnostic.replace('"12"', '"13"'), evidence).pass, false);
  const retained = '["kast","result","read","--ref","RESULT_REF","--cursor","CURSOR","--output","symbols"]';
  assert.equal(gradeSkill('retained', retained, evidence).pass, true);
  assert.equal(gradeSkill('retained', retained.replace('CURSOR', 'OTHER_CURSOR'), evidence).pass, false);
});

test('absence needs the complete final result and presence needs the queried path', () => {
  const empty = completion(selected('scheduleCallbackRetry', 'invocation', 'function'), []);
  assert.equal(gradeSkill('absent', 'ABSENT', { workspace, calls: [call(empty)] }).pass, true);
  empty.document.coverage.exhaustive = false;
  assert.equal(gradeSkill('absent', 'ABSENT', { workspace, calls: [call(empty)] }).pass, false);
  const row = { name: 'invokeCallback', location: { file: join(workspace, invocationFile) } };
  const present = completion(selected('invokeCallback', 'invocation', 'function'), [row]);
  assert.equal(gradeSkill('present', invocationFile, { workspace, calls: [call(present)] }).pass, true);
  assert.equal(gradeSkill('present', invocationFile, { workspace, calls: [] }).pass, false);
  const plain = { tool: 'search_kotlin_source', matches: [{ file: invocationFile, line: 'fun invokeCallback(block: () -> String)' }] };
  assert.equal(gradeSkill('source-lookup', invocationFile, { workspace, calls: [plain] }).pass, true);
  assert.equal(gradeSkill('source-lookup', invocationFile, { workspace, calls: [] }).pass, false);
});

test('callback answer needs a preceding issued reference and obligations from the same rejection', () => {
  const seed = completion(selected('forwardOnce', 'forwarding', 'function'), [
    { ref: 'issued', name: 'forwardOnce', kind: 'function' },
  ]);
  const rejection = {
    type: 'rejected_document', document: { rejection: {
      type: 'COMPLETION_UNPROVEN', detail: {
        evidence: { question: { from: reference('issued'), steps: [{ type: 'related', relation: 'callees' }] } },
        cause: {
          type: 'CALLBACK_GRAPH_UNPROVEN',
          graphFailure: { cause: { obligations: ['VALUE_FLOW_UNKNOWN'] } },
        },
      },
    } },
  };
  const exact = 'CALLBACK_GRAPH_UNPROVEN';
  const metadata = { workspace, calls: [call(seed), call(rejection)] };
  assert.equal(gradeSkill('callback', exact, metadata).namedScores.exactFormat, 1);
  const qualified = gradeSkill('callback', exact + ' (VALUE_FLOW_UNKNOWN)', metadata);
  assert.equal(qualified.pass, true);
  assert.equal(qualified.namedScores.exactFormat, 0);
  assert.equal(gradeSkill('callback', exact + ' (OUTPUT_UNKNOWN)', metadata).pass, false);
  assert.equal(gradeSkill('callback', exact + ' (VALUE_FLOW_UNKNOWN', metadata).pass, false);
  assert.equal(gradeSkill('callback', exact, { workspace, calls: [call(rejection), call(seed)] }).pass, false);
});

test('guidance reads stay outside the per-case source and implementation query budget', () => {
  const name = 'QueryCompletionDocument';
  const file = join(workspace, completionFile);
  const seed = completion(selected(name, 'protocol', 'class'), [
    { name, location: { file }, ref: 'issued', kind: 'classlike' },
  ]);
  const source = { type: 'complete', document: {
    operation: 'source.read', status: 'complete', live, snapshot: { file },
    region: { kind: 'declaration' }, text: { type: 'returned', text: 'data class QueryCompletionDocument(' },
  } };
  const action = {
    request: { type: 'READ_SOURCE', candidateRef: 'issued', region: 'ANCHOR',
      text: { type: 'WINDOW', beforeLines: 10, afterLines: 10 } },
  };
  const expansion = {
    type: 'SOURCE_DOMAIN', source_sets: ['main'], directory: { path: 'protocol', containment: 'descendants' },
    source_policy: 'production-only', generated_sources: 'exclude',
  };
  const implementations = completion({
    from: reference('issued'),
    steps: [{ type: 'related', relation: 'implementations', expansion_scope: expansion }],
    output: { type: 'symbols' },
  }, []);
  const answer = JSON.stringify({ path: completionFile, declarationKind: 'data class', implementations: [] });
  const metadata = () => ({ workspace, calls: [
    { tool: 'read_kast_guidance', path: 'skills/kast/SKILL.md' },
    call(seed, ['query', '--name', name]),
    call(source, ['query', '--request', '-'], JSON.stringify(action)),
    { tool: 'read_kast_guidance', path: 'skills/kast/public-cli.md' },
    call(implementations, ['query', '--request', '-'], 'different input'),
  ] });
  assert.equal(gradeSkill('query-completion-document', answer, metadata()).pass, true);
  action.request.candidateRef = 'other';
  assert.equal(gradeSkill('query-completion-document', answer, metadata()).pass, false);
  action.request.candidateRef = 'issued';
  const missingSource = metadata();
  missingSource.calls.splice(2, 1);
  assert.equal(gradeSkill('query-completion-document', answer, missingSource).pass, false);
  expansion.directory.path = '.';
  assert.equal(gradeSkill('query-completion-document', answer, metadata()).pass, false);
});

test('Pi decoding preserves all token counters and attempted calls and rejects unrelated tool and model evidence', () => {
  const message = {
    role: 'assistant', provider: 'openai-codex', model: 'test-model', stopReason: 'stop',
    content: [{ type: 'text', text: 'ok' }],
    usage: { input: 1, output: 2, cacheRead: 3, cacheWrite: 4, totalTokens: 10 },
  };
  const args = { argv: ['query', '--request', '-'], stdin: '{}' };
  const events = [
    { type: 'tool_execution_start', toolName: 'kast', toolCallId: '1', args },
    { type: 'tool_execution_end', toolName: 'kast', toolCallId: '1' },
    { type: 'message_end', message },
  ];
  const stdout = events.map(row => JSON.stringify(row)).join('\n');
  const observed = decodePiTrial(stdout, 'openai-codex/test-model', ['kast']);
  assert.equal(observed.usage.total, 10);
  assert.equal(observed.usage.cacheWrite, 4);
  assert.equal(observed.toolCalls, 1);
  assert.deepEqual(observed.calls, [{ tool: 'kast', argv: args.argv, input: args.stdin }]);
  assert.throws(() => decodePiTrial(stdout, 'openai-codex/other', ['kast']), /different model/);
  assert.throws(() => decodePiTrial(stdout, 'openai-codex/test-model', []), /Unexpected Pi tool/);
});
