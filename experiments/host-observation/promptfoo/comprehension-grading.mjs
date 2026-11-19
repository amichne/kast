// Private evaluator owner. Never stage this file or its oracle data for a participant.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { isDeepStrictEqual } from 'node:util';
import { exactAnswer } from './corpus-grading.mjs';
import { comprehensionManifest } from './comprehension-cases.mjs';
import { gradeReplayDecision, replayOracleControls } from '../pi_evaluation_replay_grading.mjs';

function oracle(caseId) {
  const metadata = comprehensionManifest.cases.find(item => item.id === caseId);
  if (!metadata) throw Error('COMPREHENSION_CASE_REJECTED');
  const bytes = readFileSync(new URL(`./comprehension/oracles/${caseId}.json`, import.meta.url));
  if (createHash('sha256').update(bytes).digest('hex') !== metadata.oracleSha256) throw Error('COMPREHENSION_ORACLE_IDENTITY_CHANGED');
  return { metadata, expected: JSON.parse(bytes.toString('utf8')).expected };
}

export function gradeComprehension(caseId, output) {
  const { metadata, expected } = oracle(caseId);
  if (metadata.kind === 'exact-locations') {
    const correct = exactAnswer(output, expected);
    return { correct, reason: correct ? 'EXACT_LOCATIONS' : 'ANSWER_DIFFERS' };
  }
  let decision;
  try {
    if (typeof output !== 'string') throw Error('Expected response text');
    decision = JSON.parse(output);
  } catch { return { correct: false, reason: 'MALFORMED_RESPONSE' }; }
  if (metadata.kind === 'scope-coverage') {
    const graded = gradeReplayDecision('C5', decision);
    return { ...graded, reason: graded.correct ? 'QUALIFIED_SCOPE_AND_COVERAGE' : graded.reasons.join(' / ') };
  }
  if (metadata.kind !== 'freshness' && metadata.kind !== 'readiness') throw Error('COMPREHENSION_GRADER_REJECTED');
  const correct = isDeepStrictEqual(decision, expected);
  return { correct, reason: correct ? 'EXACT_QUALIFIED_DECISION' : 'ANSWER_DIFFERS' };
}

export function comprehensionControls(caseId) {
  const { metadata, expected } = oracle(caseId);
  let samples;
  if (metadata.kind === 'exact-locations') {
    const archiveBytes = readFileSync(new URL('./comprehension/controls/ktfmt-archived-response.json', import.meta.url));
    if (createHash('sha256').update(archiveBytes).digest('hex') !== metadata.controlsSha256) throw Error('COMPREHENSION_CONTROL_IDENTITY_CHANGED');
    const archived = JSON.parse(archiveBytes.toString('utf8'));
    const wrongTarget = 'core/src/main/kotlin/org/jetbrains/kotlinx/ktfmt/format/visitor/CallFormatter.kt:192';
    samples = [
      { name: 'correct', value: expected, correct: true },
      { name: 'correct-reordered', value: [...expected].reverse(), correct: true },
      { name: 'archived-missing-lines', output: archived.answer, correct: false, provenance: archived.provenance, evalId: archived.evalId },
      { name: 'omitted', value: expected.slice(1), correct: false },
      { name: 'extra', value: [...expected, wrongTarget], correct: false },
      { name: 'duplicate', value: [...expected.slice(0, -1), expected[0]], correct: false },
      { name: 'duplicate-extra', value: [...expected, expected[0]], correct: false },
      { name: 'wrong-target', value: [wrongTarget, ...expected.slice(1)], correct: false },
      { name: 'path-line-mismatch', value: expected.map(value => value.replace('CallFormatter.kt:507', 'CallFormatter.kt:348')
        .replace('ControlFlowExpressionFormatter.kt:348', 'ControlFlowExpressionFormatter.kt:507')), correct: false },
      { name: 'missing-lines', value: expected.map(value => value.replace(/\d+$/, '')), correct: false },
      { name: 'fenced-json', output: '```json\n' + JSON.stringify(expected) + '\n```', correct: false },
      { name: 'wrong-shape', value: { answer: expected }, correct: false },
    ];
  } else {
    const omitted = { ...expected };
    delete omitted[Object.keys(expected)[0]];
    samples = [
      { name: 'correct', value: expected, correct: true },
      { name: 'omitted', value: omitted, correct: false },
      { name: 'extra', value: { ...expected, invented: true }, correct: false },
      { name: 'duplicate-scope', value: { ...expected, scope: { ...expected.scope, sourceSets: ['main', 'main'] } }, correct: false },
      { name: 'wrong-scope', value: { ...expected, scope: { directory: '.', sourceSets: ['main', 'test'] } }, correct: false },
      { name: 'wrong-shape', value: [], correct: false },
    ];
    const changed = (name, values) => ({ name, value: { ...expected, ...values }, correct: false });
    switch (metadata.kind) {
      case 'scope-coverage':
        samples.push(...replayOracleControls('C5').reject.map((sample, index) => ({ name: `existing-counterexample-${index + 1}`, value: sample.decision, correct: false })),
          changed('duplicate-limitations', { limitations: ['byte-limit-reached', 'byte-limit-reached'] }));
        break;
      case 'freshness':
        samples.push(changed('wrong-target', { target: 'fixture.orders.submit(String)' }),
          changed('stale-location', { currentLineFromText: 5 }), changed('malformed-location', { currentLineFromText: '6' }),
          changed('reuse-stale-evidence', { canUsePriorLocation: true }), changed('false-compiler-proof', { compilerStatus: 'proven' }),
          changed('automatic-repeat', { nextAction: 'repeat-identical-request' }));
        break;
      case 'readiness':
        samples.push(changed('wrong-cause', { cause: 'DEADLINE_EXCEEDED' }), changed('false-compiler-proof', { compilerStatus: 'available' }),
          changed('false-absence', { absenceEstablished: true }), changed('automatic-retry', { automaticRetry: true }),
          changed('malformed-retry', { automaticRetry: 'false' }), changed('automatic-repeat', { nextAction: 'repeat-identical-request' }));
        break;
      default: throw Error('COMPREHENSION_GRADER_REJECTED');
    }
  }
  return [...samples.map(sample => ({ ...sample, output: sample.output ?? JSON.stringify(sample.value), provenance: sample.provenance ?? 'SYNTHETIC_CONTROL' })),
    { name: 'invalid-json', output: 'not JSON', correct: false }];
}
