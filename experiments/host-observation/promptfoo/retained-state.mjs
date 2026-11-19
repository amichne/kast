export const worlds = ['alphaSink', 'betaSink'];

export function participantPrompt(arm, source, selected) {
  const text = [
    'A previous query retained exactly one declaration from this unchanged Kotlin file.',
    'Which declaration did that query retain? Return only its name, or UNKNOWN if the given evidence cannot distinguish it.',
    source,
  ];
  if (arm === 'materialized')
    text.push('The independent setup record contains the previous exact-name query: ' + selected);
  return text.join('\n\n');
}

export function gradeName(answer, expected, reads = []) {
  const pass = answer === expected;
  return {
    pass,
    score: Number(pass),
    namedScores: {
      supportedByKast: Number(
        pass &&
          reads.some(
            (read) =>
              read.tool === 'read_previous_result' && read.complete === true && read.name === expected,
          ),
      ),
    },
    reason: pass
      ? 'Recovered the retained declaration'
      : answer === 'UNKNOWN'
        ? 'Abstained without a declaration'
        : 'Incorrect retained declaration',
  };
}
