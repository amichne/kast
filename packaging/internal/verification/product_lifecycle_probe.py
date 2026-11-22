"""Native installed-product acceptance in an exclusively owned disposable HOME.

The baseline is an explicit complete qualified composition, never latest/main
or a same-composition repair. Legacy native receipts qualify old baselines;
assembly-only baselines require detached installed acceptance. Publication is not required.
No receipt is emitted unless every real lifecycle case completes.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import platform
import selectors
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from enum import Enum
from pathlib import Path
from threading import Barrier
from typing import override

if not sys.flags.isolated:
    sys.path[:0] = [
        str(Path(__file__).resolve().parents[1] / family)
        for family in ("bootstrap", "composition", "installation", "product", "runtime")
    ] + [str(Path(__file__).resolve().parents[2])]

import managed_installation as installation
import managed_release as product
import product_acceptance as acceptance
from composed_host_probe import (
    HostIdentity,
    caller_names,
    endpoint,
    java_reference_contexts,
    linux_core_library,
    live_host,
)
from composed_host_probe import (
    complete as compiler_complete,
)
from composition_platform import HostPlatform, host_platform
from private_interpreter import PrivateInterpreter

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / ".github/scripts/release"))
import product_candidate as candidate

INVOCATION_FILE = Path(
    "invocation/src/main/kotlin/fixture/staticcallbacks/invocation/Invocation.kt"
)
CALLERS = frozenset({"forwardOnce", "mutualSecond", "aliasWrapper", "mutableAliasWrapper"})
CALLERS_QUERY = (
    "query",
    "--name",
    "invokeCallback",
    "--kind",
    "function",
    "--in",
    "invocation",
    "--source-set",
    "main",
    "--relation",
    "callers",
    "--fields",
    "name,signature,location",
    "--json",
)


def declaration_query(name: str) -> tuple[str, ...]:
    return "query", "--name", name, "--kind", "function", "--json"


class RestartRead(Enum):
    DECLARATION = "restart"
    CALLERS = "callers-after-restart"

    @property
    def arguments(self) -> tuple[str, ...]:
        match self:
            case RestartRead.DECLARATION:
                return declaration_query("invokeCallback")
            case RestartRead.CALLERS:
                return CALLERS_QUERY


@dataclass(frozen=True)
class NativeCaseResult:
    code: int
    response: bytes

    def expect(self, case: str, *, rejected: bool = False) -> bytes:
        if (self.code == 0) == rejected:
            raise ValueError(f"native case failed: {case} (exit {self.code})")
        return self.response

    def requests_fresh_read(self) -> bool:
        if self.code != 10:
            return False
        match product.document(self.response):
            case {
                "type": "rejected_document",
                "document": {
                    "schemaVersion": int() as version,
                    "outcome": "rejected",
                    "failure": "FRESHNESS_REJECTED",
                    "detail": "MOVED",
                    "stage": "CONTENT_REVALIDATION" | "MODEL_CAPTURE",
                    "recovery": {"kind": "restart_read"},
                },
            }:
                return type(version) is int and version == 1
            case _:
                return False


@dataclass(frozen=True)
class Source:
    declarationName: str
    type: str = "SEARCH_DECLARATIONS"


@dataclass(frozen=True)
class Terminal:
    type: str = "EMIT"


@dataclass(frozen=True)
class Query:
    source: Source
    terminalStep: Terminal = Terminal()
    type: str = "RUN"


@dataclass(frozen=True)
class Arguments:
    request: Query


@dataclass(frozen=True)
class Initialize:
    protocolVersion: str = "2025-06-18"


@dataclass(frozen=True)
class Call:
    arguments: Arguments
    name: str = "query_symbols"


@dataclass(frozen=True)
class McpRequest:
    id: int
    method: str
    params: Initialize | Call
    jsonrpc: str = "2.0"


def encoded(value: Arguments | McpRequest | product.ProductSelection) -> bytes:
    return (json.dumps(asdict(value), separators=(",", ":")) + "\n").encode()


class LocalSource(product.ReleaseSource):
    """Exact retained URLs only; no network fallback or dependency resolution."""

    def __init__(self, directory: Path, record: product.ProductRecord):
        base = f"{product.RELEASE_URL}/developer-v{record.version}"
        self.files = {
            f"{base}/{asset.name}": directory / "payload" / asset.name for asset in record.assets()
        }
        self.files[f"{base}/build-record.json"] = directory / "build-record.json"

    @override
    def read(self, url: str, limit: int) -> bytes | product.TransferFailure:
        path = self.files.get(url)
        if path is None or path.is_symlink() or not path.is_file():
            return product.TransferFailure.NOT_FOUND
        if path.stat().st_size > limit:
            return product.TransferFailure.LIMIT_EXCEEDED
        return path.read_bytes()

    @override
    def download(
        self, url: str, destination: Path, limit: int
    ) -> product.DownloadedFile | product.TransferFailure:
        path = self.files.get(url)
        if path is None or path.is_symlink() or not path.is_file():
            return product.TransferFailure.NOT_FOUND
        if path.stat().st_size > limit:
            return product.TransferFailure.LIMIT_EXCEEDED
        _ = shutil.copyfile(path, destination)
        return product.DownloadedFile(
            destination, product.file_digest(destination), destination.stat().st_size
        )


def complete(raw: bytes, name: str, expected_file: Path) -> str:
    value = product.document(raw)
    if not product.mapping(value) or value.get("type") != "complete":
        raise ValueError("semantic operation incomplete")
    return semantic(value.get("document"), name, expected_file)


def semantic(document: object, name: str, expected_file: Path) -> str:
    if not product.mapping(document) or document.get("status") != "complete":
        raise ValueError("missing or incomplete semantic document")
    coverage, summary, rows = (
        document.get(key) for key in ("coverage", "original_evidence_summary", "items")
    )
    if (
        not product.mapping(coverage)
        or coverage.get("exhaustive") is not True
        or not product.mapping(summary)
        or summary.get("failure_count") != 0
        or summary.get("omission_count") != 0
        or not product.sequence(rows)
        or len(rows) != 1
        or not product.mapping(rows[0])
        or rows[0].get("name") != name
    ):
        raise ValueError("semantic result differs from independent expected declaration")
    location = rows[0].get("location")
    live = document.get("live")
    if (
        not product.mapping(location)
        or location.get("file") != str(expected_file)
        or not product.mapping(live)
        or not isinstance(live.get("host"), str)
        or not live["host"]
    ):
        raise ValueError("semantic root or compiler workspace lifetime missing")
    return str(live["host"])


@dataclass(frozen=True)
class WarmCoordinatorCall:
    caller: int
    coordinator: int
    generation: uuid.UUID


def warm_coordinator_call(stderr: bytes) -> WarmCoordinatorCall:
    return coordinator_call(stderr, warm=True)


def trace_pid(value: object) -> int:
    if type(value) is not int or value <= 0:
        raise ValueError("coordinator stage lacks a process identity")
    return value


def trace_generation(value: object) -> uuid.UUID:
    if not isinstance(value, str):
        raise ValueError("coordinator invocation lacks a generation")
    return uuid.UUID(value)


def coordinator_call(stderr: bytes, *, warm: bool) -> WarmCoordinatorCall:
    if len(stderr) > 1_048_576:
        raise ValueError("warm coordinator stage evidence exceeds its limit")
    callers: list[int] = []
    invocations: list[tuple[int, uuid.UUID]] = []
    for line in stderr.splitlines():
        if b'"kast_control_stage"' not in line and b'"frontend_trace"' not in line:
            continue
        value = product.document(line)
        if not product.mapping(value):
            raise ValueError("invalid coordinator stage evidence")
        if value.get("type") == "frontend_trace":
            callers.append(trace_pid(value.get("callerProcessId")))
            invocations.append(
                (
                    trace_pid(value.get("processId")),
                    trace_generation(value.get("serviceGeneration")),
                )
            )
            continue
        if value.get("event") != "kast_control_stage":
            raise ValueError("invalid coordinator stage evidence")
        stage = value.get("stage")
        if warm and stage in (
            "PAYLOAD_VERIFICATION",
            "QUERY_CHILD_LAUNCH",
            "WORKSPACE_PREPARATION",
        ):
            raise ValueError(f"warm coordinator call repeated {stage}")
        pid = trace_pid(value.get("pid"))
        if stage == "PROCESS_START" and value.get("outcome") == "CREATED":
            callers.append(pid)
        if stage == "COORDINATED_INVOCATION" and value.get("outcome") == "COMPLETED":
            invocations.append((pid, trace_generation(value.get("generation"))))
    if len(callers) != 1 or len(invocations) != 1 or callers[0] == invocations[0][0]:
        raise ValueError("warm invocation lacks separate caller and coordinator identities")
    return WarmCoordinatorCall(callers[0], *invocations[0])


def require_warm_coordinator_reuse(first: bytes, second: bytes) -> None:
    previous = warm_coordinator_call(first)
    current = warm_coordinator_call(second)
    if previous.caller == current.caller:
        raise ValueError("warm reads must use separate native callers")
    if (previous.coordinator, previous.generation) != (current.coordinator, current.generation):
        raise ValueError("warm reads changed coordinator process or generation")


_DISPOSABLE_LAUNCHER = """import os, signal, subprocess, sys
descriptor, *command = sys.argv[1:]
with os.fdopen(int(descriptor), "w") as result:
    code = subprocess.run(command, stdin=subprocess.DEVNULL, check=False).returncode
    result.write(str(code))
