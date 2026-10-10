import copy
from argparse import Namespace
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from enterprise_cache_oracle import CACHE, FIXTURE, FixtureIntegrityError, load_oracle, materialize
import kast_ide
from qualify_enterprise_cache import Budget, DirectoryScope, Location, Output, PackageScope, Payload, PhotoWalkBudget, Refs, RowsOutput, Run, ScopedTrace, Search, SourceDomain, Trace, Walk, encode, request_validator, retained_pages, run


class EnterpriseCacheQualificationTest(unittest.TestCase):
    def test_unavailable_exact_fixture_stops_before_any_public_rpc(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'observation'
            args = Namespace(root=FIXTURE, output=output, rpc=Path('/unavailable/rpc'))
            with patch('qualify_enterprise_cache.kast_ide.exchange', return_value=kast_ide.Rejected(kast_ide.Failure.HOST_UNAVAILABLE)), \
                 patch('qualify_enterprise_cache.replay.capture', side_effect=AssertionError('unexpected public RPC')):
                self.assertEqual(run(args), 2)
            self.assertEqual(json.loads((output / 'report.json').read_text()), {
                'type': 'CACHE_OBSERVATION_REJECTED', 'failure': 'EXACT_OPEN_FIXTURE_HOST_UNAVAILABLE'})

    def test_interface_class_family_and_photo_requests_use_canonical_schema(self):
        validator = request_validator()
        oracle = load_oracle()
        for source in (Search('CacheManager', PackageScope(), ('CLASS',)),
                       Search('CacheManager', DirectoryScope(), ('CLASS',)),
                       Location(CACHE, oracle.generic.range.startInclusive)):
            validator.validate(encode(Payload(Run(source))))

    def test_interface_kind_and_retention_object_are_rejected_independently(self):
        validator = request_validator()
        valid = encode(Payload(Run(Search('CacheManager', PackageScope(), ('CLASS',)))))
        self.assertTrue(validator.is_valid(valid))
        wrong_kind = copy.deepcopy(valid)
        wrong_kind['request']['source']['declarationKinds'] = ['INTERFACE']
        self.assertFalse(validator.is_valid(wrong_kind))
        wrong_retention = copy.deepcopy(valid)
        wrong_retention['request']['retention'] = {'type': 'RETAIN'}
        self.assertFalse(validator.is_valid(wrong_retention))

    def test_generic_name_and_body_offsets_use_utf16(self):
        oracle = load_oracle()
        text = (FIXTURE / CACHE).read_text()
        start = text.index('T : Any')
        self.assertEqual(oracle.generic.range.startInclusive, start + 1)
        self.assertLess(oracle.generic.range.endExclusive, oracle.method_name.range.startInclusive)
        self.assertLess(oracle.method_name.range.endExclusive, oracle.implementation_body.range.startInclusive)
        for site in (oracle.generic, oracle.method_name, oracle.implementation_body):
            encoded = text.encode('utf-16-le')
            extracted = encoded[site.range.startInclusive * 2:site.range.endExclusive * 2].decode('utf-16-le')
            self.assertEqual(extracted, site.text)

    def test_authored_oracle_rejects_fixture_drift(self):
        documents = {str(path.relative_to(FIXTURE)): path.read_text() for path in FIXTURE.rglob('*.kt')}
        documents[CACHE] = documents[CACHE].replace('load()', 'load.invoke()')
        with self.assertRaises(FixtureIntegrityError):
            materialize(documents)

    def test_identical_grants_and_kind_for_scope_control(self):
        package = encode(Payload(Run(Search('CacheManager', PackageScope(), ('CLASS',)))))
        directory = encode(Payload(Run(Search('CacheManager', DirectoryScope(), ('CLASS',)))))
        package['request']['source'].pop('scope')
        directory['request']['source'].pop('scope')
        self.assertEqual(package, directory)

    def test_photo_walk_keeps_default_work_and_byte_dimensions_omitted(self):
        request = encode(Payload(Run(Refs(('exact:v4:' + '0' * 64,)), RowsOutput('TRAVERSAL_RECORDS'),
                                    (Walk(),), PhotoWalkBudget())))
        self.assertEqual(request['request']['executionBudget'], {'maxElapsedMs': 15000, 'maxResults': 100})
        self.assertEqual(request['request']['steps'], [{'type': 'WALK', 'relation': 'CALLEES', 'maximumDepth': 1}])
        request_validator().validate(request)

    def test_scoped_trace_retains_the_explicit_domain_under_the_public_schema(self):
        source = Search('CacheManager', DirectoryScope(), ('CLASS',))
        domain = SourceDomain(sourceSets=('main', 'test'), sourcePolicy='PRODUCTION_AND_TEST',
                              directory='src/main/kotlin/example/cache')
        request = encode(Payload(Run(source, steps=(ScopedTrace(domain),))))
        self.assertEqual(request['request']['steps'], [{
            'type': 'TRACE', 'expansionScope': {
                'type': 'SOURCE_DOMAIN', 'sourceSets': ['main', 'test'],
                'sourcePolicy': 'PRODUCTION_AND_TEST', 'generatedSources': 'EXCLUDE',
                'directory': 'src/main/kotlin/example/cache'}}])
        request_validator().validate(request)

    def test_default_trace_keeps_workspace_expansion_omitted_without_changing_the_grant(self):
        source = Search('CacheManager', DirectoryScope(), ('CLASS',))
        workspace = encode(Payload(Run(source, steps=(Trace(),))))
        scoped = encode(Payload(Run(source, steps=(ScopedTrace(SourceDomain()),))))
        self.assertEqual(workspace['request']['steps'], [{'type': 'TRACE'}])
        self.assertEqual(workspace['request']['executionBudget'], {
            'maxElapsedMs': 15000, 'maxResults': 100,
            'maxWorkUnits': 100000, 'maxReturnedBytes': 524288})
        self.assertEqual(workspace['request']['source'], scoped['request']['source'])
        self.assertEqual(workspace['request']['executionBudget'], scoped['request']['executionBudget'])
        request_validator().validate(workspace)

    def test_retained_rows_and_evidence_have_independent_cursors(self):
        first = {'live': 'basis', 'items': ['first'], 'next_cursor': 1,
                 'retention': {'kind': 'retained', 'reference': 'result'},
                 'evidence_window': {'type': 'MORE', 'end': 2, 'total': 4}}
        responses = iter([
            {'live': 'basis', 'items': ['second'], 'evidence_window': {'type': 'MORE', 'end': 3, 'total': 4}},
            {'live': 'basis', 'items': [], 'evidence_window': {'type': 'FINAL', 'end': 4, 'total': 4}},
        ])
        requests = []
        def invoke(payload):
            requests.append(payload.request)
            return next(responses)
        pages = retained_pages(first, Output(), Budget(), invoke)
        self.assertEqual([item for page in pages for item in page['items']], ['first', 'second'])
        self.assertEqual([(p.cursor, p.evidence_cursor) for p in requests], [(1, 2), (2, 3)])

    def test_retained_reader_rejects_cursor_stall_and_basis_change(self):
        first = {'live': 'basis', 'items': [], 'next_cursor': 0,
                 'retention': {'kind': 'retained', 'reference': 'result'},
                 'evidence_window': {'type': 'FINAL', 'end': 0, 'total': 0}}
        with self.assertRaisesRegex(ValueError, 'PRESENTATION_STALLED'):
            retained_pages(first, Output(), Budget(), lambda _: first)
        moved = dict(first, live='moved')
        with self.assertRaisesRegex(ValueError, 'PRESENTATION_BASIS_CHANGED'):
            retained_pages(first, Output(), Budget(), lambda _: moved)

    def test_rejected_evidence_cannot_become_success_when_presented(self):
        first = {'live': 'basis', 'items': [], 'next_cursor': 0,
                 'retention': {'kind': 'retained', 'reference': 'result'},
                 'interpretation': {'type': 'POLICY_REJECTED_EVIDENCE'},
                 'evidence_window': {'type': 'FINAL', 'end': 0, 'total': 0}}
        with self.assertRaisesRegex(ValueError, 'PRESENTATION_INTERPRETATION_CHANGED'):
            retained_pages(first, Output(), Budget(), lambda _: dict(first, interpretation={'type': 'QUERY_RESULT'}))


if __name__ == '__main__':
    unittest.main()
