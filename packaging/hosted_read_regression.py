"""Replay the authored semantic oracle through the staged CLI and production provider.

Only bounded assertions and counts survive in the receipt. Returned references,
source text and canonical payloads are used in memory and are never logged.
"""
from collections import Counter
from dataclasses import replace
import hashlib
import importlib.util
import json
import sys
import subprocess

from hosted_read_transport import HostedReadTransport, ReadTransportRejected


def _reproduction(repo):
    directory = repo / 'experiments/host-observation'
    name = 'kast_native_semantic_oracle'
    if name in sys.modules:
        return sys.modules[name]
    sys.path.insert(0, str(directory))
    try:
        spec = importlib.util.spec_from_file_location(name, directory / 'reproduce_semantic_queries.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        spec.loader.exec_module(module)
        return module
    finally:
        sys.path.remove(str(directory))


def run_read_regression(isolation, fixture, product, java, harness, repo, read_fixture, initial_live):
    """Call once after readiness, before the first mutation or external fixture edit."""
    oracle = _reproduction(repo)
    rows, failure, unchanged, before = [], None, False, False
    try:
        before = read_fixture.unchanged()
        with HostedReadTransport(isolation, fixture, product, java, harness).open() as transport:
            for surface in ('cli', 'provider'):
                replay = _ReadReplay(oracle, read_fixture, initial_live, transport, surface, rows)
                replay.run()
    except ReadTransportRejected:
        failure = 'READ_TRANSPORT_REJECTED'
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError):
        failure = 'READ_RESULT_OR_FIXTURE_REJECTED'
    finally:
        try:
            unchanged = before and read_fixture.unchanged()
        except (OSError, ValueError):
            failure = 'READ_FIXTURE_REJECTED'
    passed = failure is None and unchanged and bool(rows) and all(row['passed'] for row in rows)
    return {'schemaVersion': 1, 'outcome': 'passed' if passed else 'rejected', 'failure': failure,
            'scope': 'complete-authored-base-semantic-matrix-and-eight-default-read-tools',
            'fixture': read_fixture.evidence(), 'sourceUnchanged': unchanged,
            'queryBudgets': 'unchanged-production-policy', 'sourcePayloadsLogged': False,
            'stockCodexUi': 'unqualified', 'caseCount': len(rows),
            'passedCount': sum(row['passed'] for row in rows), 'cases': rows}


