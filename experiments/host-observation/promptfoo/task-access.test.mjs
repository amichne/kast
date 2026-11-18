import assert from 'node:assert/strict';
import test from 'node:test';
import { gradeAccess, exact, retained, questions } from './task-access.mjs';

const symbols = { type: 'SYMBOLS', fields: ['NAME', 'SIGNATURE', 'LOCATION'] };
const scope = (directory) => ({
  type: 'DIRECTORY',
  relativeDirectoryPath: directory,
  sourceSetNames: ['main'],
});
const find = (name, kind, directory) => ({
  type: 'RUN',
  source: {
    type: 'SEARCH_DECLARATIONS',
    declarationName: name,
    declarationKinds: [kind],
    ...(directory ? { scope: scope(directory) } : {}),
  },
  terminalStep: { type: 'EMIT', output: symbols },
});
const refs = { type: 'SYMBOL_REFS', symbolRefs: [exact] };
const occurrences = { type: 'OCCURRENCES' };
const relation = {
  type: 'RUN',
  source: refs,
  steps: [
    {
      type: 'EXPAND_RELATION',
      relation: 'REFERENCES',
      expansionScope: {
        type: 'SOURCE_DOMAIN',
        generatedSources: 'INCLUDE',
        sourcePolicy: 'PRODUCTION_AND_TEST',
        directory: 'cli',
        sourceSets: ['main'],
      },
    },
  ],
  terminalStep: { type: 'EMIT', output: occurrences },
};
const page = { type: 'READ_RESULT', result: retained, output: occurrences, cursor: 20, evidence_cursor: 3 };
const body = {
  type: 'READ_SOURCE',
  candidateRef: exact,
  region: 'CALLABLE_BODY',
  text: { type: 'WINDOW', beforeLines: 10, afterLines: 10 },
};
const walk = {
  type: 'RUN',
  source: refs,
  steps: [{ type: 'WALK', relation: 'CALLERS', maximumDepth: 3, expansionScope: { type: 'WORKSPACE' } }],
  terminalStep: { type: 'EMIT', output: { type: 'TRAVERSAL_RECORDS' } },
};
const samples = {
  interface: [
    find('QueryCompletionDocument', 'CLASS', 'protocol'),
    find('QueryCompletionDocument', 'CLASS', 'cli'),
  ],
  overloads: [find('run', 'FUNCTION', 'app-server'), find('run', 'FUNCTION', 'cli')],
  occurrences: [relation, { ...relation, steps: [{ ...relation.steps[0], relation: 'CALLERS' }] }],
  budget: ['4096', '1000'],
  retained: [page, { ...page, evidence_cursor: 0 }],
  cursor: [find('submitOrder', 'FUNCTION'), find('submitOrder', 'FUNCTION', 'orders')],
  source: [body, { ...body, text: { type: 'COMPLETE' } }],
  walk: [walk, { ...walk, steps: [{ ...walk.steps[0], maximumDepth: 2 }] }],
};
test('every historical task accepts its reference answer and rejects a neighboring mistake', () => {
  assert.deepEqual(Object.keys(questions).sort(), Object.keys(samples).sort());
  for (const [id, [valid, invalid]] of Object.entries(samples)) {
    const encode = (request) => (typeof request === 'string' ? request : JSON.stringify({ request }));
    assert.equal(gradeAccess(id, encode(valid)).pass, true, id);
    assert.equal(gradeAccess(id, encode(invalid)).pass, false, id);
  }
});

test('a representative walk cannot establish every caller through depth three', () => {
  const r = {
    ...walk,
    steps: [{ ...walk.steps[0], strategy: { type: 'BOUNDED_FAN_OUT', maximumEdgesPerNode: 1 } }],
  };
  assert.equal(gradeAccess('walk', JSON.stringify({ request: r })).pass, false);
});

test('the historical CLI can express workspace discovery as recursive root scope', () => {
  const r = find('submitOrder', 'FUNCTION');
  r.source.scope = {
    type: 'DIRECTORY',
    relativeDirectoryPath: '.',
    includeSubdirectories: true,
    sourceSetNames: ['main', 'test'],
  };
  assert.equal(gradeAccess('cursor', JSON.stringify({ request: r })).pass, true);
  r.source.scope.includeSubdirectories = false;
  assert.equal(gradeAccess('cursor', JSON.stringify({ request: r })).pass, false);
});