signal.pause()
"""


def run_in_disposable_group(
    command: list[str], workspace: Path, environment: dict[str, str], output: Path, error: Path
) -> int:
    """Reproduce executor cleanup after the real native caller returns."""
    with output.open("wb") as stdout, error.open("wb") as stderr:
        read_fd, write_fd = os.pipe()
        with os.fdopen(read_fd, "rb") as result, os.fdopen(write_fd, "wb") as launch_result:
            with subprocess.Popen(
                [sys.executable, "-I", "-c", _DISPOSABLE_LAUNCHER, str(write_fd), *command],
                cwd=workspace,
                env=environment,
                stdin=subprocess.DEVNULL,
                stdout=stdout,
                stderr=stderr,
                pass_fds=(write_fd,),
                start_new_session=True,
            ) as launcher:
                launch_result.close()
                try:
                    with selectors.DefaultSelector() as ready:
                        _ = ready.register(result, selectors.EVENT_READ)
                        if not ready.select(timeout=1800):
                            raise ValueError("Disposable caller did not complete")
                        status = result.read(16)
                    if not status or launcher.poll() is not None:
                        raise ValueError(
                            "Disposable caller did not report an exit in its process group"
                        )
                finally:
                    if launcher.poll() is None and os.getpgid(launcher.pid) == launcher.pid:
                        os.killpg(launcher.pid, signal.SIGKILL)
                        _ = launcher.wait(timeout=10)
                return int(status)


@dataclass
class Probe:
    root: Path
    home: Path
    evidence: Path
    python: Path
    dependency: Path
    environment: dict[str, str]
    completed: set[acceptance.Case] = field(default_factory=lambda: set[acceptance.Case]())
    sandbox: Path | None = None

    @property
    def control(self) -> Path:
        return self.root / "installation/share/kast/libexec/kast-management"

    def run(
        self,
        case: str,
        workspace: Path,
        arguments: tuple[str, ...],
        raw: bytes | None = None,
        *,
        launcher: Path | None = None,
        rejected: bool = False,
        trace: bool = False,
        disposable_caller: bool = False,
    ) -> bytes:
        result = self._invoke(
            case,
            workspace,
            arguments,
            raw,
            launcher=launcher,
            trace=trace,
            disposable_caller=disposable_caller,
        )
        return result.expect(case, rejected=rejected)

    def read_after_restart(self, read: RestartRead, workspace: Path) -> bytes:
        result = self._invoke(read.value, workspace, read.arguments)
        if result.requests_fresh_read():
            return self.run(f"{read.value}-fresh-read", workspace, read.arguments)
        return result.expect(read.value)

    def _invoke(
        self,
        case: str,
        workspace: Path,
        arguments: tuple[str, ...],
        raw: bytes | None = None,
        *,
        launcher: Path | None = None,
        trace: bool = False,
        disposable_caller: bool = False,
    ) -> NativeCaseResult:
        prefix = (
            ["/usr/bin/sandbox-exec", "-f", str(self.sandbox)] if self.sandbox is not None else []
        )
        executable = launcher if launcher is not None else self.home / ".local/bin/kast"
        command = [*prefix, str(executable), *arguments]
        environment = self.environment | {"KAST_DEBUG": "1"} if trace else self.environment
        output = self.evidence / f"{case}.stdout"
        error = self.evidence / f"{case}.stderr"
        if disposable_caller:
            if raw is not None:
                raise ValueError("A disposable caller expects no stdin")
            code = run_in_disposable_group(command, workspace, environment, output, error)
            response = output.read_bytes()
        else:
            result = subprocess.run(
                command,
                cwd=workspace,
                env=environment,
                input=raw,
                capture_output=True,
                timeout=1800,
                check=False,
            )
            code = result.returncode
            response = result.stdout
            _ = output.write_bytes(result.stdout)
            _ = error.write_bytes(result.stderr)
        return NativeCaseResult(code, response)

    def observe_host(
        self, case: str, workspace: Path, lifetime: str, runtime_digest: str
    ) -> HostIdentity:
        """Bind the answering compiler lifetime to this installation's runtime and profile."""
        target = host_platform(platform.system(), platform.machine())
        if not isinstance(target, HostPlatform):
            raise ValueError("unsupported native Host platform")
        store = self.root.parent / "kast-workspaces"
        directory = store / "containers" / hashlib.sha256(os.fsencode(workspace)).hexdigest()
        record = product.document((directory / "container.json").read_bytes())
        generation = product.document((directory / "generation.json").read_bytes())
        reference = product.document(
            (self.root / "installation/share/kast/product-selection.json").read_bytes()
        )
        runtime = store / "runtimes" / runtime_digest
        if (
            not product.mapping(record)
            or record.get("workspace") != str(workspace)
            or record.get("id") != directory.name
            or not product.mapping(generation)
            or generation.get("archiveSha256") != runtime_digest
            or generation.get("runtime") != runtime_digest
            or not product.mapping(reference)
            or reference.get("runtimeRoot") != str(runtime)
            or reference.get("runtimeArchiveSha256") != runtime_digest
            or runtime.resolve() != runtime
        ):
            raise ValueError("Host generation differs from installed runtime")
        profile = directory
        if generation.get("schemaVersion") == 2:
            identity = generation.get("generation")
            if not isinstance(identity, str) or str(uuid.UUID(identity)) != identity:
                raise ValueError("invalid Host generation identity")
            profile = directory / "generations" / identity
        elif generation.get("schemaVersion") != 1 or generation.get("generation") is not None:
            raise ValueError("invalid Host generation")
        if profile.resolve() != profile:
            raise ValueError("Host profile escaped private generation")
        home = runtime / ("host" if target.is_linux else "host/Kast.app/Contents")
        # The Gradle tooling API loads its native platform library from the declared
        # Gradle user home; no other library outside the installed runtime is admitted.
        gradle = self.environment.get("GRADLE_USER_HOME")
        gradle_native = None if gradle is None else Path(gradle) / "native"
        observed = live_host(
            profile,
            workspace,
            target,
            self.evidence,
            case,
            runtime_home=home,
            gradle_native=gradle_native,
        )
        if observed.workspaceLifetime != lifetime:
            raise ValueError("answering compiler differs from installed Host lifetime")
        if target.is_linux and any(
            not Path(library).is_relative_to(home)
            and not (gradle_native is not None and Path(library).is_relative_to(gradle_native))
            and not linux_core_library(Path(library))
            for library in observed.libraries
        ):
            raise ValueError("Host loaded a shared library outside the installed runtime")
        return observed

    def callers(
        self, case: str, workspace: Path, expected: frozenset[str], lifetime: str
    ) -> tuple[str, ...]:
        return self.require_callers(
            self.run(case, workspace, CALLERS_QUERY), workspace, expected, lifetime
        )

    def require_callers(
        self, raw: bytes, workspace: Path, expected: frozenset[str], lifetime: str
    ) -> tuple[str, ...]:
        document = self.compiler_document(raw, lifetime)
        names = caller_names(document, workspace)
        if names != tuple(sorted(expected)):
            raise ValueError("callers differ from independent expected edges")
        return names

    @staticmethod
    def compiler_document(raw: bytes, lifetime: str) -> dict[object, object]:
        document = compiler_complete(product.document(raw))
        live = document.get("live")
        if (
            document.get("status") != "complete"
            or not product.mapping(live)
            or live.get("host") != lifetime
        ):
            raise ValueError("compiler result differs from answering Host lifetime")
        return document

    def compiler_cases(self, workspace: Path, lifetime: str, runtime_digest: str) -> HostIdentity:
        original = self.observe_host("answering-host", workspace, lifetime, runtime_digest)
        _ = self.callers("callers", workspace, CALLERS, lifetime)
        self.completed.add(acceptance.Case.CALLERS)
        document = self.compiler_document(
            self.run(
                "java-references",
                workspace,
                (
                    "query",
                    "--name",
                    "Nested",
                    "--kind",
                    "class",
                    "--in",
                    "invocation",
                    "--source-set",
                    "main",
                    "--relation",
                    "references",
                    "--output",
                    "occurrences",
                    "--json",
                ),
            ),
            lifetime,
        )
        _ = java_reference_contexts(document, workspace)
        self.completed.add(acceptance.Case.JAVA)
        unknown = product.document(
            self.run(
                "unknown-result",
                workspace,
                (
                    "result",
                    "read",
                    "--ref",
                    "result:v1:00000000-0000-4000-8000-000000000000",
                    "--json",
                ),
                rejected=True,
            )
        )
        if not product.mapping(unknown) or unknown.get("type") != "rejected_document":
            raise ValueError("unknown retained result was not rejected")
        self.completed.add(acceptance.Case.UNKNOWN)
        fresh = "freshExternalCaller"
        forwarding = workspace / (
            "forwarding/src/main/kotlin/fixture/staticcallbacks/forwarding/Forwarding.kt"
        )
        with forwarding.open("a") as source:
            _ = source.write(
                f"\nfun {fresh}(block: () -> String): String = invokeCallback(block)\n"
            )
        _ = self.callers("external-edit", workspace, CALLERS | {fresh}, lifetime)
        reused = self.observe_host("reanalysed-host", workspace, lifetime, runtime_digest)
        if (reused.pid, reused.started, reused.lifetime, reused.workspaceLifetime) != (
            original.pid,
            original.started,
            original.lifetime,
            original.workspaceLifetime,
        ):
            raise ValueError("external edit replaced the answering Host")
        self.completed.update((acceptance.Case.HOST, acceptance.Case.REANALYSIS))
        return reused

    @staticmethod
    def stopped_endpoints(host: HostIdentity) -> None:
        profile = Path(host.profile)
        # The observed IDEA profile is below the generation's private HOME.
        private_home = next((path for path in profile.parents if path.name == "home"), None)
        if private_home is None or private_home.parent.name != "state":
            raise ValueError("Host profile lost private HOME")
        root = private_home.parent.parent
        for path in (
            endpoint(root, "ide-hosted", Path(host.workspace)),
            endpoint(root, "ide-lifecycle", Path(host.runtimeHome)),
        ):
            if os.path.lexists(path) or os.path.lexists(path.with_name("host.sock")):
                raise ValueError("stop retained endpoint or socket")

    def query(
        self,
        case: str,
        workspace: Path,
        name: str = "invokeCallback",
        *,
        trace: bool = False,
        disposable_caller: bool = False,
    ) -> str:
        expected_file = workspace / (
            INVOCATION_FILE
            if name == "invokeCallback"
            else "invocation/src/main/kotlin/AcceptanceEdited.kt"
        )
        return complete(
            self.run(
                case,
                workspace,
                declaration_query(name),
                trace=trace,
                disposable_caller=disposable_caller,
            ),
            name,
            expected_file,
        )

    def parallel_first_queries(self, workspace: Path) -> str:
        try:
            _ = (self.root.parent / "kast-workspaces/containers").lstat()
        except FileNotFoundError:
            pass
        else:
            raise ValueError("parallel first queries require cold workspace state")
        gate = Barrier(3, timeout=60)

        def cold_query(index: int) -> str:
            _ = gate.wait()
            return self.query(
                f"parallel-first-{index}", workspace, trace=True, disposable_caller=True
            )

        with ThreadPoolExecutor(max_workers=3) as executor:
            futures = [executor.submit(cold_query, index) for index in range(3)]
            first_hosts = [future.result() for future in futures]
        current_host = self.query("repeated", workspace, trace=True)
        if any(host != current_host for host in first_hosts):
            raise ValueError("parallel first queries did not share one compiler lifetime")
        retained = warm_coordinator_call((self.evidence / "repeated.stderr").read_bytes())
        for index in range(3):
            caller = coordinator_call(
                (self.evidence / f"parallel-first-{index}.stderr").read_bytes(), warm=False
            )
            if (caller.coordinator, caller.generation) != (
                retained.coordinator,
                retained.generation,
            ):
                raise ValueError("The coordinator did not outlive its disposable caller group")
        return current_host

    def warm_coordinator_reuse(self, workspace: Path, expected_host: str) -> None:
        cases = ("coordinator-warm-first", "coordinator-warm-second")
        for case in cases:
            if self.query(case, workspace, trace=True) != expected_host:
                raise ValueError("warm coordinator read changed compiler workspace lifetime")
        first, second = ((self.evidence / f"{case}.stderr").read_bytes() for case in cases)
        require_warm_coordinator_reuse(first, second)
        raw = self.run(
            "coordinator-workspace-wait",
            workspace,
            ("workspace", "wait", "--wait", "60000"),
            trace=True,
        )
        output = product.document(raw)
        preparation = output.get("preparation") if product.mapping(output) else None
        if preparation != {"type": "ready", "wait": {"mode": "BOUNDED", "timeoutMillis": 60000}}:
            raise ValueError("Workspace wait did not report readiness with the selected wait")
        waited = (self.evidence / "coordinator-workspace-wait.stderr").read_bytes()
        require_warm_coordinator_reuse(second, waited)

    def install(
        self, directory: Path, record: product.ProductRecord | product.LocalProductRecord
    ) -> None:
        source = None
        if isinstance(record, product.LocalProductRecord):
            arguments = ["--local-artifact", str(directory)]
        else:
            selection = product.ProductSelection(
                f"developer-v{record.version}",
                record,
                product.file_digest(directory / "build-record.json"),
                "PinnedBuild",
                None,
                record.sourceRevision,
            )
            path = self.evidence / f"selection-{selection.recordSha256}.json"
            _ = path.write_bytes(encoded(selection))
            arguments = ["--resolved-selection", str(path)]
            source = LocalSource(directory, record)
        # Production activation and its retirement journal, with only acquisition virtualized.
        if (
            installation.main(
                [
                    *arguments,
                    "--install-root",
                    str(self.root),
                    "--private-python-archive",
                    str(self.python),
                    "--private-dependency",
                    str(self.dependency),
                    "--json",
                ],
                source=source,
            )
            != 0
        ):
            raise ValueError("native product installation rejected")

    def mcp(self, workspace: Path, lifetime: str) -> None:
        with (
            (self.evidence / "mcp.stderr").open("wb") as errors,
            subprocess.Popen(
                [str(self.control), "--internal-entrypoint=mcp", "--catalog=COMPLETE"],
                cwd=workspace,
                env=self.environment,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=errors,
                bufsize=0,
            ) as process,
        ):
            assert process.stdin is not None and process.stdout is not None
            try:
                with selectors.DefaultSelector() as selector:
                    _ = selector.register(process.stdout, selectors.EVENT_READ)
                    for request in (
                        McpRequest(1, "initialize", Initialize()),
                        McpRequest(
                            2, "tools/call", Call(Arguments(Query(Source("invokeCallback"))))
                        ),
                    ):
                        _ = process.stdin.write(encoded(request))
                        process.stdin.flush()
                        if not selector.select(timeout=1800):
                            raise ValueError("MCP native response timeout")
                        raw = process.stdout.readline(16_777_217)
                        value = product.document(raw)
                        if (
                            not product.mapping(value)
                            or value.get("id") != request.id
                            or "error" in value
                        ):
                            raise ValueError("MCP native response rejected")
                        _ = (self.evidence / f"mcp-{request.id}.json").write_bytes(raw)
                        if request.id == 2:
                            result = value.get("result")
                            if not product.mapping(result) or result.get("isError") is not False:
                                raise ValueError("MCP semantic rejection")
                            content = result.get("content")
                            if (
                                not product.sequence(content)
                                or len(content) != 1
                                or not product.mapping(content[0])
                                or not isinstance(content[0].get("text"), str)
                            ):
                                raise ValueError("MCP semantic content absent")
                            document = product.document(str(content[0]["text"]).encode())
                            if result.get("structuredContent") != document:
                                raise ValueError("MCP text and structured evidence differ")
                            observed = semantic(
                                document,
                                "invokeCallback",
                                workspace / INVOCATION_FILE,
                            )
                            if observed != lifetime:
                                raise ValueError("MCP answered from a different Host lifetime")
                process.stdin.close()
                if process.wait(timeout=60):
                    raise ValueError("MCP child failed")
            finally:
                if process.poll() is None:
                    process.kill()
                    _ = process.wait(timeout=60)


