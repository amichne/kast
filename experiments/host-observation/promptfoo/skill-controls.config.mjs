// Synthetic receipts exercise the same assertion as the live skill suite.
import { completionFile, invocationFile } from './skill-cases.mjs';
import { gradeSkill } from './skill-grading.mjs';

const workspace = '/project';
const live = { root: workspace, generation: 2 };
const scope = path => ({ sourceSets: ['main'], directory: { path, containment: 'descendants' }, packageName: null });
const symbols = (name, path, kinds) => ({
  type: 'symbols', match: { type: 'name', text: name, matching: 'exact-name' }, scope: scope(path), declarationKinds: kinds,
});
const query = (from, steps = []) => ({ from, steps, output: { type: 'symbols' } });
const call = (argv, stdout, status = 0) => ({ tool: 'kast', argv, status, stdout: JSON.stringify(stdout) });
const complete = (question, items) => ({ type: 'complete', document: {
  live, status: 'complete', question, items, coverage: { exhaustive: true }, evidence_window: { type: 'FINAL' },
  invocation: { accumulated_row_count: items.length }, original_evidence_summary: { failure_count: 0, omission_count: 0 },
} });

const controls = [];
function sample(caseId, id, answer, calls, pass, { planned, uncertainty } = {}) {
  controls.push({ id: caseId + '/' + id, caseId, answer, metadata: { workspace, calls, planned }, pass, uncertainty });
}

const describe = ['describe', 'query'];
const spellings = ['class', 'function', 'property', 'type-alias'];
const kindEvidence = call(describe, { content: { flags: [{ name: '--kind', value_spellings: spellings }] } });
const kinds = ['property', 'type-alias'];
function kind(id, selection, receipt, pass) {
  const answer = JSON.stringify(['kast', 'query', '--name', 'Widget', '--in', 'app/src', '--source-set', 'main',
    ...selection.flatMap(value => ['--kind', value])]);
  sample('kind', id, answer, [receipt], pass, { planned: query(symbols('Widget', 'app/src', selection)) });
}
kind('full-contract', kinds, kindEvidence, true);
kind('projected-spellings', kinds, call([...describe, '--jq', '.content.flags[] | select(.name == "--kind") | .value_spellings'], spellings), true);
kind('irrelevant-projection', kinds, call([...describe, '--jq', '".irrelevant"'], '.irrelevant'), false);
kind('omitted-choice', ['property'], kindEvidence, false);
kind('extra-choice', [...kinds, 'function'], kindEvidence, false);

function rejectedCallback(from, relation, cause) {
  return call(['query'], { type: 'rejected_document', document: { operation: 'query.run', status: 'rejected',
    rejection: { type: 'COMPLETION_UNPROVEN', detail: {
      evidence: { type: 'RETAINED', question: query(from, [{ type: 'related', relation }]) }, cause: { type: cause },
    } },
  } }, 10);
}
const allKinds = ['class', 'function', 'property', 'type-alias'];
const cause = 'CALLBACK_GRAPH_UNPROVEN';
const forward = symbols('forwardOnce', 'forwarding', allKinds);
sample('callback', 'direct', cause, [rejectedCallback(forward, 'callees', cause)], true);
sample('callback', 'wrong-scope', cause, [rejectedCallback({ ...forward, scope: scope('invocation') }, 'callees', cause)], false);
sample('callback', 'wrong-name', cause, [rejectedCallback(symbols('sharedWrapper', 'forwarding', allKinds), 'callees', cause)], false);
sample('callback', 'wrong-kind', cause, [rejectedCallback({ ...forward, declarationKinds: ['property'] }, 'callees', cause)], false);
sample('callback', 'malformed-kind', cause, [rejectedCallback({ ...forward, declarationKinds: 'function' }, 'callees', cause)], false);
sample('callback', 'wrong-direction', cause, [rejectedCallback(forward, 'callers', cause)], false);
sample('callback', 'wrong-cause', cause, [rejectedCallback(forward, 'callees', 'DEADLINE_EXCEEDED')], false);
const ref = { type: 'references', values: [{ token: 'issued' }] };
const seed = call(['query'], complete(query(forward), [{ name: 'forwardOnce', kind: 'function', ref: 'issued' }]));
const rejection = rejectedCallback(ref, 'callees', cause);
sample('callback', 'issued-ref', cause, [seed, rejection], true);
sample('callback', 'unissued-ref', cause, [rejection], false);

sample('callback', 'malformed-references', cause, [seed,
  rejectedCallback({ type: 'references', values: { 0: { token: 'issued' }, length: 1 } }, 'callees', cause)], false);

const correctRow = { name: 'invokeCallback', kind: 'function', location: { file: workspace + '/' + invocationFile } };
const locate = name => complete(query(symbols(name, 'invocation', ['function'])), [correctRow]);
sample('present', 'scoped-declaration', invocationFile, [call(['query'], locate('invokeCallback'))], true);
sample('present', 'wrong-seed', invocationFile, [call(['query'], locate('main'))], false);
sample('present', 'unknown-after-unrelated-query', 'UNKNOWN', [call(['query'], locate('main'))], false);

