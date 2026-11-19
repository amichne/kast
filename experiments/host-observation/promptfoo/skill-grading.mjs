import { isDeepStrictEqual } from 'node:util';
import { resolve } from 'node:path';
import { completionFile, declarationQuestions, invocationFile, skillCases } from './skill-cases.mjs';
import { parseFlags, sourceFlags } from './skill-sandbox.mjs';

const selectFlags = new Set(['--name', '--name-match', '--in', '--kind', '--source-set', '--fields', '--output']);
const references = { type: 'references', values: [{ token: 'EXACT_REF' }] };
const scope = path => ({ sourceSets: ['main'], directory: { path, containment: 'descendants' }, packageName: null });
const matching = name => ({ type: 'name', text: name, matching: 'exact-name' });
const equal = isDeepStrictEqual;
const sorted = value => Array.isArray(value) ? value.toSorted() : undefined;

function parse(text) {
  try { return JSON.parse(text); } catch { return undefined; }
}

function argv(text, head, allowed) {
  const value = parse(text);
  if (!Array.isArray(value) || !value.every(arg => typeof arg === 'string') ||
    !equal(value.slice(0, head.length), head)) return null;
  return parseFlags(value.slice(head.length), allowed) && value.slice(head.length);
}

export async function planSkillDraft(caseId, output, plan) {
  if (caseId === 'walk') {
    if (!parse(output)) return undefined;
    const result = await plan(['--request', '-'], output);
    return result.status === 0 && parse(result.stdout)?.type === 'planned' ? parse(result.stdout).request : undefined;
  }
  const allowed = caseId === 'callees' ? new Set(['--ref', '--relation']) : selectFlags;
  const args = argv(output, ['kast', 'query'], allowed);
  if (!args) return undefined;
  const result = await plan(args);
  if (result.status !== 0) return undefined;
  const answer = parse(result.stdout);
  return answer?.type === 'planned' ? answer.request : undefined;
}

function recordedQueries(calls) {
  return calls.flatMap(call => {
    if (call?.tool !== 'kast' || call.argv?.[0] !== 'query') return [];
    const result = parse(call.stdout);
    if (result?.type === 'complete' && call.status === 0) return [result];
    if (result?.type === 'rejected_document' && Number.isInteger(call.status) && call.status !== 0) return [result];
    return [];
  });
}

function exhaustive(doc, workspace) {
  return doc?.status === 'complete' && doc.coverage?.exhaustive === true &&
    doc.original_evidence_summary?.failure_count === 0 && doc.original_evidence_summary?.omission_count === 0 &&
    doc.live?.root === workspace && doc.evidence_window?.type === 'FINAL';
}

function selected(doc, workspace, name, directory) {
  const source = doc?.question?.from;
  return exhaustive(doc, workspace) && source?.type === 'symbols' &&
    equal(source.match, matching(name)) && equal(source.scope, scope(directory)) &&
    equal(doc.question.steps, []) && doc.question.output?.type === 'symbols';
}

function functionEvidence(caseId, metadata) {
  const { name, directory } = declarationQuestions[caseId];
  const paths = caseId === 'present' ? [resolve(metadata.workspace, invocationFile)] : [];
  return recordedQueries(metadata.calls).some(result => {
    const doc = result.document;
    return result.type === 'complete' && selected(doc, metadata.workspace, name, directory) &&
      equal(doc.question.from.declarationKinds, ['function']) &&
      doc.invocation?.accumulated_row_count === paths.length && Array.isArray(doc.items) &&
      doc.items.every(item => item?.name === name) && equal(doc.items.map(item => item.location?.file), paths);
  });
}

