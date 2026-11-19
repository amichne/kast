const owners = [
  ['preparation', 'coordinator'],
  ['oracle-preflight', 'grader'],
  ['compiler-preflight', 'coordinator'],
  ['host-preflight', 'coordinator'],
  ['participant', 'participant'],
  ['grading', 'grader'],
  ['cleanup', 'coordinator'],
];

/** One monotonic boundary, including failed phases and cleanup; absent work is never zero-duration work. */
export function createTrialTiming(boundary, clock = () => performance.now()) {
  const started = clock();
  const phases = new Map(owners.map(([phase, role]) => [phase, { phase, role, outcome: 'NOT_RUN' }]));
  let measured = 0;
  return {
    async measure(phase, effect, outcome = () => 'COMPLETED') {
      const receipt = phases.get(phase);
      if (!receipt || receipt.outcome !== 'NOT_RUN') throw Error('TRIAL_PHASE_REJECTED');
      const phaseStart = clock();
      receipt.outcome = 'FAILED';
      try {
        const result = await effect();
        receipt.outcome = outcome(result);
        return result;
      } finally {
        const elapsed = Math.max(0, clock() - phaseStart);
        measured += elapsed;
        receipt.elapsedMillis = Math.round(elapsed);
      }
    },
    snapshot() {
      const elapsed = Math.max(0, clock() - started);
      return { boundary, clock: 'MONOTONIC', wallMillis: Math.round(elapsed),
        unattributedMillis: Math.round(Math.max(0, elapsed - measured)),
        phases: [...phases.values()].map(receipt => ({ ...receipt })) };
    },
  };
}
