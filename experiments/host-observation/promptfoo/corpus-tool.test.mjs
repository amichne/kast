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

async function kastTool(t) {
  const fixture = corpusWorkspace(t);
  Object.assign(process.env, { KAST_CORPUS_WORKSPACE: fixture.workspace, KAST_CORPUS_REVISION: fixture.revision,
    KAST_CORPUS_GIT: fixture.git, KAST_CORPUS_KAST: fixture.kast });
  const tools = new Map();
  const { default: extension } = await import('./corpus-tool.ts');
  extension({ registerTool: tool => tools.set(tool.name, tool) });
  const run = async argv => {
    const result = await tools.get('kast').execute('call', { argv });
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
