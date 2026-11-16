import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {test} from 'node:test';
import {scan} from './source-declarations.ts';

test('records declaration identifiers and executable test names at their source positions', () => {
  const result = scan('example.ts', `import {test} from 'node:test';
const value = 1;
function helper() {}
test('works', () => {});
it('also works', function () { return value; });
`);
  assert.deepEqual(result, {type: 'accepted', names: ['also works', 'helper', 'value', 'works'], declarations: [
    {name: 'value', startLine: 2, endLine: 2, executableTest: false},
    {name: 'helper', startLine: 3, endLine: 3, executableTest: false},
    {name: 'works', startLine: 4, endLine: 4, executableTest: true},
    {name: 'also works', startLine: 5, endLine: 5, executableTest: true},
  ]});
});

test('ignores text, non-body callbacks and non-direct calls', () => {
  const result = scan('example.js', `// test('comment', () => {})
const text = "test('string', () => {})";
test('missing');
test('reference', callback);
other.test('member', () => {});
`);
  assert.deepEqual(result, {type: 'accepted', names: ['text'], declarations: [
    {name: 'text', startLine: 2, endLine: 2, executableTest: false},
  ]});
});

test('rejects syntax errors and parses JSX and destructured names', () => {
  assert.deepEqual(scan('bad.ts', 'function broken( {'), {type: 'rejected', reason: 'INVALID_SOURCE'});
  assert.deepEqual(scan('view.tsx', 'const {first, nested: [second]} = data;\nconst View = () => <div/>;'), {
    type: 'accepted', names: ['View', 'first', 'second'], declarations: [
      {name: 'first', startLine: 1, endLine: 1, executableTest: false},
      {name: 'second', startLine: 1, endLine: 1, executableTest: false},
      {name: 'View', startLine: 2, endLine: 2, executableTest: false},
    ],
  });
});

test('CLI reports accepted and unreadable paths without source payload', () => {
  const output = execFileSync(process.execPath, ['source-declarations.ts', 'source-declarations.test.ts', 'missing-file.ts'], {cwd: import.meta.dirname, encoding: 'utf8'});
  const result = JSON.parse(output);
  assert.equal(result[0].path, 'source-declarations.test.ts');
  assert.equal(result[0].result.type, 'accepted');
  assert.deepEqual(result[1], {path: 'missing-file.ts', result: {type: 'rejected', reason: 'UNREADABLE_SOURCE'}});
  assert.equal(output.includes('execFileSync(process.execPath'), false);
});
