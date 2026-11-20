import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { admitTwin, fisherGreater, gradeAnswer, lowerConfidenceBound, progressReport, readAnswer, renderPrompt, separationReport } from './separation.mjs';
import { reportExport } from './separation-report.mjs';
import { extensionCallers, fixture, kastCallers, library, materializedFact, request } from './ledger-shadow.mjs';
import { admitParticipantSurface } from './participant-surface.mjs';

const twin = (overrides = {}) => ({
  id: 'shadowed-extension', request: 'Which functions call it?', visible: 'same text',
  worlds: [
    { world: 'west', visible: 'same text', oracle: [], fact: 'west jar' },
    { world: 'east', visible: 'same text', oracle: ['refund'], fact: 'east jar' },
  ],
  ...overrides,
});

test('admission proves identical delivered prompts and distinct answers before any model call', () => {
  const admitted = admitTwin(twin());
  assert.equal(admitted.state, 'ADMITTED');
  assert.equal(admitted.ceiling, 0.5);
  const [west, east] = admitted.worlds;
  assert.equal(west.promptSha256.source, east.promptSha256.source);
  assert.equal(west.promptSha256.kast, east.promptSha256.kast);
  assert.notEqual(west.promptSha256.materialized, east.promptSha256.materialized);
  const expected = createHash('sha256').update('Which functions call it?\nReturn one per line with no other text. ' +
    'Return NONE if there are none. Return UNKNOWN if the available evidence does not decide it.\n\nsame text').digest('hex');
  assert.equal(west.promptSha256.source, expected);
});

test('admission rejects every twin that would not bound a text-only answer', () => {
  const differs = twin();
  differs.worlds[1] = { ...differs.worlds[1], visible: 'other text' };
  assert.equal(admitTwin(differs).failure, 'TWIN_VISIBLE_INPUT_DIFFERS');
  const same = twin();
  same.worlds[1] = { ...same.worlds[1], oracle: [] };
  assert.equal(admitTwin(same).failure, 'TWIN_ORACLES_NOT_DISTINCT');
  const facts = twin();
  facts.worlds[1] = { ...facts.worlds[1], fact: 'west jar' };
  assert.equal(admitTwin(facts).failure, 'TWIN_FACTS_NOT_DISTINCT');
  assert.equal(admitTwin(twin({ worlds: twin().worlds.slice(0, 1) })).failure, 'TWIN_TOO_FEW_WORLDS');
  const reserved = twin();
  reserved.worlds[1] = { ...reserved.worlds[1], oracle: ['NONE'] };
  assert.equal(admitTwin(reserved).failure, 'TWIN_ORACLE_INVALID');
});

test('answers are exact sets with explicit empty and abstention forms', () => {
  assert.deepEqual(readAnswer('NONE'), { type: 'SET', members: [] });
  assert.deepEqual(readAnswer(' UNKNOWN \n'), { type: 'ABSTAINED' });
  assert.deepEqual(readAnswer('settle\nrefund'), { type: 'SET', members: ['refund', 'settle'] });
  assert.deepEqual(readAnswer('refund\nrefund'), { type: 'UNREADABLE' });
  assert.deepEqual(readAnswer('NONE\nrefund'), { type: 'UNREADABLE' });
  assert.equal(gradeAnswer('NONE', []), 'CORRECT');
  assert.equal(gradeAnswer('refund', []), 'WRONG');
  assert.equal(gradeAnswer('refund', ['refund']), 'CORRECT');
  assert.equal(gradeAnswer('refund\nsettle', ['refund']), 'WRONG');
  assert.equal(gradeAnswer('`refund`', ['refund']), 'WRONG');
  assert.equal(gradeAnswer('UNKNOWN', ['refund']), 'ABSTAINED');
  assert.equal(gradeAnswer(undefined, ['refund']), 'WRONG');
});

test('the lower bound is the exact one-sided Clopper-Pearson value', () => {
  assert.equal(lowerConfidenceBound(0, 6), 0);
  assert.ok(Math.abs(lowerConfidenceBound(12, 12) - 0.05 ** (1 / 12)) < 1e-12);
  // Independent reference values from scipy.stats.beta.ppf(0.05, x, n - x + 1).
  assert.ok(Math.abs(lowerConfidenceBound(11, 12) - 0.6613) < 1e-4);
  assert.ok(Math.abs(lowerConfidenceBound(6, 12) - 0.2453) < 1e-4);
  assert.throws(() => lowerConfidenceBound(3, 2), /SEPARATION_COUNTS_INVALID/);
});

function rowsFor(admission, arm, outcomes) {
  return admission.worlds.flatMap(world => outcomes[world.world].map(outcome =>
    ({ caseId: admission.id, world: world.world, arm, outcome, promptSha256: world.promptSha256[arm] })));
}

