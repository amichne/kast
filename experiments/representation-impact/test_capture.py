"""Private effect-boundary checks; scripted captures establish no native product success."""
from argparse import Namespace
from dataclasses import asdict, dataclass, replace
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import capture as c
from capture_contract import Captured, Correlation, Counter, Failure, Rejected, ExpectedBasisUnavailable, ExpectedSeedBasis, RequestAdmitted, RpcOutcome, Uncertainty
import fixture_intent as intent


@dataclass(frozen=True)
class Search:
    declarationName: str = 'investigate'
    type: str = 'SEARCH_DECLARATIONS'


@dataclass(frozen=True)
class RequestAction:
    source: Search = Search()
    type: str = 'RUN'


@dataclass(frozen=True)
class Request:
    request: RequestAction = RequestAction()


@dataclass(frozen=True)
class Bound:
    host: str = '00000000-0000-0000-0000-000000000001'
    epoch: int = 7
    type: str = 'bound'


@dataclass(frozen=True)
class LiveBasis:
    root: str = '/workspace'
    host: str = '00000000-0000-0000-0000-000000000001'
    epoch: int = 7
    contentView: str = 'SAVED_PSI_COMMITTED'
    referenceVersion: int = 1
    type: str = 'LIVE'


@dataclass(frozen=True)
class Range:
    start: int = 0
    end: int = 100


@dataclass(frozen=True)
class Declaration:
    basis: LiveBasis = LiveBasis()
    file: str = '/workspace/File.kt'
    range: Range = Range()
    compilerIdentity: str = 'canonical-signature-sha256-v1|' + 'a' * 64


@dataclass(frozen=True)
class Role:
    type: str = 'EXPRESSION_RESULT'


@dataclass(frozen=True)
class Seed:
    enclosing: Declaration = Declaration()
    range: Range = Range(10, 20)
    role: Role = Role()


@dataclass(frozen=True)
class Status:
    required: tuple[str, ...] = ('NATIVE_FLOW',)
    type: str = 'UNRESOLVED'


@dataclass(frozen=True)
class View:
    type: str = 'PATHS'


@dataclass(frozen=True)
class Investigation:
    seeds: tuple[Seed, ...] = (Seed(),)
    requestedDomain: Role = Role('WORKSPACE')
    semantics: str = 'KOTLIN_FORWARD_V1'
    representationModelReferences: tuple = ()
    boundaryModelReferences: tuple = ()
    originalReadRejectionCount: int = 0
    originalObservationCount: int = 1
    originalPathCount: int = 1
    pagePathCount: int = 1
    status: Status = Status()
    view: View = View()
    type: str = 'INVESTIGATED'


@dataclass(frozen=True)
class LiveSummary:
    root: str = '/workspace'
    contentView: str = 'saved-psi-committed'


@dataclass(frozen=True)
class ResultDocument:
    impact_accounting: Investigation = Investigation()
    live: LiveSummary = LiveSummary()


@dataclass(frozen=True)
class NotApplicable:
    type: str = 'NOT_APPLICABLE'


@dataclass(frozen=True)
class OrdinaryResultDocument:
    impact_accounting: NotApplicable = NotApplicable()
    live: LiveSummary = LiveSummary()


@dataclass(frozen=True)
class Reply:
    document: ResultDocument | OrdinaryResultDocument = ResultDocument()
    type: str = 'qualified'


@dataclass(frozen=True)
class Diagnostic:
    pid: int = 42
    correlation: Bound = Bound()
    counters: tuple[Counter, ...] = (Counter('NATIVE_VALUE_FLOW_WORK', 'NONE', 3),)
    schemaVersion: int = 6
    limits: tuple = ()
    readId: str = 'fixture-read-id'
    durationNanos: int = 10
    stages: tuple = ()
    nativePhase: 'EnteredPhase | None' = None
    nativePhaseDurations: tuple = ()
    semanticEntry: None = None
    semanticBudget: None = None
    gauges: tuple = ()
    terminations: tuple = ()
    outcome: None = None
    unexpectedFailures: tuple = ()


def encoded(record):
    return json.dumps(asdict(record)).encode()


