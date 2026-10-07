#!/usr/bin/env python3
"""Opt-in pinned native callback-target/supplier matrix.

This boundary proves the authored target/supplier inventory. The receipt names that
scope explicitly; exact transfer-site and fresh/reused mutation proof remain
separate acceptance gates.
"""
import argparse
from contextlib import closing
from dataclasses import asdict, dataclass
from pathlib import Path
import json
import sys

import jsonschema

import immutable_callback_oracle as oracle
import immutable_callback_qualification as proof
import qualify_callback_tracing as shared


@dataclass(frozen=True)
class CaseResult:
    case: str
    expected: oracle.Complete | oracle.CompleteEmpty | oracle.Rejected
    targets: proof.CallbackTargets | None
    response_status: str
    calls: int
    semantic_fact_counters: dict[str, int] | None
    qualified_evidence: tuple[str, ...]


@dataclass(frozen=True)
class MatrixResult:
    source: shared.SourceFingerprint
    build_receipt_sha256: str
    harness_hashes: dict[str, str]
    fixture_sha256: str
    selected_cases: tuple[str, ...]
    budget: shared.Budget
    cases: tuple[CaseResult, ...]
    type: str = 'NATIVE_IMMUTABLE_TARGET_MATRIX_OBSERVED'
    scope: str = 'CALLBACK_TARGETS_AND_SUPPLIER_INVENTORIES'


def negative_control(case, document):
    assert document.get('status') == 'rejected', 'negative callback control was admitted'
    rejection = document['rejection']
    assert rejection['type'] == 'COMPLETION_UNPROVEN', rejection
    detail = rejection['detail']
    assert detail['model'] == 'COMPILER_RESOLVED_STATIC_V1', detail
    assert detail['cause']['type'] == 'CALLBACK_GRAPH_UNPROVEN', detail
    cause = detail['cause']['graphFailure']['cause']
    expected = {oracle.Boundary.MUTABLE_STORAGE: 'STORED_CALLBACK',
                oracle.Boundary.EXTERNAL_TRANSFER: 'EXTERNAL_CALLABLE',
                oracle.Boundary.FACTORY_RECEIVER: 'UNSUPPORTED_CALLBACK_SUPPLY',
                oracle.Boundary.CAPTURE_FORWARDING: 'PARAMETER_ESCAPES',
                oracle.Boundary.BODY_GETTER: 'UNSUPPORTED_CALLBACK_SUPPLY',
                oracle.Boundary.BODY_OPERATOR: 'UNSUPPORTED_CALLBACK_SUPPLY',
                oracle.Boundary.BODY_SUBJECTFUL_WHEN: 'UNSUPPORTED_CALLBACK_SUPPLY'}[case.expected.boundary]
    assert cause in ({'type': 'UNAVAILABLE', 'cause': expected},
                     {'type': 'UNRESOLVED', 'obligations': [expected], 'scan': 'INCOMPLETE'}), cause
    assert detail['evidence']['type'] == 'RETAINED', detail
    assert detail['policyProgress'] == {'type': 'EVIDENCE_ONLY'}, detail


