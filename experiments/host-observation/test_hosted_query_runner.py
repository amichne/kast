import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from run_hosted_query import AcceptanceCase, AcceptanceResult, CarrierOutcome, EvidenceFailure, EvidenceRejected, observe_carrier, restore_project, run, validate_case


class HostedAcceptanceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.evidence = Path(self.directory.name)
        (self.evidence / "retired.txt").write_text("RETIRED")
        self.identity = dict(hostPid=123, sameProject=True, originalProjectDisposed=False,
                             newProjects=0, readLockDuringTransport=False, modelBefore=[1], modelAfter=[1])

    def test_restoration_never_launches_when_original_host_is_lost(self):
        with patch("run_hosted_query.subprocess.check_output", return_value="456 /idea/MacOS/idea\n"), \
             patch("run_hosted_query.subprocess.run") as launch:
            with self.assertRaisesRegex(RuntimeError, "ORIGINAL_HOST_LOST"):
                restore_project(Path("/idea/MacOS/idea"), Path("/workspace"), self.evidence, 123)
            launch.assert_not_called()

    def test_cancelled_result_requires_checkpoint_and_last_observed_stage(self):
        answer = dict(outcome="rejected", failure="CANCELLED", stage="REQUEST_ADMISSION")
        self.assertEqual(EvidenceRejected(EvidenceFailure.UNAVAILABLE),
                         validate_case(AcceptanceCase.CANCEL, answer, self.identity, self.evidence, 123))
        (self.evidence / "checkpoint.txt").write_text("SEMANTIC_READ_DETACHED")
        self.assertEqual(EvidenceRejected(EvidenceFailure.UNEXPECTED_RESULT),
                         validate_case(AcceptanceCase.CANCEL, answer, self.identity, self.evidence, 123))
        answer["stage"] = "CONTENT_REVALIDATION"
        self.assertEqual(AcceptanceResult.VERIFIED, validate_case(AcceptanceCase.CANCEL, answer, self.identity, self.evidence, 123))

    def test_edit_proof_rejects_publication_and_requires_restoration(self):
        (self.evidence / "checkpoint.txt").write_text("SEMANTIC_READ_DETACHED")
        answer = dict(outcome="published", stage="RESULT_DETACHED")
        self.assertEqual(EvidenceRejected(EvidenceFailure.UNEXPECTED_RESULT),
                         validate_case(AcceptanceCase.EDIT, answer, self.identity, self.evidence, 123))
        answer = dict(outcome="rejected", failure="FRESHNESS_REJECTED", detail="MOVED", stage="CONTENT_REVALIDATION")
        self.assertEqual(EvidenceRejected(EvidenceFailure.UNAVAILABLE),
                         validate_case(AcceptanceCase.EDIT, answer, self.identity, self.evidence, 123))
        (self.evidence / "edit-cleanup.txt").write_text("RESTORED")
        self.assertEqual(AcceptanceResult.VERIFIED, validate_case(AcceptanceCase.EDIT, answer, self.identity, self.evidence, 123))

    def test_project_close_requires_original_project_disposal_and_no_substitution(self):
        (self.evidence / "checkpoint.txt").write_text("SEMANTIC_READ_DETACHED")
        answer = dict(outcome="rejected", failure="RETIRED", stage="CONTENT_REVALIDATION")
        self.assertEqual(EvidenceRejected(EvidenceFailure.PROJECT_MISMATCH),
                         validate_case(AcceptanceCase.PROJECT_CLOSE, answer, self.identity, self.evidence, 123))
        self.identity.update(sameProject=False, originalProjectDisposed=True, modelAfter=[])
        self.assertEqual(AcceptanceResult.VERIFIED, validate_case(AcceptanceCase.PROJECT_CLOSE, answer, self.identity, self.evidence, 123))
        self.identity["newProjects"] = 1
        self.assertEqual(EvidenceRejected(EvidenceFailure.SUBSTITUTE_PROJECT),
                         validate_case(AcceptanceCase.PROJECT_CLOSE, answer, self.identity, self.evidence, 123))

    def test_plugin_result_requires_confirmed_unload_and_no_loaded_plugin(self):
        (self.evidence / "plugin-loaded.txt").write_text("LOADED")
        answer = dict(outcome="published")
        for value in ['{"unloaded":false,"stillLoaded":false}', '{"unloaded":true,"stillLoaded":true}',
                      '{"unloaded":1,"stillLoaded":0}']:
            (self.evidence / "plugin-unloaded.json").write_text(value)
            self.assertEqual(EvidenceRejected(EvidenceFailure.PLUGIN_UNLOAD_UNCONFIRMED),
                             validate_case(AcceptanceCase.PLUGIN_LIFECYCLE, answer, self.identity, self.evidence, 123))
        (self.evidence / "plugin-unloaded.json").write_text('{"unloaded":true,"stillLoaded":false}')
        self.assertEqual(AcceptanceResult.VERIFIED, validate_case(AcceptanceCase.PLUGIN_LIFECYCLE, answer, self.identity, self.evidence, 123))

    def test_query_requires_exact_identity_and_transport_evidence(self):
        answer = dict(outcome="published")
        for field, invalid, failure in (
            ("hostPid", 456, EvidenceFailure.HOST_MISMATCH),
            ("sameProject", 1, EvidenceFailure.PROJECT_MISMATCH),
            ("originalProjectDisposed", None, EvidenceFailure.PROJECT_MISMATCH),
            ("newProjects", False, EvidenceFailure.SUBSTITUTE_PROJECT),
            ("readLockDuringTransport", None, EvidenceFailure.TRANSPORT_READ_LOCK),
            ("modelAfter", [2], EvidenceFailure.MODEL_CHANGED),
        ):
            with self.subTest(field=field):
                identity = self.identity | {field: invalid}
                self.assertEqual(EvidenceRejected(failure),
                                 validate_case(AcceptanceCase.QUERY, answer, identity, self.evidence, 123))
        self.assertEqual(AcceptanceResult.VERIFIED,
                         validate_case(AcceptanceCase.QUERY, answer, self.identity, self.evidence, 123))
        self.assertEqual(AcceptanceResult.QUERY_REJECTED,
                         validate_case(AcceptanceCase.QUERY, dict(outcome="rejected"), self.identity, self.evidence, 123))

    def test_incomplete_or_unknown_evidence_is_a_typed_rejection(self):
        for answer, identity, failure in (
            (dict(outcome="unknown"), self.identity, EvidenceFailure.UNEXPECTED_RESULT),
            (dict(outcome="published"), {}, EvidenceFailure.MALFORMED),
            (None, self.identity, EvidenceFailure.MALFORMED),
        ):
            with self.subTest(answer=answer, identity=identity):
                self.assertEqual(EvidenceRejected(failure),
                                 validate_case(AcceptanceCase.QUERY, answer, identity, self.evidence, 123))

    def test_report_preserves_evidence_rejection_in_exit_status_and_retirement_claim(self):
        idea = self.evidence / "idea"
        (idea / "Resources").mkdir(parents=True)
        (idea / "Resources/product-info.json").write_text('{"buildNumber":"262.1"}')
        payload = io.BytesIO()
        with zipfile.ZipFile(payload, "w") as jar:
            jar.writestr("META-INF/plugin.xml", '<idea-plugin><idea-version since-build="262.1" until-build="262.1"/></idea-plugin>')
        artifact = self.evidence / "plugin.zip"
        with zipfile.ZipFile(artifact, "w") as archive:
            archive.writestr("kast-hosted-query/lib/kast-hosted-query-0.1.0.jar", payload.getvalue())
            for name in ("kernel", "workspace-contract", "symbol-contract", "contract"):
                archive.writestr(f"kast-hosted-query/lib/{name}-1.jar", b"")

        for reported_pid, expected_exit, acceptance in (
            (123, 0, "verified"), (456, 1, "evidence_rejected"), (None, 1, "carrier_rejected"),
        ):
            with self.subTest(reported_pid=reported_pid):
                output = self.evidence / str(reported_pid)
                output.mkdir()
                answer = dict(schemaVersion=1, outcome="rejected", failure="CANCELLED", detail={}, stage="CONTENT_REVALIDATION")

                def complete_query(*args, **kwargs):
                    if reported_pid is None:
                        return
                    (output / "script-entered.txt").write_text("ENTERED")
                    (output / "result.json").write_text(json.dumps(answer))
                    (output / "identity.json").write_text(json.dumps(self.identity | {"hostPid": reported_pid}))
                    (output / "checkpoint.txt").write_text("SEMANTIC_READ_DETACHED")
                    (output / "retired.txt").write_text("RETIRED")

                with patch("run_hosted_query.tempfile.mkdtemp", return_value=str(output)), \
                     patch("run_hosted_query.subprocess.check_output", return_value=f"123 {idea}/MacOS/idea\n"), \
                     patch("run_hosted_query.subprocess.run", side_effect=complete_query), \
                     patch("run_hosted_query.time.monotonic", side_effect=[0, 61]), \
                     contextlib.redirect_stdout(io.StringIO()):
                    exit_code = run(idea, artifact, self.evidence, "Fixture.kt", 0, AcceptanceCase.CANCEL)
                self.assertEqual(expected_exit, exit_code)
                report = json.loads((output / "report.json").read_text())
                carrier_outcome = "SCRIPT_ENTRY_UNCONFIRMED" if reported_pid is None else "COMPLETED"
                self.assertEqual({"schemaVersion": 1, "outcome": carrier_outcome},
                                 json.loads((output / "carrier.json").read_text()))
                self.assertEqual(acceptance, report["acceptance"])
                if reported_pid == 123:
                    self.assertEqual("RETIRED", report["retirement"])
                else:
                    self.assertEqual("SCRIPT_ENTRY_UNCONFIRMED" if reported_pid is None else "HOST_MISMATCH", report["failure"])
                    self.assertNotIn("retirement", report)

    def test_carrier_observation_distinguishes_script_execution_and_retirement(self):
        (self.evidence / "retired.txt").unlink()
        self.assertEqual(CarrierOutcome.SCRIPT_ENTRY_UNCONFIRMED, observe_carrier(self.evidence))
        (self.evidence / "script-entered.txt").write_text("ENTERED")
        self.assertEqual(CarrierOutcome.RESULT_UNCONFIRMED, observe_carrier(self.evidence))
        (self.evidence / "result.json").write_text("{}")
        (self.evidence / "identity.json").write_text("{}")
        self.assertEqual(CarrierOutcome.RETIREMENT_UNCONFIRMED, observe_carrier(self.evidence))
        (self.evidence / "retired.txt").write_text("RETIRED")
        self.assertEqual(CarrierOutcome.COMPLETED, observe_carrier(self.evidence))
        (self.evidence / "bootstrap-failure.json").write_text("{}")
        self.assertEqual(CarrierOutcome.BOOTSTRAP_REJECTED, observe_carrier(self.evidence))


class OptimizedEvidenceTest(unittest.TestCase):
    def test_optimization_preserves_acceptance_checks(self):
        result = subprocess.run(
            [sys.executable, "-O", "-m", "unittest", "test_hosted_query_runner.HostedAcceptanceEvidenceTest"],
            cwd=Path(__file__).resolve().parent, capture_output=True, text=True, timeout=15,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