class _ReadReplay:
    def __init__(self, oracle, fixture, live, transport, surface, rows):
        self.oracle, self.fixture, self.live = oracle, fixture, live
        self.transport, self.surface, self.rows = transport, surface, rows
        self.seeds = {}

    def run(self):
        for case in self.oracle.cases(self.fixture.oracle, {'callee': 'kotlin', 'javaReferences': False}):
            if case.reported == 'budget-exceeded' or case.reported.startswith('relation-incomplete'):
                case = replace(case, reported='complete')
            if case.name == 'invalid-reference-syntax':
                case = replace(case, schema_valid=True, reported='malformed-reference')
            self.query(case)
        self.roundtrips()
        self.specialists()

    def query(self, case):
        tool, _, request = self.oracle.invocation(case, self.oracle.ToolSurface.PUBLIC)
        response = self.transport.invoke(self.surface, tool, request)
        assessment = self.oracle.assess(case, response, self.fixture.workspace, self.fixture.oracle)
        checks = dict(assessment['assertions'])
        checks['expectedOutcome'] = assessment['finding'] == self.oracle.Finding.REPRODUCED.value
        checks['sameLiveAuthority'] = response.get('live') == self.live or (
            'live' not in response and case.reported in ('malformed-reference', 'invalid-arguments'))
        self.record(case.name, tool, checks, len(response.get('items', [])), response)
        if case.name.startswith('exact-') and checks['expectedOutcome'] and len(response.get('items', [])) == 1:
            self.seeds[case.name[6:]] = response['items'][0]

    def roundtrips(self):
        for name, keys in (('refs-roundtrip', ('logger',)), ('refs-multiple', ('logger', 'alias')),
                           ('refs-deduplicate', ('logger', 'logger'))):
            unique = tuple(dict.fromkeys(keys))
            if not all(key in self.seeds for key in keys):
                self.record(name, 'query_symbols', {'issuerAvailable': False})
                continue
            tokens = tuple(self.seeds[key]['ref']['token'] for key in unique)
            self.query(self.oracle.Case(name, {'type': 'REFS', 'refs': [self.seeds[key]['ref']['token'] for key in keys]},
                tuple(self.fixture.oracle['declarations'][key][1] for key in unique),
                steps=({'type': 'DISTINCT'},), tokens=tokens, issued=tuple(self.seeds[key] for key in unique)))
        if 'helper' not in self.seeds:
            self.record('separate-field-projections', 'query_symbols', {'issuerAvailable': False})
            return
        token = self.seeds['helper']['ref']['token']
        for field in self.oracle.FIELDS:
            self.query(self.oracle.Case('projection-' + field.lower(), {'type': 'REFS', 'refs': [token]},
                (self.fixture.oracle['declarations']['helper'][1],), select=(field,), tokens=(token,),
                issued=(self.seeds['helper'],)))

    def specialists(self):
        if not {'logger', 'helper'} <= self.seeds.keys():
            self.record('specialist-read-tools', 'all', {'issuerAvailable': False})
            return
        self.source_read()
        self.relations()
        response = self.transport.invoke(self.surface, 'check_diagnostics',
            {'relative_path': 'src/main/kotlin/Fixture.kt', 'max_diagnostics': None})
        self.record('diagnostics-exact-file', 'check_diagnostics', {
            **self.completed(response), 'diagnosticsPresent': isinstance(response.get('diagnostics'), list),
            'noCompilerErrors': all(d.get('severity') != 'error' for d in response.get('diagnostics', [])),
        }, len(response.get('diagnostics', [])), response)

    def source_read(self):
        response = self.transport.invoke(self.surface, 'source_read', {
            'anchor': {'type': 'symbol', 'selector': self.seeds['logger']['symbol_ref']},
            'region': {'type': 'file'}, 'entities': {'type': 'none'}, 'text': {'type': 'complete'},
            'entityLimit': 100, 'textByteLimit': 65536, 'page': {'type': 'first'}})
        path = self.fixture.workspace / self.fixture.oracle['declarations']['logger'][0]
        text = response.get('text', {})
        snapshot = response.get('snapshot', {})
        self.record('source-exact-saved-file', 'source_read', {
            **self.completed(response), 'exactText': text.get('text') == path.read_text(),
            'textReturned': text.get('type') == 'returned', 'exactFile': snapshot.get('file') == str(path),
            'nestedLiveRetained': snapshot.get('live') == self.live, 'publishedFieldsAbsent':
                'generation' not in snapshot and 'sourceState' not in snapshot,
        }, response=response)

    def relations(self):
        token = self.seeds['helper']['symbol_ref']
        response = self.transport.invoke(self.surface, 'semantic_query', {
            'exactSelector': token, 'relation': 'callers', 'limit': 100, 'position': {'type': 'start'}})
        relations = response.get('relations', [])
        expected = Counter(self.fixture.oracle['helperCallers'])
        helper = self.fixture.oracle['declarations']['helper'][1]
        self.record('semantic-callers-five', 'semantic_query', {
            **self.completed(response), 'exactCallers': Counter(r.get('source', {}).get('qualifiedIdentity')
                for r in relations) == expected,
            'exactTarget': all(r.get('target', {}).get('qualifiedIdentity') == helper for r in relations),
            'compilerCoverage': all(r.get('coverage') == 'exact-compiler-confirmed' and
                r.get('provenance') == 'k2-authored-source' for r in relations),
        }, len(relations), response)
        self.traversal(token, expected, helper)

    def traversal(self, token, expected, helper):
        response = self.transport.invoke(self.surface, 'impact_analyze', {
            'exactSelector': token, 'relation': 'callers', 'maximumDepth': 4, 'maximumResults': 100,
            'position': {'type': 'start'}})
        graph = response.get('graph', {})
        nodes = {n['id']: n for n in graph.get('nodes', [])}
        edges = graph.get('edges', [])
        self.record('transitive-callers-five', 'impact_analyze', {
            **self.completed(response), 'nestedLiveRetained': graph.get('snapshot', {}).get('live') == self.live,
            'exactCallers': Counter(nodes.get(e.get('source'), {}).get('qualifiedIdentity') for e in edges) == expected,
            'exactTarget': all(nodes.get(e.get('target'), {}).get('qualifiedIdentity') == helper for e in edges),
            'compilerCoverage': all(e.get('coverage') == 'exact-compiler-confirmed' and
                e.get('provenance') == 'k2-authored-source' for e in edges),
            'compilerProofs': bool(graph.get('proofs')) and all(p.get('identity') for p in graph.get('proofs', [])),
        }, len(edges), response)

    def completed(self, response):
        return {'complete': response.get('status') == 'complete', 'sameLiveAuthority': response.get('live') == self.live}

    def record(self, name, tool, checks, count=0, response=None):
        if (len(self.rows) >= 256 or not all(type(value) is bool for value in checks.values())
                or type(count) is not int or not 0 <= count <= 1000):
            raise ValueError('READ_RECEIPT_REJECTED')
        self.rows.append({'case': name, 'tool': tool, 'surface': self.surface,
            'passed': all(value is True for value in checks.values()), 'assertions': checks, 'resultCount': count,
            'observation': _read_observation(response)})


def _read_observation(response):
    if response is None:
        return {'outcome': 'not-invoked'}
    status = response.get('status')
    result = {'outcome': 'observed', 'status': status if status in ('complete', 'qualified', 'rejected')
              else 'unrecognized'}
    live = response.get('live')
    if (isinstance(live, dict) and set(live) == {'root', 'host', 'epoch', 'contentView', 'version'}
            and type(live['epoch']) is int and 1 <= live['epoch'] <= 2**63 - 1
            and type(live['version']) is int and live['version'] == 1
            and all(isinstance(live[key], str) and 0 < len(live[key]) <= 4096
                    for key in ('root', 'host', 'contentView'))):
        result.update(epoch=live['epoch'], authoritySha256=hashlib.sha256(
            json.dumps(live, sort_keys=True, separators=(',', ':')).encode()).hexdigest())
    else:
        result['live'] = 'absent-or-unadmitted'
    return result
