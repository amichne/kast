"""Native installed-product acceptance in an exclusively owned disposable HOME.

The baseline is an explicit complete component-qualified composition, never latest/main
or a same-composition repair. Publication and prior installed acceptance are not required.
No receipt is emitted unless every real lifecycle case completes.
"""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import platform
import selectors
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import override

import managed_installation as installation
import managed_release as product
import product_acceptance as acceptance
from private_interpreter import PrivateInterpreter

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".github/scripts/release"))
import product_candidate as candidate

INVOCATION_FILE = Path(
    "invocation/src/main/kotlin/fixture/staticcallbacks/invocation/Invocation.kt"
)


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


@dataclass
class Probe:
    root: Path
    home: Path
    evidence: Path
    python: Path
    dependency: Path
    environment: dict[str, str]
    completed: set[acceptance.Case] = field(default_factory=lambda: set[acceptance.Case]())

    @property
    def control(self) -> Path:
        return self.root / "installation/share/kast/libexec/kast-management"

    def run(
        self, case: str, workspace: Path, arguments: tuple[str, ...], raw: bytes | None = None
    ) -> bytes:
        result = subprocess.run(
            [str(self.home / ".local/bin/kast"), *arguments],
            cwd=workspace,
            env=self.environment,
            input=raw,
            capture_output=True,
            timeout=1800,
            check=False,
        )
        _ = (self.evidence / f"{case}.stdout").write_bytes(result.stdout)
        _ = (self.evidence / f"{case}.stderr").write_bytes(result.stderr)
        if result.returncode:
            raise ValueError(f"native case failed: {case}")
        return result.stdout

    def query(self, case: str, workspace: Path, name: str = "invokeCallback") -> str:
        expected_file = workspace / (
            INVOCATION_FILE
            if name == "invokeCallback"
            else "invocation/src/main/kotlin/AcceptanceEdited.kt"
        )
        return complete(
            self.run(
                case,
                workspace,
                (
                    "query",
                    "--name",
                    name,
                    "--kind",
                    "function",
                    "--json",
                ),
            ),
            name,
            expected_file,
        )

    def install(self, directory: Path, record: product.ProductRecord) -> None:
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
        # Production activation and its retirement journal, with only acquisition virtualized.
        if (
            installation.main(
                [
                    "--resolved-selection",
                    str(path),
                    "--install-root",
                    str(self.root),
                    "--private-python-archive",
                    str(self.python),
                    "--private-dependency",
                    str(self.dependency),
                    "--json",
                ],
                source=LocalSource(directory, record),
            )
            != 0
        ):
            raise ValueError("native product installation rejected")

    def mcp(self, workspace: Path) -> None:
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
                            _ = semantic(
                                document,
                                "invokeCallback",
                                workspace / INVOCATION_FILE,
                            )
                process.stdin.close()
                if process.wait(timeout=60):
                    raise ValueError("MCP child failed")
            finally:
                if process.poll() is None:
                    process.kill()
                    _ = process.wait(timeout=60)


def require_baseline(directory: Path, record: product.ProductRecord) -> product.ProductRecord:
    baseline = candidate.read(directory)
    if isinstance(baseline, candidate.candidate.Failure):
        raise ValueError("unmet input: complete component-qualified baseline product")
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
    repository = Path(__file__).resolve().parents[1]
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
            probe.install(directory, record)
            facts = next(item for item in record.targets if item.target == target)
            if product.file_digest(probe.control) != facts.qualification.controlSha256:
                raise ValueError("installed Control differs from qualified bytes")
            upgraded_host = probe.query("upgrade", roots[0])
            if upgraded_host == baseline_host:
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
            containers = root.parent / "kast-workspaces/containers"
            if containers.exists():
                raise ValueError("installation created semantic workspace state")
            for case, arguments in (
                ("help", ("--help",)),
                ("status", ("status", "--json")),
                ("describe", ("describe", "--help")),
                ("plan", ("query", "plan", "--name", "invokeCallback", "--json")),
                ("catalog", ("--internal-entrypoint=rpc", "--catalog=COMPLETE", "catalog")),
            ):
                _ = probe.run(f"passive-{case}", roots[0], arguments)
                if containers.exists():
                    raise ValueError(f"passive {case} created semantic workspace state")
            probe.completed.add(acceptance.Case.PASSIVE)
            with ThreadPoolExecutor(max_workers=2) as executor:
                futures = [
                    executor.submit(probe.query, f"parallel-first-{index}", roots[0])
                    for index in range(2)
                ]
                first_hosts = [future.result() for future in futures]
            current_host = probe.query("repeated", roots[0])
            if any(host != current_host for host in first_hosts):
                raise ValueError("parallel first queries did not share one compiler lifetime")
            probe.completed.update(
                (acceptance.Case.FRESH, acceptance.Case.CLI, acceptance.Case.PARALLEL)
            )
            _ = complete(
                probe.run(
                    "rpc",
                    roots[0],
                    ("--internal-entrypoint=rpc", "--catalog=COMPLETE", "call", "query_symbols"),
                    encoded(Arguments(Query(Source("invokeCallback")))),
                ),
                "invokeCallback",
                roots[0] / INVOCATION_FILE,
            )
            probe.completed.add(acceptance.Case.RPC)
            probe.mcp(roots[0])
            probe.completed.add(acceptance.Case.MCP)
            _ = probe.run("stop", roots[0], ("stop", "--all", "--json"))
            restarted = probe.query("restart", roots[0])
            if restarted == current_host:
                raise ValueError("stop did not replace the compiler workspace lifetime")
            probe.completed.add(acceptance.Case.RESTART)
            with ThreadPoolExecutor(max_workers=2) as executor:
                second = executor.submit(probe.query, "second-root", roots[1])
                first = executor.submit(probe.query, "first-root-again", roots[0])
                second_host, first_host = second.result(), first.result()
            if first_host == second_host or first_host != restarted:
                raise ValueError("workspace roots did not retain separate compiler lifetimes")
            probe.completed.add(acceptance.Case.ROOTS)
            source = roots[0] / "invocation/src/main/kotlin/AcceptanceEdited.kt"
            _ = source.write_text("fun acceptanceEditedDeclaration() = 42\n")
            _ = probe.query("edited", roots[0], "acceptanceEditedDeclaration")
            probe.completed.add(acceptance.Case.EDIT)
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
    except (OSError, ValueError, subprocess.SubprocessError) as failure:
        print(f"installed-product acceptance rejected: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
