"""Scripted contract tests only; these fixtures never establish native qualification."""

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from unittest.mock import patch

import managed_release as product
import product_acceptance as acceptance
import product_lifecycle_probe as probe


@dataclass(frozen=True)
class Location:
    file: str


@dataclass(frozen=True)
class Row:
    name: str
    location: Location


@dataclass(frozen=True)
class Coverage:
    exhaustive: bool = True


@dataclass(frozen=True)
class Summary:
    failure_count: int = 0
    omission_count: int = 0


@dataclass(frozen=True)
class Live:
    host: str = "compiler-workspace-one"


@dataclass(frozen=True)
class Semantic:
    items: tuple[Row, ...]
    status: str = "complete"
    coverage: Coverage = Coverage()
    original_evidence_summary: Summary = Summary()
    live: Live = Live()


@dataclass(frozen=True)
class Reply:
    document: Semantic
    type: str = "complete"


def record() -> product.ProductRecord:
    targets = tuple(
        product.ProductTarget(
            target,
            product.ProductInputs("1" * 64, "2" * 64, "3" * 64, "4" * 64),
            product.ProductAsset("bundle-" + target, "5" * 64, 10),
            product.ProductAsset("runtime-" + target, "6" * 64, 20),
            product.NativeQualification(
                product.ProductAsset("receipt-" + target, "7" * 64, 30),
                product.ProductProducer(56, 2, ".github/workflows/composed-linux.yml"),
                "5" * 64,
                "6" * 64,
                "8" * 64,
            ),
        )
        for target in product.PRODUCT_TARGETS
    )
    return product.ProductRecord(
        2,
        "amichne/kast",
        "a" * 40,
        "b" * 40,
        "0.0.123",
        product.ProductProducer(123, 2, ".github/workflows/ci.yml"),
        targets,
    )


class AcceptanceTest(unittest.TestCase):
    def test_public_dispatch_and_exact_semantic_root_are_required(self) -> None:
        workspace = Path("/owned/first")
        document = Semantic(
            (Row("invokeCallback", Location(str(workspace / probe.INVOCATION_FILE))),)
        )
        raw = json.dumps(asdict(Reply(document))).encode()
        runner = probe.Probe(
            Path("/owned/home/.local/share/kast"),
            Path("/owned/home"),
            Path("/owned/evidence"),
            Path("/python.tar.gz"),
            Path("/dependency.whl"),
            {},
        )
        with patch.object(probe.Probe, "run", return_value=raw) as run:
            self.assertEqual("compiler-workspace-one", runner.query("public", workspace))
            run.assert_called_once_with(
                "public",
                workspace,
                ("query", "--name", "invokeCallback", "--kind", "function", "--json"),
            )
        self.assertEqual(
            "compiler-workspace-one",
            probe.semantic(
                product.document(json.dumps(asdict(document)).encode()),
                "invokeCallback",
                workspace / probe.INVOCATION_FILE,
            ),
        )
        for changed in (
            replace(document, coverage=Coverage(False)),
            replace(document, status="qualified"),
            replace(document, original_evidence_summary=Summary(1, 0)),
            replace(document, items=()),
            replace(document, items=(Row("invokeCallback", Location("/foreign/Invocation.kt")),)),
            replace(document, live=Live("")),
        ):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                _ = probe.complete(
                    json.dumps(asdict(Reply(changed))).encode(),
                    "invokeCallback",
                    workspace / probe.INVOCATION_FILE,
                )

    def test_independent_required_case_inventory_and_bindings(self) -> None:
        self.assertEqual(
            [
                "FRESH_INSTALL",
                "PASSIVE_DISCOVERY",
                "CLI_SEMANTICS",
                "RPC_SEMANTICS",
                "MCP_SEMANTICS",
                "STOP_RESTART",
                "SAME_ROOT_PARALLEL_FIRST_QUERIES",
                "TWO_ROOTS",
                "EDIT_QUERY",
                "DIFFERENT_BUILD_UPGRADE_QUERY",
                "UNINSTALL",
                "DELETION_SENTINELS",
            ],
            [case.value for case in acceptance.Case],
        )
        selected = record()
        receipt = acceptance.Receipt(
            "c" * 64,
            "a" * 40,
            "linux-x86_64",
            "8" * 64,
            selected.producer,
            "d" * 64,
            selected.sourceRevision,
            "1" * 64,
            "2" * 64,
            "3" * 64,
            tuple(acceptance.Case),
        )
        self.assertEqual(
            receipt,
            acceptance.decode(acceptance.encode(receipt), selected, "c" * 64, "linux-x86_64"),
        )
        for changed in (
            replace(receipt, qualificationStage="COMPONENT_NATIVE"),
            replace(receipt, qualification="NOT_RUN"),
            replace(receipt, completedCases=()),
            replace(receipt, baselineRecordSha256="c" * 64),
            replace(
                receipt,
                baselineBundleSha256="5" * 64,
                baselineRuntimeSha256="6" * 64,
                baselineControlSha256="8" * 64,
            ),
            replace(receipt, sourceRevision="e" * 40),
            replace(receipt, producer=replace(receipt.producer, runAttempt=3)),
        ):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                _ = acceptance.decode(
                    acceptance.encode(changed), selected, "c" * 64, "linux-x86_64"
                )

    def test_missing_baseline_and_same_build_repair_are_not_upgrade_evidence(self) -> None:
        selected = record()
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "component-qualified baseline"):
                _ = probe.require_baseline(Path(temporary), selected)
        with patch.object(probe.candidate, "read", return_value=selected) as read:
            with self.assertRaisesRegex(ValueError, "repair is not upgrade"):
                _ = probe.require_baseline(Path("unused"), selected)
            read.assert_called_once_with(Path("unused"))
        changed = replace(
            selected,
            targets=tuple(
                replace(target, runtime=replace(target.runtime, sha256="9" * 64))
                for target in selected.targets
            ),
        )
        with patch.object(probe.candidate, "read", return_value=changed):
            self.assertEqual(changed, probe.require_baseline(Path("unused"), selected))

    def test_local_asset_source_never_substitutes_or_queries_network(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            selected = record()
            (root / "payload").mkdir()
            _ = (root / "build-record.json").write_bytes(b"original record bytes\n")
            source = probe.LocalSource(root, selected)
            base = product.RELEASE_URL + "/developer-v0.0.123/"
            self.assertEqual(
                b"original record bytes\n", source.read(base + "build-record.json", 100)
            )
            self.assertEqual(
                product.TransferFailure.LIMIT_EXCEEDED, source.read(base + "build-record.json", 1)
            )
            self.assertEqual(product.TransferFailure.NOT_FOUND, source.read(base + "latest", 100))
            self.assertEqual(
                product.TransferFailure.NOT_FOUND,
                source.download(base + "missing", root / "output", 100),
            )
            self.assertFalse((root / "output").exists())


if __name__ == "__main__":
    _ = unittest.main()
