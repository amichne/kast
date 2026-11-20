// Reconstructed decisions, with synthetic measurements and commit identifiers.
// Each pair shares a request. Only its final evidence field changes.
const pairs = [
  {
    id: 'installed-readiness',
    source: 'Installed Kast readiness investigation, 2e42da75aede4e3cc621614a9e8f7a004c8e8315',
    request: 'Can I tell the team that the installed Kast can resolve KastDirectToolSession in this checkout? The installer receipt matches the requested release. Earlier status showed EPOCH_UNAVAILABLE. The latest exact-name query against this checkout returned the receipt below. Reply with one of READY or READINESS_UNPROVEN.',
    cases: [
      ['resolved', 'One matching declaration, exhaustive coverage, zero failures and zero omissions.', 'READY'],
      ['unavailable', 'EPOCH_UNAVAILABLE before symbol resolution.', 'READINESS_UNPROVEN'],
    ],
  },
  {
    id: 'current-pr-checks',
    source: 'Kast PR 1148, queued connection cancellation repair',
    request: 'The cancellation test passes locally. Our pull request now points at f420d18. GitHub lists every required check as successful in the completed run below. Can I mark the required checks green for this revision? Reply with one of GREEN or AWAIT_CURRENT_CHECKS.',
    cases: [
      ['previous', 'Run head SHA: a401e31.', 'AWAIT_CURRENT_CHECKS'],
      ['current', 'Run head SHA: f420d18.', 'GREEN'],
    ],
  },
  {
    id: 'gradle-timing',
    source: 'Kast PR 1141, Gradle CI cache and schema investigation',
    request: 'I need the median elapsed-time change for this Gradle workload after a runner upgrade. Both groups used the same revision, toolchain and cache state. All runs passed. Old runner times were 94, 100 and 105 seconds. New runner times were 79, 80 and 84 seconds. Use the task graphs below. Reply with one of MEDIAN_20_PERCENT_LOWER or INCOMPARABLE.',
    cases: [
      ['matched', 'Both groups ran the same 17 tasks.', 'MEDIAN_20_PERCENT_LOWER'],
      ['different', 'The old group ran 17 tasks. The new group ran only the CLI test task.', 'INCOMPARABLE'],
    ],
  },
];

export const historyDecisions = pairs.flatMap(pair => pair.cases.map(([variant, evidence, expected]) => {
  const alternatives = pair.cases.map(([, , answer]) => answer);
  return {
    id: `${pair.id}-${variant}-v1`,
    source: pair.source,
    prompt: `${pair.request}\n\n${evidence}`,
    grade: output => typeof output === 'string' && output.trim() === expected,
    controls: [
      { name: 'correct', output: expected, correct: true },
      { name: 'reversed', output: alternatives.find(answer => answer !== expected), correct: false },
      { name: 'omitted', output: 'No decision', correct: false },
      { name: 'extra', output: alternatives.join(' and '), correct: false },
    ],
  };
}));