HOST_LOG_NAMES = frozenset({"idea.log", "launcher.log"})
# IDE caches and scratch space hold no Host logs; skipping them keeps the walk small.
HOST_LOG_PRUNED_DIRECTORIES = frozenset({"system", "tmp"})
MAXIMUM_RETAINED_HOST_LOGS = 16
MAXIMUM_RETAINED_HOST_LOG_BYTES = 512 * 1024


def retain_host_logs(home: Path, evidence: Path) -> Path:
    """Copy the tail of each Host log under the disposable home into evidence.

    Uninstall deletes the logs on success and the disposable root is not uploaded on failure,
    so this is the only durable copy. The first call wins; later calls keep its result.
    """
    destination = evidence / "host-logs"
    if destination.exists():
        return destination
    destination.mkdir()
    found: list[Path] = []
    for directory, children, files in os.walk(home):
        children[:] = sorted(name for name in children if name not in HOST_LOG_PRUNED_DIRECTORIES)
        found.extend(Path(directory) / name for name in sorted(files) if name in HOST_LOG_NAMES)
    for index, path in enumerate(found[:MAXIMUM_RETAINED_HOST_LOGS]):
        if path.is_symlink() or not path.is_file():
            continue
        with path.open("rb") as source:
            size = source.seek(0, os.SEEK_END)
            _ = source.seek(max(0, size - MAXIMUM_RETAINED_HOST_LOG_BYTES))
            tail = source.read(MAXIMUM_RETAINED_HOST_LOG_BYTES)
        name = f"{index:02d}-" + "-".join(path.relative_to(home).parts)
        _ = (destination / name).write_bytes(tail)
    return destination


