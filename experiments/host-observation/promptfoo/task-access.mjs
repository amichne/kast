import { execFileSync } from 'node:child_process';
import { isDeepStrictEqual } from 'node:util';
import { fileURLToPath } from 'node:url';
import Ajv from 'ajv/dist/2020.js';

// PR 1120 is the merged source for the contract-17 exercise.
export const revision = 'bc18ec841073c32f4d4bc2a93fe4d15b43912a6d';
export function historicalFile(path) {
  return execFileSync('git', ['show', revision + ':' + path], {
    cwd: fileURLToPath(new URL('.', import.meta.url)),
    encoding: 'utf8',
  });
}
export const schemaText = historicalFile('agent-tools/references/query-schema.json');
const admits = new Ajv({ strict: false }).compile(JSON.parse(schemaText));
export const exact = 'exact:v5:AAAAAAAAAAAAAAAAAAAAAA';
export const retained = 'result:v1:11111111-1111-1111-1111-111111111111';

export const questions = {
  interface:
    'Find the interface QueryCompletionDocument under protocol in main. Include signature and location.',
  overloads:
    'Several functions named run are declared under app-server in main. Before requesting their callers, find those declarations with their signatures and locations.',
  occurrences: 'Show every use site for ' + exact + '. Restrict destinations to cli and source set main.',
  budget:
    'Trace affected uses of ' +
    exact +
    ', with names and signatures. The response must fit in 1000 bytes. If the API cannot enforce this, return its minimum byte limit as a JSON integer.',
  retained:
    'Read the next page of retained occurrences for ' +
    retained +
    ' from row cursor 20 and evidence cursor 3.',
  cursor:
    'A cursor is on submitOrder() inside checkout() at UTF-16 offset 130 in orders/src/main/kotlin/Orders.kt. Find callers of submitOrder without guessing its identity. The declaration scope is unknown. Draft the first workspace lookup with the function signatures and locations.',
  source: 'Read only the callable body for ' + exact + ' with ten lines before and after the anchor.',
  walk: 'List incoming callers of ' + exact + ' through three hops. Keep each hop.',
};

function directory(scope, path) {
  return (
    scope?.type === 'DIRECTORY' &&
    scope.relativeDirectoryPath === path &&
    scope.includeSubdirectories !== false &&
    isDeepStrictEqual(scope.sourceSetNames, ['main'])
  );
}
function workspaceScope(scope) {
  if (scope == null) return true;
  const sourceSets = scope.sourceSetNames ?? ['main', 'test'];
  return (
    scope.type === 'DIRECTORY' &&
    scope.relativeDirectoryPath === '.' &&
    scope.includeSubdirectories !== false &&
    isDeepStrictEqual(sourceSets.toSorted(), ['main', 'test'])
  );
}

function find(request, name, kind, path) {
  const s = request.source;
  const projection = request.terminalStep?.output;
  return (
    request.type === 'RUN' &&
    !request.steps?.length &&
    s?.type === 'SEARCH_DECLARATIONS' &&
    s.declarationName === name &&
    (s.nameMatch ?? 'EXACT') === 'EXACT' &&
    isDeepStrictEqual(s.declarationKinds, [kind]) &&
    (path ? directory(s.scope, path) : workspaceScope(s.scope)) &&
    request.terminalStep.type === 'EMIT' &&
    projection?.type === 'SYMBOLS' &&
    ['SIGNATURE', 'LOCATION'].every((field) => projection.fields.includes(field))
  );
}
function fromReference(request, relation, projection) {
  return (
    request.type === 'RUN' &&
    request.source.type === 'SYMBOL_REFS' &&
    isDeepStrictEqual(request.source.symbolRefs, [exact]) &&
    request.steps?.length === 1 &&
    request.steps[0].relation === relation &&
    request.terminalStep.type === 'EMIT' &&
    request.terminalStep.output?.type === projection
  );
}

const predicates = {
  interface: (r) => find(r, 'QueryCompletionDocument', 'CLASS', 'protocol'),
  overloads: (r) => find(r, 'run', 'FUNCTION', 'app-server'),
  cursor: (r) => find(r, 'submitOrder', 'FUNCTION'),
  occurrences: (r) => {
    if (!fromReference(r, 'REFERENCES', 'OCCURRENCES')) return false;
    const [step] = r.steps;
    const scope = step.expansionScope;
    return (
      step.type === 'EXPAND_RELATION' &&
      scope?.type === 'SOURCE_DOMAIN' &&
      scope.directory === 'cli' &&
      isDeepStrictEqual(scope.sourceSets, ['main']) &&
      scope.includeSubdirectories !== false &&
      scope.generatedSources !== 'EXCLUDE' &&
      (scope.sourcePolicy ?? 'PRODUCTION_AND_TEST') === 'PRODUCTION_AND_TEST'
    );
  },
  retained: (r) =>
    r.type === 'READ_RESULT' &&
    r.result === retained &&
    r.cursor === 20 &&
    r.evidence_cursor === 3 &&
    r.output?.type === 'OCCURRENCES',
  source: (r) =>
    r.type === 'READ_SOURCE' &&
    r.candidateRef === exact &&
    r.region === 'CALLABLE_BODY' &&
    r.text?.type === 'WINDOW' &&
    r.text.beforeLines === 10 &&
    r.text.afterLines === 10,
  walk: (r) =>
    fromReference(r, 'CALLERS', 'TRAVERSAL_RECORDS') &&
    r.steps[0].type === 'WALK' &&
    r.steps[0].maximumDepth === 3 &&
    (r.steps[0].strategy?.type ?? 'BREADTH_FIRST') === 'BREADTH_FIRST' &&
    [undefined, null, 'WORKSPACE', 'RETAINED_SEED'].includes(r.steps[0].expansionScope?.type),
};

export function gradeAccess(caseId, output) {
  let pass = false;
  try {
    const answer = typeof output === 'string' ? JSON.parse(output) : output;
    pass =
      caseId === 'budget'
        ? answer === 4096
        : admits(answer) &&
          (answer.request.executionBudget?.maxReturnedBytes ?? 4096) >= 4096 &&
          predicates[caseId](answer.request);
  } catch (error) {
    if (!(error instanceof SyntaxError)) throw error;
  }
  return {
    pass,
    score: Number(pass),
    reason: caseId + (pass ? ' matched the contract' : ' failed the contract'),
  };
}
