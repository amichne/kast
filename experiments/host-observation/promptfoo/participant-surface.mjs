// Participant blinding owner. A participant must see an ordinary engineering
// request, never the measurement around it. This module decides, before any model
// process starts, whether the text, paths and tool names a participant can observe
// are free of measurement vocabulary. It is pure except for directory creation.
import { createHash } from 'node:crypto';
import { mkdtemp, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, join, sep } from 'node:path';

// Adapted from the pstack eval playbook's blinding rules. Each entry names a role
// or process of the measurement itself. Ordinary engineering words such as
// "test", "fixture" and "diagnostic" stay admissible: Kotlin tasks legitimately
// ask about tests, and compiler diagnostics are Kast domain vocabulary.
export const measurementVocabulary = Object.freeze({
  version: 1,
  source: 'https://github.com/backnotprop/pstack/blob/main/skills/poteto-mode/playbooks/eval.md',
  terms: Object.freeze([
    ['eval', /^evals?$|^evaluat(e|es|ed|ing|ion|ions|or|ors)$/],
    ['judge', /^judg(e|es|ed|ing)$/],
    ['grader', /^grad(er|ers|ing)$/],
    ['oracle', /^oracles?$/],
    ['rubric', /^rubrics?$/],
    ['score', /^scor(e|es|ed|ing)$/],
    ['benchmark', /^benchmarks?$|^benchmarking$/],
    ['candidate', /^candidates?$/],
    ['arena', /^arenas?$/],
    ['experiment', /^experiments?$|^experimental$/],
    ['trial', /^trials?$/],
    ['arm', /^arms?$/],
    ['panel', /^panels?$/],
    ['harness', /^harness(es)?$/],
    ['promptfoo', /^promptfoo$/],
    ['curated', /^curat(ed|or|ors|ion)$/],
    ['holdout', /^holdouts?$/],
    ['compare', /^compar(e|es|ed|ing|ison|isons)$/],
  ].map(([term, pattern]) => Object.freeze({ term, pattern }))),
});

export const participantDirectoryPattern = /^project-[A-Za-z0-9]{6}$/;
export const participantRoles = Object.freeze(['participant', 'coordinator']);

// Split camelCase, PascalCase and acronyms before lowercasing so that
// `readEvalPlan`, `KAST_EVAL_PI` and `eval-i9W` all expose the word `eval`.
export function vocabularyTokens(text) {
  return String(text)
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean);
}

export function measurementTerms(text) {
  const found = new Set();
  for (const token of vocabularyTokens(text)) {
    for (const { term, pattern } of measurementVocabulary.terms) if (pattern.test(token)) found.add(term);
  }
  return [...found].sort();
}

const sha256 = text => createHash('sha256').update(text).digest('hex');

// Returns a closed outcome. ADMITTED carries the digest of the exact observed
// surface; REJECTED carries one finding per surface and term, never the text.
export function admitParticipantSurface({ role = 'participant', system = '', prompt, directory, visiblePaths = [], toolNames = [] }) {
  if (!participantRoles.includes(role)) return rejected([{ surface: 'ROLE', code: 'PARTICIPANT_ROLE_UNKNOWN' }]);
  if (role === 'coordinator') {
    // The playbook permits dispatch roles to know their purpose. Their delegates
    // pass through this owner separately as participants.
    return Object.freeze({ state: 'NOT_APPLICABLE', role, vocabularyVersion: measurementVocabulary.version });
  }
  if (typeof prompt !== 'string' || typeof system !== 'string' || typeof directory !== 'string' ||
      !Array.isArray(visiblePaths) || visiblePaths.some(path => typeof path !== 'string') ||
      !Array.isArray(toolNames) || toolNames.some(name => typeof name !== 'string')) {
    return rejected([{ surface: 'INPUT', code: 'PARTICIPANT_SURFACE_MALFORMED' }]);
  }
  const findings = [];
  const scan = (surface, code, text) => {
    for (const term of measurementTerms(text)) findings.push({ surface, code, term });
  };
  scan('SYSTEM', 'PARTICIPANT_TEXT_VOCABULARY', system);
  scan('TASK', 'PARTICIPANT_TEXT_VOCABULARY', prompt);
  if (!participantDirectoryPattern.test(basename(directory))) {
    findings.push({ surface: 'WORKING_DIRECTORY', code: 'PARTICIPANT_DIRECTORY_UNSANITIZED' });
  }
  const paths = [...new Set([directory, ...visiblePaths])];
  // A sanitized directory name is fixed apart from its random suffix; scanning the
  // suffix would reject an occasional accidental token and make admission flaky.
  for (const path of paths) {
    for (const segment of path.split(sep)) {
      if (!participantDirectoryPattern.test(segment)) scan('VISIBLE_PATH', 'PARTICIPANT_PATH_VOCABULARY', segment);
    }
  }
  for (const name of toolNames) scan('TOOL_NAME', 'PARTICIPANT_TOOL_VOCABULARY', name);
  if (findings.length) return rejected(findings);
  return Object.freeze({
    state: 'ADMITTED', role, vocabularyVersion: measurementVocabulary.version,
    surfaceSha256: sha256(JSON.stringify({ system, prompt, paths, toolNames: [...toolNames].sort() })),
  });
}

function rejected(findings) {
  const unique = [...new Map(findings.map(item => [JSON.stringify(item), Object.freeze(item)])).values()];
  return Object.freeze({ state: 'REJECTED', vocabularyVersion: measurementVocabulary.version, findings: Object.freeze(unique) });
}

// Pi renders its working directory into every system prompt, so the harness
// names it like an ordinary checkout. The product name is excluded as well:
// a source-only control must not learn which tool the other configuration has.
export async function createParticipantDirectory(parent = tmpdir()) {
  return realpath(await mkdtemp(join(parent, 'project-')));
}
