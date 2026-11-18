import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { isAbsolute, join, relative } from 'node:path';
import test from 'node:test';
import { developerCases } from './developer-cases.mjs';

const parcelCases = developerCases.filter(item =>
  ['locker-pickup', 'audit-v2', 'dispatch-trace'].includes(item.id),
);

test('developer cases identify a pristine Kotlin project and keep grading inputs outside it', () => {
  assert.equal(new Set(developerCases.map(item => item.id)).size, developerCases.length);
  assert.equal(parcelCases.length, 3);
  for (const item of parcelCases) {
    const oracle = item.oracle.witness ?? item.oracle;
    assert.ok(isAbsolute(item.fixture));
    assert.ok(statSync(join(item.fixture, 'settings.gradle.kts')).isFile());
    assert.ok(statSync(oracle.main).isFile());
    assert.ok(relative(item.fixture, oracle.main).startsWith('../'));
    assert.equal(oracle.expectedStdout, 'PASS');
    const realSources = readdirSync(join(item.fixture, 'src/main/kotlin'), { recursive: true })
      .filter(path => path.endsWith('.kt'))
      .map(path => join('src/main/kotlin', path))
      .sort();
    assert.deepEqual(oracle.candidateSources.toSorted(), realSources);
    for (const changed of item.allowedChangedPaths) assert.ok(realSources.includes(changed));
  }
});

test('V2 audit changes target the parcel API and all three callers, distinct from the analytics homonym', () => {
  const audit = parcelCases.find(item => item.id === 'audit-v2');
  const affected = audit.allowedChangedPaths.map(path => readFileSync(join(audit.fixture, path), 'utf8'));
  assert.equal(affected.length, 4);
  assert.match(affected[0], /interface AuditSink/);
  assert.ok(affected.slice(1).every(source => source.includes('audit.record(')));
  const decoy = 'src/main/kotlin/parcel/analytics/AuditSink.kt';
  assert.ok(!audit.allowedChangedPaths.includes(decoy));
  assert.match(readFileSync(join(audit.fixture, decoy), 'utf8'), /fun record\(kind: String, parcelId: String, actorId: String\)/);
});

test('parcel tasks admit new source only under their own edit domains', () => {
  const locker = parcelCases.find(item => item.id === 'locker-pickup');
  const audit = parcelCases.find(item => item.id === 'audit-v2');
  const trace = parcelCases.find(item => item.id === 'dispatch-trace');
  assert.deepEqual(locker.allowedNewSourceDirs, ['src/main/kotlin/parcel/api', 'src/main/kotlin/parcel/checkout']);
  assert.deepEqual(audit.allowedNewSourceDirs, [
    'src/main/kotlin/parcel/audit', 'src/main/kotlin/parcel/operations', 'src/main/kotlin/parcel/replay',
  ]);
  assert.equal(trace.allowedNewSourceDirs, undefined);
});

test('each trace branch has a separate exact answer and an independent executable witness', () => {
  const trace = parcelCases.find(item => item.id === 'dispatch-trace');
  assert.deepEqual(trace.allowedChangedPaths, []);
  assert.match(trace.oracle.expectedTrace.selectedOverload, /\(DispatchRequest\)$/);
  assert.deepEqual(trace.oracle.expectedTrace.duplicate.calls, trace.oracle.expectedTrace.first.calls.slice(0, 1));
  assert.deepEqual(trace.oracle.expectedTrace.first.calls.slice(1), [
    'parcel.carriers.NetworkCarrier.prepare', 'parcel.dispatch.DispatchQueue.enqueue',
  ]);
  assert.notEqual(trace.oracle.expectedTrace.first.outcome, trace.oracle.expectedTrace.duplicate.outcome);
  assert.match(readFileSync(trace.oracle.witness.main, 'utf8'), /\bDispatchResult\.Duplicate\b/);
});
