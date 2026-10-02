"""Bounded helper regressions; no native application or real HOME effects."""
import hashlib
import importlib.util
import json
import os
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path
import sys
import subprocess
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "kast_native_helpers_under_test", Path(__file__).with_name("mixed_version_acceptance.py"))
NATIVE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = NATIVE
SPEC.loader.exec_module(NATIVE)


FRESH_READ_INSTRUCTION = (
    "The model or source changed during the read. Start a fresh read; "
    "a continuation from the old epoch cannot establish current evidence.")


@dataclass(frozen=True)
class RejectionRecovery:
    kind: str = "restart_read"
    instruction: str | None = FRESH_READ_INSTRUCTION


@dataclass(frozen=True)
class RejectionDocument:
    schemaVersion: int = 1
    outcome: str = "rejected"
    failure: str = "FRESHNESS_REJECTED"
    detail: str | dict | list = "MOVED"
    stage: str = "CONTENT_REVALIDATION"
    recovery: RejectionRecovery = field(default_factory=RejectionRecovery)


@dataclass(frozen=True)
class RejectionReply:
    document: RejectionDocument
    type: str = "rejected_document"


class NativeInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kast-native-helper-test-")
        self.addCleanup(self.temporary.cleanup)
        self.case = Path(self.temporary.name).resolve()
        self.root = self.case / "inventory"
        self.root.mkdir()

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def assert_limit(self, rejected, resource, maximum, observed):
        self.assertEqual(NATIVE.Failure.INVENTORY_LIMIT_EXCEEDED, rejected.failure)
        self.assertIsNone(rejected.exit_code)
        self.assertEqual(NATIVE.InventoryLimit(resource, maximum, observed), rejected.limit)
        expected_resource = {
            NATIVE.InventoryResource.TRAVERSED_ENTRIES: "TRAVERSED_ENTRIES",
            NATIVE.InventoryResource.PAYLOAD_BYTES: "PAYLOAD_BYTES",
        }[resource]
        report = NATIVE.Failed("REJECTED", os.getuid(), str(self.root), NATIVE.Stage.SNAPSHOT,
                               rejected.failure, rejected.exit_code, (), rejected.limit)
        self.assertEqual({
            "type": "REJECTED", "uid": os.getuid(), "owned_root": str(self.root),
            "stage": "SNAPSHOT", "failure": "INVENTORY_LIMIT_EXCEEDED", "exit_code": None,
            "cleanup_failures": [], "semantic": None, "limit": {
                "resource": expected_resource, "maximum": maximum, "observed_at_least": observed,
            },
        }, json.loads(NATIVE.encode(report)))

    def test_identity_uses_whole_relative_strings_and_only_immutable_files(self):
        self.write("bin/a-", b"left")
        self.write("bin/a/z", b"right")
        (self.root / "lib").mkdir()
        (self.root / "share").mkdir()
        self.write("installation.json", b"receipt-one")
        self.write("config/settings.json", b"saved-config")
        self.write("state/readiness.json", b"runtime-observation")
        self.write(".kast-control-sha256", b"archive-marker")
        # Whole strings order a- before a/z; Path component ordering reverses them.
        # The literal suffix is an independent identity oracle, not an inventory fold.
        expected = "sha256:" + hashlib.sha256(
            str(self.root).encode() + b"\0bin/a-\0left\0bin/a/z\0right").hexdigest()
        self.assertEqual(expected, NATIVE.runtime_installation_identity(self.root))
        self.write("installation.json", b"different-receipt")
        self.write("config/settings.json", b"different-config")
        self.write("state/readiness.json", b"different-runtime")
        self.write(".kast-control-sha256", b"different-marker")
        self.assertEqual(expected, NATIVE.runtime_installation_identity(self.root))

    def test_inventory_admits_both_exact_resource_limits(self):
        self.write("branch/a", b"ab")
        self.write("z", b"cd")
        with patch.multiple(NATIVE, SNAPSHOT_MAXIMUM_ENTRIES=3, SNAPSHOT_MAXIMUM_BYTES=4):
            facts = NATIVE.files(self.root)
        self.assertEqual(("branch/a", "z"), tuple(fact.relative_path for fact in facts))
        self.assertEqual((2, 2), tuple(fact.size for fact in facts))
        self.assertEqual((hashlib.sha256(b"ab").hexdigest(), hashlib.sha256(b"cd").hexdigest()),
                         tuple(fact.sha256 for fact in facts))
        self.assertTrue(all(fact.owner == os.getuid() for fact in facts))

    def test_entry_limit_rejection_retains_encoded_lower_bound(self):
        self.write("branch/empty", b"")
        with patch.multiple(NATIVE, SNAPSHOT_MAXIMUM_ENTRIES=1, SNAPSHOT_MAXIMUM_BYTES=4):
            with self.assertRaises(NATIVE.Rejected) as failure:
                NATIVE.files(self.root)
        self.assert_limit(failure.exception, NATIVE.InventoryResource.TRAVERSED_ENTRIES, 1, 2)

    def test_payload_limit_rejects_before_digest_and_retains_encoded_lower_bound(self):
        self.write("payload", b"abc")
        with patch.multiple(NATIVE, SNAPSHOT_MAXIMUM_ENTRIES=2, SNAPSHOT_MAXIMUM_BYTES=2):
            with patch.object(NATIVE, "digest", side_effect=AssertionError("over-limit payload read")) as digest:
                with self.assertRaises(NATIVE.Rejected) as failure:
                    NATIVE.files(self.root)
                digest.assert_not_called()
        self.assert_limit(failure.exception, NATIVE.InventoryResource.PAYLOAD_BYTES, 2, 3)

    def test_payload_limit_counts_aggregate_bytes_before_reading_crossing_file(self):
        self.write("first", b"ab")
        self.write("second", b"cd")
        with patch.multiple(NATIVE, SNAPSHOT_MAXIMUM_ENTRIES=2, SNAPSHOT_MAXIMUM_BYTES=3):
            with patch.object(NATIVE, "digest", wraps=NATIVE.digest) as digest:
                with self.assertRaises(NATIVE.Rejected) as failure:
                    NATIVE.files(self.root)
                self.assertEqual(1, digest.call_count)
        self.assert_limit(failure.exception, NATIVE.InventoryResource.PAYLOAD_BYTES, 3, 4)

    def test_symlink_rejects_ownership_without_reading_or_changing_target(self):
        target = self.case / "protected"
        target.write_bytes(b"protected")
        (self.root / "link").symlink_to(target)
        with patch.object(NATIVE, "digest", side_effect=AssertionError("symlink target read")) as digest:
            with self.assertRaises(NATIVE.Rejected) as failure:
                NATIVE.files(self.root)
            digest.assert_not_called()
        self.assertEqual(NATIVE.Failure.OWNERSHIP_REJECTED, failure.exception.failure)
        self.assertIsNone(failure.exception.limit)
        self.assertEqual(b"protected", target.read_bytes())

    def test_nonregular_entry_rejects_ownership_without_blocking_read(self):
        os.mkfifo(self.root / "fifo")
        with patch.object(NATIVE, "digest", side_effect=AssertionError("FIFO read")) as digest:
            with self.assertRaises(NATIVE.Rejected) as failure:
                NATIVE.files(self.root)
            digest.assert_not_called()
        self.assertEqual(NATIVE.Failure.OWNERSHIP_REJECTED, failure.exception.failure)
        self.assertIsNone(failure.exception.limit)

    def test_empty_inventory_rejects_ownership(self):
        with self.assertRaises(NATIVE.Rejected) as failure:
            NATIVE.files(self.root)
        self.assertEqual(NATIVE.Failure.OWNERSHIP_REJECTED, failure.exception.failure)
        self.assertIsNone(failure.exception.limit)