const scopedCase = 'declaration-outside-scope';
const forwardLookup = query(symbols('invokeCallback', 'forwarding', ['function']));
sample(scopedCase, 'exhaustive-absence', 'ABSENT', [call(['query'], complete(forwardLookup, []))], true);
sample(scopedCase, 'other-directory', 'ABSENT', [call(['query'], complete(query(symbols('invokeCallback', 'invocation', ['function'])), []))], false);
sample(scopedCase, 'other-name', 'ABSENT', [call(['query'], complete(query(symbols('scheduleCallbackRetry', 'forwarding', ['function'])), []))], false);
sample(scopedCase, 'out-of-scope-path', invocationFile, [call(['query'], locate('invokeCallback'))], false);
const partial = complete(forwardLookup, []);
partial.document.coverage.exhaustive = false;
sample(scopedCase, 'incomplete-absence', 'ABSENT', [call(['query'], partial)], false);

const name = 'QueryCompletionDocument';
const file = workspace + '/' + completionFile;
const discover = call(['query', '--name', name], complete(query(symbols(name, 'protocol', ['class'])), [
  { name, location: { file }, ref: 'issued', kind: 'classlike' },
]));
const text = 'data class QueryCompletionDocument()';
const selection = { selector: 'source-selector', range: { startInclusive: 12, endExclusive: 12 + text.length } };
const read = call(['source', 'read', '--ref', 'issued'], { type: 'complete', document: {
  operation: 'source.read', status: 'complete', live, snapshot: { file }, region: { kind: 'declaration', selection },
  text: { type: 'returned', selection, text },
} });
const expansion = { type: 'SOURCE_DOMAIN', source_sets: ['main'], directory: scope('protocol').directory,
  source_policy: 'production-only', generated_sources: 'exclude' };
const step = { type: 'related', relation: 'implementations', expansion_scope: expansion };
const resolved = call(['query', '--request', '-'], complete(query(ref, [step]), []));
const rejected = call(['query', '--ref', 'issued', '--relation', 'implementations', '--in', 'protocol', '--source-set', 'main'],
  { type: 'rejected', failure: 'INVALID_COMMAND' }, 2);
const answer = implementations => JSON.stringify({ path: completionFile, declarationKind: 'data class', implementations });
const qcd = (id, result, calls, pass, uncertainty) => sample('query-completion-document', id, answer(result), calls, pass, { uncertainty });
qcd('completed', [], [discover, read, resolved], true, 0);
qcd('qualified-rejection', null, [discover, read, rejected], false, 1);
qcd('invented-absence', [], [discover, read, rejected], false, 0);
qcd('null-after-proof', null, [discover, read, resolved], false, 0);
qcd('null-without-attempt', null, [discover, read], false, 0);
qcd('rejection-of-other-ref', null, [discover, read, { ...rejected, argv: rejected.argv.map(v => v === 'issued' ? 'other' : v) }], false, 0);

const originalQuestion = { ...query(ref, [step]), completion: { type: 'COMPLETE_ONLY', model: 'COMPILER_RESOLVED_STATIC_V1' } };
function rejectedQuestion(question, detail = {}) {
  return call(['query', '--request', '-'], { type: 'rejected_document', document: {
    operation: 'query.run', status: 'rejected', rejection: { type: 'COMPLETION_UNPROVEN', detail: {
      model: 'COMPILER_RESOLVED_STATIC_V1', cause: { type: 'OMITTED_EVIDENCE' },
      evidence: { type: 'RETAINED', result: 'result:v1:73ec38aa-39b9-479b-9072-e6b5aec13474', question }, ...detail,
    } },
  } }, 10);
}
const semanticRejection = rejectedQuestion(originalQuestion);
qcd('scoped-semantic-rejection', null, [discover, read, semanticRejection], false, 1);
qcd('semantic-rejection-is-not-absence', [], [discover, read, semanticRejection], false, 0);
qcd('other-semantic-ref', null, [discover, read, rejectedQuestion({ ...originalQuestion,
  from: { type: 'references', values: [{ token: 'other' }] } })], false, 0);
qcd('other-semantic-scope', null, [discover, read, rejectedQuestion({ ...originalQuestion,
  steps: [{ ...step, expansion_scope: { ...expansion, directory: scope('runtime').directory } }] })], false, 0);
qcd('stale-ref-rejection', null, [discover, read, rejectedQuestion(originalQuestion,
  { cause: { type: 'ITEM_FAILURE' }, originalFailure: { type: 'REFERENCE', position: 0, reason: 'STALE_AUTHORITY' } })], false, 0);
qcd('missing-retained-question', null, [discover, read, rejectedQuestion(originalQuestion,
  { evidence: { type: 'UNAVAILABLE' } })], false, 0);
qcd('scoped-proof-after-rejection', null, [discover, read, semanticRejection,
  { ...resolved, argv: ['query', '--request', '-', '--json'] }], false, 0);

const byId = new Map(controls.map(control => [control.id, control]));
export default {
  description: 'Kast skill grader controls', sharing: false, prompts: ['{{controlId}}'],
  providers: [{
    id: () => 'kast:skill-grader-controls',
    async callApi(id) {
      const control = byId.get(id);
      return control ? { output: control.answer, metadata: control.metadata } : { error: 'Unknown skill grader control' };
    },
  }],
  tests: controls.map(control => ({
    description: '[skill-control] ' + control.id, vars: { controlId: control.id },
    assert: [{ type: 'javascript', metric: 'graderControl', value: (output, context) => {
      const result = gradeSkill(control.caseId, output, context.providerResponse?.metadata);
      const pass = result.pass === control.pass &&
        (control.uncertainty === undefined || result.namedScores.qualifiedUncertainty === control.uncertainty);
      return { pass, score: Number(pass), reason: control.id + ': ' + result.reason };
    } }],
  })),
};
