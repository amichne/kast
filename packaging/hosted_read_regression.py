"""Replay the authored semantic oracle through the staged CLI and production provider.

Only bounded assertions and counts survive in the receipt. Returned references,
source text and canonical payloads are used in memory and are never logged.
"""
from collections import Counter
from dataclasses import asdict, dataclass, replace
from enum import Enum
from hosted_peer_probe import EndpointAdmissionFailure, EndpointAdmissionRejected
from hosted_wire_schema import HostedWireSchemaFailure, HostedWireSchemaRejected
import hashlib
import importlib.util
import json
import sys
import subprocess

from hosted_read_transport import HostedReadTransport, ReadProviderFailure, ReadTransportRejected
from native_provider_qualification import qualification_document
from hosted_concurrent_read import run_concurrent_read_regression
from hosted_authority_read_regression import run_authority_read_regression
from hosted_budget_read_regression import progress_advances, run_budget_read_regression
from hosted_resume_budget_regression import Checkpoint, Finished, admit_progress, run_resume_budget_regression
from hosted_enum_read_regression import run_enum_read_regression
from hosted_repair_budget_regression import run_repair_time_regression
from hosted_kotlin_call_regression import run_kotlin_call_regression
from hosted_compact_source_regression import run_compact_source_regression
from hosted_vfs_overflow_regression import run_vfs_overflow_regression
from hosted_read_policy import NativeReadPolicy
from hosted_source_failure_regression import run_source_failure_regression
from hosted_diagnostic_pages_regression import (run_diagnostic_pages_regression, DiagnosticRequest,
    DiagnosticGrant, DiagnosticDrained, drain_diagnostics)
from hosted_source_read_regression import run_source_paging_regression, source_qualification_observation
from query_name_request import QueryInput, QueryResume, relation_query, occurrence_facts, walk_query, walk_records, walk_observation


MAX_READ_RECEIPTS = 512  # Two surfaces, each bounded to 256 authored cases.


class ReadReceiptFailure(str, Enum):
    CAPACITY = 'receipt_capacity_exceeded'
    ASSERTION = 'receipt_assertion_rejected'
    COUNT = 'receipt_count_rejected'


class ReadReceiptRejected(ValueError):
    def __init__(self, failure):
        self.failure = failure
        super().__init__(failure.value)


class ReadRegressionStage(str, Enum):
    FIXTURE = 'fixture'
    PROVIDER = 'provider'
    OVERFLOW = 'overflow'
    SEMANTIC = 'semantic'
    CONCURRENT = 'concurrent'
    AUTHORITY = 'authority'


class ReadRegressionFailure(str, Enum):
    IO = 'io_rejected'
    VALUE = 'value_rejected'
    TYPE = 'type_rejected'
    KEY = 'key_rejected'
    PROCESS = 'process_rejected'


@dataclass(frozen=True)
class ReadRegressionRejection:
    stage: ReadRegressionStage
    cause: ReadRegressionFailure | ReadReceiptFailure | EndpointAdmissionFailure | HostedWireSchemaFailure


def regression_rejection(stage, error):
    if isinstance(error, (ReadReceiptRejected, EndpointAdmissionRejected, HostedWireSchemaRejected)):
        cause = error.failure
    else:
        cause = next(reason for kind, reason in (
            (OSError, ReadRegressionFailure.IO), (ValueError, ReadRegressionFailure.VALUE),
            (TypeError, ReadRegressionFailure.TYPE), (KeyError, ReadRegressionFailure.KEY),
            (subprocess.SubprocessError, ReadRegressionFailure.PROCESS)) if isinstance(error, kind))
    return asdict(ReadRegressionRejection(stage, cause))


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