function callback(output, metadata) {
  const seeds = new Set();
  const code = '[A-Z_]+';
  const list = `${code}(?:[\\s:,—-]+${code})*`;
  for (const result of recordedQueries(metadata.calls)) {
    const doc = result.document;
    if (result.type === 'complete' && selected(doc, metadata.workspace, 'forwardOnce', 'forwarding') &&
        Array.isArray(doc.items)) {
      for (const item of doc.items) {
        if (item?.name === 'forwardOnce' && item.kind === 'function' && typeof item.ref === 'string') seeds.add(item.ref);
      }
      continue;
    }
    if (result.type !== 'rejected_document' || doc?.rejection?.type !== 'COMPLETION_UNPROVEN') continue;
    const { evidence, cause } = doc.rejection.detail ?? {};
    const question = evidence?.question;
    const source = question?.from;
    const token = source?.values?.[0]?.token;
    const issued = source?.type === 'references' && Array.isArray(source.values) && source.values.length === 1 && seeds.has(token);
    const direct = source?.type === 'symbols' && equal(source.match, matching('forwardOnce')) &&
      equal(source.scope, scope('forwarding')) && Array.isArray(source.declarationKinds) && source.declarationKinds.includes('function');
    if ((!issued && !direct) || !equal(question?.steps, [{ type: 'related', relation: 'callees' }]) ||
        cause?.type !== 'CALLBACK_GRAPH_UNPROVEN') continue;
    if (!new RegExp(`^${list}(?:\\s+\\(${list}\\))?$`).test(output)) continue;
    const [first, ...rest] = output.match(/[A-Z_]+/g);
    const obligations = cause.graphFailure?.cause?.obligations ?? [];
    if (first === cause.type && Array.isArray(obligations) && rest.every(value => obligations.includes(value)) &&
        new Set(rest).size === rest.length) return true;
  }
  return false;
}

function unprepared(output, metadata) {
  if (output !== 'UNKNOWN GRADLE_DISTRIBUTION_UNAVAILABLE') return false;
  const attempts = metadata.calls.filter(call => call?.tool === 'kast' && call.argv?.[0] === 'query')
    .map(call => JSON.stringify([call.argv, call.input ?? null]));
  if (new Set(attempts).size !== attempts.length) return false;
  return recordedQueries(metadata.calls).some(({ type, document }) => type === 'rejected_document' &&
    document?.status === 'rejected' && document.boundary === 'managed_preparation' &&
    document.failure?.cause?.type === 'lifecycle' && document.failure.cause.reason === 'GRADLE_DISTRIBUTION_UNAVAILABLE');
}

function literalRead(output, metadata) {
  return output === 'escape' && !metadata.calls.some(call => call?.tool === 'kast') && metadata.calls.some(
    call => call?.tool === 'search_kotlin_source' && Array.isArray(call.matches) &&
      call.matches.some(row => row?.file === invocationFile && /\bfun\s+escapeSink\s*\(\)[^=]*=\s*"escape"/.test(row.line)),
  );
}

function readsTenLineWindow(call, document, ref) {
  if (call.argv[0] === 'query') {
    const input = parse(call.input);
    const action = input?.request ?? input;
    return action?.type === 'READ_SOURCE' && action.candidateRef === ref && (action.region ?? 'ANCHOR') === 'ANCHOR' &&
      action.text?.type === 'WINDOW' && action.text.beforeLines === 10 && action.text.afterLines === 10;
  }
  if (call.argv[0] !== 'source' || call.argv[1] !== 'read') return false;
  const flags = parseFlags(call.argv.slice(2), sourceFlags);
  if (!equal(flags?.get('--ref'), [ref]) || !equal(flags.get('--region') ?? ['anchor'], ['anchor']) ||
      !equal(flags.get('--text') ?? ['complete'], ['complete'])) return false;
  // A window clips both ends to its selected region. In a complete anchor of at most eleven
  // lines, every line lies within ten lines of any focus, so both projections return the same text.
  const region = document.region.selection;
  const range = region?.range;
  return typeof region?.selector === 'string' && Number.isSafeInteger(range?.startInclusive) && range.startInclusive >= 0 &&
    Number.isSafeInteger(range.endExclusive) && range.endExclusive - range.startInclusive === document.text.text.length &&
    equal(document.text.selection, region) && document.text.text.split('\n').length <= 11;
}

function isScopedImplementationQuestion(question, ref) {
  const steps = question?.steps;
  if (!Array.isArray(steps) || !(steps.length === 1 || (steps.length === 2 && equal(steps[1], { type: 'distinct' })))) return false;
  const step = steps[0];
  const expansion = step?.expansion_scope;
  return equal(question.from, { type: 'references', values: [{ token: ref }] }) && question.output?.type === 'symbols' &&
    step?.type === 'related' && step.relation === 'implementations' &&
    ['production-only', 'production-and-test'].includes(expansion?.source_policy) &&
    equal(expansion, { type: 'SOURCE_DOMAIN', source_sets: ['main'], directory: scope('protocol').directory,
      source_policy: expansion.source_policy, generated_sources: 'exclude' });
}