test('a configuration separates only when its lower bound exceeds the text-only ceiling', () => {
  const admission = admitTwin(twin());
  const all = outcome => ({ west: [outcome, outcome, outcome], east: [outcome, outcome, outcome] });
  const source = rowsFor(admission, 'source', { west: ['WRONG', 'WRONG', 'WRONG'], east: ['CORRECT', 'CORRECT', 'CORRECT'] });
  const materialized = rowsFor(admission, 'materialized', all('CORRECT'));
  const perfect = separationReport([admission], [...source, ...rowsFor(admission, 'kast', all('CORRECT')), ...materialized],
    { repetitions: 3 });
  assert.equal(perfect.verdict, 'SEPARATED');
  assert.equal(perfect.cases[0].arms.source.accuracy, 0.5);
  assert.ok(perfect.pooled.kast.lowerBound > 0.6);
  const errors = separationReport([admission], [...source, ...materialized,
    ...rowsFor(admission, 'kast', { west: ['CORRECT', 'ERROR', 'CORRECT'], east: ['CORRECT', 'CORRECT', 'ERROR'] })],
  { repetitions: 3 });
  assert.equal(errors.verdict, 'NOT_SEPARATED');
  assert.equal(errors.pooled.kast.ERROR, 2);
  const missing = separationReport([admission], [...source, ...materialized,
    ...rowsFor(admission, 'kast', { west: ['CORRECT', 'CORRECT', 'CORRECT'], east: ['CORRECT', 'CORRECT'] })], { repetitions: 3 });
  assert.equal(missing.verdict, 'INCOMPLETE');
  const forged = rowsFor(admission, 'kast', all('CORRECT')).map(row => ({ ...row, promptSha256: '0'.repeat(64) }));
  assert.equal(separationReport([admission], forged, { repetitions: 3 }).failure, 'SEPARATION_PROMPT_DIVERGED');
});

function exportFor(admission, answers) {
  const summary = { id: admission.id, ceiling: admission.ceiling, visibleSha256: admission.visibleSha256,
    worlds: admission.worlds.map(({ world, oracle, factSha256, promptSha256 }) => ({ world, oracle, factSha256, promptSha256 })) };
  return { results: { results: answers.map(([world, arm, output, outcome]) => ({
    vars: { caseId: admission.id, world, arm },
    response: { output, metadata: { separation: { caseId: admission.id, world, arm, outcome, admission: summary,
      promptSha256: summary.worlds.find(item => item.world === world).promptSha256[arm] }, usage: { total: 10 }, toolCalls: 0 } },
  })) } };
}

test('the report regrades every answer from the export and rejects a recorded outcome it cannot reproduce', () => {
  const admission = admitTwin(twin());
  const answers = [
    ['west', 'source', 'refund', 'WRONG'], ['east', 'source', 'refund', 'CORRECT'],
    ['west', 'kast', 'NONE', 'CORRECT'], ['east', 'kast', 'refund', 'CORRECT'],
    ['west', 'materialized', 'NONE', 'CORRECT'], ['east', 'materialized', 'refund\nsettle', 'WRONG'],
  ];
  const exported = exportFor(admission, answers);
  exported.results.results[0].error = 'Custom function returned false';
  const report = reportExport(exported);
  assert.equal(report.verdict, 'NOT_SEPARATED');
  assert.equal(report.cases[0].arms.kast.CORRECT, 2);
  assert.equal(report.cases[0].arms.materialized.WRONG, 1);
  assert.deepEqual(report.cases[0].arms.kast.byConsultation.unconsulted, { CORRECT: 2, ABSTAINED: 0, WRONG: 0, ERROR: 0 });
  const consulted = exportFor(admission, answers);
  consulted.results.results[2].response.metadata.calls = [{ tool: 'kast', status: 0 }, { tool: 'kast', status: 2 }];
  const split = reportExport(consulted).cases[0].arms.kast;
  const replayed = exportFor(admission, answers);
  replayed.results.results[3].response.cached = true;
  assert.equal(reportExport(replayed).failure, 'SEPARATION_OUTCOME_DIVERGED');
  assert.equal(split.byConsultation.consulted.CORRECT, 1);
  assert.equal(split.byConsultation.unconsulted.CORRECT, 1);
  assert.equal(split.successfulKastCalls, 1);
  const forged = answers.map(answer => answer[1] === 'source' ? [answer[0], 'source', 'refund', 'CORRECT'] : answer);
  assert.equal(reportExport(exportFor(admission, forged)).failure, 'SEPARATION_OUTCOME_DIVERGED');
  const unblinded = exportFor(admission, answers);
  unblinded.results.results[0].response.metadata.separation.admission.worlds[0].promptSha256.kast = 'f'.repeat(64);
  for (const row of unblinded.results.results) {
    row.response.metadata.separation.admission = unblinded.results.results[0].response.metadata.separation.admission;
  }
  assert.equal(reportExport(unblinded).failure, 'SEPARATION_TWIN_NOT_BLIND');
});

