// Selection only. Task inputs and independent oracles remain with their existing owners.
export const naturalPortfolio = Object.freeze([
  'spring-reactive-server-response-primitive-status-callers',
  'picnic-ansi',
  'coroutine-delay-rounding',
]);

export function selectPortfolioCases(cases, selection = 'all') {
  if (selection === 'all') return cases;
  if (selection === 'curated') return cases.filter(item => naturalPortfolio.includes(item.id));
  throw Error('PORTFOLIO_SELECTION_REJECTED');
}

const variants = Object.freeze({
  A: 'Kast tool access with the ordinary Kast skill, against the same source-only tools and permissions.',
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
].map(([id, panel, criteria]) => [id, Object.freeze({ panel, variant: variants[panel], criteria: Object.freeze(criteria) })])));