class NativeFailureEncodingTest(unittest.TestCase):
    def test_recovery_report_preserves_original_inventory_failure_and_limit(self):
        report = NATIVE.Failed(
            "RECOVERY_REQUIRED", 1001, "/owned-evidence", NATIVE.Stage.SNAPSHOT,
            NATIVE.Failure.INVENTORY_LIMIT_EXCEEDED, None,
            (NATIVE.Failure.RESTORATION_UNVERIFIED,),
            NATIVE.InventoryLimit(NATIVE.InventoryResource.TRAVERSED_ENTRIES, 3, 4))
        self.assertEqual({
            "type": "RECOVERY_REQUIRED", "uid": 1001, "owned_root": "/owned-evidence",
            "stage": "SNAPSHOT", "failure": "INVENTORY_LIMIT_EXCEEDED", "exit_code": None,
            "cleanup_failures": ["RESTORATION_UNVERIFIED"],
            "limit": {"resource": "TRAVERSED_ENTRIES", "maximum": 3, "observed_at_least": 4},
            "semantic": None,
        }, json.loads(NATIVE.encode(report)))


class NativeFreshReadPolicyTest(unittest.TestCase):
    def rejection(self, document):
        return NATIVE.semantic_rejection(asdict(RejectionReply(document)), "/owned-evidence/reply.json")

    def test_exact_observed_freshness_rejection_admits_fresh_read_and_preserves_evidence(self):
        observed = self.rejection(RejectionDocument())
        self.assertTrue(NATIVE.restart_fresh_read(observed))
        report = NATIVE.Failed("REJECTED", 1001, "/owned-evidence", NATIVE.Stage.QUERY_C1_P1,
                               NATIVE.Failure.SEMANTIC_QUERY_REJECTED, None, (), semantic=observed)
        self.assertEqual({
            "type": "REJECTED", "uid": 1001, "owned_root": "/owned-evidence",
            "stage": "QUERY_C1_P1", "failure": "SEMANTIC_QUERY_REJECTED", "exit_code": None,
            "cleanup_failures": [], "limit": None, "semantic": {
                "failure": "FRESHNESS_REJECTED", "detail": "MOVED", "stage": "CONTENT_REVALIDATION",
                "recovery": "restart_read", "instruction": FRESH_READ_INSTRUCTION,
                "reply_file": "/owned-evidence/reply.json",
            },
        }, json.loads(NATIVE.encode(report)))

    def test_model_capture_movement_with_exact_restart_instruction_admits_fresh_read(self):
        observed = self.rejection(RejectionDocument(stage="MODEL_CAPTURE"))
        self.assertTrue(NATIVE.restart_fresh_read(observed))
        self.assertEqual(NATIVE.HostedStage.MODEL_CAPTURE, observed.stage)
        self.assertEqual("/owned-evidence/reply.json", observed.reply_file)

    def test_every_other_closed_stage_rejects_fresh_read(self):
        permitted = {"MODEL_CAPTURE", "CONTENT_REVALIDATION"}
        for stage in NATIVE.HostedStage:
            if stage.value not in permitted:
                with self.subTest(stage=stage.value):
                    self.assertFalse(NATIVE.restart_fresh_read(self.rejection(RejectionDocument(stage=stage.value))))

    def test_other_closed_failure_detail_stage_recovery_and_instruction_do_not_admit_restart(self):
        exact = RejectionDocument()
        for rejected in (
            replace(exact, failure="BUDGET_EXCEEDED"),
            replace(exact, detail="UNKNOWN"),
            replace(exact, detail=["MOVED"]),
            replace(exact, detail={"detail": "MOVED"}),
            replace(exact, stage="SEMANTIC_READ"),
            replace(exact, recovery=RejectionRecovery(kind="after_state_change")),
            replace(exact, recovery=RejectionRecovery(instruction=None)),
            replace(exact, recovery=RejectionRecovery(instruction="Start a fresh read.")),
        ):
            with self.subTest(document=rejected):
                observed = self.rejection(rejected)
                self.assertFalse(NATIVE.restart_fresh_read(observed))
                self.assertEqual(rejected.detail, observed.detail)

    def test_unknown_or_absent_boundary_evidence_rejects_without_semantic_authority(self):
        exact = RejectionDocument()
        incompatible = [asdict(RejectionReply(document)) for document in (
            replace(exact, failure="UNKNOWN_FAILURE"),
            replace(exact, stage="UNKNOWN_STAGE"),
            replace(exact, recovery=RejectionRecovery(kind="unknown_recovery")),
        )]
        # Deliberately malformed boundary documents prove missing required facts are rejected.
        for missing in ("failure", "detail", "stage", "recovery"):
            document = asdict(RejectionReply(exact))
            del document["document"][missing]
            incompatible.append(document)
        for document in incompatible:
            with self.subTest(document=document):
                with self.assertRaises(NATIVE.Rejected) as failure:
                    NATIVE.semantic_rejection(document, "/owned-evidence/reply.json")
                self.assertEqual(NATIVE.Failure.SEMANTIC_QUERY_REJECTED, failure.exception.failure)
                self.assertIsNone(failure.exception.semantic)

    def test_absent_restart_instruction_retains_missing_fact_without_admitting_restart(self):
        reply = asdict(RejectionReply(RejectionDocument()))
        del reply["document"]["recovery"]["instruction"]
        observed = NATIVE.semantic_rejection(reply, "/owned-evidence/reply.json")
        self.assertIsNone(observed.instruction)
        self.assertFalse(NATIVE.restart_fresh_read(observed))


