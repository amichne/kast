"""Scripted contract tests only; these fixtures never establish native qualification."""

from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
from collections.abc import Callable, Iterator
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
class Target:
    qualifiedIdentity: str = "fixture.staticcallbacks.invocation.invokeCallback"


@dataclass(frozen=True)
class Edge:
    target: Target = Target()
    coverage: str = "exact-compiler-confirmed"
    provenance: str = "k2-authored-source"


@dataclass(frozen=True)
class Row:
    name: str
    location: Location
    connections: tuple[Edge, ...] = ()


@dataclass(frozen=True)
class Range:
    startInclusive: int
    endExclusive: int


@dataclass(frozen=True)
class Span:
    file: str
    range: Range


@dataclass(frozen=True)
class Occurrence:
    occurrence: Span
    context: str
    target: Target = Target("fixture.reference.target.ReferenceOuter.Nested")
    coverage: str = "exact-compiler-confirmed"
    provenance: str = "k2-authored-source"


@dataclass(frozen=True)
class ReferenceRow:
    occurrence: Occurrence


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
    items: tuple[Row | ReferenceRow, ...]
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
class Rejection:
    reason: str = "UNKNOWN_REFERENCE"


@dataclass(frozen=True)
class RejectedDocument:
    document: Rejection = Rejection()
    type: str = "rejected_document"


@dataclass(frozen=True)
class ReadRecovery:
    kind: str = "restart_read"


@dataclass(frozen=True)
class MovedRead:
    detail: str = "MOVED"
    stage: str = "CONTENT_REVALIDATION"
    recovery: ReadRecovery = ReadRecovery()
    schemaVersion: int = 1
    outcome: str = "rejected"
    failure: str = "FRESHNESS_REJECTED"


@dataclass(frozen=True)
class MovedReply:
    document: MovedRead = MovedRead()
    type: str = "rejected_document"


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


@dataclass(frozen=True)
class WorkspaceRecord:
    id: str
    workspace: str
    gradleHome: str
    name: str = "acceptance"
    schemaVersion: int = 2


@dataclass(frozen=True)
class Generation:
    runtime: str
    archiveSha256: str
    schemaVersion: int = 1
    generation: str | None = None


@dataclass(frozen=True)
class RuntimeReference:
    """Only the installed-reference projection consumed by Host observation."""

    runtimeRoot: str
    runtimeArchiveSha256: str


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


@dataclass(frozen=True)
class ControlStage:
    stage: str
    outcome: str
    pid: int
    generation: str | None = None
    elapsedNanos: int = 1
    bytes: int | None = None
    event: str = "kast_control_stage"


def trace(*events: ControlStage) -> bytes:
    return b"".join(json.dumps(asdict(event)).encode() + b"\n" for event in events)


@dataclass(frozen=True)
class FrontendTrace:
    callerProcessId: int
    processId: int
    serviceGeneration: str
    type: str = "frontend_trace"


