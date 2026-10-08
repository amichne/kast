"""Assert authored invariants against captured registered-tool replies.

This checks supplied receipts only. The caller owns actual Pi registration,
native candidate admission, source/build pins, import, and session capture.
Passing this checker alone never establishes those external facts.
"""

from dataclasses import asdict, dataclass
from pathlib import Path
import argparse
import json

import try_local_oracle as oracle
import konditional_try_oracle as konditional
import reproduce_semantic_queries as native


class QualificationFailure(AssertionError):
    pass


@dataclass(frozen=True)
class CheckedCase:
    name: str
    observedPaths: int
    expectation: str


@dataclass(frozen=True)
class ReceiptChecks:
    sourceSha256: str
    cases: tuple[CheckedCase, ...]
    qualificationSlice: str
    type: str = 'CAPTURED_RECEIPT_ASSERTIONS_PASSED'
    qualification: str = 'REQUIRES_INDEPENDENT_NATIVE_AND_PI_TRANSPORT_PINS'


def require(condition, message):
    if not condition:
        raise QualificationFailure(message)


def bounds(site):
    region = site['range']
    return region.get('start', region.get('startInclusive')), region.get('end', region.get('endExclusive'))


def expected_bounds(site):
    return site.startInclusive, site.endExclusive


def document(call):
    require(call.get('tool') == 'query_symbols', 'foreign tool in query receipt')
    require(not call.get('isError', False), 'Pi tool execution error')
    reply = call['reply']
    require(reply.get('type') in ('complete', 'qualified'), 'query receipt rejected')
    return reply['document']


def stale_document(call):
    require(call.get('tool') == 'query_symbols' and not call.get('isError', False), 'stale check is transport error')
    reply = call['reply']
    require(reply.get('type') == 'rejected_document', 'stale reference did not produce semantic rejection')
    result = reply['document']
    rejection = result.get('rejection', {})
    require(result.get('status') == 'rejected' and rejection.get('type') == 'reference-rejected',
            'stale reference became another failure')
    require(rejection.get('reason') in ('stale-authority', 'stale-generation', 'revalidation-content-changed'),
            'stale reference cause was erased or unrelated')
    return result