def run_read_regression(isolation, fixture, product, java, harness, repo, read_fixture, initial_live,
                        read_policy=NativeReadPolicy.DEFAULT):
    """Call once after readiness, before the first mutation or external fixture edit."""
    oracle = _reproduction(repo)
    rows, failure, failure_details, unchanged, before = [], None, None, False, False
    qualification = None
    concurrent = None
    authority = None
    overflow = None
    stage = ReadRegressionStage.FIXTURE
    try:
        before = read_fixture.unchanged()
        stage = ReadRegressionStage.PROVIDER
        with HostedReadTransport(isolation, fixture, product, java, harness).open() as transport:
            qualification = qualification_document(transport.qualification)
            if read_policy is NativeReadPolicy.OVERFLOW:
                stage = ReadRegressionStage.OVERFLOW
                observed, successor = run_vfs_overflow_regression(isolation, fixture, transport, initial_live)
                overflow = asdict(observed)
                if successor is None:
                    raise ValueError("Owned overflow fixture did not restore fresh authority")
                initial_live = successor
            stage = ReadRegressionStage.SEMANTIC
            for surface in ('cli', 'provider'):
                replay = _ReadReplay(oracle, read_fixture, initial_live, transport, surface, rows)
                if read_policy is NativeReadPolicy.DIAGNOSTIC_PAGES:
                    run_diagnostic_pages_regression(replay)
                replay.run()
            stage = ReadRegressionStage.CONCURRENT
            concurrent = run_concurrent_read_regression(isolation, read_fixture, oracle, transport, initial_live)
            stage = ReadRegressionStage.AUTHORITY
            authority = asdict(run_authority_read_regression(isolation, fixture, transport, initial_live))
    except ReadTransportRejected as error:
        failure = 'READ_TRANSPORT_REJECTED'
        failure_details = error.evidence()
        if error.qualification is not None:
            qualification = qualification_document(error.qualification)
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError) as error:
        failure_details = regression_rejection(stage, error)
        failure = 'READ_RESULT_OR_FIXTURE_REJECTED'
    finally:
        try:
            unchanged = before and read_fixture.unchanged()
        except (OSError, ValueError):
            failure = 'READ_FIXTURE_REJECTED'
    passed = (failure is None and unchanged and bool(rows) and all(row['passed'] for row in rows)
              and (read_policy is not NativeReadPolicy.OVERFLOW or overflow is not None and overflow['outcome'] == 'passed')
              and concurrent is not None and concurrent['outcome'] == 'passed'
              and authority is not None and authority['outcome'] == 'passed')
    return {'schemaVersion': 1, 'outcome': 'passed' if passed else 'rejected', 'failure': failure,
            'failureDetails': failure_details, 'providerQualification': qualification,
            'scope': 'complete-authored-base-semantic-matrix-and-three-semantic-read-tools',
            'fixture': read_fixture.evidence(), 'sourceUnchanged': unchanged,
            'queryBudgets': 'unchanged-production-policy', 'sourcePayloadsLogged': False,
            'stockCodexUi': 'unqualified', 'caseCount': len(rows),
            'passedCount': sum(row['passed'] for row in rows), 'cases': rows, 'concurrentReplay': concurrent,
            'authorityReplay': authority, 'overflowReplay': overflow}


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
        run_kotlin_call_regression(self)
        run_compact_source_regression(self)
        run_source_failure_regression(self)

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
            tokens = tuple(self.seeds[key]['ref'] for key in unique)
            self.query(self.oracle.Case(name, {'type': 'REFS', 'refs': [self.seeds[key]['ref'] for key in keys]},
                tuple(self.fixture.oracle['declarations'][key][1] for key in unique),
                steps=({'type': 'DISTINCT'},), tokens=tokens, issued=tuple(self.seeds[key] for key in unique)))
        if 'helper' not in self.seeds:
            self.record('separate-field-projections', 'query_symbols', {'issuerAvailable': False})
            return
        token = self.seeds['helper']['ref']
        for field in self.oracle.FIELDS:
            self.query(self.oracle.Case('projection-' + field.lower(), {'type': 'REFS', 'refs': [token]},
                (self.fixture.oracle['declarations']['helper'][1],), select=(field,), tokens=(token,),
                issued=(self.seeds['helper'],)))

    def specialists(self):
        if not {'logger', 'helper'} <= self.seeds.keys():
            self.record('specialist-read-tools', 'all', {'issuerAvailable': False})
            return
        self.source_read()
        run_source_paging_regression(self)
        run_enum_read_regression(self)
        self.relations()
        run_budget_read_regression(self)
        run_repair_time_regression(self)
        run_resume_budget_regression(self)
        request = DiagnosticRequest('src/main/kotlin/Fixture.kt', 1000,
            execution_budget=DiagnosticGrant(max_results=1000))
        first, _ = self.transport.invoke_observed(self.surface, 'check_diagnostics', asdict(request))
        drained = drain_diagnostics(self, request, first)
        complete = isinstance(drained, DiagnosticDrained)
        response = drained.pages[-1] if complete else first
        facts = tuple(item for page in drained.pages for item in page['diagnostics']) if complete else ()
        self.record('diagnostics-exact-file', 'check_diagnostics', {
            **self.completed(response), 'boundedDrainCompleted': complete,
            'diagnosticsPresent': complete and all(isinstance(page.get('diagnostics'), list) for page in drained.pages),
            'noCompilerErrors': complete and all(d.get('severity') != 'error' for d in facts),
        }, len(facts), response)

    def source_read(self):
        response = self.transport.invoke(self.surface, 'source_read', {
            'anchor': {'type': 'symbol', 'selector': self.seeds['logger']['ref']},
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
        token = self.seeds['helper']['ref']
        response = self.transport.invoke(self.surface, 'query_symbols', asdict(relation_query(token, 'callers')))
        relations = occurrence_facts(response)
        expected = Counter(self.fixture.oracle['helperCallers'])
        helper = self.fixture.oracle['declarations']['helper'][1]
        self.record('semantic-callers-five', 'query_symbols', {
            **self.completed(response), 'exactCallers': Counter(r.get('source', {}).get('qualifiedIdentity')
                for r in relations) == expected,
            'exactTarget': all(r.get('target', {}).get('qualifiedIdentity') == helper for r in relations),
            'compilerCoverage': all(r.get('coverage') == 'exact-compiler-confirmed' and
                r.get('provenance') == 'k2-authored-source' for r in relations),
        }, len(relations), response)
        self.walk(token, expected, helper)

    def walk(self, token, expected, helper):
        # Query owns continuation; an issued token resumes its saved walk plan.
        request, seen, callers = walk_query(token), set(), Counter()
        complete, valid_pages, response = False, True, None
        previous_progress = {}
        for page in range(sum(expected.values()) + 1):
            response = self.transport.invoke(self.surface, 'query_symbols', asdict(request))
            records = walk_records(response)
            observation = walk_observation(response)
            progress = observation.get('progress', {})
            state = admit_progress('query_symbols', response)
            terminal = isinstance(state, Finished)
            continuation = state.token if isinstance(state, Checkpoint) and state.token not in seen else None
            checks = {
                'completeOrResumableTimeBound': terminal or continuation is not None,
                'sameLiveAuthority': response.get('live') == self.live,
                'walkIdentity': observation.get('subject') == token and observation.get('relation') == 'callers'
                    and observation.get('maximum_depth') == 4 and observation.get('strategy') == {'type': 'breadth_first'},
                'progressMonotonic': progress_advances(previous_progress, progress),
                'frontierObserved': type(observation.get('expanded_frontier')) is int
                    and observation['expanded_frontier'] >= 0,
                'coverageRetained': observation.get('coverage', {}).get('kind') ==
                    ('complete' if terminal else 'resumable'),
                'exactTarget': all(record.get('relation', {}).get('target', {}).get('qualifiedIdentity') == helper
                                   for record in records),
                'compilerCoverage': all(record.get('relation', {}).get('coverage') == 'exact-compiler-confirmed' and
                    record.get('relation', {}).get('provenance') == 'k2-authored-source' for record in records),
                'compilerProofs': all(all(relation.get(end, {}).get('compilerEvidence', {}).get('identity')
                    for end in ('source', 'target')) for relation in (record['relation'] for record in records)),
            }
            self.record('transitive-callers-page-' + str(page + 1), 'query_symbols', checks, len(records), response)
            valid_pages = valid_pages and all(checks.values())
            if not valid_pages:
                break
            callers.update(record['relation'].get('source', {}).get('qualifiedIdentity') for record in records)
            previous_progress = progress
            if terminal:
                complete = True
                break
            seen.add(continuation)
            request = QueryInput(QueryResume(continuation))
        self.record('transitive-callers-five', 'query_symbols', {
            'complete': complete, 'allPagesProven': valid_pages, 'exactCallers': callers == expected,
        }, sum(callers.values()), response)

    def completed(self, response):
        return {'complete': response.get('status') == 'complete', 'sameLiveAuthority': response.get('live') == self.live}

    def record(self, name, tool, checks, count=0, response=None):
        if len(self.rows) >= MAX_READ_RECEIPTS:
            raise ReadReceiptRejected(ReadReceiptFailure.CAPACITY)
        if not all(type(value) is bool for value in checks.values()):
            raise ReadReceiptRejected(ReadReceiptFailure.ASSERTION)
        if type(count) is not int or not 0 <= count <= 1000:
            raise ReadReceiptRejected(ReadReceiptFailure.COUNT)
        self.rows.append({'case': name, 'tool': tool, 'surface': self.surface,
            'passed': all(value is True for value in checks.values()), 'assertions': checks, 'resultCount': count,
            'observation': _read_observation(response)})


def _read_observation(response):
    if response is None:
        return {'outcome': 'not-invoked'}
    status = response.get('status')
    result = {'outcome': 'observed', 'status': status if status in ('complete', 'qualified', 'rejected')
              else 'unrecognized'}
    failure = response.get('failure')
    if isinstance(failure, str) and failure in {known.value for known in ReadProviderFailure}:
        result['providerFailure'] = ReadProviderFailure(failure).value
    qualification = response.get('qualification')
    observations = response.get('walk_observations', [])
    if isinstance(observations, list) and len(observations) == 1:
        result['walkCoverage'] = _walk_coverage_observation(observations[0].get('coverage'))
    if isinstance(qualification, dict) and 'knownMinimumEntityCount' in qualification:
        result['sourceQualification'] = source_qualification_observation(qualification)
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


_TRAVERSAL_LIMITATIONS = frozenset(('record-limit-reached', 'byte-limit-reached', 'work-limit-reached',
    'time-limit-reached', 'depth-limit-reached', 'frontier-limit-reached', 'one-hop-incomplete'))
_RELATION_LIMITATIONS = frozenset(('result-limit-reached', 'byte-limit-reached', 'work-limit-reached',
    'time-limit-reached', 'dumb-mode-transition', 'unresolved-target', 'unsupported-item',
    'provider-failure', 'provider-incomplete'))


def _walk_coverage_observation(coverage):
    if not isinstance(coverage, dict):
        return {'outcome': 'unadmitted'}
    kind = coverage.get('kind')
    if kind == 'complete' and set(coverage) == {'kind'}:
        return {'kind': kind}
    limits, relations = coverage.get('limitations'), coverage.get('relation_limitations')
    if (kind not in ('resumable', 'terminal_incomplete')
            or not isinstance(limits, list) or not 1 <= len(limits) <= len(_TRAVERSAL_LIMITATIONS)
            or not all(isinstance(value, str) and value in _TRAVERSAL_LIMITATIONS for value in limits)
            or not isinstance(relations, list) or len(relations) > len(_RELATION_LIMITATIONS)
            or not all(isinstance(value, str) and value in _RELATION_LIMITATIONS for value in relations)):
        return {'outcome': 'unadmitted'}
    return {'kind': kind, 'limitations': limits, 'relation_limitations': relations}
