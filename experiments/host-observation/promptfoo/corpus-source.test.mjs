import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { createCorpusSource } from './corpus-source.mjs';

async function fixture(t, files) {
  const workspace = await mkdtemp(join(tmpdir(), 'kast-corpus-source-'));
  t.after(() => rm(workspace, { recursive: true, force: true }));
  for (const [path, content] of Object.entries(files)) {
    await mkdir(dirname(join(workspace, path)), { recursive: true });
    await writeFile(join(workspace, path), content);
  }
  function git(...args) {
    const result = spawnSync('git', args, { cwd: workspace, encoding: 'utf8',
      env: { ...process.env, GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null' } });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  }
  git('init', '--quiet', '--template=');
  git('add', '--all');
  git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', 'Pinned fixture');
  const revision = git('rev-parse', 'HEAD');
  return { workspace, revision, source: createCorpusSource({ workspace, revision, git: 'git' }) };
}

test('source tools read only regular blobs from the pinned tree and literal requested scope', async t => {
  const { source, workspace } = await fixture(t, {
    'src/Example.kt': 'fun exact() = 1\n', 'src[old]/Other.kt': 'fun exact() = 2\n', 'README.md': 'exact documentation\n',
  });
  await writeFile(join(workspace, 'src/Example.kt'), 'uncommitted changes\n');
  await symlink('/etc/passwd', join(workspace, 'external.kt'));
  assert.equal(source.read({ path: 'src/Example.kt' }).rows[0].text, 'fun exact() = 1');
  assert.equal(source.hasFile('external.kt'), false);
  assert.deepEqual(source.search({ pattern: 'exact', scope: 'src[old]' }).rows.map(row => row.path), ['src[old]/Other.kt']);
  assert.deepEqual(source.list({ scope: 'src', suffix: '.kt' }).rows, ['src/Example.kt']);
  assert.throws(() => source.read({ path: '../private-oracle.kt' }), /PATH_REJECTED/);
});

test('source search provides continuation rather than silently dropping matching rows', async t => {
  const { source } = await fixture(t, { 'Many.kt': Array.from({ length: 32 }, (_, i) => `fun sample${i}() = ${i}`).join('\n') + '\n' });
  const first = source.search({ pattern: 'sample' });
  assert.equal(first.rows.length, 30);
  assert.equal(first.nextOffset, 30);
  const second = source.search({ pattern: 'sample', offset: first.nextOffset });
  assert.deepEqual(second.rows.map(row => row.line), [31, 32]);
  assert.equal(second.nextOffset, null);
});

test('source search selects suffix and literal scope before its capture budget', async t => {
  const { source } = await fixture(t, {
    'src/Useful.kt': 'needle target\n',
    'src/Noise.txt': 'needle '.repeat(2_500_000) + '\n',
    'elsewhere/Other.kt': 'needle outside scope\n',
  });
  const result = source.search({ pattern: 'needle', scope: 'src', suffix: '.kt' });
  assert.deepEqual(result.rows.map(row => [row.path, row.line, row.text]), [['src/Useful.kt', 1, 'needle target']]);
  assert.equal(result.truncated, false);
  assert.equal(result.totalRows, 1);
  assert.deepEqual(source.search({ pattern: 'needle', scope: 'src', suffix: '.java' }).rows, []);
});

test('source reads expose complete line and column continuation with explicit page completeness', async t => {
  const longLine = 'prefix:' + 'x'.repeat(1700) + ':end';
  const { source } = await fixture(t, { 'Long.kt': longLine + '\n' + Array.from({ length: 109 }, (_, i) => `line ${i + 2}`).join('\n') + '\n' });
  const first = source.read({ path: 'Long.kt', lineCount: 100 });
  assert.equal(first.rows.length, 100);
  assert.equal(first.totalLines, 110);
  assert.equal(first.nextLine, 101);
  assert.equal(first.truncated, true);
  const head = first.rows[0];
  assert.equal(head.lineTruncated, true);
  assert.equal(head.nextColumn, 901);
  const tail = source.read({ path: 'Long.kt', startLine: 1, startColumn: head.nextColumn, lineCount: 1 });
  assert.equal(tail.rows[0].nextColumn, null);
  assert.equal(head.text + tail.rows[0].text, longLine);
  const last = source.read({ path: 'Long.kt', startLine: first.nextLine });
  assert.equal(last.rows.length, 10);
  assert.equal(last.nextLine, null);
  assert.equal(last.truncated, false);
  assert.throws(() => source.read({ path: 'Long.kt', lineCount: 201 }), /PAGE_REJECTED/);
  assert.throws(() => source.search({ pattern: 'x', offset: -1 }), /PAGE_REJECTED/);
});