def check_flow(case, calls, root):
    matching = []
    for call in calls:
        request = call.get('arguments', {}).get('request', {})
        source = request.get('source', {})
        if request.get('type') != 'RUN' or source.get('type') != 'IMPACT':
            continue
        seeds = source.get('seeds', [])
        if len(seeds) == 1 and bounds({'range': seeds[0]['anchor']}) == expected_bounds(case.producer):
            matching.append(call)
    require(len(matching) == 1, case.name + ': require exactly one captured initial investigation')
    initial = document(matching[0])
    retained = initial.get('retention', {}).get('reference')
    require(retained is not None, case.name + ': investigation is not retained')
    pages = [initial]
    if retained is not None:
        pages += [document(call) for call in calls
                  if call.get('arguments', {}).get('request', {}).get('result') == retained
                  and call.get('arguments', {}).get('request', {}).get('output', {}).get('type') == 'VALUE_PATHS']
    paths = [item['path'] for page in pages for item in page.get('items', []) if item.get('type') == 'VALUE_PATH']
    require(len(pages) > 1 and pages[-1].get('next_cursor') is None,
            case.name + ': retained presentation was not fully drained')
    original = {item['row_id']: item['path'] for item in initial.get('items', []) if item.get('type') == 'VALUE_PATH'}
    replayed = {item['row_id']: item['path'] for page in pages[1:]
                for item in page.get('items', []) if item.get('type') == 'VALUE_PATH'}
    require(original == replayed, case.name + ': retained path identity or evidence changed')
    require(paths, case.name + ': no captured value paths')
    for path in paths:
        require(bounds(path['producer']) == expected_bounds(case.producer), case.name + ': foreign producer')
        require(Path(path['producer']['enclosing']['file']).resolve() == (root / case.producer.file).resolve(),
                case.name + ': producer belongs to another file')
    transfers = [step['transfer'] for path in paths for step in path['steps'] if step['type'] == 'COMPILER']
    for transfer in transfers:
        evidence = transfer.get('evidence')
        require(isinstance(evidence, dict) and evidence.get('type') in ('DIRECT', 'NORMAL_BRANCH_RESULT'),
                case.name + ': missing or unknown transfer evidence')
        if evidence['type'] == 'NORMAL_BRANCH_RESULT':
            require(evidence['condition'] == 'NORMAL_COMPLETION', case.name + ': branch condition erased')
            require(evidence['alternative']['type'] in ('TRY_BODY', 'CATCH_BODY'), case.name + ': unknown branch')
            require(evidence['alternative']['type'] == case.branch + '_BODY', case.name + ': wrong originating branch')
            if evidence['alternative']['type'] == 'CATCH_BODY':
                require(evidence['alternative']['index'] == 0, case.name + ': foreign catch alternative')
            for key in ('try_range', 'branch_range'):
                start, end = bounds({'range': evidence[key]})
                require(start <= case.producer.startInclusive < case.producer.endExclusive <= end,
                        case.name + ': branch evidence does not own producer')
    branch_evidence = {json.dumps(transfer['evidence'], sort_keys=True) for transfer in transfers
                       if transfer['evidence']['type'] == 'NORMAL_BRANCH_RESULT'}
    actual_branches = {(bounds({'range': transfer['evidence']['try_range']}),
                        bounds({'range': transfer['evidence']['branch_range']}),
                        transfer['evidence']['alternative']['type']) for transfer in transfers
                      if transfer['evidence']['type'] == 'NORMAL_BRANCH_RESULT'}
    expected_branches = {(expected_bounds(branch.tryRange), expected_bounds(branch.branchRange), branch.alternative + '_BODY')
                         for branch in case.branchResults}
    require(actual_branches == expected_branches, case.name + ': branch ranges differ from source-authored oracle')
    returns = [transfer for transfer in transfers if transfer['target']['role']['type'] == 'RETURN']
    require(len(branch_evidence) == case.normalBranchResults, case.name + ': branch result count differs from oracle')
    if case.expectation == oracle.CompletionExpectation.NORMAL_RETURN:
        require(returns, case.name + ': normal value never reaches return')
        require({bounds(transfer['target']) for transfer in returns} == {expected_bounds(site) for site in case.returnRanges},
                case.name + ': value reaches wrong return expression')
    else:
        require(not returns, case.name + ': unsupported or discarded value became return')
    if case.expectation == oracle.CompletionExpectation.FINALLY_UNSUPPORTED:
        require(any(path['terminal']['type'] == 'UNRESOLVED_FLOW'
                    and path['terminal']['cause'] == 'FINALLY_UNSUPPORTED' for path in paths),
                case.name + ': finally qualification lost')
    if case.expectation in (oracle.CompletionExpectation.UNIT_UNSUPPORTED, oracle.CompletionExpectation.ABRUPT_COMPLETION):
        expected_cause = ('UNSUPPORTED_EXPRESSION' if case.expectation == oracle.CompletionExpectation.UNIT_UNSUPPORTED
                          else 'ABRUPT_COMPLETION')
        require(any(path['terminal']['type'] == 'UNRESOLVED_FLOW' and path['terminal']['cause'] == expected_cause
                    for path in paths), case.name + ': compiler result qualification lost')
    for forbidden in case.forbiddenProducer:
        require(not any(bounds(transfer['target']) == expected_bounds(forbidden) for transfer in transfers),
                case.name + ': producer flowed into independent fallback')
    require(initial.get('continuation') is None, case.name + ': initial execution remains suspended; drain before checking')
    return CheckedCase(case.name, len(paths), case.expectation.value)


