import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { coroutineDelayCase as task } from './coroutine-delay-case.mjs';
import { gradeWorkspace, prepareWorkspace } from './developer-oracle.mjs';

test('delay conversion rejects early wakeups and overflow and accepts the upstream repair', {
  skip: process.platform !== 'darwin' && 'The Kotlin execution oracle uses the macOS sandbox.',
}, async (t) => {
  const workspace = await mkdtemp(join(tmpdir(), 'kast-delay-'));
  t.after(() => rm(workspace, { recursive: true, force: true }));
  await prepareWorkspace(task, workspace);
  const source = join(workspace, task.allowedChangedPaths[0]);
  const original = await readFile(source, 'utf8');
  const prefix = original.slice(0, original.indexOf('internal fun Duration.toDelayMillis()'));
  const grade = async () => (await gradeWorkspace(task, workspace, '')).pass;
  assert.equal(await grade(), false, 'the pre-fix conversion truncates positive fractions');

  await writeFile(source, prefix + `internal fun Duration.toDelayMillis(): Long =
    if (isPositive()) (inWholeNanoseconds + 999_999) / 1_000_000 else 0
`);
  assert.equal(await grade(), false, 'the arithmetic overflows on large positive durations');

  // The implementation in kotlinx.coroutines PR 3921, with an explicit import of its extension.
  const imports = prefix.replace('import kotlin.time.Duration\n', 'import kotlin.time.Duration\nimport kotlin.time.Duration.Companion.nanoseconds\n');
  await writeFile(source, imports + `internal fun Duration.toDelayMillis(): Long = when (isPositive()) {
    true -> plus(999_999L.nanoseconds).inWholeMilliseconds
    false -> 0L
}
`);
  assert.equal(await grade(), true);
});
