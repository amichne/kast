import assert from 'node:assert/strict';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import test from 'node:test';
import {
  admitParticipantSurface, createParticipantDirectory, measurementTerms, participantDirectoryPattern, vocabularyTokens,
} from './participant-surface.mjs';

const directory = '/private/var/folders/ab/T/project-Ab12Cd';
const admitted = overrides => admitParticipantSurface({ system: 'You are a Kotlin developer.', prompt: 'Fix the parser.', directory, ...overrides });

test('identifier and path forms expose measurement words that plain splitting would hide', () => {
  assert.deepEqual(vocabularyTokens('readEvalPlan KAST_EVAL_PI eval-i9W HTTPServerTrial'),
    ['read', 'eval', 'plan', 'kast', 'eval', 'pi', 'eval', 'i9', 'w', 'http', 'server', 'trial']);
  assert.deepEqual(measurementTerms('Run the frozen Promptfoo evaluation; the judge scores both arms.'),
    ['arm', 'eval', 'judge', 'promptfoo', 'score']);
});

test('ordinary engineering vocabulary stays admissible', () => {
  for (const text of [
    'Make the failing TestResult test pass and keep the fixture build.',
    'Report up to 12 Kotlin diagnostics under app/src.',
    'Upgraded the arm64 toolchain; the scoreboard widget renders a trialing comma.',
  ]) assert.deepEqual(measurementTerms(text), [], text);
});

test('an admitted surface records its digest; any change to observed text changes the digest', () => {
  const first = admitted();
  assert.equal(first.state, 'ADMITTED');
  assert.match(first.surfaceSha256, /^[a-f0-9]{64}$/);
  assert.notEqual(admitted({ prompt: 'Fix the lexer.' }).surfaceSha256, first.surfaceSha256);
  assert.equal(admitted({ toolNames: ['b', 'a'] }).surfaceSha256, admitted({ toolNames: ['a', 'b'] }).surfaceSha256);
});

test('each observed surface rejects with its own finding and never echoes the text', () => {
  const cases = [
    [{ system: 'Both arms receive this instruction.' }, { surface: 'SYSTEM', code: 'PARTICIPANT_TEXT_VOCABULARY', term: 'arm' }],
    [{ prompt: 'This candidate answer is graded.' }, { surface: 'TASK', code: 'PARTICIPANT_TEXT_VOCABULARY', term: 'candidate' }],
    [{ directory: '/tmp/kast-skill-eval-Ab12Cd' }, { surface: 'WORKING_DIRECTORY', code: 'PARTICIPANT_DIRECTORY_UNSANITIZED' }],
    [{ directory: '/tmp/kast-skill-eval-Ab12Cd' }, { surface: 'VISIBLE_PATH', code: 'PARTICIPANT_PATH_VOCABULARY', term: 'eval' }],
    [{ visiblePaths: ['/repo/build/promptfoo/static-callback-fixture'] }, { surface: 'VISIBLE_PATH', code: 'PARTICIPANT_PATH_VOCABULARY', term: 'promptfoo' }],
    [{ visiblePaths: ['/Users/me/experiments/workspaces/ktfmt'] }, { surface: 'VISIBLE_PATH', code: 'PARTICIPANT_PATH_VOCABULARY', term: 'experiment' }],
    [{ toolNames: ['read_oracle'] }, { surface: 'TOOL_NAME', code: 'PARTICIPANT_TOOL_VOCABULARY', term: 'oracle' }],
  ];
  for (const [override, finding] of cases) {
    const result = admitted(override);
    assert.equal(result.state, 'REJECTED', JSON.stringify(override));
    assert.ok(result.findings.some(item => JSON.stringify(item) === JSON.stringify(finding)), JSON.stringify(result.findings));
    assert.doesNotMatch(JSON.stringify(result), /instruction|graded|static-callback/);
  }
});

test('a product-named or nonstandard working directory is unsanitized even without a measurement word', () => {
  for (const name of ['kast-access-Ab12Cd', 'project-short', 'workspace-Ab12Cd']) {
    assert.equal(admitted({ directory: join('/tmp', name) }).findings[0].code, 'PARTICIPANT_DIRECTORY_UNSANITIZED', name);
  }
});