def check_local(case, calls, root):
    lookups = [call for call in calls
               if call.get('arguments', {}).get('request', {}).get('source', {}).get('type') == 'AT_LOCATION'
               and call.get('phase', 'BASELINE') == 'BASELINE'
               and call['arguments']['request']['source'].get('offset') == case.nameSite.startInclusive
               and call['arguments']['request']['source'].get('file') == case.nameSite.file]
    require(len(lookups) == 1, case.name + ': require one exact local lookup')
    found = document(lookups[0])['items']
    require(len(found) == 1 and found[0]['type'] == 'exact-symbol', case.name + ': local lookup is not exact')
    symbol = found[0]
    require(bounds(symbol['location']) == expected_bounds(case.declaration), case.name + ': wrong declaration range')
    signature = symbol.get('signature')
    require(isinstance(signature, dict) and signature['type'] == 'LOCAL_' + case.kind,
            case.name + ': locality is absent from signature')
    if case.kind == 'PROPERTY':
        require(signature['mutability'] == ('VAR' if case.mutable else 'VAL'), case.name + ': mutability proof lost')
    address = signature['address']
    check_local_file_address(case, address['file'], root)
    require(address['kind'] == case.kind
            and bounds({'range': address['range']}) == expected_bounds(case.declaration), case.name + ': wrong local address')
    owner_start, owner_end = bounds({'range': address['ownerRange']})
    require((owner_start, owner_end) == expected_bounds(case.ownerRange), case.name + ': wrong compiler owner anchor')
    require([bounds({'range': region}) for region in address['lexicalOwners']] == [expected_bounds(site) for site in case.lexicalOwners],
            case.name + ': lexical owner chain differs from independent source anchors')
    require(address['ownerIdentity'], case.name + ': missing owner identity evidence')
    require(Path(symbol['location']['file']).resolve() == (root / case.declaration.file).resolve(),
            case.name + ': wrong declaration file')
    ref = symbol['ref']
    source_reads = [document(call) for call in calls
                    if call.get('phase', 'BASELINE') == 'BASELINE'
                    and call.get('arguments', {}).get('request', {}).get('source') ==
                    {'type': 'SYMBOL_REFS', 'symbolRefs': [ref]}
                    and 'SOURCE' in call['arguments']['request'].get('output', {}).get('fields', [])]
    require(len(source_reads) == 1 and len(source_reads[0]['items']) == 1,
            case.name + ': exact local source read missing')
    source_symbol = source_reads[0]['items'][0]
    source_window = source_symbol.get('source')
    require(source_symbol.get('signature') == signature
            and isinstance(source_window, dict) and case.declaration.text in source_window.get('text', ''),
            case.name + ': source adapter failed compiler identity parity or exact source read')
    discovered = []
    for call in calls:
        source = call.get('arguments', {}).get('request', {}).get('source', {})
        if source.get('type') == 'SEARCH_DECLARATIONS' and source.get('declarationName') == case.nameSite.text:
            discovered.extend(item for item in document(call)['items'] if item['type'] == 'exact-symbol'
                              and bounds(item['location']) == expected_bounds(case.declaration)
                              and Path(item['location']['file']).resolve() == (root / case.declaration.file).resolve())
    require(len(discovered) == 1 and discovered[0]['signature'] == signature,
            case.name + ': discovery and exact resolution identity differ')
    relations = []
    for call in calls:
        source = call.get('arguments', {}).get('request', {}).get('source', {})
        if source.get('type') == 'SYMBOL_REFS' and source.get('symbolRefs') == [ref]:
            request = call['arguments']['request']
            steps = request.get('steps', [])
            if len(steps) != 1 or steps[0].get('type') != 'EXPAND_RELATION' or steps[0].get('relation') != 'REFERENCES':
                continue
            relations.extend(reference_rows(document(call)))
    expected = {expected_bounds(site) for site in case.references}
    actual = {bounds(relation['occurrence']) for relation in relations}
    require(actual == expected, case.name + ': exact reference inventory differs from independent binding oracle')
    for relation in relations:
        require(Path(relation['occurrence']['file']).resolve() == (root / case.declaration.file).resolve(),
                case.name + ': reference belongs to another file')
        require(bounds(relation['target']) == expected_bounds(case.declaration), case.name + ': target rebound')
        require(relation['target']['compilerEvidence']['signature'] == signature, case.name + ': adapter identity mismatch')
    require(actual.isdisjoint({expected_bounds(site) for site in case.forbiddenReferences}),
            case.name + ': shadowed reference admitted')
    return CheckedCase(case.name, len(relations), 'EXACT_LOCAL_BINDING')


