#!/usr/bin/env python3
import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import release_semantic_corruption as corruption


class SemanticCorruptionTest(unittest.TestCase):
    def test_public_cache_identity_preserves_its_sha256_prefix(self):
        host = SimpleNamespace(workspace=Path("/workspace"))
        document = {"command": "status", "status": "complete", "runtime": "running", "root": "/workspace",
                    "runtimeId": "sha256:" + "b" * 64, "cache": {"identity": "sha256:" + "a" * 64}}
        self.assertEqual("sha256:" + "a" * 64, corruption.running_status(document, host))

    def test_generic_failure_cannot_prove_finite_corruption_rejection(self):
        result = subprocess.CompletedProcess([], 1, "", "unrelated failure")
        with self.assertRaises(corruption.SemanticCorruptionFailure):
            corruption.admit_boundary_rejection(result, "runtime", "status-cache-invalid-identity", 4)

    def test_wrong_channel_reason_code_or_multiple_documents_reject(self):
        document = {"status": "rejected", "boundary": "runtime", "reason": "status-cache-invalid-identity"}
        raw = json.dumps(document)
        for result in [subprocess.CompletedProcess([], 4, raw, ""),
                       subprocess.CompletedProcess([], 0, "", raw),
                       subprocess.CompletedProcess([], 4, "", raw + raw),
                       subprocess.CompletedProcess([], 4, "", json.dumps({**document, "reason": "runtime-not-running"})),
                       subprocess.CompletedProcess([], 4, "", json.dumps({**document, "diagnostic": "foreign"}))]:
            with self.assertRaises(corruption.SemanticCorruptionFailure):
                corruption.admit_boundary_rejection(result, "runtime", "status-cache-invalid-identity", 4)

    def test_usage_rejection_requires_exact_continuation_family(self):
        document = {"status": "rejected", "boundary": "usage", "reason": "arguments-rejected",
                    "diagnostic": "Usage: kast relation read\n\nError: --continuation must be one intact relation continuation token"}
        result = subprocess.CompletedProcess([], 2, "", json.dumps(document))
        corruption.admit_boundary_rejection(result, "usage", "arguments-rejected", 2,
            "--continuation must be one intact relation continuation token")
        with self.assertRaises(corruption.SemanticCorruptionFailure):
            corruption.admit_boundary_rejection(result, "usage", "arguments-rejected", 2,
                "--continuation must be one intact traversal continuation token")

    def test_returned_continuation_requires_payload_digest_and_resumable_shape(self):
        token = continuation("relation")
        good = {"operation": "relation.read", "status": "qualified", "qualification": {"type": "resumable", "continuation": token}}
        self.assertEqual(token, corruption.admitted_continuation(good, corruption.Family.RELATION))
        for changed in [{**good, "status": "complete"},
                        {**good, "qualification": {"type": "terminal_incomplete"}},
                        {**good, "qualification": {"type": "resumable", "continuation": token[:-1] + ("0" if token[-1] != "0" else "1")}}]:
            with self.assertRaises(corruption.SemanticCorruptionFailure):
                corruption.admitted_continuation(changed, corruption.Family.RELATION)

    def test_success_proves_four_tokens_and_exact_state_restoration_without_source_payload(self):
        with Fixture() as fixture:
            before = fixture.path.read_bytes()
            mode = stat.S_IMODE(fixture.path.stat().st_mode)
            with mock.patch.object(corruption, "rejected_command", side_effect=fixture.rejected):
                proof = corruption.prove_semantic_corruption(fixture, fixture.host)
            self.assertEqual("passed", proof["status"])
            self.assertEqual(4, len(proof["continuations"]))
            self.assertEqual(2, fixture.valid_resumes)
            self.assertEqual(before, fixture.path.read_bytes())
            self.assertEqual(mode, stat.S_IMODE(fixture.path.stat().st_mode))
            self.assertEqual(proof["stateReceipt"]["originalReceiptDigest"], proof["stateReceipt"]["restoredReceiptDigest"])
            self.assertEqual(proof["workspaceDigestBefore"], proof["workspaceDigestAfter"])
            self.assertNotIn("private source payload", json.dumps(proof))
            self.assertNotIn(continuation("relation"), json.dumps(proof))

    def test_receipt_is_restored_even_when_the_expected_rejection_is_absent(self):
        with Fixture("wrong-rejection") as fixture:
            original = fixture.path.read_bytes()
            with mock.patch.object(corruption, "rejected_command", side_effect=fixture.rejected):
                with self.assertRaises(corruption.SemanticCorruptionFailure) as raised:
                    corruption.prove_semantic_corruption(fixture, fixture.host)
            self.assertEqual(corruption.Cause.REJECTION_UNPROVEN, raised.exception.cause)
            self.assertEqual(original, fixture.path.read_bytes())
            self.assertEqual(0o640, stat.S_IMODE(fixture.path.stat().st_mode))

    def test_workspace_change_rejects_and_restores_receipt(self):
        with Fixture("workspace") as fixture:
            original = fixture.path.read_bytes()
            with mock.patch.object(corruption, "rejected_command", side_effect=fixture.rejected):
                with self.assertRaises(corruption.SemanticCorruptionFailure) as raised:
                    corruption.prove_semantic_corruption(fixture, fixture.host)
            self.assertEqual(corruption.Cause.WORKSPACE_CHANGED, raised.exception.cause)
            self.assertEqual(original, fixture.path.read_bytes())

    def test_selected_path_rejects_symlinks_or_foreign_environment(self):
        with Fixture() as fixture:
            fixture.environment["KAST_CACHE_ROOT"] = "/foreign"
            with self.assertRaises(corruption.SemanticCorruptionFailure):
                corruption.selected_receipt(fixture, fixture.host, "sha256:" + "a" * 64)
            fixture.environment["KAST_CACHE_ROOT"] = str(fixture.host.runtime / "intellij-caches")
            target = fixture.path.with_name("unselected.properties")
            fixture.path.rename(target)
            fixture.path.symlink_to(target)
            before = target.read_bytes()
            with self.assertRaises(corruption.SemanticCorruptionFailure):
                corruption.selected_receipt(fixture, fixture.host, "sha256:" + "a" * 64)
            self.assertEqual(before, target.read_bytes())