class AcceptanceTest(unittest.TestCase):
    def test_restart_reads_issue_only_one_fresh_command_and_keep_both_responses(self) -> None:
        moved = json.dumps(asdict(MovedReply())).encode()
        complete = json.dumps(asdict(Reply(Semantic(())))).encode()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner = probe.Probe(root, root, root, root, root, {})
            for read in probe.RestartRead:
                for status, raw in ((0, complete), (10, moved)):
                    with self.subTest(read=read, status=status):
                        replies = (
                            probe.CapturedRun(10, moved, b"first", (), False),
                            probe.CapturedRun(status, raw, b"second", (), False),
                        )
                        with patch.object(probe, "run_captured", side_effect=replies) as invoke:
                            if status == 0:
                                self.assertEqual(raw, runner.read_after_restart(read, root))
                            else:
                                with self.assertRaisesRegex(ValueError, "fresh-read"):
                                    _ = runner.read_after_restart(read, root)
                        self.assertEqual(2, invoke.call_count)
                        # The same command, environment and input; only the start time differs.
                        first, fresh = (call.args for call in invoke.call_args_list)
                        self.assertEqual(first[:-1], fresh[:-1])
                        self.assertEqual(moved, (root / f"{read.value}.stdout").read_bytes())
                        self.assertEqual(
                            raw, (root / f"{read.value}-fresh-read.stdout").read_bytes()
                        )
                        self.assertEqual(b"first", (root / f"{read.value}.stderr").read_bytes())
                        self.assertEqual(
                            b"second", (root / f"{read.value}-fresh-read.stderr").read_bytes()
                        )

    def test_restart_read_rejects_other_exits_stages_and_recovery(self) -> None:
        moved = MovedRead()
        for status, document in (
            (6, moved),
            (124, moved),
            (10, replace(moved, failure="CANCELLED")),
            (10, replace(moved, detail="UNAVAILABLE")),
            (10, replace(moved, stage="SEMANTIC_READ")),
            (10, replace(moved, recovery=ReadRecovery("save_source"))),
            (10, replace(moved, schemaVersion=2)),
        ):
            response = json.dumps(asdict(MovedReply(document))).encode()
            self.assertFalse(probe.NativeCaseResult(status, response).requests_fresh_read())
        capture = json.dumps(asdict(MovedReply(replace(moved, stage="MODEL_CAPTURE")))).encode()
        self.assertTrue(probe.NativeCaseResult(10, capture).requests_fresh_read())

    def test_go_and_jvm_callers_must_identify_the_same_serving_process(self) -> None:
        generation = "19785c96-1977-4cb6-8388-592dc0c36bf5"
        jvm = trace(
            ControlStage("PROCESS_START", "CREATED", 11),
            ControlStage("COORDINATED_INVOCATION", "COMPLETED", 7, generation),
        )
        event = FrontendTrace(13, 7, generation)
        frontend = json.dumps(asdict(event)).encode() + b"\n"
        probe.require_warm_coordinator_reuse(jvm, frontend)
        probe.require_warm_coordinator_reuse(frontend, jvm)
        for stale in (
            frontend + frontend,
            frontend + jvm,
            frontend + trace(ControlStage("WORKSPACE_PREPARATION", "COMPLETED", 7)),
            *(
                json.dumps(asdict(changed)).encode()
                for changed in (
                    replace(event, callerProcessId=7),
                    replace(event, callerProcessId=0),
                    replace(event, processId=-1),
                    replace(event, serviceGeneration="invalid"),
                )
            ),
        ):
            with self.assertRaises(ValueError):
                _ = probe.warm_coordinator_call(stale)

    def test_warm_coordinator_reuse_rejects_competing_owner_and_setup(self) -> None:
        generation = "19785c96-1977-4cb6-8388-592dc0c36bf5"
        coordinated = ControlStage("COORDINATED_INVOCATION", "COMPLETED", 7, generation)
        first = trace(ControlStage("PROCESS_START", "CREATED", 11), coordinated)
        second = ControlStage("PROCESS_START", "CREATED", 13)
        probe.require_warm_coordinator_reuse(first, trace(second, coordinated))
        for stale in (
            trace(second),
            trace(second, replace(coordinated, pid=8)),
            trace(second, replace(coordinated, generation="e6eaf2a8-d0c1-4b08-869a-6562cdcd875f")),
            trace(replace(second, pid=11), coordinated),
            trace(second, coordinated, ControlStage("PAYLOAD_VERIFICATION", "COMPLETED", 13)),
            trace(second, coordinated, ControlStage("QUERY_CHILD_LAUNCH", "CREATED", 13)),
            trace(second, coordinated, ControlStage("WORKSPACE_PREPARATION", "COMPLETED", 13)),
        ):
            with self.assertRaises(ValueError):
                probe.require_warm_coordinator_reuse(first, stale)

    def test_compiler_cases_reanalyse_the_existing_source_without_replacing_host(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            forwarding = root / (
                "forwarding/src/main/kotlin/fixture/staticcallbacks/forwarding/Forwarding.kt"
            )
            java = root / "invocation/src/main/java/fixture/reference/usage/ReadJavaReferences.java"
            for path in (forwarding, java):
                path.parent.mkdir(parents=True)
            source = (
                "import fixture.reference.target.ReferenceOuter.Nested;\n"
                "class ReadJavaReferences { Nested reference() { return new Nested(); } }\n"
            )
            _ = java.write_text(source)
            names = ("forwardOnce", "mutualSecond", "aliasWrapper", "mutableAliasWrapper")
            original_rows = tuple(Row(name, Location(str(forwarding)), (Edge(),)) for name in names)
            edited_rows = (
                *original_rows,
                Row("freshExternalCaller", Location(str(forwarding)), (Edge(),)),
            )
            first = source.index("Nested")
            second = source.index("Nested", first + 6)
            third = source.index("Nested", second + 6)
            references = tuple(
                ReferenceRow(Occurrence(Span(str(java), Range(offset, offset + 6)), context))
                for offset, context in zip(
                    (first, second, third), ("IMPORT", "TYPE", "CODE"), strict=True
                )
            )
            host = probe.HostIdentity(
                1, 2, "app", str(root), "compiler-workspace-one", "runtime", "profile", "idea", ()
            )
            for stale in (False, True):
                _ = forwarding.write_text("// original fixture\n")
                runner = probe.Probe(root / "kast", root / "home", root, root, root, {})
                replies = iter(
                    (
                        Reply(Semantic(original_rows)),
                        Reply(Semantic(references)),
                        RejectedDocument(),
                        Reply(Semantic(original_rows if stale else edited_rows)),
                    )
                )
                calls: list[str] = []

                def run(
                    case: str,
                    workspace: Path,
                    arguments: tuple[str, ...],
                    *,
                    rejected: bool = False,
                    calls: list[str] = calls,
                    replies: Iterator[Reply | RejectedDocument] = replies,
                ) -> bytes:
                    self.assertEqual(root, workspace)
                    self.assertEqual(case == "unknown-result", rejected)
                    if case == "external-edit":
                        self.assertEqual(
                            "// original fixture\n\nfun freshExternalCaller(block: () -> String): "
                            "String = invokeCallback(block)\n",
                            forwarding.read_text(),
                        )
                    calls.append(case)
                    return json.dumps(asdict(next(replies))).encode()

                with (
                    self.subTest(stale=stale),
                    patch.object(probe.Probe, "run", side_effect=run),
                    patch.object(probe.Probe, "observe_host", return_value=host) as observe,
                ):
                    if stale:
                        with self.assertRaisesRegex(ValueError, "expected edges"):
                            _ = runner.compiler_cases(root, "compiler-workspace-one", "6" * 64)
                        self.assertNotIn(acceptance.Case.REANALYSIS, runner.completed)
                        self.assertEqual(1, observe.call_count)
                    else:
                        self.assertEqual(
                            host, runner.compiler_cases(root, "compiler-workspace-one", "6" * 64)
                        )
                        self.assertEqual(
                            {
                                acceptance.Case.CALLERS,
                                acceptance.Case.JAVA,
                                acceptance.Case.UNKNOWN,
                                acceptance.Case.HOST,
                                acceptance.Case.REANALYSIS,
                            },
                            runner.completed,
                        )
                        self.assertEqual(2, observe.call_count)
                self.assertEqual(
                    ["callers", "java-references", "unknown-result", "external-edit"], calls
                )
                with self.assertRaises(StopIteration):
                    _ = next(replies)

    def test_expected_rejection_requires_an_unsuccessful_installed_child(self) -> None:
        with tempfile.TemporaryDirectory(prefix="rejected probe ") as temporary:
            root = Path(temporary)
            runner = probe.Probe(root / "kast", root / "home", root, root, root, {})
            launcher = runner.home / ".local/bin/kast"
            launcher.parent.mkdir(parents=True)
            for status in (0, 7):
                _ = launcher.write_text(f"#!{sys.executable}\nraise SystemExit({status})\n")
                launcher.chmod(0o755)
                with self.subTest(status=status):
                    if status:
                        self.assertEqual(b"", runner.run("unknown-result", root, (), rejected=True))
                    else:
                        with self.assertRaisesRegex(ValueError, "unknown-result"):
                            _ = runner.run("unknown-result", root, (), rejected=True)
            self.assertEqual(set[acceptance.Case](), runner.completed)

    def test_stop_cleanup_rejects_remaining_or_dangling_endpoints_and_sockets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workspace, runtime = root / "workspace", root / "runtime"
            host = probe.HostIdentity(
                1,
                2,
                "application",
                str(workspace),
                "compiler",
                str(runtime),
                str(root / "state/home/.config/idea"),
                "idea",
                (),
            )
            lifecycle = root / "l/endpoint.json"
            hosted = root / "h/endpoint.json"
            for path in (lifecycle, hosted):
                path.parent.mkdir()

            def selected_endpoint(owner: Path, kind: str, identity: Path) -> Path:
                self.assertEqual(root, owner)
                self.assertEqual(runtime if kind == "ide-lifecycle" else workspace, identity)
                return lifecycle if kind == "ide-lifecycle" else hosted

            with patch.object(
                probe,
                "endpoint",
                side_effect=selected_endpoint,
            ) as endpoint:
                probe.Probe.stopped_endpoints(host)
                self.assertCountEqual(
                    [(root, "ide-lifecycle", runtime), (root, "ide-hosted", workspace)],
                    [call.args for call in endpoint.call_args_list],
                )
                for path in (
                    lifecycle,
                    hosted,
                    lifecycle.with_name("host.sock"),
                    hosted.with_name("host.sock"),
                ):
                    for dangling in (False, True):
                        with self.subTest(path=path, dangling=dangling):
                            if dangling:
                                path.symlink_to(root / "absent")
                            else:
                                _ = path.write_bytes(b"")
                            try:
                                with self.assertRaisesRegex(ValueError, "endpoint or socket"):
                                    probe.Probe.stopped_endpoints(host)
                            finally:
                                path.unlink()
                probe.Probe.stopped_endpoints(host)

    def test_callers_require_exact_edges_and_the_answering_compiler_lifetime(self) -> None:
        workspace = Path("/owned/workspace")
        forwarding = workspace / (
            "forwarding/src/main/kotlin/fixture/staticcallbacks/forwarding/Forwarding.kt"
        )
        expected = frozenset({"forwardOnce", "mutualSecond", "aliasWrapper", "mutableAliasWrapper"})
        rows = tuple(Row(name, Location(str(forwarding)), (Edge(),)) for name in sorted(expected))
        document = Semantic(rows)
        runner = probe.Probe(
            Path("/owned/kast"), Path("/owned/home"), workspace, workspace, workspace, {}
        )
        for changed in (
            document,
            replace(document, live=Live("foreign-host")),
            replace(document, items=rows[:3]),
            replace(document, items=(*rows, rows[0])),
            replace(
                document, items=(replace(rows[0], location=Location("/foreign.kt")), *rows[1:])
            ),
            *(
                replace(document, items=(replace(rows[0], connections=(edge,)), *rows[1:]))
                for edge in (
                    Edge(coverage="approximate"),
                    Edge(provenance="text"),
                    Edge(target=Target("foreign.invokeCallback")),
                )
            ),
        ):
            with (
                self.subTest(changed=changed),
                patch.object(
                    probe.Probe, "run", return_value=json.dumps(asdict(Reply(changed))).encode()
                ) as run,
            ):
                if changed == document:
                    self.assertEqual(
                        tuple(sorted(expected)),
                        runner.callers("callers", workspace, expected, "compiler-workspace-one"),
                    )
                else:
                    with self.assertRaises((ValueError, AssertionError)):
                        _ = runner.callers("callers", workspace, expected, "compiler-workspace-one")
                run.assert_called_once_with("callers", workspace, probe.CALLERS_QUERY)
        self.assertEqual(set[acceptance.Case](), runner.completed)

    def test_host_observation_binds_installed_runtime_generation_and_answering_lifetime(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            workspace = root / "workspace"
            gradle = root / "gradle"
            runner = probe.Probe(
                root / "kast", root / "home", root, root, root, {"GRADLE_USER_HOME": str(gradle)}
            )
            directory = (
                root
                / "kast-workspaces/containers"
                / hashlib.sha256(os.fsencode(workspace)).hexdigest()
            )
            directory.mkdir(parents=True)
            _ = (directory / "container.json").write_text(
                json.dumps(
                    asdict(
                        WorkspaceRecord(
                            directory.name, str(workspace), str(directory / "state/home/.gradle")
                        )
                    )
                )
            )
            selection = runner.root / "installation/share/kast/product-selection.json"
            selection.parent.mkdir(parents=True)
            digest = "6" * 64
            runtime = root / "kast-workspaces/runtimes" / digest
            reference = RuntimeReference(str(runtime), digest)
            home = runtime / "host"
            identity = probe.HostIdentity(
                123,
                456,
                "application-one",
                str(workspace),
                "compiler-workspace-one",
                str(home),
                str(directory / "state/home/.config/idea"),
                str(home / "bin/idea"),
                (str(home / "jbr/lib/server/libjvm.so"), "/usr/lib/x86_64-linux-gnu/libc.so.6"),
            )
            for generation in (
                Generation(digest, digest),
                Generation(digest, digest, 2, "00000000-0000-4000-8000-000000000001"),
            ):
                _ = (directory / "generation.json").write_text(json.dumps(asdict(generation)))
                profile = (
                    directory
                    if generation.generation is None
                    else (directory / "generations" / generation.generation)
                )
                for observed, installed, error in (
                    (identity, reference, None),
                    (replace(identity, workspaceLifetime="foreign-host"), reference, "lifetime"),
                    (
                        replace(
                            identity,
                            libraries=(
                                *identity.libraries,
                                str(gradle / "native/abc/linux-amd64/libnative-platform.so"),
                            ),
                        ),
                        reference,
                        None,
                    ),
                    (
                        replace(identity, libraries=("/usr/lib/libforeign.so",)),
                        reference,
                        "shared library",
                    ),
                    (
                        replace(identity, libraries=(str(gradle / "caches/libforeign.so"),)),
                        reference,
                        "shared library",
                    ),
                    (identity, replace(reference, runtimeArchiveSha256="f" * 64), "runtime"),
                    (identity, replace(reference, runtimeRoot="/foreign/runtime"), "runtime"),
                ):
                    _ = selection.write_text(json.dumps(asdict(installed)))
                    with (
                        self.subTest(generation=generation, error=error),
                        patch.object(
                            probe, "host_platform", return_value=probe.HostPlatform.LINUX_X86_64
                        ),
                        patch.object(probe, "live_host", return_value=observed) as live,
                    ):
                        if error is None:
                            self.assertEqual(
                                observed,
                                runner.observe_host(
                                    "host", workspace, "compiler-workspace-one", digest
                                ),
                            )
                            live.assert_called_once_with(
                                profile,
                                workspace,
                                probe.HostPlatform.LINUX_X86_64,
                                root,
                                "host",
                                runtime_home=home,
                                gradle_native=gradle / "native",
                            )
                        else:
                            with self.assertRaisesRegex(ValueError, error):
                                _ = runner.observe_host(
                                    "host", workspace, "compiler-workspace-one", digest
                                )
                            if error == "runtime":
                                live.assert_not_called()
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
            runner.evidence.mkdir()
            coordinated = ControlStage(
                "COORDINATED_INVOCATION", "COMPLETED", 7, "19785c96-1977-4cb6-8388-592dc0c36bf5"
            )
            encode_trace = trace
            raw = json.dumps(asdict(Reply(document))).encode()

            def resulting_call(
                case: str,
                selected: Path,
                arguments: tuple[str, ...],
                *,
                trace: bool,
                disposable_caller: bool,
            ) -> bytes:
                self.assertTrue(trace)
                self.assertEqual(disposable_caller, case != "repeated")
                self.assertEqual(selected, workspace)
                self.assertEqual(arguments[0], "query")
                identity = 24 if case == "repeated" else 20 + int(case[-1])
                _ = (runner.evidence / f"{case}.stderr").write_bytes(
                    encode_trace(ControlStage("PROCESS_START", "CREATED", identity), coordinated)
                )
                return raw

            with patch.object(probe.Probe, "run", side_effect=resulting_call) as run:
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

            def observed(
                case: str, selected: Path, *, trace: bool, disposable_caller: bool = False
            ) -> str:
                self.assertTrue(trace)
                self.assertEqual(disposable_caller, case != "repeated")
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
                trace=False,
                disposable_caller=False,
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
                "MCP_SEMANTICS",
                "STOP_RESTART",
                "SAME_ROOT_PARALLEL_FIRST_QUERIES",
                "TWO_ROOTS",
                "EDIT_QUERY",
                "DIFFERENT_BUILD_UPGRADE_QUERY",
                "UNINSTALL",
                "DELETION_SENTINELS",
                "EXACT_CALLER_PROVENANCE",
                "JAVA_REFERENCE_CONTEXTS_OFFSETS",
                "UNKNOWN_RESULT_REJECTION",
                "INSTALLED_HOST_RUNTIME_IDENTITY",
                "STOP_ENDPOINT_SOCKET_CLEANUP",
                "CALLER_REANALYSIS_AFTER_EDIT",
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
        self.assertEqual(3, receipt.schemaVersion)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "payload").mkdir()
            _ = (directory / "build-record.json").write_bytes(b"original record bytes\n")
            digest = product.file_digest(directory / "build-record.json")
            component = ComponentReceiptFixture("5" * 64, ComponentBindings("6" * 64, "8" * 64))
            for target in selected.targets:
                _ = (directory / "payload" / acceptance.filename(target.target)).write_bytes(
                    acceptance.encode(
                        replace(receipt, target=target.target, buildRecordSha256=digest)
                    )
                )
                _ = (directory / "payload" / target.qualification.receipt.name).write_text(
                    json.dumps(asdict(component))
                )
            self.assertEqual(2, len(acceptance.assets(directory, selected)))
        for changed in (
            replace(receipt, schemaVersion=2),
            replace(receipt, completedCases=receipt.completedCases[:12]),
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
            with self.assertRaisesRegex(ValueError, "qualified baseline"):
                _ = probe.require_baseline(Path(temporary), selected)
        with patch.object(probe.candidate, "read_baseline", return_value=selected) as read:
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
        with patch.object(probe.candidate, "read_baseline", return_value=changed):
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


class HostLogRetentionTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name) / "home"
        self.evidence = Path(temporary.name) / "evidence"
        self.evidence.mkdir()

    def write(self, relative: str, content: bytes) -> None:
        path = self.home / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        _ = path.write_bytes(content)

    def test_host_logs_are_retained_by_path_and_caches_are_skipped(self) -> None:
        self.write("lane/state/log/idea.log", b"host started\n")
        self.write("lane/state/launcher.log", b"launch\n")
        self.write("lane/state/system/log/idea.log", b"cache copy\n")
        self.write("lane/state/log/idea.1.log", b"rotated\n")
        retained = probe.retain_host_logs(self.home, self.evidence)
        self.assertEqual(
            {
                "00-lane-state-launcher.log": b"launch\n",
                "01-lane-state-log-idea.log": b"host started\n",
            },
            {path.name: path.read_bytes() for path in retained.iterdir()},
        )

    def test_a_long_log_keeps_only_its_bounded_tail(self) -> None:
        limit = probe.MAXIMUM_RETAINED_HOST_LOG_BYTES
        self.write("log/idea.log", b"h" * 10 + b"t" * limit)
        (retained,) = probe.retain_host_logs(self.home, self.evidence).iterdir()
        self.assertEqual(b"t" * limit, retained.read_bytes())

    def test_the_number_of_retained_logs_is_bounded(self) -> None:
        for index in range(probe.MAXIMUM_RETAINED_HOST_LOGS + 3):
            self.write(f"lane-{index:02d}/log/idea.log", b"x\n")
        retained = probe.retain_host_logs(self.home, self.evidence)
        self.assertEqual(probe.MAXIMUM_RETAINED_HOST_LOGS, len(list(retained.iterdir())))

    def test_the_first_retention_is_kept_after_uninstall_removes_the_logs(self) -> None:
        self.write("log/idea.log", b"before uninstall\n")
        first = probe.retain_host_logs(self.home, self.evidence)
        (self.home / "log/idea.log").unlink()
        second = probe.retain_host_logs(self.home, self.evidence)
        self.assertEqual(first, second)
        self.assertEqual([b"before uninstall\n"], [path.read_bytes() for path in second.iterdir()])


class StderrTimelineTest(unittest.TestCase):
    """A real child, so arrival times bind to the actual pipes; only the clock is scripted."""

    SCRIPT = "printf 'out'; printf 'one\\n' >&2; printf 'two\\n' >&2; printf 'tail' >&2"

    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()

    @staticmethod
    def clock(*ticks: int) -> Callable[[], int]:
        values = iter(ticks)
        return lambda: next(values)

    def test_each_stderr_line_keeps_its_arrival_offset_and_the_output_is_exact(self) -> None:
        captured = probe.run_captured(
            ["/bin/sh", "-c", self.SCRIPT],
            self.root,
            {},
            None,
            60,
            self.clock(3_000_000, 7_500_000, 9_000_000),
            1_000_000,
        )
        self.assertEqual(
            probe.CapturedRun(0, b"out", b"one\ntwo\ntail", (2, 6, 8), False), captured
        )

    def test_stdin_reaches_the_child(self) -> None:
        captured = probe.run_captured(["/bin/cat"], self.root, {}, b"request", 60, self.clock(), 0)
        self.assertEqual((0, b"request", b""), (captured.code, captured.stdout, captured.stderr))

    def test_offsets_are_bounded_and_the_bound_is_reported(self) -> None:
        with patch.object(probe, "MAXIMUM_STDERR_LINE_OFFSETS", 2):
            captured = probe.run_captured(
                ["/bin/sh", "-c", self.SCRIPT], self.root, {}, None, 60, self.clock(1, 2, 3), 0
            )
        self.assertEqual(b"one\ntwo\ntail", captured.stderr)
        self.assertEqual(((0, 0), True), (captured.lineOffsetsMs, captured.truncated))

    def test_a_probe_case_writes_its_stderr_timeline_beside_its_stderr(self) -> None:
        runner = probe.Probe(
            self.root,
            self.root,
            self.root,
            self.root,
            self.root,
            {},
            clock=self.clock(0, 4_000_000, 5_000_000, 6_000_000, 9_000_000),
        )
        _ = runner.run("lines", self.root, ("-c", self.SCRIPT), launcher=Path("/bin/sh"))
        self.assertEqual(b"one\ntwo\ntail", (self.root / "lines.stderr").read_bytes())
        self.assertEqual(
            {"schemaVersion": 1, "lineOffsetsMs": [4, 5, 6], "truncated": False},
            json.loads((self.root / "lines.stderr-timeline.json").read_text()),
        )


class StageTimingTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        # Scripted monotonic nanoseconds: each stage reads the clock once at start and end.
        ticks = iter((1_000_000, 251_000_000, 300_000_000, 305_500_000))
        self.runner = probe.Probe(
            self.root, self.root, self.root, self.root, self.root, {}, clock=lambda: next(ticks)
        )

    def test_a_command_records_its_duration_and_exit_code(self) -> None:
        # A real child, so the timing wraps the actual process boundary. A rejected case
        # still records its stage before the caller decides on the exit status.
        _ = self.runner.run(
            "exit-three", self.root, ("-c", "exit 3"), launcher=Path("/bin/sh"), rejected=True
        )
        self.assertEqual(
            [probe.StageTiming("exit-three", probe.StageKind.COMMAND, 250, 3)], self.runner.timings
        )

    def test_a_stage_that_raises_records_no_exit_code(self) -> None:
        with self.assertRaises(OSError):
            _ = self.runner.run(
                "missing", self.root / "absent", ("-c", "exit 0"), launcher=Path("/bin/sh")
            )
        self.assertEqual(
            [probe.StageTiming("missing", probe.StageKind.COMMAND, 250, None)], self.runner.timings
        )

    def test_timings_are_written_once_with_their_typed_shape(self) -> None:
        stages = [
            probe.StageTiming("install-1", probe.StageKind.INSTALLATION, 61_250, 0),
            probe.StageTiming("baseline", probe.StageKind.COMMAND, 75_000, None),
        ]
        path = probe.write_stage_timings(self.root, stages)
        self.assertEqual(
            {
                "schemaVersion": 1,
                "stages": [
                    {
                        "stage": "install-1",
                        "kind": "INSTALLATION",
                        "elapsedMs": 61250,
                        "exitCode": 0,
                    },
                    {"stage": "baseline", "kind": "COMMAND", "elapsedMs": 75000, "exitCode": None},
                ],
            },
            json.loads(path.read_text()),
        )
        # The first write wins, so a later failure path keeps the complete record.
        _ = probe.write_stage_timings(self.root, [])
        self.assertEqual(2, len(json.loads(path.read_text())["stages"]))

    def test_the_number_of_written_timings_is_bounded(self) -> None:
        stages = [
            probe.StageTiming(f"case-{index}", probe.StageKind.COMMAND, index, 0)
            for index in range(probe.MAXIMUM_STAGE_TIMINGS + 5)
        ]
        written = json.loads(probe.write_stage_timings(self.root, stages).read_text())["stages"]
        self.assertEqual(probe.MAXIMUM_STAGE_TIMINGS, len(written))


if __name__ == "__main__":
    _ = unittest.main()
