export default function assertOracle(output) {
  const pass = output?.status === 'PASSED';
  return {
    pass,
    score: pass ? 1 : 0,
    reason: typeof output?.reason === 'string' ? output.reason : 'ORACLE_RESULT_UNAVAILABLE',
  };
}