def check_local_file_address(case, file_address, root):
    # CanonicalWorkspaceFilePath projects its absolute admitted path. Relative oracle spans
    # are rooted in the independently admitted fixture; never normalize an observed address.
    require(file_address == {'type': 'WORKSPACE', 'path': str(root / case.declaration.file)},
            case.name + ': wrong canonical local file address')


def reference_rows(observation):
    items = observation['items']
    require(all(item.get('type') == 'reference-occurrence' and isinstance(item.get('occurrence'), dict)
                for item in items), 'unexpected reference occurrence row contract')
    return [item['occurrence'] for item in items]


def check_replays(calls):
    grouped = {}
    for call in calls:
        request = call.get('arguments', {}).get('request', {})
        if request.get('type') == 'READ_RESULT' and request.get('output', {}).get('type') == 'VALUE_PATHS':
            grouped.setdefault(json.dumps(request, sort_keys=True), []).append(document(call))
    require(grouped, 'no retained-page replay observations')
    for observations in grouped.values():
        require(len(observations) == 2, 'retained cursor was not replayed exactly once')
        first, second = observations
        require(first.get('items') == second.get('items') and first.get('next_cursor') == second.get('next_cursor'),
                'retained-page evidence or cursor changed on replay')
    return CheckedCase('retained-page-replay', len(grouped), 'IDENTICAL_ROWS_EVIDENCE_AND_CURSOR')


def check_owner_identities(locals_, calls):
    identities = {}
    for case in locals_:
        lookups = [call for call in calls if call.get('phase', 'BASELINE') == 'BASELINE'
                   and call.get('arguments', {}).get('request', {}).get('source') ==
                   {'type': 'AT_LOCATION', 'file': case.nameSite.file, 'offset': case.nameSite.startInclusive}]
        require(len(lookups) == 1, case.name + ': require one owner identity observation')
        items = document(lookups[0])['items']
        require(len(items) == 1, case.name + ': owner identity observation is ambiguous')
        identity = items[0]['signature']['address']['ownerIdentity']
        key = (case.declaration.file, expected_bounds(case.ownerRange))
        require(key not in identities or identities[key] == identity,
                case.name + ': one compiler owner has inconsistent identities')
        identities[key] = identity
    require(len(set(identities.values())) == len(identities),
            'distinct compiler owners collapsed into one identity')
    return CheckedCase('local-owner-identities', len(identities), 'DISTINCT_COMPILER_OWNERS')


def check_stale(intent, calls, root):
    rejected = [call for call in calls if call.get('phase') == 'STALE_REFERENCE']
    require(len(rejected) == 1, 'one controlled stale-reference rejection required')
    stale_document(rejected[0])
    local = next(case for case in intent.locals if case.name == 'outer-val')
    baseline = [call for call in calls if call.get('phase', 'BASELINE') == 'BASELINE'
                and call.get('arguments', {}).get('request', {}).get('source') ==
                {'type': 'AT_LOCATION', 'file': local.nameSite.file, 'offset': local.nameSite.startInclusive}]
    require(len(baseline) == 1, 'stale-reference baseline missing')
    old_ref = document(baseline[0])['items'][0]['ref']
    require(rejected[0]['arguments']['request']['source'] == {'type': 'SYMBOL_REFS', 'symbolRefs': [old_ref]},
            'stale check used another reference')
    rediscovery = [call for call in calls if call.get('phase') == 'REDISCOVERY'
                  and call.get('arguments', {}).get('request', {}).get('source', {}).get('type') == 'AT_LOCATION']
    require(len(rediscovery) == 1, 'controlled edited local was not rediscovered')
    items = document(rediscovery[0])['items']
    require(len(items) == 1 and items[0]['type'] == 'exact-symbol' and items[0]['ref'] != old_ref,
            'edited local reused old exact handle')
    require(items[0]['signature'] == document(baseline[0])['items'][0]['signature'],
            'same-length control changed local address or callable facts')
    require(bounds(items[0]['location']) == expected_bounds(local.declaration)
            and Path(items[0]['location']['file']).resolve() == (root / local.declaration.file).resolve(),
            'edited local changed source identity under controlled mutation')
    fresh_reads = [call for call in calls if call.get('phase') == 'REDISCOVERY'
                   and call.get('arguments', {}).get('request', {}).get('source') ==
                   {'type': 'SYMBOL_REFS', 'symbolRefs': [items[0]['ref']]}]
    require(len(fresh_reads) == 1 and document(fresh_reads[0])['items'][0]['signature'] == items[0]['signature'],
            'fresh rediscovered local was not readable')
    return CheckedCase('edited-local-stale-reference', 1, 'REJECT_OLD_READ_REDISCOVERED')