test('the coordinator role is recorded as not applicable; unknown roles and malformed input fail closed', () => {
  assert.deepEqual(admitParticipantSurface({ role: 'coordinator', prompt: 'Run the frozen Promptfoo evaluation.', directory: '/tmp/x' }),
    { state: 'NOT_APPLICABLE', role: 'coordinator', vocabularyVersion: 1 });
  assert.equal(admitParticipantSurface({ role: 'judge', prompt: 'x', directory }).findings[0].code, 'PARTICIPANT_ROLE_UNKNOWN');
  for (const malformed of [{ prompt: undefined }, { visiblePaths: [1] }, { toolNames: 'kast' }]) {
    assert.equal(admitted(malformed).findings[0].code, 'PARTICIPANT_SURFACE_MALFORMED');
  }
});

test('created participant directories satisfy the sanitized pattern under the supplied parent', async t => {
  const parent = await mkdtemp(join(tmpdir(), 'owner-'));
  t.after(() => rm(parent, { recursive: true, force: true }));
  const created = await createParticipantDirectory(parent);
  assert.match(basename(created), participantDirectoryPattern);
  assert.equal(admitted({ directory: created }).state, 'ADMITTED');
  // A random suffix that happens to spell a measurement word stays admissible,
  // including below the directory in a visible workspace path.
  assert.equal(admitted({ directory: '/tmp/project-armAbc', visiblePaths: ['/tmp/project-armAbc/workspace'] }).state, 'ADMITTED');
});

test('the shared launcher rejects an unblinded surface before resolving Pi or staging credentials', async t => {
  const { runIsolatedPi } = await import('./skill-provider.mjs');
  const parent = await mkdtemp(join(tmpdir(), 'owner-'));
  t.after(() => rm(parent, { recursive: true, force: true }));
  const previous = process.env.KAST_EVAL_PI;
  process.env.KAST_EVAL_PI = join(parent, 'absent-pi');
  t.after(() => { if (previous === undefined) delete process.env.KAST_EVAL_PI; else process.env.KAST_EVAL_PI = previous; });
  const sanitized = await createParticipantDirectory(parent);
  const rejection = await runIsolatedPi({ directory: sanitized, prompt: 'Which arm wins the comparison?' }).then(
    () => assert.fail('an unblinded prompt must not launch'), error => error);
  assert.equal(rejection.code, 'PARTICIPANT_SURFACE_REJECTED');
  assert.deepEqual(rejection.trial, { participantSurface: rejection.participantSurface });
  assert.deepEqual(rejection.participantSurface.findings.map(item => item.term), ['arm', 'compare']);
  // No agent directory, system prompt file or copied credential was created.
  assert.deepEqual(await readdir(sanitized), []);
  const unsanitized = await mkdtemp(join(parent, 'trial-'));
  const directoryRejection = await runIsolatedPi({ directory: unsanitized, prompt: 'Fix the parser.' }).catch(error => error);
  assert.equal(directoryRejection.code, 'PARTICIPANT_SURFACE_REJECTED');
  assert.deepEqual(await readdir(unsanitized), []);
});

test('the projection carries a launcher rejection into provider metadata without inventing usage', async () => {
  const { projectPiTrial } = await import('./pi-accounting.mjs');
  const participantSurface = admitted({ prompt: 'Grade this trial.' });
  const projected = projectPiTrial({ participantSurface });
  assert.equal(projected.metadata.participantSurface, participantSurface);
  assert.equal(projected.cost, undefined);
  assert.equal(projected.tokenUsage, undefined);
  assert.equal(projectPiTrial(undefined).metadata.participantSurface, null);
});

test('every catalogued participant prompt and system text is admissible as delivered', async () => {
  const [{ skillCases }, { developerCases }, { casesForSuite, suites },
    { neutralSystem }, { developerSystem }, { corpusParticipantSystem }] = await Promise.all([
    import('./skill-cases.mjs'), import('./developer-cases.mjs'), import('./corpus-cases.mjs'),
    import('./skill-provider.mjs'), import('./developer-provider.mjs'), import('./corpus-participant.mjs'),
  ]);
  const surfaces = [
    ...Object.entries(skillCases).map(([id, item]) => [`skill/${id}`, neutralSystem, item.prompt]),
    ...developerCases.map(item => [`developer/${item.id}`, developerSystem, item.prompt]),
    ...suites.flatMap(casesForSuite).map(item => [`corpus/${item.id}`, corpusParticipantSystem, item.task]),
  ];
  assert.ok(surfaces.length > 20);
  for (const [label, system, prompt] of surfaces) {
    assert.equal(typeof prompt, 'string', label);
    const result = admitParticipantSurface({ system, prompt, directory });
    assert.equal(result.state, 'ADMITTED', `${label}: ${JSON.stringify(result.findings)}`);
  }
});
