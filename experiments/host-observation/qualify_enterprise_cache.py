#!/usr/bin/env python3
"""Read-only public Tool RPC observations for an already-open portable cache fixture.

Reuses the repository's process capture and existing native socket preflight. This runner never
installs, restarts or opens an IDE. Installed observations do not prove that the
current checkout is loaded: retain a separate artifact/native pin for that claim.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from enum import Enum
import json
from pathlib import Path

import reproduce_semantic_queries as replay
import kast_ide
from enterprise_cache_oracle import CACHE, FIXTURE, load_oracle, require_adapter_occurrences


@dataclass(frozen=True)
class Budget:
    maxElapsedMs: int = 15000
    maxResults: int = 100
    maxWorkUnits: int = 100000
    maxReturnedBytes: int = 524288

    def __post_init__(self):
        if any(type(value) is not int or value <= 0 for value in asdict(self).values()):
            raise ValueError('INVALID_GRANT')


@dataclass(frozen=True)
class PhotoWalkBudget:
    """The photo supplied only these dimensions; work and bytes stay omitted."""
    maxElapsedMs: int = 15000
    maxResults: int = 100


@dataclass(frozen=True)
class PackageScope:
    packageName: str = 'example.cache'
    sourceSetNames: tuple[str, ...] = ('main',)
    includeSubpackages: bool = False
    type: str = field(default='PACKAGE', init=False)


@dataclass(frozen=True)
class DirectoryScope:
    relativeDirectoryPath: str = 'src/main/kotlin/example/cache'
    sourceSetNames: tuple[str, ...] = ('main',)
    includeSubdirectories: bool = False
    type: str = field(default='DIRECTORY', init=False)


@dataclass(frozen=True)
class Search:
    declarationName: str
    scope: PackageScope | DirectoryScope
    declarationKinds: tuple[str, ...]
    type: str = field(default='SEARCH_DECLARATIONS', init=False)


@dataclass(frozen=True)
class Location:
    file: str
    offset: int
    type: str = field(default='AT_LOCATION', init=False)


@dataclass(frozen=True)
class Refs:
    symbolRefs: tuple[str, ...]
    type: str = field(default='SYMBOL_REFS', init=False)


@dataclass(frozen=True)
class SourceDomain:
    sourceSets: tuple[str, ...] = ('main',)
    sourcePolicy: str = 'PRODUCTION_ONLY'
    generatedSources: str = 'EXCLUDE'
    directory: str = 'src/main/kotlin/example/cache'
    type: str = field(default='SOURCE_DOMAIN', init=False)


@dataclass(frozen=True)
class Relation:
    relation: str
    type: str = field(default='EXPAND_RELATION', init=False)


@dataclass(frozen=True)
class ScopedRelation:
    relation: str
    expansionScope: SourceDomain = SourceDomain()
    type: str = field(default='EXPAND_RELATION', init=False)


@dataclass(frozen=True)
class Walk:
    relation: str = 'CALLEES'
    maximumDepth: int = 1
    type: str = field(default='WALK', init=False)


@dataclass(frozen=True)
class Distinct:
    type: str = field(default='DISTINCT_SYMBOLS', init=False)


@dataclass(frozen=True)
class Trace:
    type: str = field(default='TRACE', init=False)


@dataclass(frozen=True)
class ScopedTrace:
    expansionScope: SourceDomain
    type: str = field(default='TRACE', init=False)


@dataclass(frozen=True)
class Output:
    type: str = 'SYMBOLS'
    fields: tuple[str, ...] = ('NAME', 'LOCATION', 'SIGNATURE')


@dataclass(frozen=True)
class RowsOutput:
    type: str


@dataclass(frozen=True)
class Run:
    source: Search | Location | Refs
    output: Output | RowsOutput = Output()
    steps: tuple[Relation | ScopedRelation | Walk | Distinct | Trace | ScopedTrace, ...] = ()
    executionBudget: Budget | PhotoWalkBudget = Budget()
    retention: str = 'RETAIN'
    type: str = field(default='RUN', init=False)


@dataclass(frozen=True)
class ReadResult:
    result: str
    cursor: int
    evidence_cursor: int
    output: Output | RowsOutput
    executionBudget: Budget
    type: str = field(default='READ_RESULT', init=False)


@dataclass(frozen=True)
class Payload:
    request: Run | ReadResult
    verbose: bool = True


@dataclass(frozen=True)
class Describe:
    type: str = field(default='DESCRIBE', init=False)


class Outcome(str, Enum):
    PASSED = 'PASSED'
    FAILED = 'FAILED'
    OBSERVED = 'OBSERVED'


class AdmissionFailure(str, Enum):
    EXACT_OPEN_FIXTURE_HOST_UNAVAILABLE = 'EXACT_OPEN_FIXTURE_HOST_UNAVAILABLE'


@dataclass(frozen=True)
class AdmissionRejected:
    failure: AdmissionFailure
    type: str = field(default='CACHE_OBSERVATION_REJECTED', init=False)


@dataclass(frozen=True)
class Check:
    name: str
    outcome: Outcome
    callFiles: tuple[str, ...]


@dataclass(frozen=True)
class Report:
    root: str
    rpcSha256: str
    fixtureHashes: dict[str, str]
    checks: tuple[Check, ...]
    type: str = field(default='INSTALLED_CACHE_OBSERVATION', init=False)
    runtimeCorrespondence: str = 'UNPROVEN_WITHOUT_SEPARATE_NATIVE_ARTIFACT_PIN'


@dataclass(frozen=True)
class SchedulingObservation:
    requests: tuple[dict, ...]
    sequential: tuple[dict, ...]
    concurrent: tuple[dict, ...]
    type: str = field(default='IDENTICAL_GRANT_SCHEDULING_OBSERVATION', init=False)


def encode(payload):
    # tuples refine into JSON arrays at the serialization boundary.
    return json.loads(json.dumps(asdict(payload)))


def contract_validator(name):
    import jsonschema
    document = json.loads((replay.REPO / 'docs/public/reference/callables.openapi.json').read_text())
    document['$ref'] = '#/components/schemas/' + name
    return jsonschema.Draft202012Validator(document)


def request_validator():
    return contract_validator('query_symbolsRequest')


def retained_pages(first, output, budget, invoke, max_calls=128):
    """Drain independent row/evidence cursors; reject stalls, basis loss and duplication."""
    pages, current = [first], first
    row_cursor = len(first.get('items', []))
    seen = set()
    for _ in range(max_calls):
        next_row = current.get('next_cursor')
        window = current.get('evidence_window', {})
        more_evidence = window.get('type') == 'MORE'
        if next_row is None and not more_evidence:
            return pages
        retention = first.get('retention', {})
        if retention.get('kind') != 'retained':
            raise ValueError('PRESENTATION_WITHOUT_RETAINED_RESULT')
        cursor = next_row if next_row is not None else row_cursor
        evidence_cursor = window['end'] if more_evidence else window.get('total', 0)
        position = cursor, evidence_cursor
        if position in seen:
            raise ValueError('PRESENTATION_STALLED')
        seen.add(position)
        current = invoke(Payload(ReadResult(retention['reference'], cursor, evidence_cursor, output, budget)))
        if current.get('live') != first.get('live'):
            raise ValueError('PRESENTATION_BASIS_CHANGED')
        if current.get('interpretation') != first.get('interpretation'):
            raise ValueError('PRESENTATION_INTERPRETATION_CHANGED')
        row_cursor = cursor + len(current.get('items', []))
        pages.append(current)
    raise ValueError('PRESENTATION_CALL_CAP')


def identity(item):
    return item['signature']['qualifiedIdentity']


def require_symbols(response, expected):
    if response.get('status') != 'complete' or response.get('coverage', {}).get('exhaustive') is not True:
        raise ValueError('COMPLETE_SYMBOL_INVENTORY_UNPROVEN')
    actual = [identity(item) for item in response['items']]
    if sorted(actual) != sorted(expected):
        raise ValueError('SYMBOL_INVENTORY_MISMATCH')


def require_connection(response, site, endpoints, root):
    """An independently authored use site must connect the expected compiler identities."""
    for item in response['items']:
        for connection in item['connections']:
            location = connection['occurrence']
            identities = {connection['source']['qualifiedIdentity'], connection['target']['qualifiedIdentity']}
            if location['file'] == str((root / site.file).resolve(strict=True)) and location['range'] == asdict(site.range) and identities == set(endpoints):
                if connection['provenance'] != 'k2-authored-source' or connection['coverage'] != 'exact-compiler-confirmed':
                    raise ValueError('TRACE_COMPILER_CONNECTION_AUTHORITY_MISSING')
                return
    raise ValueError('TRACE_EXACT_OCCURRENCE_CONNECTION_MISSING')


def run(args):
    import jsonschema
    oracle = load_oracle(args.root)
    output = replay.fresh(args.output)
    fixture_before = replay.inventory(args.root)
    # Reuse the existing socket client: no automatic IDE/project preparation in preflight.
    native = kast_ide.exchange(args.root, asdict(Describe()), Path.home())
    replay.write(output / 'native-preflight.json', asdict(native))
    if isinstance(native, kast_ide.Rejected) or native.document.get('type') != 'KAST_IDE_HOST':
        replay.write(output / 'report.json', asdict(AdmissionRejected(AdmissionFailure.EXACT_OPEN_FIXTURE_HOST_UNAVAILABLE)))
        print(output / 'report.json')
        return 2
    catalog = replay.capture([args.rpc, 'catalog'], args.root, timeout=60)
    replay.write(output / 'catalog-process.json', catalog)
    if catalog.get('exitCode') != 0:
        raise ValueError('CATALOG_UNAVAILABLE')
    tool = next(t for t in json.loads(catalog['stdout'])['catalog']['tools'] if t['name'] == 'query_symbols')
    validator = jsonschema.Draft202012Validator(tool['inputSchema'])
    result_validator = contract_validator('query_symbolsSemanticResult')
    checks, counter = [], 0

    def invoke(payload):
        nonlocal counter
        request = encode(payload)
        validator.validate(request)
        process = replay.capture([args.rpc, 'call', 'query_symbols'], args.root, json.dumps(request), args.timeout)
        name = f'call-{counter:04d}.json'
        counter += 1
        envelope = json.loads(process['stdout']) if process.get('outcome') == 'completed' else {}
        document = envelope.get('document')
        replay.write(output / name, asdict(replay.ReplayCall(payload.request.type, request, process,
            document, [], [], 'UNAVAILABLE')))
        if document is None:
            raise ValueError('SEMANTIC_DOCUMENT_UNAVAILABLE:' + name)
        result_validator.validate(document)
        return document

    def check(name, action):
        before = counter
        action()
        checks.append(Check(name, Outcome.PASSED, tuple(f'call-{i:04d}.json' for i in range(before, counter))))

    def search(name, kind='FUNCTION', scope=PackageScope()):
        return Payload(Run(Search(name, scope, (kind,)), executionBudget=budget))

    budget = Budget()
    def fresh(name, fqn, kind='FUNCTION'):
        response = invoke(search(name, kind))
        matches = [item for item in response.get('items', []) if identity(item) == fqn]
        if response.get('status') != 'complete' or len(matches) != 1:
            raise ValueError('EXACT_SEED_UNPROVEN')
        return matches[0]['ref']

    check('interface-class-family', lambda: require_symbols(invoke(search('CacheManager', 'CLASS')), ('example.cache.CacheManager',)))
    check('equivalent-directory-scope', lambda: require_symbols(invoke(search('CacheManager', 'CLASS', DirectoryScope())), ('example.cache.CacheManager',)))

    for label, site, fqn in (
        ('generic-parameter', oracle.generic, 'example.cache.CacheManager.execute'),
        ('method-name', oracle.method_name, 'example.cache.CacheManager.execute'),
        ('implementation-body', oracle.implementation_body, 'example.cache.RedisCacheManager.execute')):
        check('location-' + label, lambda site=site, fqn=fqn: require_symbols(
            invoke(Payload(Run(Location(site.file, site.range.startInclusive), executionBudget=budget))), (fqn,)))

    def implementations():
        token = fresh('CacheManager', 'example.cache.CacheManager', 'CLASS')
        response = invoke(Payload(Run(Refs((token,)), steps=(Relation('IMPLEMENTATIONS'), Distinct()), executionBudget=budget)))
        require_symbols(response, oracle.implementations)
    check('production-and-test-implementations', implementations)

    def callers():
        token = fresh('execute', 'example.cache.CacheManager.execute')
        response = invoke(Payload(Run(Refs((token,)), steps=(ScopedRelation('CALLERS'), Distinct()), executionBudget=budget)))
        require_symbols(response, ('example.cache.executeWithCache',))
    check('scoped-interface-callers', callers)

    def unchanged_reference():
        token = fresh('executeWithCache', 'example.cache.executeWithCache')
        read = Payload(Run(Refs((token,)), executionBudget=budget))
        first, second = invoke(read), invoke(read)
        for response in (first, second):
            require_symbols(response, ('example.cache.executeWithCache',))
        if first.get('live') != second.get('live'):
            raise ValueError('UNCHANGED_READ_BASIS_MOVED')
    check('unchanged-reference-read', unchanged_reference)

    if args.trace:
        token = fresh('executeWithCache', 'example.cache.executeWithCache')
        traced = invoke(Payload(Run(Refs((token,)), steps=(Trace(),), executionBudget=budget)))
        if traced.get('status') != 'complete' or traced.get('coverage', {}).get('exhaustive') is not True:
            raise ValueError('TRACE_COMPLETION_UNPROVEN')
        identities = {identity(item) for item in traced['items']}
        if not {'example.cache.executeWithCache', 'example.client.Client.get', 'example.cache.testConsumer'} <= identities:
            raise ValueError('TRACE_REQUIRED_DOWNSTREAM_FACTS_MISSING')
        if any(name.startswith('example.unrelated.') for name in identities):
            raise ValueError('TRACE_UNRELATED_NAME_CONTROL')
        require_connection(traced, oracle.production_adapter_call,
                           ('example.cache.executeWithCache', 'example.client.Client.get'), args.root)
        require_connection(traced, oracle.test_adapter_call,
                           ('example.cache.executeWithCache', 'example.cache.testConsumer'), args.root)
        checks.append(Check('trace-required-downstream-facts', Outcome.PASSED, (f'call-{counter - 1:04d}.json',)))

        token = fresh('CacheManager', 'example.cache.CacheManager', 'CLASS')
        interface_trace = invoke(Payload(Run(Refs((token,)), steps=(Trace(),), executionBudget=budget)))
        if interface_trace.get('status') != 'complete' or interface_trace.get('coverage', {}).get('exhaustive') is not True:
            raise ValueError('INTERFACE_TRACE_COMPLETION_UNPROVEN')
        identities = {identity(item) for item in interface_trace['items']}
        if not {'example.cache.CacheManager.execute', 'example.cache.executeWithCache',
                'example.client.InferredClient.get', 'example.client.Client.get', 'example.cache.testConsumer'} <= identities:
            raise ValueError('INTERFACE_TRACE_INFERRED_RECEIVER_OR_DOWNSTREAM_FACTS_MISSING')
        if any(name.startswith('example.unrelated.') for name in identities):
            raise ValueError('INTERFACE_TRACE_UNRELATED_NAME_CONTROL')
        require_connection(interface_trace, oracle.inferred_interface_call,
                           ('example.cache.CacheManager.execute', 'example.client.InferredClient.get'), args.root)
        checks.append(Check('interface-trace-member-inferred-call-and-adapter', Outcome.PASSED, (f'call-{counter - 1:04d}.json',)))

    def references():
        token = fresh('executeWithCache', 'example.cache.executeWithCache')
        rows_output = RowsOutput('OCCURRENCES')
        response = invoke(Payload(Run(Refs((token,)), rows_output, (Relation('REFERENCES'),), budget)))
        pages = retained_pages(response, rows_output, budget, invoke)
        if response.get('status') != 'complete' or response.get('coverage', {}).get('exhaustive') is not True:
            raise ValueError('REFERENCE_ENUMERATION_UNPROVEN')
        require_adapter_occurrences([item for page in pages for item in page['items']], oracle, args.root)
    check('adapter-import-production-test-and-paging', references)

    before = counter
    token = fresh('executeWithCache', 'example.cache.executeWithCache')
    walk = invoke(Payload(Run(Refs((token,)), RowsOutput('TRAVERSAL_RECORDS'), (Walk(),), PhotoWalkBudget())))
    # Observe the reported photo failure without asserting a tiny fixture must exhaust bytes.
    if walk.get('status') == 'complete':
        relations = [item['record']['relation'] for item in walk['items']]
        expected = [relation for relation in relations if relation['target']['qualifiedIdentity'] == 'example.cache.CacheManager.execute']
        if len(expected) != 1 or expected[0]['occurrence']['range'] != asdict(oracle.interface_call.range):
            raise ValueError('DIRECT_CALLEE_UNPROVEN')
    elif walk.get('rejection', {}).get('type') == 'COMPLETION_UNPROVEN':
        detail = walk['rejection']['detail']
        evidence = detail['evidence']
        if evidence['type'] == 'RETAINED':
            validator.validate(evidence['nextQuery'])
            read_output = RowsOutput('TRAVERSAL_RECORDS')
            partial = invoke(Payload(ReadResult(evidence['result'], 0, 0, read_output, budget)))
            if partial.get('interpretation', {}).get('type') != 'POLICY_REJECTED_EVIDENCE':
                raise ValueError('STRICT_REJECTION_PRESENTED_AS_QUERY_SUCCESS')
            retained_pages(partial, read_output, budget, invoke)
    checks.append(Check('photo-depth-one-callback-walk', Outcome.OBSERVED,
                        tuple(f'call-{i:04d}.json' for i in range(before, counter))))

    for name, invalid in (
        ('INTERFACE-kind', {'request': {'type': 'RUN', 'source': {'type': 'SEARCH_DECLARATIONS', 'declarationName': 'CacheManager', 'declarationKinds': ['INTERFACE']}}}),
        ('object-retention', {'request': {'type': 'RUN', 'source': {'type': 'SEARCH_DECLARATIONS', 'declarationName': 'CacheManager'}, 'retention': {'type': 'RETAIN'}}})):
        if validator.is_valid(invalid):
            raise ValueError('NEGATIVE_SCHEMA_CONTROL_ACCEPTED:' + name)
        checks.append(Check(name, Outcome.PASSED, ()))

    if args.concurrency_control:
        # Change only scheduling; name, scope, kind, grants and outputs stay identical.
        requests = [search('execute') for _ in range(7)]
        sequential = [invoke(request) for request in requests]
        # capture thread-safe receipt naming outside invoke's counter mutation.
        def concurrent(request):
            encoded = encode(request)
            return replay.capture([args.rpc, 'call', 'query_symbols'], args.root, json.dumps(encoded), args.timeout)
        with ThreadPoolExecutor(max_workers=7) as pool:
            concurrent_processes = list(pool.map(concurrent, requests))
        replay.write(output / 'concurrency-control.json', asdict(SchedulingObservation(
            tuple(encode(p) for p in requests), tuple(sequential), tuple(concurrent_processes))))
        checks.append(Check('identical-grant-scheduling-control', Outcome.OBSERVED, ('concurrency-control.json',)))

    if replay.inventory(args.root) != fixture_before:
        raise ValueError('FIXTURE_CHANGED_DURING_OBSERVATION')
    replay.write(output / 'report.json', asdict(Report(str(args.root.resolve()), replay.digest(args.rpc), fixture_before, tuple(checks))))
    print(output / 'report.json')
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=FIXTURE)
    parser.add_argument('--rpc', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=60)
    parser.add_argument('--concurrency-control', action='store_true')
    parser.add_argument('--trace', action='store_true', help='Require candidate TRACE capability; older installed schemas reject it')
    raise SystemExit(run(parser.parse_args()))
