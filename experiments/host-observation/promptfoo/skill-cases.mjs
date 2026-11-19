export const invocationFile = 'invocation/src/main/kotlin/fixture/staticcallbacks/invocation/Invocation.kt';
export const completionFile = 'protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt';
export const liveNames = { present: 'invokeCallback', absent: 'scheduleCallbackRetry' };
export const originalCases = ['select', 'kind', 'walk', 'present', 'absent', 'callback'];
// The guided comparison gives one arm source search; these cases measure when Kast should or should not run.
export const guidedCases = new Set(['source-lookup', 'literal-read']);

function declarationPrompt(name) {
  return `Which file declares the exact function ${name} under invocation in main? Return its workspace-relative path, ABSENT for proved absence, or UNKNOWN.`;
}

export const skillCases = {
  select: {
    prompt: 'Find interface Widget exactly under app/src in the main source set. Return only a JSON argv array including kast. Use the declaration family accepted by the installed CLI and request name, signature, and location.',
  },
  kind: {
    prompt: 'Find declarations named Widget under app/src in main. Restrict the result to both property and type alias declarations. Consult the installed --kind flag contract. Return only a JSON argv array including kast.',
  },
  walk: {
    prompt: 'Return only the JSON request document for two hops of incoming callers with depth records and a 20-result presentation. Use the placeholder EXACT_REF for an exact symbol ref. Consult the installed contract. This is a draft, do not send it.',
  },
  callees: {
    prompt: 'Draft the Kast command to find declarations called by the exact declaration selected by EXACT_REF. Use one hop and return only a JSON argv array including kast. Treat EXACT_REF as a placeholder and do not run the command.',
  },
  diagnostics: {
    prompt: 'Draft a Kast command to report up to 12 Kotlin diagnostics under app/src. Return only a JSON argv array including kast.',
  },
  retained: {
    prompt: 'A previous Kast query issued a retained result reference RESULT_REF and presentation cursor CURSOR. Draft a command to read the next page with output kind symbols. Return only a JSON argv array including kast. Use these placeholders literally.',
  },
  present: { workspace: 'fixture', prompt: declarationPrompt(liveNames.present) },
  absent: { workspace: 'fixture', prompt: declarationPrompt(liveNames.absent) },
  callback: {
    workspace: 'fixture',
    prompt: 'Does Kast prove the complete set of direct callees for forwardOnce under forwarding in main? Return COMPLETE if it does, or the specific Kast cause code if it cannot.',
  },
  'query-completion-document': {
    workspace: 'repository',
    prompt: 'Use at most four Kast semantic calls without an identical retry. Find QueryCompletionDocument under protocol in main. Establish whether it is an interface. Read a source region that contains the declaration. Use ten lines of context on each side when the installed contract accepts a window. List its implementations under protocol in main. Keep issued identifiers. Preserve scope, completeness, failures, and omissions. Return only a JSON object with keys path, declarationKind, and implementations. Use the workspace-relative path and the Kotlin declaration form. Use an array of implementation names in the requested scope. Use null if Kast cannot prove the result.',
  },
  'source-lookup': { workspace: 'fixture', prompt: declarationPrompt(liveNames.present) },
  // Source text answers this directly, so a correct Kast-enabled agent makes no Kast call.
  'literal-read': {
    workspace: 'fixture',
    prompt: 'Which string literal does the function escapeSink return under invocation? Return only the literal text without quotes.',
  },
  // The workspace has no Gradle wrapper, so preparation must reject with a typed cause and no identical retry.
  unprepared: {
    workspace: 'unwrapped',
    prompt: `${declarationPrompt(liveNames.present)} If Kast cannot answer, return UNKNOWN followed by one space and the Kast cause code.`,
  },
};

export const armCapabilities = {
  canonical: { comparison: 'canonical-skill', kast: true, read: false },
  reference: { comparison: 'skill-version', kast: true, read: false, snapshot: 'reference' },
  delivered: { comparison: 'skill-version', kast: true, read: false, snapshot: 'delivered' },
  'with-kast': { comparison: 'guided-kast-access', kast: true, read: true },
  'without-kast': { comparison: 'guided-kast-access', kast: false, read: true },
};
