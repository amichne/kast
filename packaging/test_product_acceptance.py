"""Scripted contract tests only; these fixtures never establish native qualification."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from unittest.mock import patch

import local_product_probe as local
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


@dataclass(frozen=True)
class CatalogTool:
    name: str


@dataclass(frozen=True)
class Catalog:
    tools: tuple[CatalogTool, ...]


@dataclass(frozen=True)
class CatalogReply:
    catalog: Catalog
    type: str = "catalog"


@dataclass(frozen=True)
class RejectedReply:
    failure: str = "CATALOG_UNAVAILABLE"
    type: str = "rejected"


@dataclass(frozen=True)
class ComponentBindings:
    runtimeSha256: str
    controlSha256: str


@dataclass(frozen=True)
class ComponentReceiptFixture:
    bundleSha256: str
    containers: ComponentBindings
    probe: Coverage = Coverage()
    qualification: str = "COMPLETE"
    qualificationStage: str = "COMPONENT_NATIVE"
    sourceRevision: str = "a" * 40
    harnessSha256: str = "9" * 64


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
    def test_rpc_catalog_uses_installed_launcher_and_rejects_unsuccessful_documents(self) -> None:
        # A real child is needed here: the selected launcher, not a scripted
        # semantic adapter, must produce the document that acceptance checks.
        catalog = CatalogReply(
            Catalog(
                tuple(
                    CatalogTool(name)
                    for name in (
                        "add_declaration",
                        "check_diagnostics",
                        "health_check",
                        "query_symbols",
                        "replace_body",
                    )
                )
            )
        )
        with tempfile.TemporaryDirectory(prefix="rpc probe ") as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            evidence.mkdir()
            runner = probe.Probe(root / "kast", root / "home", evidence, root, root, {})
            public = runner.home / ".local/bin/kast"
            rpc = runner.root / "installation/bin/kast-tool-rpc"
            for launcher in (public, rpc):
                launcher.parent.mkdir(parents=True)
            for reply in (
                catalog,
                RejectedReply(),
                replace(catalog, type="complete"),
                CatalogReply(Catalog(())),
                CatalogReply(Catalog((CatalogTool("health_check"),))),
            ):
                with self.subTest(reply=reply):
                    raw = json.dumps(asdict(reply)).encode()
                    for launcher, output in (
                        (public, json.dumps(asdict(RejectedReply())).encode()),
                        (rpc, raw),
                    ):
                        _ = launcher.write_text(
                            f"#!{sys.executable}\nimport sys\nsys.stdout.buffer.write({output!r})\n"
                        )
                        launcher.chmod(0o755)
                    if reply == catalog:
                        runner.catalog(root)
                    else:
                        with self.assertRaisesRegex(ValueError, "RPC catalog"):
                            runner.catalog(root)
                    self.assertEqual(raw, (evidence / "passive-catalog.stdout").read_bytes())
                    self.assertEqual(set[acceptance.Case](), runner.completed)

    def test_local_fixture_keeps_real_native_binding_but_has_no_other_target_payload(self) -> None:
        native = record().targets[1]
        receipt = ComponentReceiptFixture(
            native.bundle.sha256,
            ComponentBindings(native.runtime.sha256, native.qualification.controlSha256),
        )
        # Existing generic fixtures have noncanonical asset names; local setup must
        # produce the production record's canonical names, not weaken its decoder.
        native = replace(
            native,
            bundle=replace(native.bundle, name="kast-composed-setup-v0.0.123-macos-aarch64.tar.gz"),
            runtime=replace(native.runtime, name="kast-runtime-v0.0.123-macos-aarch64.zip"),
            qualification=replace(
                native.qualification,
                receipt=replace(
                    native.qualification.receipt, name="qualification-macos-aarch64.json"
                ),
            ),
        )
        fixture = local.fixture_record("0.0.123", "a" * 40, "b" * 40, native, asdict(receipt))
        self.assertEqual(native, fixture.targets[1])
        self.assertEqual(
            fixture, product.decode_product_record(json.dumps(asdict(fixture)).encode())
        )
        self.assertEqual("0" * 64, fixture.targets[0].runtime.sha256)
        with self.assertRaisesRegex(ValueError, "component qualification"):
            _ = local.fixture_record(
                "0.0.123",
                "a" * 40,
                "b" * 40,
                native,
                asdict(replace(receipt, qualificationStage="SCRIPTED")),
            )
        wrong = asdict(
            replace(receipt, containers=replace(receipt.containers, runtimeSha256="f" * 64))
        )
        with self.assertRaisesRegex(ValueError, "component qualification"):
            _ = local.fixture_record("0.0.123", "a" * 40, "b" * 40, native, wrong)

    def test_three_cold_queries_and_warm_control_use_the_public_shipped_command(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workspace = root / "workspace"
            document = Semantic(
                (Row("invokeCallback", Location(str(workspace / probe.INVOCATION_FILE))),)
            )
            runner = probe.Probe(
                root / "kast/installation-owner",
                root / "home",
                root / "evidence",
                root / "python.tar.gz",
                root / "dependency.whl",
                {},
            )
            with patch.object(
                probe.Probe, "run", return_value=json.dumps(asdict(Reply(document))).encode()
            ) as run:
                self.assertEqual("compiler-workspace-one", runner.parallel_first_queries(workspace))
                self.assertCountEqual(
                    ["parallel-first-0", "parallel-first-1", "parallel-first-2", "repeated"],
                    [call.args[0] for call in run.call_args_list],
                )
                for call in run.call_args_list:
                    self.assertEqual(
                        (
                            workspace,
                            ("query", "--name", "invokeCallback", "--kind", "function", "--json"),
                        ),
                        call.args[1:],
                    )
            self.assertEqual(set[acceptance.Case](), runner.completed)

    def test_parallel_first_query_case_rejects_warm_state_before_any_query(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "kast-workspaces/containers").mkdir(parents=True)
            runner = probe.Probe(root / "kast", root, root, root, root, {})
            with patch.object(probe.Probe, "query") as query:
                with self.assertRaisesRegex(ValueError, "cold"):
                    _ = runner.parallel_first_queries(root / "workspace")
                query.assert_not_called()
            self.assertEqual(set[acceptance.Case](), runner.completed)

    def test_parallel_first_query_case_rejects_mismatched_host_or_failed_caller(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner = probe.Probe(root / "kast", root, root, root, root, {})
            workspace = root / "workspace"

            def observed(case: str, selected: Path) -> str:
                self.assertEqual(workspace, selected)
                return "different-host" if case == "parallel-first-2" else "one-host"

            with patch.object(probe.Probe, "query", side_effect=observed):
                with self.assertRaisesRegex(ValueError, "compiler lifetime"):
                    _ = runner.parallel_first_queries(workspace)
            with patch.object(probe.Probe, "query", side_effect=ValueError("caller rejected")):
                with self.assertRaisesRegex(ValueError, "caller rejected"):
                    _ = runner.parallel_first_queries(workspace)
            self.assertEqual(set[acceptance.Case](), runner.completed)

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