class NativeFreshReadCapTest(unittest.TestCase):
    def test_three_moved_reads_stop_with_exact_final_failure_and_all_retained_rejections(self):
        with tempfile.TemporaryDirectory(prefix="kast-native-cap-test-") as temporary:
            for stage in ("MODEL_CAPTURE", "CONTENT_REVALIDATION"):
                with self.subTest(stage=stage):
                    root = Path(temporary).resolve() / stage
                    (root / "logs").mkdir(parents=True)
                    proof = object.__new__(NATIVE.NativeProof)
                    proof.args = SimpleNamespace(readiness_seconds=1)
                    proof.root = root
                    proof.stage = NATIVE.Stage.QUERY_C2_P2
                    proof.installation = root / "installation-address-only"
                    proof.readiness_status = "admission_ready"
                    observed_host = NATIVE.HostProof(123, "owned-start", "owned-host", "0.49.1", str(root), 1, 2, "0" * 64)
                    calls, observations = [], []

                    def host():
                        self.assertLess(len(observations), 4, "excess host observation")
                        observations.append(observed_host)
                        return observed_host

                    def command(arguments, **options):
                        self.assertLess(len(calls), 3, "excess semantic command")
                        self.assertEqual([proof.installation / "bin/kast-tool-rpc-complete", "call", "query_symbols"], arguments)
                        self.assertEqual("RUN", json.loads(options["input_text"])["request"]["type"])
                        calls.append(arguments)
                        proof.last_stdout_log = str(root / "logs" / f"reply-{len(calls)}.json")
                        return subprocess.CompletedProcess(arguments, 0,
                            stdout=json.dumps(asdict(RejectionReply(RejectionDocument(stage=stage)))))

                    proof.host = host
                    proof.command = command
                    with patch.object(NATIVE.time, "sleep", side_effect=AssertionError("unexpected semantic sleep")):
                        with self.assertRaises(NATIVE.Rejected) as rejected:
                            proof.query("0.50.3", observed_host.host)
                    self.assertEqual(3, len(calls), "unconsumed semantic commands")
                    self.assertEqual(4, len(observations), "unconsumed host observations")
                    self.assertEqual(NATIVE.Failure.SEMANTIC_QUERY_REJECTED, rejected.exception.failure)
                    self.assertEqual(NATIVE.HostedStage(stage), rejected.exception.semantic.stage)
                    self.assertEqual(str(root / "logs/reply-3.json"), rejected.exception.semantic.reply_file)
                    retained = sorted((root / "logs").glob("query_c2_p2-rejection-*.json"))
                    self.assertEqual(3, len(retained))
                    self.assertEqual([stage] * 3, [json.loads(path.read_text())["stage"] for path in retained])


if __name__ == "__main__":
    unittest.main()