def require_baseline(directory: Path, record: product.ProductRecord) -> product.ProductRecord:
    baseline = candidate.read_baseline(directory)
    if isinstance(baseline, candidate.candidate.Failure):
        raise ValueError("unmet input: complete qualified baseline product")
    for target in record.targets:
        prior = next(item for item in baseline.targets if item.target == target.target)
        if (prior.bundle.sha256, prior.runtime.sha256, prior.qualification.controlSha256) == (
            target.bundle.sha256,
            target.runtime.sha256,
            target.qualification.controlSha256,
        ):
            raise ValueError("unmet input: different qualified composition; repair is not upgrade")
    return baseline


def execute(
    directory: Path,
    baseline_directory: Path,
    python: Path,
    dependency: Path,
    evidence: Path,
    target: str,
) -> acceptance.Receipt:
    native = {("Darwin", "arm64"): "macos-aarch64", ("Linux", "x86_64"): "linux-x86_64"}.get(
        (platform.system(), platform.machine())
    )
    if target != native:
        raise ValueError("native target mismatch")
    record = candidate.read(directory)
    if isinstance(record, candidate.candidate.Failure):
        raise ValueError("sealed candidate rejected")
    baseline = require_baseline(baseline_directory, record)
    _ = PrivateInterpreter.admit(python, dependency, target)
    if evidence.exists():
        raise ValueError("evidence destination exists")
    evidence.mkdir(parents=True)
    repository = Path(__file__).resolve().parents[3]
    # Failed native retirement retains its owned tree for investigation. Never
    # recursively erase a runtime under a Host whose exit was not established.
    with tempfile.TemporaryDirectory(prefix="kast-installed-native-", delete=False) as temporary:
        work = Path(temporary).resolve()
        _ = (evidence / "disposable-root.txt").write_text(str(work) + "\n")
        home = work / "home"
        home.mkdir(mode=0o700)
        root = home / ".local/share/kast"
        env = {
            "PATH": os.defpath,
            "HOME": str(home),
            "KAST_INSTALL_ROOT": str(root),
            "XDG_CONFIG_HOME": str(home / ".config"),
            "XDG_CACHE_HOME": str(home / ".cache"),
            "XDG_DATA_HOME": str(home / ".local/share"),
            "XDG_STATE_HOME": str(home / ".local/state"),
            "TMPDIR": str(work),
            "GRADLE_USER_HOME": str(work / "gradle"),
            "KAST_OUTPUT_TOON": "0",
        }
        roots = (work / "first", work / "second")
        for workspace in roots:
            _ = shutil.copytree(
                repository / "experiments/host-observation/static-callback-fixture", workspace
            )
            _ = shutil.copytree(
                repository / "experiments/host-observation/composed-host-fixture-overlay",
                workspace,
                dirs_exist_ok=True,
            )
            _ = shutil.copytree(repository / "gradle/wrapper", workspace / "gradle/wrapper")
            _ = shutil.copy2(repository / "gradlew", workspace / "gradlew")
        sentinels = (
            home / "preserve",
            home / "foreign-idea-profile/preserve",
            work / "gradle/preserve",
            work / "preserve",
            *(path / "preserve" for path in roots),
        )
        for sentinel in sentinels:
            sentinel.parent.mkdir(parents=True, exist_ok=True)
            _ = sentinel.write_bytes(b"foreign sentinel\n")
        before = dict(os.environ)
        os.environ.clear()
        os.environ.update(env)
        probe = Probe(root, home, evidence, python, dependency, env)
        try:
            probe.install(baseline_directory, baseline)
            baseline_host = probe.query("baseline", roots[0])
            prior = next(item for item in baseline.targets if item.target == target)
            baseline_identity = probe.observe_host(
                "baseline-host", roots[0], baseline_host, prior.runtime.sha256
            )
            probe.install(directory, record)
            facts = next(item for item in record.targets if item.target == target)
            if product.file_digest(probe.control) != facts.qualification.controlSha256:
                raise ValueError("installed Control differs from qualified bytes")
            upgraded_host = probe.query("upgrade", roots[0])
            upgraded_identity = probe.observe_host(
                "upgraded-host", roots[0], upgraded_host, facts.runtime.sha256
            )
            if (
                upgraded_host == baseline_host
                or upgraded_identity.lifetime == baseline_identity.lifetime
            ):
                raise ValueError("upgrade retained the baseline compiler workspace lifetime")
            if (root / "installation/share/kast/build-record.json").read_bytes() != (
                directory / "build-record.json"
            ).read_bytes():
                raise ValueError("activation changed original build record")
            probe.completed.add(acceptance.Case.UPGRADE)
            _ = probe.run("upgrade-uninstall", roots[0], ("uninstall",))
            if root.exists() or (root.parent / "kast-workspaces").exists():
                raise ValueError("fresh-install prerequisite retained managed state")
            probe.install(directory, record)
            if product.file_digest(probe.control) != facts.qualification.controlSha256:
                raise ValueError("fresh installed Control differs from qualified bytes")
            containers = root.parent / "kast-workspaces/containers"
            if containers.exists():
                raise ValueError("installation created semantic workspace state")
            for case, arguments in (
                ("help", ("--help",)),
                ("status", ("status", "--json")),
                ("describe", ("describe", "--help")),
                ("plan", ("query", "plan", "--name", "invokeCallback", "--json")),
            ):
                _ = probe.run(f"passive-{case}", roots[0], arguments)
                if containers.exists():
                    raise ValueError(f"passive {case} created semantic workspace state")
            probe.completed.add(acceptance.Case.PASSIVE)
            current_host = probe.parallel_first_queries(roots[0])
            probe.warm_coordinator_reuse(roots[0], current_host)
            probe.completed.update(
                (acceptance.Case.FRESH, acceptance.Case.CLI, acceptance.Case.PARALLEL)
            )
            observed_host = probe.compiler_cases(roots[0], current_host, facts.runtime.sha256)
            probe.mcp(roots[0], current_host)
            probe.completed.add(acceptance.Case.MCP)
            _ = probe.run("stop", roots[0], ("stop", "--all", "--json"))
            probe.stopped_endpoints(observed_host)
            probe.completed.add(acceptance.Case.CLEANUP)
            restarted = complete(
                probe.read_after_restart(RestartRead.DECLARATION, roots[0]),
                "invokeCallback",
                roots[0] / INVOCATION_FILE,
            )
            if restarted == current_host:
                raise ValueError("stop did not replace the compiler workspace lifetime")
            restarted_host = probe.observe_host(
                "restarted-host", roots[0], restarted, facts.runtime.sha256
            )
            if (
                restarted_host.lifetime == observed_host.lifetime
                or restarted_host.started <= observed_host.started
            ):
                raise ValueError("stop did not replace the native Host process incarnation")
            _ = probe.require_callers(
                probe.read_after_restart(RestartRead.CALLERS, roots[0]),
                roots[0],
                CALLERS | {"freshExternalCaller"},
                restarted,
            )
            probe.completed.add(acceptance.Case.RESTART)
            with ThreadPoolExecutor(max_workers=2) as executor:
                second = executor.submit(probe.query, "second-root", roots[1])
                first = executor.submit(probe.query, "first-root-again", roots[0])
                second_host, first_host = second.result(), first.result()
            if first_host == second_host or first_host != restarted:
                raise ValueError("workspace roots did not retain separate compiler lifetimes")
            second_identity = probe.observe_host(
                "second-host", roots[1], second_host, facts.runtime.sha256
            )
            _ = probe.run(
                "stop-second-root", roots[1], ("stop", "--workspace", str(roots[1]), "--json")
            )
            probe.stopped_endpoints(second_identity)
            if probe.query("first-root-after-scoped-stop", roots[0]) != first_host:
                raise ValueError("scoped stop changed the other workspace's compiler lifetime")
            probe.completed.add(acceptance.Case.ROOTS)
            source = roots[0] / "invocation/src/main/kotlin/AcceptanceEdited.kt"
            _ = source.write_text("fun acceptanceEditedDeclaration() = 42\n")
            if probe.query("edited", roots[0], "acceptanceEditedDeclaration") != restarted:
                raise ValueError("new-file edit replaced the compiler workspace lifetime")
            probe.completed.add(acceptance.Case.EDIT)
            _ = retain_host_logs(home, evidence)
            _ = probe.run("uninstall", roots[0], ("uninstall",))
            if (
                root.exists()
                or (root.parent / "kast-runtime-bases").exists()
                or (root.parent / "kast-workspaces").exists()
            ):
                raise ValueError("uninstall left managed state")
            probe.completed.add(acceptance.Case.UNINSTALL)
            if any(path.read_bytes() != b"foreign sentinel\n" for path in sentinels):
                raise ValueError("uninstall changed foreign sentinel")
            probe.completed.add(acceptance.Case.SENTINELS)
        finally:
            if probe.control.exists():
                with contextlib.suppress(OSError, ValueError, subprocess.SubprocessError):
                    _ = probe.run("failure-stop", roots[0], ("stop", "--all", "--json"))
            with contextlib.suppress(OSError):
                _ = retain_host_logs(home, evidence)
            os.environ.clear()
            os.environ.update(before)
        if probe.completed != set(acceptance.Case):
            raise ValueError("native cases incomplete")
        shutil.rmtree(work)
        prior = next(item for item in baseline.targets if item.target == target)
        return acceptance.Receipt(
            product.file_digest(directory / "build-record.json"),
            record.sourceRevision,
            target,
            facts.qualification.controlSha256,
            record.producer,
            product.file_digest(baseline_directory / "build-record.json"),
            baseline.sourceRevision,
            prior.bundle.sha256,
            prior.runtime.sha256,
            prior.qualification.controlSha256,
            tuple(acceptance.Case),
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--candidate", type=Path, required=True)
    _ = parser.add_argument("--baseline", type=Path, required=True)
    _ = parser.add_argument("--private-python-archive", type=Path, required=True)
    _ = parser.add_argument("--private-dependency", type=Path, required=True)
    _ = parser.add_argument("--target", choices=product.PRODUCT_TARGETS, required=True)
    _ = parser.add_argument("--evidence", type=Path, required=True)
    _ = parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.receipt.exists():
            raise ValueError("receipt already exists")
        receipt = execute(
            args.candidate.resolve(),
            args.baseline.resolve(),
            args.private_python_archive.resolve(),
            args.private_dependency.resolve(),
            args.evidence.resolve(),
            args.target,
        )
        with args.receipt.open("xb") as output:
            _ = output.write(acceptance.encode(receipt))
        return 0
    except (OSError, ValueError, AssertionError, subprocess.SubprocessError) as failure:
        print(f"installed-product acceptance rejected: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
