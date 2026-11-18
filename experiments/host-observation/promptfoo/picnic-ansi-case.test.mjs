import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { developerCases } from './developer-cases.mjs';
import { gradeWorkspace, prepareWorkspace } from './developer-oracle.mjs';
import { picnicAnsiCase } from './picnic-ansi-case.mjs';

const invoke = promisify(execFile);
const upstream = fileURLToPath(new URL('./developer-tasks/controls/picnic-ansi-upstream.patch', import.meta.url));
const kotlinPackage = 'src/main/kotlin/com/jakewharton/picnic';

test('Picnic admits the entire library source and keeps the rendering oracle outside it', async () => {
  assert.ok(developerCases.includes(picnicAnsiCase));
  const sources = await readdir(join(picnicAnsiCase.fixture, kotlinPackage));
  assert.equal(sources.length, 7);
  assert.deepEqual(picnicAnsiCase.oracle.candidateSources.toSorted(), sources.map(file => join(kotlinPackage, file)).toSorted());
  assert.deepEqual(picnicAnsiCase.allowedChangedPaths, picnicAnsiCase.oracle.candidateSources);
  assert.deepEqual(picnicAnsiCase.allowedNewSourceDirs, [kotlinPackage]);
  assert.ok(!sources.includes('text.kt'));
  assert.ok(relative(picnicAnsiCase.fixture, picnicAnsiCase.oracle.main).startsWith('../'));
  assert.match(await readFile(join(picnicAnsiCase.fixture, 'ORIGIN.txt'), 'utf8'), /cd569782e2503ddd4f95fb47017d12f9e9484210/);
  assert.match(await readFile(join(picnicAnsiCase.fixture, 'LICENSE.txt'), 'utf8'), /Apache License/);
});

test('Picnic renders exact public UTF-8 output only after the complete upstream repair', {
  skip: process.platform !== 'darwin' && 'Kotlin behavior grading uses the macOS runtime sandbox',
}, async parent => {
  const directory = await mkdtemp(join(tmpdir(), 'kast-picnic-control-'));
  try {
    const root = await prepareWorkspace(picnicAnsiCase, join(directory, 'workspace'));
    const expectGrade = async (expectedPass, expectedReason) => {
      const { pass, reason } = await gradeWorkspace(picnicAnsiCase, root);
      assert.deepEqual({ pass, reason }, { pass: expectedPass, reason: expectedReason });
    };
    await parent.test('the original source compiles and fails the behavior oracle', async () => {
      await expectGrade(false, 'CANDIDATE_BEHAVIOR_FAILED');
    });
    await invoke('git', ['apply', upstream], { cwd: root });
    await parent.test('the pinned upstream repair passes the public renderer oracle', async () => {
      assert.equal((await readdir(join(root, kotlinPackage))).length, 8);
      assert.match(await readFile(join(root, kotlinPackage, 'text.kt'), 'utf8'), /internal fun CharSequence\.visualIndex/);
      await expectGrade(true, 'COMPILED_BEHAVIOR_MATCHED');
    });
    await parent.test('correct sizing with discarded escape bytes fails', async () => {
      const path = join(root, kotlinPackage, 'textSurface.kt');
      const fixed = await readFile(path, 'utf8');
      const expression = 'rowBuilder.replace(writeStartIndex, writeEndIndex, line)';
      const replacement = String.raw`rowBuilder.replace(writeStartIndex, writeEndIndex, line.replace(Regex("""\u001B\[[0-9;]+m"""), ""))`;
      assert.ok(fixed.includes(expression));
      await writeFile(path, fixed.replace(expression, replacement));
      await expectGrade(false, 'CANDIDATE_BEHAVIOR_FAILED');
    });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