def execute(args):
    replay = shared.replay
    sources = oracle.load_sources(args.root)
    by_source = {source.relative_path: source.text for source in sources}
    selected = tuple(case for case in oracle.CASES if not args.case or case.name in args.case)
    assert selected and (not args.case or set(args.case) == {case.name for case in selected}), 'unknown/empty case selection'
    budget = shared.Budget(args.max_elapsed_ms, args.max_work_units, args.max_results, args.max_returned_bytes)
    assert min(asdict(budget).values()) > 0, 'positive grants required'
    pinned = json.loads(args.pin.read_text())
    receipt = shared.admit_candidate(args, pinned)
    output = replay.fresh(args.output)
    replay.write(output / 'candidate-build.json', asdict(receipt))
    pin_args = argparse.Namespace(output=output / 'start-pin', fixture=args.root, cli=args.rpc,
        idea_contents=args.idea_contents, source_tree=Path(receipt.sourceTree),
        public_rpc=True, public_mcp=True, catalog_rpc=args.catalog_rpc)
    assert replay.pin(pin_args) == 0, 'native start pin unavailable'
    started = json.loads((pin_args.output / 'pin.json').read_text())
    assert replay.artifact_identity(started) == replay.artifact_identity(pinned), 'native artifact changed'
    assert started['fixture'] == pinned['fixture'] and started['limits'] == pinned['limits'], 'native pin changed'
    results, calls = [], []
    try:
        with closing(replay.McpReplaySession(args.rpc, args.root, output, args.timeout)) as session:
            tools = session.catalog()['tools']
            tool = next(tool for tool in tools if tool['name'] == 'query_symbols')
            request_validator = jsonschema.Draft202012Validator(tool['inputSchema'])
            response_validator = jsonschema.Draft202012Validator(tool['outputSchema'])

            def invoke(request):
                request_validator.validate(request)
                before = args.idea_log.stat()
                process = session.call(request)
                assert process['outcome'] == 'completed' and process['exitCode'] == 0, 'native exchange unavailable'
                response = json.loads(process['stdout']).get('result', {}).get('structuredContent')
                diagnostics, phases = replay.collect_observations(args.idea_log, before)
                call = replay.ReplayCall(request['request']['type'], request, process, response, diagnostics, phases, 'UNAVAILABLE')
                replay.write(output / f'call-{len(calls):04d}.json', asdict(call))
                calls.append(call)
                assert response is not None, 'public semantic document missing'
                response_validator.validate(response)
                return call

            for case in selected:
                source = by_source[case.source]
                token = 'fun ' + case.seed + '('
                assert source.count(token) == 1, 'fixture seed not unique'
                offset = len(source[:source.index(token) + 4].encode('utf-16-le')) // 2
                request = shared.static_wire(shared.StaticRunRequest(shared.AtLocation(case.source, offset),
                    (shared.StaticRelationStep(shared.StaticRelation.CALLEES),), shared.OccurrencesOutput(),
                    shared.StaticRetention.RETAIN, budget))
                observed = replay.drain_workload(request, invoke, 512)
                pages = [call.response for call in observed]
                assert observed and replay.continuation(pages[-1]) is None, 'query did not exhaust'
                if isinstance(case.expected, oracle.Rejected):
                    negative_control(case, pages[-1])
                    qualified = None
                    fact_counters = None
                    dimensions = ('EXACT_TYPED_REJECTION', 'RETAINED_POLICY_EVIDENCE')
                else:
                    qualified = proof.qualify_case(case, pages, args.root)
                    fact_counters = {name: 0 for name in shared.SEMANTIC_FACT_COUNTERS}
                    for call in observed:
                        observed_counters = shared.native_semantic_fact_counters(call, pages[-1]['live'])
                        for name, count in observed_counters.items(): fact_counters[name] += count
                    dimensions = ('CALLBACK_TARGETS', 'COMPLETE_FLOW_INVENTORY')
                    if case.name in ('formal-empty-inventory', 'formal-supplier-inventory'):
                        dimensions += ('EXACT_FORMAL_SUPPLIER_INVENTORY',)
                    if case.name in ('capture-alpha', 'capture-beta', 'captured-alias', 'branch-alternatives', 'direct-returned-invocation', 'local-returned-invocation'):
                        dimensions += ('EXACT_FACTORY_SOURCE_AND_CAPTURE_CONTEXT',)
                    if case.name == 'identity-return': dimensions += ('EXACT_TRANSPARENT_RETURN_TRANSFERS',)
                results.append(CaseResult(case.name, case.expected, qualified, pages[-1]['status'], len(observed), fact_counters, dimensions))
                replay.write(output / 'cases.json', [asdict(result) for result in results])
        pin_args.output = output / 'end-pin'
        assert replay.pin(pin_args) == 0, 'native end pin unavailable'
        ending = json.loads((pin_args.output / 'pin.json').read_text())
        assert replay.artifact_identity(ending) == replay.artifact_identity(started), 'native artifact changed'
        for key in ('fixture', 'limits', 'source'):
            assert ending[key] == started[key], 'native pin changed: ' + key
        for key in ('ideaBuild', 'jbr', 'kotlinPlugin', 'javaHome', 'pid', 'processStart', 'model'):
            assert ending['host'][key] == started['host'][key], 'native host changed: ' + key
        fingerprint = shared.SourceFingerprint(**{key: started['source'][key] for key in shared.SourceFingerprint.__dataclass_fields__})
        harness = {path.name: replay.digest(path) for name in ('qualify_immutable_callbacks.py', 'immutable_callback_qualification.py',
            'immutable_callback_oracle.py', 'immutable_factory_qualification.py', 'qualify_callback_tracing.py', 'reproduce_semantic_queries.py')
            for path in (Path(__file__).with_name(name),)}
        result = MatrixResult(fingerprint, replay.digest(args.build_receipt), harness,
            oracle.source_digest(sources), tuple(case.name for case in selected), budget, tuple(results))
        replay.write(output / 'matrix.json', asdict(result))
        print(output / 'matrix.json')
    except (AssertionError, ValueError, KeyError, TypeError, OSError, jsonschema.ValidationError):
        replay.write(output / 'failure.json', asdict(shared.StaticAcceptanceFailure(shared.StaticAcceptanceFailureCause.REQUIRED_EVIDENCE_REJECTED)))
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('root', 'rpc', 'output', 'pin', 'candidate-plugin', 'candidate-cli', 'build-receipt', 'catalog-rpc', 'idea-contents', 'idea-log'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--case', action='append', default=[])
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--max-elapsed-ms', type=int, default=20000)
    parser.add_argument('--max-work-units', type=int, default=20000)
    parser.add_argument('--max-results', type=int, default=128)
    parser.add_argument('--max-returned-bytes', type=int, default=524288)
    execute(parser.parse_args())