def verify(intent, calls, root, qualification_slice):
    slice_ = native.QualificationSlice.admit(qualification_slice)
    require(len(calls) <= 4096, 'receipt count exceeds qualification bound')
    for call in calls:
        require(call.get('qualificationSlice') == slice_.value, 'captured call belongs to another or missing qualification slice')
        if slice_ == native.QualificationSlice.TRY_BRANCH_RESULTS:
            require(call.get('phase', 'BASELINE') == 'BASELINE', 'local stale/reacquisition stage in try-only capture')
            source = call.get('arguments', {}).get('request', {}).get('source', {})
            require(source.get('type') != 'SEARCH_DECLARATIONS'
                    and not (source.get('type') == 'AT_LOCATION' and any(
                        source.get('file') == item.nameSite.file and source.get('offset') == item.nameSite.startInclusive
                        for item in intent.locals)), 'local declaration observation in try-only capture')
        if call.get('phase') == 'STALE_REFERENCE':
            stale_document(call)
        else:
            require(call.get('phase', 'BASELINE') in ('BASELINE', 'REDISCOVERY'), 'unknown capture phase')
            document(call)
    checked = [check_flow(case, calls, root) for case in intent.flows]
    if slice_ == native.QualificationSlice.TRY_LOCAL_IDENTITIES:
        checked.extend(check_local(case, calls, root) for case in intent.locals)
        if intent.locals:
            checked.append(check_owner_identities(intent.locals, calls))
    checked.append(check_replays(calls))
    if slice_ == native.QualificationSlice.TRY_LOCAL_IDENTITIES and intent.scenario == 'fixture':
        checked.append(check_stale(intent, calls, root))
    return ReceiptChecks(intent.sourceSha256, tuple(checked), slice_.value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=('fixture', 'supplemental', 'konditional'), default='fixture')
    parser.add_argument('--source', type=Path)
    parser.add_argument('--other-source', type=Path)
    parser.add_argument('--supplemental-source', type=Path)
    parser.add_argument('--receipts', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--qualification-slice', choices=tuple(value.value for value in native.QualificationSlice), required=True)
    args = parser.parse_args()
    require(args.receipts.stat().st_size <= 32 * 1024 * 1024, 'receipt bytes exceed qualification bound')
    calls = json.loads(args.receipts.read_text())
    require(isinstance(calls, list), 'expected retained registered-tool call list')
    if args.scenario == 'fixture':
        require(args.source is not None and args.other_source is not None, 'both source files required for fixture scenario')
        require(args.supplemental_source is not None, 'supplemental source required for full fixture scenario')
        intent = oracle.load_oracle(args.source, args.other_source, args.supplemental_source)
    elif args.scenario == 'supplemental':
        import supplemental_trust_oracle as supplemental
        require(args.source is None and args.other_source is None and args.supplemental_source is not None,
                'one supplemental source required for supplemental scenario')
        intent = supplemental.load_oracle(args.supplemental_source)
    else:
        require(args.source is None and args.other_source is None and args.supplemental_source is None,
                'Konditional source files are owned-root constrained')
        intent = konditional.load_oracle(args.root)
    print(json.dumps(asdict(verify(intent, calls, args.root.resolve(), args.qualification_slice)), indent=2))


if __name__ == '__main__':
    main()