function rejectedImplementations(call, result, ref) {
  if (call.argv[0] !== 'query' || !Number.isInteger(call.status) || call.status === 0) return false;
  if (result?.type === 'rejected' && result.failure === 'INVALID_COMMAND') {
    const flags = parseFlags(call.argv.slice(1), new Set(['--ref', '--relation', '--in', '--source-set', '--output']));
    return equal(flags?.get('--ref'), [ref]) && equal(flags.get('--relation'), ['implementations']) &&
      equal(flags.get('--in'), ['protocol']) && equal(flags.get('--source-set'), ['main']);
  }
  const doc = result?.document;
  if (result?.type !== 'rejected_document' || doc?.operation !== 'query.run' || doc.status !== 'rejected' ||
      doc.rejection?.type !== 'COMPLETION_UNPROVEN') return false;
  const detail = doc.rejection.detail;
  const evidence = detail?.evidence;
  // The retained question identifies the attempt. It does not establish a fresh source read or prove absence.
  return detail?.model === 'COMPILER_RESOLVED_STATIC_V1' && detail.originalFailure === undefined &&
    ['INCOMPLETE_EXECUTION', 'OMITTED_EVIDENCE', 'ITEM_FAILURE'].includes(detail.cause?.type) &&
    evidence?.type === 'RETAINED' && typeof evidence.result === 'string' && evidence.result.length > 0 &&
    equal(evidence.question?.completion, { type: 'COMPLETE_ONLY', model: 'COMPILER_RESOLVED_STATIC_V1' }) &&
    isScopedImplementationQuestion(evidence.question, ref);
}

function queryCompletion(output, metadata) {
  const answer = parse(output);
  const base = { path: completionFile, declarationKind: 'data class' };
  const reportsAbsence = equal(answer, { ...base, implementations: [] });
  const reportsUnknown = equal(answer, { ...base, implementations: null });
  if (!reportsAbsence && !reportsUnknown) return 'failed';
  const calls = metadata.calls.filter(call => call?.tool === 'kast' && Array.isArray(call.argv));
  const semantic = calls.filter(call => call.argv?.[0] === 'query' || call.argv?.[0] === 'source')
    .filter(call => !call.argv.some(value => ['--help', '-h'].includes(value)));
  if (semantic.length > 4 || new Set(semantic.map(call => JSON.stringify([call.argv, call.input]))).size !== semantic.length) return 'failed';
  let seed;
  let sourceRead = false;
  let rejectedRelation = false;
  for (const call of semantic) {
    const result = parse(call.stdout);
    if (call.status !== 0 || result?.type !== 'complete') {
      if (seed && sourceRead && rejectedImplementations(call, result, seed.ref)) rejectedRelation = true;
      continue;
    }
    const doc = result.document;
    if (doc?.operation === 'source.read') {
      if (seed && doc.status === 'complete' && doc.region?.kind === 'declaration' &&
          doc.snapshot?.file === resolve(metadata.workspace, completionFile) && equal(doc.live, seed.live) &&
          doc.text?.type === 'returned' && typeof doc.text.text === 'string' &&
          /\bdata class QueryCompletionDocument\b/.test(doc.text.text) && readsTenLineWindow(call, doc, seed.ref)) sourceRead = true;
      continue;
    }
    if (!exhaustive(doc, metadata.workspace)) continue;
    if (selected(doc, metadata.workspace, 'QueryCompletionDocument', 'protocol') && Array.isArray(doc.items)) {
      const rows = doc.items.filter(item => item?.name === 'QueryCompletionDocument');
      if (rows.length === 1 && rows[0].location?.file === resolve(metadata.workspace, completionFile) &&
          rows[0].kind === 'classlike' && typeof rows[0].ref === 'string') {
        seed = { ref: rows[0].ref, live: doc.live };
        sourceRead = false;
        rejectedRelation = false;
      }
      continue;
    }
    if (seed && sourceRead && equal(doc.live, seed.live) && isScopedImplementationQuestion(doc.question, seed.ref) &&
        doc.invocation?.accumulated_row_count === 0 && equal(doc.items, [])) {
      return reportsAbsence ? 'complete' : 'failed';
    }
  }
  return reportsUnknown && rejectedRelation ? 'qualified-uncertainty' : 'failed';
}

function consultedKind(call) {
  if (call?.tool !== 'kast' || call.status !== 0 || call.argv?.[0] !== 'describe' || call.argv[1] !== 'query') return false;
  const received = parse(call.stdout);
  const flags = received?.content?.flags;
  const kind = Array.isArray(flags) ? flags.find(flag => flag?.name === '--kind') : received;
  if (kind?.name !== undefined && kind.name !== '--kind') return false;
  const spellings = Array.isArray(kind) ? kind : kind?.value_spellings;
  return Array.isArray(spellings) && spellings.includes('property') && spellings.includes('type-alias');
}

