// Selection only. Task inputs and independent oracles remain with their existing owners.
export const naturalPortfolio = Object.freeze([
  'spring-reactive-server-response-primitive-status-callers',
  'picnic-ansi',
  'coroutine-delay-rounding',
]);

export const comprehensionPortfolio = Object.freeze([
  'ktfmt-constant-indent-negation-evidence-v1',
  'C5',
  'source-freshness-after-edit-v1',
  'readiness-failure-no-absence-v1',
]);

export function selectPortfolioCases(cases, selection = 'all') {
  if (selection === 'all') return cases;
  if (selection === 'curated') return cases.filter(item => naturalPortfolio.includes(item.id));
  throw Error('PORTFOLIO_SELECTION_REJECTED');
}

const variants = Object.freeze({
  A: 'Kast tool access with the ordinary Kast skill, against the same source-only tools and permissions.',
  B: 'The ordinary Kast skill text, against no added guidance, with identical supplied evidence and no tools.',
});

// Frame each selected case before any trial (pstack eval playbook, step 1): the
// configuration under test, the question it answers, and three to six concrete
// review criteria. Criteria are reviewer-only. They never reach a participant
// prompt, packet or workspace; the executable oracle remains the authority, and
// review-packet reconciliation reports any disagreement with it.
export const portfolioFrames = Object.freeze(Object.fromEntries([
  ['spring-reactive-server-response-primitive-status-callers', 'A', [
    'Names every Kotlin function that directly calls the webflux ServerResponse.status(int) overload.',
    'Excludes the neighboring RouterFunctionDsl.status(HttpStatusCode) overload.',
    'Excludes the webmvc RouterFunctionDsl.status(Int) owner with the same simple name.',
    'Uses the requested enclosing-class and simple-parameter-type form without duplicates.',
  ]],
  ['picnic-ansi', 'A', [
    'Counts numeric SGR escape sequences as zero visible columns, including adjacent escapes and resets.',
    'Preserves the exact escape bytes in renderText output.',
    'Keeps borders and neighboring cells aligned on multiline cells by visible width.',
    'Preserves supplementary Unicode code points and existing unstyled output.',
    'Keeps the public DSL and renderText signatures compatible and edits only production sources.',
  ]],
  ['coroutine-delay-rounding', 'A', [
    'Rounds a positive finite sub-millisecond remainder up, so 998 ms plus one nanosecond waits 999 ms.',
    'Returns exact millisecond durations unchanged, including large finite values.',
    'Returns zero for nonpositive durations and Long.MAX_VALUE for positive infinity.',
    'Keeps the existing toDelayMillis signature.',
  ]],
  ['ktfmt-constant-indent-negation-evidence-v1', 'B', [
    'Returns all seven call sites that resolve statically to Indentation.Const.unaryMinus.',
    'Omits plausible nonmatching expressions from the supplied windows.',
    'Reports each site once as a repository-relative path:line string.',
    'Distinguishes enclosing-declaration ranges in the prior receipt from exact use-site lines.',
  ]],
  ['C5', 'B', [
    'Does not conclude absence from a result whose coverage is not exhaustive.',
    'Preserves the requested directory and source-set scope.',
    'Copies the issued limitation codes verbatim without adding evidence fields.',
  ]],
  ['source-freshness-after-edit-v1', 'B', [
    'Keeps the target identity submit(Long) after the edit.',
    'Rejects the prior location as justification for editing.',
    'Locates the current line from the supplied text and labels compiler authority unproven.',
    'Chooses to reacquire current source instead of repeating the identical request.',
  ]],
  ['readiness-failure-no-absence-v1', 'B', [
    'Does not treat the readiness rejection as evidence that the symbol is absent.',
    'Reports the supplied failure code and an unavailable compiler model.',
    'Reports the readiness blocker without an identical automatic retry.',
  ]],
].map(([id, panel, criteria]) => [id, Object.freeze({ panel, variant: variants[panel], criteria: Object.freeze(criteria) })])));