def decoded(record):
    return json.loads(encoded(record))


def log_line(record):
    return b'unretained IDE prefix kast_semantic_read ' + encoded(record) + b'\n'


@dataclass(frozen=True)
class EnteredPhase:
    phase: str = 'VALUE_SITE_RESTORATION'
    type: str = 'entered'


@dataclass(frozen=True)
class PhaseDuration:
    phase: str
    durationNanos: int


class CaptureTest(unittest.TestCase):
    def test_traversal_and_native_intervals_remain_separate_opaque_schema6_evidence(self):
        durations = (PhaseDuration('TRAVERSAL', 7), PhaseDuration('REFERENCE_CONFIRMATION', 11))
        diagnostic = replace(Diagnostic(), nativePhase=EnteredPhase('TRAVERSAL'), nativePhaseDurations=durations)
        observed = c.select_receipts((decoded(diagnostic),), 42, decoded(Reply()))
        self.assertEqual(Correlation.MATCHED, observed.correlation)
        self.assertEqual((), observed.uncertainty)
        self.assertEqual(decoded(diagnostic), observed.receipts[0])
        self.assertEqual([asdict(value) for value in durations], observed.receipts[0]['nativePhaseDurations'])

    def test_additive_restoration_vocabulary_preserves_schema6_receipt_and_exact_counters(self):
        for outcome in ('VALUE_SITE_SHAPES_RESTORED', 'VALUE_SITE_SHAPES_REJECTED', 'VALUE_SITE_ANCHORS_UNAVAILABLE'):
            counts = (Counter('VALUE_SITE_RESTORATIONS', 'NONE', 1), Counter(outcome, 'NONE', 1))
            diagnostic = replace(Diagnostic(), counters=counts, nativePhase=EnteredPhase())
            response = decoded(Reply())
            observed = c.select_receipts((decoded(diagnostic),), 42, response)
            self.assertEqual(Correlation.MATCHED, observed.correlation)
            self.assertEqual((), observed.uncertainty)
            self.assertEqual(counts, observed.counters)
            self.assertEqual(decoded(diagnostic), observed.receipts[0])
            self.assertEqual(6, observed.receipts[0]['schemaVersion'])

    def test_current_generated_schema_rejects_unknown_or_duplicate_request_before_effects(self):
        self.assertIsInstance(c.validate_request(encoded(Request())), RequestAdmitted)
        for raw in (b'{"request":{"type":"RUN","source":{"type":"ASSUMED"}}}',
                    b'{"request":{},"request":{}}', b'{"request":null}'):
            self.assertEqual(Failure.REQUEST_SCHEMA_REJECTED, c.validate_request(raw))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            args = self.args(root, b'{"request":null}')
            result = c.invoke(args, run=lambda *a, **k: self.fail('Schema rejection must precede process effects'))
            self.assertEqual(Rejected(Failure.REQUEST_SCHEMA_REJECTED), result)
            self.assertEqual('REQUEST_SCHEMA_REJECTED', json.loads((args.output_dir / 'capture.json').read_text())['failure'])

    def test_only_appended_structured_pid_receipts_and_exact_counters_are_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / 'idea.log'
            log.write_bytes(b'private unrelated line\n' + log_line(Diagnostic()))
            before = log.stat()
            with log.open('ab') as stream:
                stream.write(log_line(Diagnostic()) + log_line(replace(Diagnostic(), pid=99)))
                stream.write(b'private secret not copied\nkast_semantic_read invalid\n')
            result = c.appended_receipts(log, before, 42, decoded(Reply()))
            self.assertEqual(Correlation.MATCHED, result.correlation)
            self.assertEqual((2, 1, 1, 1), (result.parsedRecordCount, result.selectedRecordCount,
                                           result.foreignPidRecordCount, result.malformedRecordCount))
            self.assertEqual((Counter('NATIVE_VALUE_FLOW_WORK', 'NONE', 3),), result.counters)
            self.assertEqual((Uncertainty.MALFORMED_DIAGNOSTIC, Uncertainty.FOREIGN_PID), result.uncertainty)
            self.assertEqual((42,), tuple(r['pid'] for r in result.receipts))
            self.assertNotIn('private secret', json.dumps(asdict(result)))

    def test_absent_ambiguous_and_mismatched_receipts_do_not_manufacture_zero_work(self):
        receipt = asdict(Diagnostic())
        for receipts, response, expected in (((), decoded(Reply()), Correlation.UNAVAILABLE),
                                             ((receipt, receipt), decoded(Reply()), Correlation.AMBIGUOUS),
                                             ((receipt,), decoded(replace(Reply(), document=ResultDocument(Investigation((Seed(Declaration(LiveBasis(epoch=8))),))))), Correlation.MISMATCHED)):
            observed = c.select_receipts(receipts, 42, response)
            self.assertEqual(expected, observed.correlation)
            self.assertEqual((), observed.counters)
            self.assertTrue(observed.uncertainty)

    def test_one_or_several_identical_emitted_seed_bases_establish_one_expected_basis(self):
        for seeds in ((Seed(),), (Seed(), Seed(range=Range(30, 40)))):
            response = decoded(replace(Reply(), document=ResultDocument(Investigation(seeds))))
            expected = c.expected_seed_basis(response)
            self.assertIsInstance(expected, ExpectedSeedBasis)
            self.assertEqual(('/workspace', '00000000-0000-0000-0000-000000000001', 7),
                             (expected.basis.root, expected.basis.host, expected.basis.epoch))
            observed = c.select_receipts((decoded(Diagnostic()),), 42, response)
            self.assertEqual(Correlation.MATCHED, observed.correlation)
            self.assertEqual((Counter('NATIVE_VALUE_FLOW_WORK', 'NONE', 3),), observed.counters)

    def test_conflicting_missing_and_unknown_seed_bases_fail_closed_without_using_models(self):
        conflict = Investigation((Seed(), Seed(Declaration(LiveBasis(root='/foreign', epoch=8)))))
        unsupported = Investigation((Seed(Declaration(LiveBasis(type='ASSUMED'))),))
        published = Investigation((Seed(Declaration(LiveBasis(type='PUBLISHED'))),))
        for accounting, reason in ((conflict, Uncertainty.CONFLICTING_SEED_BASES),
                                   (unsupported, Uncertainty.UNSUPPORTED_SEED_BASIS),
                                   (published, Uncertainty.UNSUPPORTED_SEED_BASIS),
                                   (Investigation(()), Uncertainty.SEED_BASIS_UNAVAILABLE)):
            response = decoded(replace(Reply(), document=ResultDocument(accounting)))
            observed = c.select_receipts((decoded(Diagnostic()),), 42, response)
            self.assertEqual(ExpectedBasisUnavailable(reason), observed.expectedBasis)
            self.assertEqual(Correlation.UNAVAILABLE, observed.correlation)
            self.assertEqual((), observed.counters)
            self.assertIn(reason, observed.uncertainty)
        ordinary = decoded(Reply(document=OrdinaryResultDocument()))
        observed = c.select_receipts((decoded(Diagnostic()),), 42, ordinary)
        self.assertEqual(ExpectedBasisUnavailable(Uncertainty.RESPONSE_BASIS_UNAVAILABLE), observed.expectedBasis)
        self.assertEqual((), observed.counters)
        # Deliberately incomplete output: a foreign model/boundary basis is not a seed authority.
        missing = b'{"type":"qualified","document":{"impact_accounting":{"type":"INVESTIGATED","seeds":[{}]},"items":[{"path":{"steps":[{"connection":{"rule":{"target":{"site":{"enclosing":{"basis":{"type":"LIVE","root":"/foreign","host":"00000000-0000-0000-0000-000000000002","epoch":9}}}}}}}]}}]}}'
        self.assertEqual(ExpectedBasisUnavailable(Uncertainty.SEED_BASIS_UNAVAILABLE),
                         c.expected_seed_basis(json.loads(missing)))

    def test_log_rotation_and_overlimit_appends_are_finite_unavailable_observations(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / 'idea.log'
            log.write_bytes(b'old')
            before = log.stat()
            log.rename(Path(temporary) / 'previous.log')
            log.write_bytes(log_line(Diagnostic()))
            self.assertIn(Uncertainty.LOG_ROTATED, c.appended_receipts(log, before, 42, decoded(Reply())).uncertainty)
            before = log.stat()
            with log.open('ab') as stream:
                stream.write(b'x' * (c.MAX_APPEND + 1))
            self.assertIn(Uncertainty.LOG_APPEND_LIMIT, c.appended_receipts(log, before, 42, decoded(Reply())).uncertainty)

    def test_product_rejection_is_capture_data_and_explicit_environment_has_no_ambient_secret(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            args = self.args(root, encoded(Request()))
            calls = []
            def run(argv, **kwargs):
                calls.append((argv, kwargs))
                if len(calls) == 1:
                    self.assertEqual(['/bin/ps', '-p', '42', '-o', 'comm='], argv)
                    return subprocess.CompletedProcess(argv, 0, str(args.idea_home / 'MacOS/idea') + '\n', '')
                self.assertEqual((str(args.rpc), 'call', 'query_symbols'), argv)
                self.assertEqual(args.fixture, kwargs['cwd'])
                self.assertEqual(encoded(Request()), kwargs['input'])
                self.assertEqual(str(root / 'home'), kwargs['env']['HOME'])
                self.assertEqual(str(args.idea_home), kwargs['env']['KAST_INSTALL_IDEA_HOME'])
                self.assertEqual({'HOME', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_STATE_HOME', 'XDG_CACHE_HOME',
                                  'XDG_RUNTIME_DIR', 'JAVA_HOME', 'JAVA_OPTS', 'KAST_INSTALL_IDEA_HOME', 'PATH',
                                  'TMPDIR', 'LANG', 'LC_ALL', 'TZ'}, set(kwargs['env']))
                with args.idea_log.open('ab') as stream:
                    stream.write(log_line(Diagnostic()))
                return subprocess.CompletedProcess(argv, 0, encoded(replace(Reply(), type='rejected_document')), b'')
            result = c.invoke(args, run=run)
            self.assertIsInstance(result, Captured)
            self.assertEqual(RpcOutcome.PRODUCT_REJECTED, result.rpcOutcome)
            self.assertEqual(2, len(calls))
            self.assertEqual(Correlation.MATCHED, result.observations.correlation)
            self.assertEqual(encoded(Request()), (args.output_dir / 'request.json').read_bytes())
            self.assertEqual('CAPTURED', json.loads((args.output_dir / 'capture.json').read_text())['type'])

    def test_authored_fixture_manifest_records_text_intent_and_never_live_handles(self):
        source = c.REPO / 'experiments/host-observation/semantic-fixture/value-flow/RepresentationImpactFixture.kt'
        self.assertIsInstance(intent.verify_text(source), intent.SourceTextVerified)
        expected = json.loads((c.HERE / 'fixture-intent.expected.json').read_text())
        self.assertEqual(expected, json.loads(json.dumps(asdict(intent.manifest(source)))))
        self.assertEqual(4, len(expected['seedAnchors']))
        self.assertEqual(15, len(expected['namedUses']))
        self.assertEqual('SOURCE_TEXT_INTENT', expected['type'])
        self.assertNotIn('exactSymbol', json.dumps(expected))

    @staticmethod
    def args(root, request):
        for directory in ('home', 'fixture', 'idea', 'java/bin'):
            (root / directory).mkdir(parents=True, exist_ok=True)
        rpc = root / 'rpc'
        rpc.write_bytes(b'fixture executable')
        (root / 'java/bin/java').write_bytes(b'fixture java')
        log = root / 'idea.log'
        log.write_bytes(b'')
        path = root / 'request.json'
        path.write_bytes(request)
        return Namespace(owned_root=root, fixture=root / 'fixture', idea_home=root / 'idea', idea_pid=42,
                         idea_log=log, request=path, output_dir=root / 'capture', rpc=rpc,
                         java_home=root / 'java', timeout=10)


if __name__ == '__main__':
    unittest.main()