test('bytecode attribution finds direct calls to the tracked extension only', () => {
  const disassembly = [
    'Compiled from "Settlement.kt"',
    'public final class billing.SettlementKt {',
    '  public static final java.lang.String settle(ledger.Ledger);',
    '    Code:',
    '      10: invokevirtual #23                 // Method ledger/Ledger.post:(J)Ljava/lang/String;',
    '  public static final java.lang.String refund(ledger.Ledger, int);',
    '    Code:',
    '       8: invokestatic  #30                 // Method billing/LedgerExtensionsKt.post:(Lledger/Ledger;I)Ljava/lang/String;',
    '}',
  ].join('\n');
  assert.deepEqual(extensionCallers(disassembly), ['refund']);
  assert.deepEqual(extensionCallers(disassembly.replace('invokestatic  #30', 'invokevirtual #21')), []);
  assert.throws(() => extensionCallers('       8: invokestatic  #30                 // Method billing/LedgerExtensionsKt.post:(Lledger/Ledger;I)Ljava/lang/String;'),
    /TWIN_BYTECODE_UNATTRIBUTED/);
});

test('only a complete exhaustive Kast reply qualifies a world', () => {
  const reply = items => ({ type: 'complete', document: { status: 'complete', coverage: { exhaustive: true },
    original_evidence_summary: { failure_count: 0, omission_count: 0 }, items } });
  assert.deepEqual(kastCallers(reply([])), []);
  assert.deepEqual(kastCallers(reply([{ name: 'refund' }])), ['refund']);
  const partial = reply([]);
  partial.document.coverage.exhaustive = false;
  assert.equal(kastCallers(partial), null);
  const omitted = reply([]);
  omitted.document.original_evidence_summary.omission_count = 1;
  assert.equal(kastCallers(omitted), null);
});

test('the ledger twin differs only outside the checkout and its delivered text is blind', () => {
  assert.notEqual(library.west['ledger/Ledger.kt'], library.east['ledger/Ledger.kt']);
  assert.ok(fixture['build.gradle.kts'].includes('files("../ledger.jar")'));
  const visible = Object.entries(fixture).map(([path, text]) => `--- ${path} ---\n${text}`).join('\n');
  const prompt = renderPrompt(request, `Workspace snapshot:\n${visible}`);
  const surface = admitParticipantSurface({ role: 'participant', system: 'You are a coding assistant.', prompt,
    directory: '/tmp/project-abcdef', visiblePaths: ['/tmp/root/ledger/west/workspace'], toolNames: ['kast'] });
  assert.equal(surface.state, 'ADMITTED');
  assert.ok(materializedFact('public final java.lang.String post(long);\n').endsWith('post(long);'));
});

test('the held-out subtype twin is blind and its qualification needs no file scope', async () => {
  const { spec } = await import('./payment-subtype.mjs');
  const { spec: ledgerSpec } = await import('./ledger-shadow.mjs');
  assert.notEqual(spec.library.north['payments/Card.kt'], spec.library.south['payments/Card.kt']);
  assert.ok(!spec.queryArgs.includes('--in'));
  const descriptions = spec.fixture['src/main/kotlin/billing/Descriptions.kt'];
  const offset = Number(spec.queryArgs[spec.queryArgs.indexOf('--offset') + 1]);
  assert.equal(descriptions.slice(offset, offset + 'describe'.length), 'describe');
  const visible = Object.entries(spec.fixture).map(([path, text]) => `--- ${path} ---\n${text}`).join('\n');
  const surface = admitParticipantSurface({ role: 'participant', system: 'You are a coding assistant.',
    prompt: renderPrompt(spec.request, `Workspace snapshot:\n${visible}`), directory: '/tmp/project-abcdef',
    visiblePaths: ['/tmp/root/payments/north/workspace'], toolNames: ['kast'] });
  assert.equal(surface.state, 'ADMITTED');
  // The pre-registered shadowed-extension prompt keeps its recorded build script.
  assert.equal(ledgerSpec.fixture['build.gradle.kts'], 'plugins { kotlin("jvm") version "2.4.10" }\n\n' +
    'repositories { mavenCentral() }\n\ndependencies { implementation(files("../ledger.jar")) }\n');
});

test('progress uses the exact one-sided Fisher test on matched cases', () => {
  // Independent reference values from scipy.stats.fisher_exact(alternative='greater').
  assert.ok(Math.abs(fisherGreater({ successes: 12, trials: 12 }, { successes: 4, trials: 12 }) - 0.000673038) < 1e-8);
  assert.ok(Math.abs(fisherGreater({ successes: 5, trials: 6 }, { successes: 2, trials: 6 }) - 0.121212121) < 1e-8);
  assert.ok(Math.abs(fisherGreater({ successes: 3, trials: 6 }, { successes: 3, trials: 6 }) - 0.716450216) < 1e-8);
  const report = (verdict, correct) => ({ verdict, cases: [{ id: 'a', verdict, arms: { kast: { CORRECT: correct, trials: 6 } } }] });
  const compared = progressReport(report('NOT_SEPARATED', 2), report('SEPARATED', 6));
  assert.equal(compared.state, 'COMPARED');
  assert.deepEqual(compared.pooled.after, { successes: 6, trials: 6 });
  assert.equal(progressReport({ verdict: 'INCOMPLETE' }, report('SEPARATED', 6)).failure, 'SEPARATION_PROGRESS_UNREPORTED');
});
