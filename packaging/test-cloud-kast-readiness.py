"""Cloud handoff needs an expected complete compiler answer, not installation metadata."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from dataclasses import asdict, dataclass, field
from pathlib import Path

spec = importlib.util.spec_from_file_location("cloud_kast_readiness", Path(__file__).parent / "cloud/verify-kast.py")
assert spec is not None and spec.loader is not None
readiness = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = readiness
spec.loader.exec_module(readiness)


@dataclass
class Coverage:
    exhaustive: bool = True


@dataclass
class Summary:
    failure_count: int = 0
    omission_count: int = 0


@dataclass
class Signature:
    qualifiedIdentity: str = "fixture.OrderService"


@dataclass
class Symbol:
    type: str = "exact-symbol"
    signature: Signature = field(default_factory=Signature)


@dataclass
class Completion:
    type: str = "COMPLETE_ONLY"
    model: str = "COMPILER_RESOLVED_STATIC_V1"


@dataclass
class Question:
    completion: Completion = field(default_factory=Completion)


@dataclass
class Document:
    status: str = "complete"
    coverage: Coverage = field(default_factory=Coverage)
    original_evidence_summary: Summary = field(default_factory=Summary)
    items: list[Symbol] = field(default_factory=lambda: [Symbol()])
    question: Question = field(default_factory=Question)


@dataclass
class Complete:
    type: str = "complete"
    document: Document = field(default_factory=Document)


@dataclass
class Preparation:
    type: str = "ready"


@dataclass
class Prepared:
    preparation: Preparation = field(default_factory=Preparation)


class CloudKastReadinessTest(unittest.TestCase):
    def test_complete_expected_symbol_is_admitted(self):
        self.assertEqual(
            readiness.VerifiedSymbol("fixture.OrderService"),
            readiness.complete_symbol(asdict(Complete()), "fixture.OrderService"),
        )

    def test_incomplete_failed_omitted_and_boolean_counts_do_not_prove_readiness(self):
        variants = [
            Complete(type="partial"),
            Complete(document=Document(status="partial")),
            Complete(document=Document(coverage=Coverage(False))),
            Complete(document=Document(original_evidence_summary=Summary(failure_count=1))),
            Complete(document=Document(original_evidence_summary=Summary(omission_count=1))),
            Complete(document=Document(original_evidence_summary=Summary(failure_count=False))),
            Complete(document=Document(question=Question(Completion(model="TEXT_SEARCH")))),
        ]
        for variant in variants:
            with self.subTest(variant=variant):
                self.assertEqual(readiness.Failure.INCOMPLETE, readiness.complete_symbol(asdict(variant), "fixture.OrderService"))

    def test_missing_ambiguous_or_wrong_exact_declarations_reject(self):
        for rows in ([], [Symbol(), Symbol()], [Symbol(signature=Signature("other.OrderService"))]):
            self.assertEqual(
                readiness.Failure.UNEXPECTED_SYMBOL,
                readiness.complete_symbol(asdict(Complete(document=Document(items=rows))), "fixture.OrderService"),
            )
        malformed = asdict(Complete())
        del malformed["document"]["coverage"]
        self.assertEqual(readiness.Failure.INCOMPLETE, readiness.complete_symbol(malformed, "fixture.OrderService"))

    def test_handoff_waits_before_one_scoped_query_and_consumes_every_observation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, workspace = self.layout(Path(temporary))
            observations = [
                readiness.CommandResult(0, json.dumps(asdict(Prepared()))),
                readiness.CommandResult(0, json.dumps(asdict(Complete()))),
            ]
            commands = []
            def executor(argv, cwd, timeout):
                self.assertEqual(workspace, cwd)
                self.assertIn(timeout, (31, 90))
                commands.append(argv)
                self.assertTrue(observations, "Unexpected child command")
                return observations.pop(0)
            self.assertEqual(
                readiness.Ready(str(workspace), "fixture.OrderService"),
                self.invoke(root, workspace, executor),
            )
            self.assertEqual([], observations)
            self.assertEqual(
                (str(root / "bin/kast"), "workspace", "wait", "--workspace", str(workspace), "--wait", "1000"),
                commands[0],
            )
            self.assertEqual(
                (str(root / "bin/kast"), "query", "--workspace", str(workspace), "--name", "OrderService", "--in", "orders/src/main", "--source-set", "main", "--fields", "name,signature,location", "--max-results", "2", "--max-returned-bytes", "65536", "--max-elapsed-ms", "60000", "--json"),
                commands[1],
            )

    def test_failed_preparation_preserves_child_reason_and_never_submits_query(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, workspace = self.layout(Path(temporary))
            calls = []
            rejection = readiness.Rejected(readiness.Stage.PREPARATION, readiness.Failure.COMMAND_FAILED, 4)
            def executor(argv, cwd, timeout):
                calls.append(argv)
                self.assertEqual(1, len(calls), "No query after rejected preparation")
                return readiness.CommandResult(4, json.dumps(asdict(rejection)))
            result = self.invoke(root, workspace, executor)
            self.assertEqual(readiness.Stage.PREPARATION, result.stage)
            self.assertEqual(4, result.exitCode)
            self.assertEqual(asdict(rejection), result.nativeDocument)
            self.assertEqual(1, len(calls))

    def test_timeout_is_uncertain_and_is_never_retried(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, workspace = self.layout(Path(temporary))
            calls = []
            def executor(argv, cwd, timeout):
                calls.append(argv)
                self.assertEqual(1, len(calls))
                raise subprocess.TimeoutExpired(argv, timeout)
            self.assertEqual(
                readiness.Rejected(readiness.Stage.PREPARATION, readiness.Failure.WORK_UNCERTAIN),
                self.invoke(root, workspace, executor),
            )
            self.assertEqual(1, len(calls))

    def test_pending_preparation_does_not_submit_a_query(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, workspace = self.layout(Path(temporary))
            calls = []
            pending = asdict(Prepared(Preparation("preparing")))
            def executor(argv, cwd, timeout):
                calls.append(argv)
                self.assertEqual(1, len(calls))
                return readiness.CommandResult(0, json.dumps(pending))
            result = self.invoke(root, workspace, executor)
            self.assertEqual(readiness.Failure.INCOMPLETE, result.failure)
            self.assertEqual(pending, result.nativeDocument)
            self.assertEqual(1, len(calls))

    def test_signal_exit_remains_uncertain_with_its_shell_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, workspace = self.layout(Path(temporary))
            calls = []
            def executor(argv, cwd, timeout):
                calls.append(argv)
                self.assertEqual(1, len(calls))
                return readiness.CommandResult(-9, "")
            self.assertEqual(
                readiness.Rejected(readiness.Stage.PREPARATION, readiness.Failure.WORK_UNCERTAIN, 137),
                self.invoke(root, workspace, executor),
            )
            self.assertEqual(1, len(calls))

    def test_unowned_root_rejects_before_any_child(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, workspace = self.layout(Path(temporary))
            (root / ".kast-headless-owned").unlink()
            def executor(*args):
                self.fail("Unowned root must not invoke its child")
            self.assertEqual(
                readiness.Rejected(readiness.Stage.ADMISSION, readiness.Failure.INVALID_ROOT),
                self.invoke(root, workspace, executor),
            )

    def layout(self, parent):
        root = parent / "owned"
        (root / "bin").mkdir(parents=True)
        (root / ".kast-headless-owned").write_text("kast-managed-headless-v1\n")
        (root / "bin/kast").write_text("unexecuted command fixture\n")
        (root / "bin/kast").chmod(0o700)
        workspace = parent / "workspace"
        workspace.mkdir()
        return root, workspace

    def invoke(self, root, workspace, executor):
        return readiness.verify(root, workspace, "OrderService", "orders/src/main", "main", "fixture.OrderService", 1000, executor)


if __name__ == "__main__":
    unittest.main()
