import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { appendFile, copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { developerCases } from './developer-cases.mjs';
import { gradeWorkspace, prepareWorkspace, preflightOracle } from './developer-oracle.mjs';

const run = promisify(execFile);
const native = {
  skip: process.platform !== 'darwin' ? 'Kotlin behavior runs under the macOS sandbox' : false,
};
const parcel = id => developerCases.find(task => task.id === id);
const control = name => fileURLToPath(new URL(`./developer-tasks/controls/${name}.patch`, import.meta.url));

async function ownedDirectory(t) {
  const owned = await mkdtemp(join(tmpdir(), 'kast-developer-oracle-test-'));
  t.after(() => rm(owned, { recursive: true, force: true }));
  return owned;
}

async function applyPatch(workspace, path) {
  await run('git', ['apply', path], { cwd: workspace, timeout: 10_000 });
}

async function replayGrade(task, root, grade) {
  const workspace = await prepareWorkspace(task, join(root, 'replay'));
  const path = join(root, 'recorded.patch');
  await writeFile(path, grade.patch);
  await applyPatch(workspace, path);
  assert.deepEqual(await gradeWorkspace(task, workspace, 'irrelevant answer'), grade);
}

test('seeded paths keep the wrapper and reject unauthorized project edits before compilation', async t => {
  const root = await ownedDirectory(t);
  const task = parcel('locker-pickup');
  const workspace = await prepareWorkspace(task, join(root, 'workspace'));
  const relativeWrapper = 'gradle/wrapper/gradle-wrapper.jar';
  const trustedWrapper = new URL(`../../../${relativeWrapper}`, import.meta.url);
  assert.deepEqual(await readFile(join(workspace, relativeWrapper)), await readFile(trustedWrapper));
  await assert.rejects(prepareWorkspace(task, workspace), /vacant directory/);

  await appendFile(join(workspace, task.allowedChangedPaths[0]), '\n// candidate Kotlin edit\n');
  await appendFile(join(workspace, 'build.gradle.kts'), '\n// candidate script edit\n');
  const grade = await gradeWorkspace(task, workspace, 'PASS');
  assert.equal(grade.pass, false);
  assert.equal(grade.reason, 'UNDECLARED_FILE_CHANGE');
  assert.match(grade.patch, /diff --git a\/src\/main\/kotlin/);
  assert.doesNotMatch(grade.patch, /build\.gradle/);
});

test('an addition directory admits a new Kotlin file without admitting edits to its existing files', async t => {
  const root = await ownedDirectory(t);
  const directory = 'src/main/kotlin/parcel/audit';
  const task = { ...parcel('locker-pickup'), allowedNewSourceDirs: [directory] };
  const workspace = await prepareWorkspace(task, join(root, 'workspace'));
  await appendFile(join(workspace, directory, 'AuditSink.kt'), '\n// Changed an unlisted existing file.\n');
  await writeFile(join(workspace, directory, 'Added.kt'), 'package parcel.audit\n');
  const grade = await gradeWorkspace(task, workspace, 'PASS');
  assert.equal(grade.pass, false);
  assert.equal(grade.reason, 'UNDECLARED_FILE_CHANGE');
  assert.match(grade.patch, /b\/src\/main\/kotlin\/parcel\/audit\/Added\.kt/);
  assert.doesNotMatch(grade.patch, /AuditSink\.kt/);
});

test('trace grading distinguishes the duplicate path and rejects edited source', async t => {
  const root = await ownedDirectory(t);
  const task = parcel('dispatch-trace');
  const workspace = await prepareWorkspace(task, join(root, 'workspace'));
  const answer = structuredClone(task.oracle.expectedTrace);
  answer.duplicate.calls = answer.first.calls;
  assert.deepEqual(await gradeWorkspace(task, workspace, JSON.stringify(answer)), {
    pass: false, reason: 'TRACE_MISMATCH', patch: '',
  });
  await appendFile(join(workspace, task.oracle.witness.candidateSources[0]), '\n// altered source\n');
  assert.deepEqual(await gradeWorkspace(task, workspace, JSON.stringify(task.oracle.expectedTrace)), {
    pass: false, reason: 'UNDECLARED_FILE_CHANGE', patch: '',
  });
});

for (const [id, patch, reason] of [
  ['locker-pickup', 'locker-pickup', 'COMPILED_BEHAVIOR_MATCHED'],
  ['locker-pickup', 'locker-pickup-overrestricts', 'CANDIDATE_BEHAVIOR_FAILED'],
  ['audit-v2', 'audit-v2', 'COMPILED_BEHAVIOR_MATCHED'],
  ['audit-v2', 'audit-v2-replay-regression', 'CANDIDATE_BEHAVIOR_FAILED'],
]) {
  test(`trusted Kotlin oracle grades ${patch}`, native, async t => {
    await preflightOracle();
    const root = await ownedDirectory(t);
    const directory = 'src/main/kotlin/parcel/checkout';
    const task = patch === 'locker-pickup'
      ? { ...parcel(id), allowedNewSourceDirs: [directory] }
      : parcel(id);
    const workspace = await prepareWorkspace(task, join(root, 'workspace'));
    await applyPatch(workspace, control(patch));
    if (patch === 'locker-pickup') {
      const pricingPath = join(workspace, directory, 'Pricing.kt');
      const pricing = await readFile(pricingPath, 'utf8');
      const previous = 'QuoteResult.Quoted(299, "locker:${delivery.lockerId}")';
      assert.ok(pricing.includes(previous));
      await writeFile(pricingPath, pricing.replace(previous, 'lockerQuote(delivery.lockerId)'));
      await writeFile(join(workspace, directory, 'LockerQuote.kt'), [
        'package parcel.checkout',
        'internal fun lockerQuote(lockerId: String) = QuoteResult.Quoted(299, "locker:$lockerId")',
        '',
      ].join('\n'), { flag: 'wx' });
    }
    const grade = await gradeWorkspace(task, workspace, 'PASS');
    assert.equal(grade.pass, reason === 'COMPILED_BEHAVIOR_MATCHED');
    assert.equal(grade.reason, reason);
    assert.match(grade.patch, /diff --git a\/src\/main\/kotlin/);
    if (patch === 'locker-pickup') {
      assert.match(grade.patch, /--- \/dev\/null\n\+\+\+ b\/src\/main\/kotlin\/parcel\/checkout\/LockerQuote\.kt/);
      await replayGrade(task, root, grade);
    }
  });
}

test('an unconverted replay caller fails compilation with the independent V2 consumer', native, async t => {
  await preflightOracle();
  const root = await ownedDirectory(t);
  const task = parcel('audit-v2');
  const workspace = await prepareWorkspace(task, join(root, 'workspace'));
  await applyPatch(workspace, control('audit-v2'));
  const replay = 'src/main/kotlin/parcel/replay/ImportReplay.kt';
  await copyFile(join(task.fixture, replay), join(workspace, replay));
  const grade = await gradeWorkspace(task, workspace, 'Migration complete');
  assert.equal(grade.pass, false);
  assert.equal(grade.reason, 'CANDIDATE_COMPILATION_FAILED');
  assert.match(grade.patch, /diff --git a\/src\/main\/kotlin/);
  await replayGrade(task, root, grade);
});

test('the unmodified dispatch fixture passes its executable trace witness', native, async t => {
  await preflightOracle();
  const root = await ownedDirectory(t);
  const task = parcel('dispatch-trace');
  const workspace = await prepareWorkspace(task, join(root, 'workspace'));
  assert.deepEqual(await gradeWorkspace(task, workspace, JSON.stringify(task.oracle.expectedTrace)), {
    pass: true, reason: 'EXACT_TRACE_MATCHED', patch: '',
  });
});

test('candidate JVM cannot access case-external files, execute a child, or connect to loopback', native, async t => {
  await preflightOracle();
  const root = await ownedDirectory(t);
  const server = createServer(socket => socket.destroy());
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const secret = join(root, 'outside.txt');
  await writeFile(secret, 'case-owned data');
  const outsideWrite = join(root, 'created.txt');
  const candidateSource = 'src/main/kotlin/Candidate.kt';
  const fixture = join(root, 'fixture');
  await mkdir(dirname(join(fixture, candidateSource)), { recursive: true });
  await writeFile(join(fixture, candidateSource), `
package sample
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
fun readOutside() = Files.readString(Path.of(${JSON.stringify(secret)}))
fun writeOutside() = Files.writeString(Path.of(${JSON.stringify(outsideWrite)}), "written")
fun startChild() = ProcessBuilder("/usr/bin/true").start().waitFor()
fun connectOutside() = Socket("127.0.0.1", ${server.address().port}).use { it.isConnected }
`);
  const main = join(root, 'Isolation.kt');
  await writeFile(main, `
package oracle
import sample.*
fun main() {
  check(runCatching { readOutside() }.isFailure)
  check(runCatching { writeOutside() }.isFailure)
  check(runCatching { startChild() }.isFailure)
  check(runCatching { connectOutside() }.isFailure)
  println("PASS")
}
`);
  const task = {
    kind: 'edit', fixture, allowedChangedPaths: [candidateSource],
    oracle: { main, mainClass: 'oracle.IsolationKt', candidateSources: [candidateSource], expectedStdout: 'PASS' },
  };
  const workspace = await prepareWorkspace(task, join(root, 'workspace'));
  assert.deepEqual(await gradeWorkspace(task, workspace, 'done'), {
    pass: true, reason: 'COMPILED_BEHAVIOR_MATCHED', patch: '',
  });
  await assert.rejects(readFile(outsideWrite), { code: 'ENOENT' });
});

test('an unavailable compiler rejects preflight instead of giving a candidate grade', native, async t => {
  const root = await ownedDirectory(t);
  const oracleModule = new URL('./developer-oracle.mjs', import.meta.url);
  await assert.rejects(run(process.execPath, [
    '--input-type=module', '--eval',
    `const { preflightOracle } = await import(${JSON.stringify(oracleModule.href)}); await preflightOracle();`,
  ], {
    timeout: 10_000,
    env: { ...process.env, KAST_EVAL_KOTLINC: join(root, 'unavailable-compiler') },
  }), /Developer Kotlin compiler or Java runtime is unavailable/);
});
