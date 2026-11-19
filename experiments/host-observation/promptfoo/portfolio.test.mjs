import assert from 'node:assert/strict';
import test from 'node:test';
import { casesForSuite, suites } from './corpus-cases.mjs';
import { developerCases } from './developer-cases.mjs';

test('curated selection keeps the three distinct natural tasks without deleting the catalogs', async () => {
  const { selectPortfolioCases } = await import('./portfolio.mjs');
  assert.deepEqual(selectPortfolioCases(suites.flatMap(casesForSuite), 'curated').map(item => item.id),
    ['spring-reactive-server-response-primitive-status-callers']);
  assert.deepEqual(selectPortfolioCases(developerCases, 'curated').map(item => item.id).sort(),
    ['coroutine-delay-rounding', 'picnic-ansi']);
  assert.equal(selectPortfolioCases(developerCases, 'all'), developerCases);
  assert.throws(() => selectPortfolioCases(developerCases, 'kast-wins-only'), /PORTFOLIO_SELECTION_REJECTED/);
});

test('curated corpus admission rejects the restricted baseline before invoking a participant', async () => {
  const previous = process.env.KAST_EVAL_PORTFOLIO;
  const controls = process.env.KAST_CORPUS_CONTROLS;
  try {
    process.env.KAST_EVAL_PORTFOLIO = 'curated';
    delete process.env.KAST_CORPUS_CONTROLS;
    const { default: config } = await import('./corpus.config.mjs?curated-admission-test');
    assert.equal(new Set(config.tests.map(item => item.vars.caseId)).size, 1);
    const testCase = config.tests[0];
    const result = await config.providers[0].callApi(testCase.vars.task, { vars: testCase.vars });
    assert.equal(result.error, 'PANEL_A_BASELINE_UNQUALIFIED');
    assert.equal(result.metadata.baseline.panelA.admitted, false);
    assert.equal(result.metadata.requestedPanel, 'A');
    assert.equal(result.metadata.panel, 'RESTRICTED');
  } finally {
    if (previous === undefined) delete process.env.KAST_EVAL_PORTFOLIO; else process.env.KAST_EVAL_PORTFOLIO = previous;
    if (controls === undefined) delete process.env.KAST_CORPUS_CONTROLS; else process.env.KAST_CORPUS_CONTROLS = controls;
  }
});

test('each selected case has one reviewer-only frame with three to six criteria', async () => {
  const { comprehensionPortfolio, naturalPortfolio, portfolioFrames } = await import('./portfolio.mjs');
  const { comprehensionCases } = await import('./comprehension-cases.mjs');
  assert.deepEqual(Object.keys(portfolioFrames).sort(), [...naturalPortfolio, ...comprehensionPortfolio].sort());
  assert.deepEqual(comprehensionCases().map(item => item.id).sort(), [...comprehensionPortfolio].sort());
  for (const [id, frame] of Object.entries(portfolioFrames)) {
    assert.equal(frame.panel, naturalPortfolio.includes(id) ? 'A' : 'B', id);
    assert.ok(frame.criteria.length >= 3 && frame.criteria.length <= 6, id);
    assert.equal(new Set(frame.criteria).size, frame.criteria.length, id);
    assert.ok(Object.isFrozen(frame.criteria), id);
  }
  const prompts = [
    ...developerCases.map(item => item.prompt),
    ...suites.flatMap(casesForSuite).map(item => item.task),
    ...comprehensionCases().map(item => item.prompt),
  ];
  for (const criterion of Object.values(portfolioFrames).flatMap(frame => frame.criteria)) {
    assert.ok(prompts.every(prompt => !prompt.includes(criterion)), criterion);
  }
});