def continuation(family):
    payload = b'{"cursor":"fixture"}'
    return family + "-continuation:v1:" + base64.urlsafe_b64encode(payload).decode().rstrip("=") + ":" + hashlib.sha256(payload).hexdigest()


class Fixture:
    def __init__(self, damage=None):
        self.temporary = tempfile.TemporaryDirectory()
        root = Path(self.temporary.name).resolve()
        self.host = SimpleNamespace(root=root, runtime=root / "runtime", workspace=root / "workspace")
        self.host.workspace.mkdir()
        (self.host.workspace / "Example.kt").write_text("private source payload\n")
        for arguments in [("init", "--quiet"), ("add", "."), ("-c", "user.name=Fixture", "-c", "user.email=fixture@kast.invalid", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "fixture")]:
            subprocess.run(["git", *arguments], cwd=self.host.workspace, check=True, capture_output=True)
        self.workspace = self.host.workspace
        self.environment = {"KAST_CACHE_ROOT": str(self.host.runtime / "intellij-caches")}
        self.path = self.host.runtime / "intellij-caches" / ("sha256:" + "a" * 64) / "cache-identity.properties"
        self.path.parent.mkdir(parents=True)
        self.path.write_text("format=kast.sidecar-cache.identity.v3\nproject.root=" + str(self.workspace) + "\n")
        self.path.chmod(0o640)
        self.damage = damage
        self.valid_resumes = 0
        self.maximum_startup_seconds = 60

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.temporary.cleanup()

    def resolve_symbols(self, query, limit):
        return {"exact-selector-fixture": {"name": "enterpriseRootOperation"}}

    def command(self, *arguments, **options):
        if arguments[0] == "status":
            assert b"format=kast.sidecar-cache.identity.v3" in self.path.read_bytes(), "status observed a receipt that was not restored"
            return {"command": "status", "status": "complete", "runtime": "running", "runtimeId": "sha256:" + "b" * 64,
                    "root": str(self.workspace), "cache": {"identity": "sha256:" + "a" * 64, "state": "warm"}}
        if arguments[0] == "source":
            return {"operation": "source.read", "status": "complete", "text": "private source payload"}
        if arguments[0] == "topology":
            return {"operation": "topology.build", "status": "complete"}
        operation = "relation.read" if arguments[0] == "relation" else "traversal.run"
        if "--continuation" in arguments:
            assert arguments[-1] == continuation(arguments[0])
            self.valid_resumes += 1
            return {"operation": operation, "status": "complete"}
        return {"operation": operation, "status": "qualified", "qualification": {"type": "resumable", "continuation": continuation(arguments[0])}}

    def rejected(self, acceptance, arguments):
        if arguments == ["status"]:
            assert self.path.read_bytes() == b"format=corrupted-runtime-identity\n"
            if self.damage == "workspace":
                (self.workspace / "Example.kt").write_text("changed\n")
            reason = "runtime-not-running" if self.damage == "wrong-rejection" else "status-cache-invalid-identity"
            return subprocess.CompletedProcess([], 4, "", json.dumps({"status": "rejected", "boundary": "runtime", "reason": reason}))
        family = arguments[0]
        assert arguments[-1] != continuation(family)
        return subprocess.CompletedProcess([], 2, "", json.dumps({"status": "rejected", "boundary": "usage", "reason": "arguments-rejected",
            "diagnostic": f"Usage: kast {family}\n\nError: --continuation must be one intact {family} continuation token"}))


if __name__ == "__main__":
    unittest.main()
