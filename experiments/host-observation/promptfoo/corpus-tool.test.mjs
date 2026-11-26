import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { register } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

// Pi supplies typebox when it loads an extension. Only the schema builder is stubbed; admission and effects are real.
const schema = 'const node = () => ({}); export const Type = new Proxy({}, { get: () => node });';
register('data:text/javascript,' + encodeURIComponent(`
  export async function resolve(specifier, context, next) {
    if (specifier === 'typebox') return { url: 'data:text/javascript,' + encodeURIComponent(${JSON.stringify(schema)}), shortCircuit: true };
    return next(specifier, context);
  }`));

function corpusWorkspace(t) {
  const root = mkdtempSync(join(tmpdir(), 'kast-corpus-tool-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const workspace = join(root, 'workspace');
  const git = (...args) => execFileSync('git', ['-C', workspace, ...args], { encoding: 'utf8' }).trim();
  execFileSync('git', ['init', '-q', workspace]);
  execFileSync('mkdir', ['-p', join(workspace, 'core')]);
  writeFileSync(join(workspace, 'core', 'A.kt'), 'fun a() = Unit\n');
  git('add', '.');
  git('-c', 'user.name=t', '-c', 'user.email=t@example.invalid', 'commit', '-q', '-m', 'fixture');
  const calls = join(root, 'calls.jsonl');
  const kast = join(root, 'kast');
  writeFileSync(kast, `#!${process.execPath}
require('node:fs').appendFileSync(${JSON.stringify(calls)}, JSON.stringify(process.argv.slice(2)) + '\\n');
process.stdout.write(JSON.stringify({ type: 'complete', document: { status: 'complete', items: [],
  retention: { reference: 'result:v1:issued' } } }));
`, { mode: 0o700 });
  return { workspace, calls, kast, revision: git('rev-parse', 'HEAD'), git: execFileSync('which', ['git'], { encoding: 'utf8' }).trim() };
}

async function kastTool(t, { recipe } = {}) {
  const fixture = corpusWorkspace(t);
  Object.assign(process.env, { KAST_CORPUS_WORKSPACE: fixture.workspace, KAST_CORPUS_REVISION: fixture.revision,
    KAST_CORPUS_GIT: fixture.git, KAST_CORPUS_KAST: fixture.kast });
  if (recipe) process.env.KAST_CORPUS_RECIPE = recipe;
  else delete process.env.KAST_CORPUS_RECIPE;
  const tools = new Map();
  const { default: extension } = await import('./corpus-tool.ts');
  extension({ registerTool: tool => tools.set(tool.name, tool) });
  const run = async (argv, name = 'kast') => {
    const result = await tools.get(name).execute('call', { argv });
    return { error: result.isError, details: result.details, text: result.content[0].text };
  };
  const executed = () => existsSync(fixture.calls)
    ? readFileSync(fixture.calls, 'utf8').trim().split('\n').map(line => JSON.parse(line)) : [];
  return { run, executed };
}

test('the corpus Kast tool admits the read-only query forms the shipped skill teaches', async t => {
  const { run, executed } = await kastTool(t);
  const taught = [
    ['query', '--name', 'unaryMinus', '--kind', 'function', '--in', 'core', '--jq', '{type, status: .document.status}'],
    ['query', '--all', '--kind', 'function', '--in', 'core', '--source-set', 'main', '--visibility', 'public', '--jq', '.'],
    ['query', '--name', 'a', '--relation', 'callers', '--relation-in', 'core', '--relation-source-set', 'main',
      '--relation-production-only', '--relation-exclude-generated', '--output', 'occurrences', '--retain', '--json'],
    ['query', '--library', 'kotlin.collections', '--name', 'listOfNotNull', '--fields', 'name,signature,location'],
    ['result', 'read', '--ref', 'result:v1:issued', '--output', 'occurrences', '--jq', '.document.items'],
  ];
  for (const argv of taught) {
    const reply = await run(argv);
    assert.equal(reply.error, false, `${argv.join(' ')} was refused: ${reply.text}`);
  }
  assert.deepEqual(executed(), taught);
});

test('a refused Kast command names its cause and flag and never reaches the executable', async t => {
  const { run, executed } = await kastTool(t);
  const refusals = [
    [['query', '--name', 'a', '--workspace', '/tmp'], 'FLAG_NOT_ADMITTED', '--workspace'],
    [['query', '--name', 'a', '--jq', '$ENV'], 'JQ_NOT_ADMITTED', '--jq'],
    [['query', '--name', 'a', '--jq', 'input'], 'JQ_NOT_ADMITTED', '--jq'],
    [['query', '--ref', 'exact:v5:x', '--intersect', 'result:v1:not-issued'], 'HANDLE_NOT_ISSUED', '--intersect'],
    [['query', '--name', 'a', '--in', '../outside'], 'PATH_OUTSIDE_WORKSPACE', undefined],
    [['query', '--kind', 'function'], 'SOURCE_MISSING', undefined],
    [['config', 'set'], 'COMMAND_NOT_ADMITTED', undefined],
  ];
  for (const [argv, cause, flag] of refusals) {
    const reply = await run(argv);
    assert.equal(reply.error, true, argv.join(' '));
    assert.deepEqual(JSON.parse(reply.text), { error: 'KAST_COMMAND_REJECTED', cause, ...(flag && { flag }) });
    assert.equal(reply.details.reason, 'KAST_COMMAND_REJECTED');
    assert.equal(reply.details.cause, cause);
  }
  assert.deepEqual(executed(), []);
});

test('the recipe tool admits read-only recipe forms, issues their result and refuses the rest unrun', async t => {
  const root = mkdtempSync(join(tmpdir(), 'kast-recipe-stub-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const calls = join(root, 'recipe.jsonl');
  const recipe = join(root, 'kast-recipe');
  const issued = 'result:v1:0d6e2c5a-7b3f-4c1d-9e8a-6f5b4a3c2d1e';
  writeFileSync(recipe, `#!/bin/sh
printf '%s\\n' "$*" >> ${JSON.stringify(calls)}
printf '%s' '{"verdict":{"type":"complete","status":"complete","exhaustive":true,"failures":0,"omissions":0,"total":0,"excluded_callback_calls":2,"evidence_complete":true},"result":"${issued}","items":[]}'
`, { mode: 0o700 });
  const { run } = await kastTool(t, { recipe });
  const recipeCalls = () => existsSync(calls) ? readFileSync(calls, 'utf8').trim().split('\n') : [];
  const admittedForms = [
    ['verdict', '--ref', 'exact:v5:A', '--relation', 'callers'],
    ['verdict', '--ref', 'exact:v5:B', '--relation', 'callers', '--intersect', issued],
    ['call-sites', '--ref', 'exact:v5:A', '--relation-in', 'core', '--relation-production-only'],
    ['impact', '--ref', 'exact:v5:A', '--seed', '2'],
  ];
  for (const argv of admittedForms) {
    const reply = await run(argv, 'kast_recipe');
    assert.equal(reply.error, false, `${argv.join(' ')} was refused: ${reply.text}`);
    assert.equal(reply.details.receipt.kind, 'recipe');
    assert.equal(reply.details.receipt.complete, true);
  }
  assert.equal(recipeCalls().length, admittedForms.length);
  for (const [argv, cause] of [
    [['verdict', '--name', 'a', '--jq', '$ENV'], 'JQ_NOT_ADMITTED'],
    [['verdict', '--request', 'walk.json'], 'REQUEST_DOCUMENT_NOT_ADMITTED'],
    [['call-sites', '--ref', 'exact:v5:A', '--in', 'core'], 'FLAG_NOT_ADMITTED'],
    [['call-sites', '--ref', 'exact:v5:A', '--relation-in', '../outside'], 'PATH_OUTSIDE_WORKSPACE'],
    [['impact', '--name', 'a'], 'FLAG_NOT_ADMITTED'],
    [['shell'], 'COMMAND_NOT_ADMITTED'],
  ]) {
    const reply = await run(argv, 'kast_recipe');
    assert.equal(reply.error, true, argv.join(' '));
    assert.equal(JSON.parse(reply.text).cause, cause, argv.join(' '));
  }
  assert.equal(recipeCalls().length, admittedForms.length);
});