function draftMatches(caseId, answer, metadata) {
  const planned = metadata.planned;
  if (caseId === 'diagnostics') {
    const allowed = new Set(['--path', '--max-diagnostics']);
    const args = argv(answer, ['kast', 'diagnostic', 'check'], allowed);
    const parsed = args && parseFlags(args, allowed);
    return equal(parsed?.get('--path'), ['app/src']) && equal(parsed?.get('--max-diagnostics'), ['12']);
  }
  if (caseId === 'retained') {
    const allowed = new Set(['--ref', '--cursor', '--output']);
    const args = argv(answer, ['kast', 'result', 'read'], allowed);
    const parsed = args && parseFlags(args, allowed);
    return equal(parsed?.get('--ref'), ['RESULT_REF']) && equal(parsed?.get('--cursor'), ['CURSOR']) &&
      equal(parsed?.get('--output'), ['symbols']);
  }
  if (!planned) return false;
  if (caseId === 'callees') {
    return equal(planned.from, references) && equal(planned.steps, [{ type: 'related', relation: 'callees' }]) &&
      planned.output?.type === 'symbols';
  }
  if (caseId === 'kind' && !metadata.calls.some(consultedKind)) return false;
  if (caseId === 'walk') {
    const operation = metadata.calls.some(call => call?.tool === 'kast' && call.status === 0 &&
      call.argv?.[0] === 'describe' && call.argv?.[1] === 'query');
    if (!operation) return false;
  }
  if (caseId === 'walk') {
    const step = planned.steps?.[0];
    return equal(planned.from, references) && planned.steps?.length === 1 &&
      step?.type === 'walk' && step.relation === 'callers' &&
      equal(step.extent, { type: 'THROUGH_DEPTH', maximum_depth: 2 }) &&
      planned.output?.type === 'traversal_records' && planned.execution_budget?.max_results === 20;
  }
  const expected = caseId === 'select' ? ['class'] : ['property', 'type-alias'];
  return equal(planned.from?.match, matching('Widget')) && equal(planned.from?.scope, scope('app/src')) &&
    equal(sorted(planned.from?.declarationKinds), expected) &&
    equal(planned.steps, []) && planned.output?.type === 'symbols' &&
    (caseId !== 'select' || equal(sorted(planned.output.fields), ['location', 'name', 'signature']));
}

export function gradeSkill(caseId, answer, metadata) {
  let pass = false;
  let exactFormat = false;
  const namedScores = {};
  if (Object.hasOwn(skillCases, caseId) && typeof answer === 'string' &&
      Array.isArray(metadata?.calls) && typeof metadata.workspace === 'string') {
    switch (caseId) {
      case 'callback':
        pass = callback(answer, metadata);
        exactFormat = pass && answer === 'CALLBACK_GRAPH_UNPROVEN';
        break;
      case 'present':
      case 'absent':
      case 'declaration-outside-scope':
        pass = answer === (caseId === 'present' ? invocationFile : 'ABSENT') && functionEvidence(caseId, metadata);
        exactFormat = pass;
        break;
      case 'source-lookup':
        pass = answer === invocationFile && (functionEvidence('present', metadata) || metadata.calls.some(
          call => call?.tool === 'search_kotlin_source' && Array.isArray(call.matches) &&
            call.matches.some(row => row?.file === invocationFile && /\bfun\s+invokeCallback\s*\(/.test(row.line)),
        ));
        exactFormat = pass;
        break;
      case 'literal-read':
        pass = literalRead(answer, metadata);
        exactFormat = pass;
        break;
      case 'unprepared':
        pass = unprepared(answer, metadata);
        exactFormat = pass;
        break;
      case 'query-completion-document': {
        const outcome = queryCompletion(answer, metadata);
        pass = outcome === 'complete';
        namedScores.qualifiedUncertainty = Number(outcome === 'qualified-uncertainty');
        exactFormat = pass || outcome === 'qualified-uncertainty';
        break;
      }
      default:
        pass = draftMatches(caseId, answer, metadata);
        exactFormat = pass;
    }
  }
  return {
    pass, score: Number(pass), namedScores: { ...namedScores, exactFormat: Number(exactFormat) },
    reason: pass ? 'The answer and required evidence match' : 'The answer or required evidence differs from the case oracle',
  };
}
