import { fileURLToPath } from 'node:url';

const source = 'src/main/kotlin/kotlinx/coroutines/Delay.kt';
export const coroutineDelayCase = {
  id: 'coroutine-delay-rounding',
  kind: 'edit',
  prompt: [
    'Fix the conversion used by the millisecond scheduler. A request for 998 ms plus one nanosecond currently wakes up early.',
    'Return the smallest whole millisecond delay that covers each positive finite duration. Preserve exact millisecond values, including large ones.',
    'Nonpositive durations must yield zero. Positive infinity must yield Long.MAX_VALUE. Keep the existing signature.',
  ].join(' '),
  fixture: fileURLToPath(new URL('./developer-tasks/coroutine-delay/', import.meta.url)),
  warmup: { name: 'toDelayMillis', kind: 'function' },
  allowedChangedPaths: [source],
  oracle: {
    main: fileURLToPath(new URL('./developer-tasks/oracles/CoroutineDelayRounding.kt', import.meta.url)),
    mainClass: 'oracle.CoroutineDelayRoundingKt',
    candidateSources: [source],
    expectedStdout: 'PASS',
  },
};
