"""Exercise compiler callers, Java references, and rejection through an assembled Host."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import stat
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

from component_probe import ComponentHarness, WorkspacePaths
from composition_platform import HostPlatform, host_platform
from managed_headless import object_fields, object_items

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
LINUX_CORE_LIBRARIES = frozenset(
    {
        "ld-linux-x86-64.so.2",
        "ld-linux-aarch64.so.1",
        "libc.so.6",
        "libdl.so.2",
        "libgcc_s.so.1",
        "libm.so.6",
        "libnss_dns.so.2",
        "libnss_files.so.2",
        "libpthread.so.0",
        "libresolv.so.2",
        "librt.so.1",
        "libz.so.1",
    }
)
LINUX_CORE_LIBRARY_DIRECTORIES = frozenset(
    {
        Path("/lib/x86_64-linux-gnu"),
        Path("/usr/lib/x86_64-linux-gnu"),
        Path("/lib/aarch64-linux-gnu"),
        Path("/usr/lib/aarch64-linux-gnu"),
    }
)


@dataclass(frozen=True, slots=True)
class HostIdentity:
    pid: int
    started: int
    lifetime: str
    workspace: str
    workspaceLifetime: str
    runtimeHome: str
    profile: str
    launcher: str
    libraries: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class Invocation:
    command: tuple[str, ...]
    exitStatus: int


@dataclass(frozen=True, slots=True)
class GradleInputs:
    distributionUrl: str
    distributionSha256: str
    distributionMarkers: tuple[str, ...]
    buildScriptSha256: str
    kotlinLibraries: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class CompilerResult:
    javaHome: str
    kotlinClassSha256: str
    javaClassSha256: str


@dataclass(frozen=True, slots=True)
class ProbeReceipt:
    runtime: str
    fixture: str
    callers: tuple[str, ...]
    javaReferenceContexts: tuple[str, ...]
    unknownResultRejected: bool
    passiveDiscoveryGrantAbsent: bool
    freshCaller: str
    host: HostIdentity
    restartedHost: HostIdentity
    gradle: GradleInputs
    compiler: CompilerResult
    desktopProcessesBefore: tuple[str, ...]
    desktopProcessesAfter: tuple[str, ...]


def fields(value: object) -> dict[object, object]:
    assert object_fields(value), "Expected a public object"
    return value


def items(value: object) -> list[object]:
    assert object_items(value), "Expected public rows"
    return value


def complete(value: object) -> dict[object, object]:
    result = fields(value)
    assert result.get("type") == "complete", result
    document = fields(result.get("document"))
    assert fields(document.get("coverage")).get("exhaustive") is True, document
    summary = fields(document.get("original_evidence_summary"))
    assert summary.get("failure_count") == 0 and summary.get("omission_count") == 0, summary
    return document


def rejected_reason(result: subprocess.CompletedProcess[bytes]) -> object:
    value = fields(json.loads(result.stdout))
    assert result.returncode != 0 and value.get("type") == "rejected_document", value
    return fields(value.get("document")).get("reason")


def caller_names(result: dict[object, object], fixture: Path) -> tuple[str, ...]:
    names: list[str] = []
    expected_file = (
        fixture / "forwarding/src/main/kotlin/fixture/staticcallbacks/forwarding/Forwarding.kt"
    )
    for value in items(result.get("items")):
        row = fields(value)
        name = row.get("name")
        assert isinstance(name, str)
        names.append(name)
        assert fields(row.get("location")).get("file") == str(expected_file)
        connections = items(row.get("connections"))
        assert len(connections) == 1
        edge = fields(connections[0])
        assert edge.get("coverage") == "exact-compiler-confirmed"
        assert edge.get("provenance") == "k2-authored-source"
        assert fields(edge.get("target")).get("qualifiedIdentity") == (
            "fixture.staticcallbacks.invocation.invokeCallback"
        )
    return tuple(sorted(names))


def java_reference_contexts(references: dict[object, object], fixture: Path) -> tuple[str, ...]:
    java = fixture / "invocation/src/main/java/fixture/reference/usage/ReadJavaReferences.java"
    contexts = ("IMPORT", "TYPE", "CODE")
    expected = sorted(
        (match.start(), match.end(), context)
        for match, context in zip(
            re.finditer(r"\bNested\b", java.read_text()), contexts, strict=True
        )
    )
    actual: list[tuple[int, int, str]] = []
    for value in items(references.get("items")):
        occurrence = fields(fields(value).get("occurrence"))
        span = fields(occurrence.get("occurrence"))
        assert span.get("file") == str(java)
        location = fields(span.get("range"))
        start, end, context = (
            location.get("startInclusive"),
            location.get("endExclusive"),
            occurrence.get("context"),
        )
        assert isinstance(start, int) and isinstance(end, int) and isinstance(context, str)
        actual.append((start, end, context))
        assert occurrence.get("coverage") == "exact-compiler-confirmed"
        assert occurrence.get("provenance") == "k2-authored-source"
        assert fields(occurrence.get("target")).get("qualifiedIdentity") == (
            "fixture.reference.target.ReferenceOuter.Nested"
        )
    assert sorted(actual) == expected, actual
    return contexts


def endpoint(root: Path, kind: str, identity: Path) -> Path:
    digest = hashlib.sha256(os.fsencode(identity)).hexdigest()[:32]
    owner = hashlib.sha256(os.fsencode(root / "state/home")).hexdigest()[:32]
    family = {"ide-lifecycle": "l", "ide-hosted": "h"}[kind]
    return Path("/tmp").resolve() / ("kast-" + owner) / family / digest / "endpoint.json"


def linux_core_library(path: Path) -> bool:
    return path.parent in LINUX_CORE_LIBRARY_DIRECTORIES and (
        path.name in LINUX_CORE_LIBRARIES
        or re.fullmatch(r"libz\.so\.1(?:\.[0-9]+)+", path.name) is not None
    )


def live_host(
    root: Path,
    fixture: Path,
    target: HostPlatform,
    evidence: Path,
    case: str,
    *,
    runtime_home: Path | None = None,
) -> HostIdentity:
    home = root / target.layout.home if runtime_home is None else runtime_home
    launcher = home / ("bin/idea" if target.is_linux else "MacOS/idea")
    paths = (endpoint(root, "ide-lifecycle", home), endpoint(root, "ide-hosted", fixture))
    documents: list[dict[object, object]] = []
    for path, expected_root in zip(paths, (home, fixture), strict=True):
        document = fields(json.loads(path.read_bytes()))
        assert document.get("type") == "KAST_IDE_ENDPOINT" and document.get("root") == str(
            expected_root
        )
        socket = path.with_name("host.sock")
        attributes = socket.lstat()
        assert document.get("socket") == str(socket)
        assert stat.S_ISSOCK(attributes.st_mode) and attributes.st_uid == os.getuid()
        documents.append(document)
    application, workspace = documents
    identity = fields(fields(application.get("application")).get("runtimeIdentity"))
    pid, started, lifetime, profile = (
        identity.get("pid"),
        identity.get("processStartEpochMillis"),
        identity.get("hostLifetime"),
        identity.get("profile"),
    )
    assert isinstance(pid, int) and pid > 0 and isinstance(started, int) and started > 0
    assert isinstance(lifetime, str) and lifetime and isinstance(profile, str)
    assert identity.get("mode") == "MANAGED_HEADLESS" and identity.get("ideaHome") == str(home)
    assert Path(profile).is_relative_to(root / "state/home")
    assert application.get("host") == lifetime
    assert all(document.get("hostPid") == pid for document in documents)
    workspace_lifetime = workspace.get("host")
    assert isinstance(workspace_lifetime, str) and workspace_lifetime
    libraries: tuple[str, ...] = ()
    if target.is_linux:
        proc = Path(f"/proc/{pid}")
        assert (proc / "exe").readlink() == launcher
        mappings = (proc / "maps").read_text()
        libraries = tuple(
            sorted(
                {
                    path
                    for line in mappings.splitlines()
                    if (path := line.split(maxsplit=5)[-1]).startswith("/")
                    and ".so" in Path(path).name
                }
            )
        )
    else:
        running = subprocess.check_output(
            ["/bin/ps", "-p", str(pid), "-o", "comm="], text=True
        ).strip()
        assert running == str(launcher), running
    result = HostIdentity(
        pid,
        started,
        lifetime,
        str(fixture),
        workspace_lifetime,
        str(home),
        profile,
        str(launcher),
        libraries,
    )
    _ = (evidence / (case + ".json")).write_text(json.dumps(asdict(result), indent=2) + "\n")
    if target.is_linux:
        assert str(home / "jbr/lib/server/libjvm.so") in libraries
        external = tuple(
            value
            for value in libraries
            if not Path(value).is_relative_to(root)
            and not Path(value).is_relative_to(home)
            and not linux_core_library(Path(value))
        )
        assert not external, external
    return result


def desktop_processes(root: Path, target: HostPlatform) -> tuple[str, ...]:
    if target.is_linux:
        return ()
    listing = subprocess.check_output(["/bin/ps", "-axo", "pid=,lstart=,comm="], text=True)
    return tuple(
        line.strip()
        for line in listing.splitlines()
        if line.endswith("/Contents/MacOS/idea") and str(root / "runtime") not in line
    )


def project_gradle_inputs(root: Path, fixture: Path) -> GradleInputs:
    wrapper = (fixture / "gradle/wrapper/gradle-wrapper.properties").read_text()
    url = re.search(r"^distributionUrl=(.+)$", wrapper, flags=re.MULTILINE)
    digest = re.search(r"^distributionSha256Sum=([0-9a-f]{64})$", wrapper, flags=re.MULTILINE)
    assert url is not None and digest is not None
    gradle = root / "state/home/.gradle"
    markers = tuple(sorted(path.name for path in gradle.glob("wrapper/dists/*/*/*.zip.ok")))
    kotlin = tuple(
        sorted(
            path.relative_to(gradle).as_posix()
            for path in gradle.glob(
                "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/*.jar"
            )
        )
    )
    assert len(markers) == 1 and url[1].endswith("/" + markers[0].removesuffix(".ok"))
    build = hashlib.sha256((fixture / "build.gradle.kts").read_bytes()).hexdigest()
    return GradleInputs(url[1].replace(r"\:", ":"), digest[1], markers, build, kotlin)


def probe(harness: ComponentHarness, fixture: Path, evidence: Path) -> ProbeReceipt:
    """Qualify components through a test-native entry, not installed-product admission."""
    root = harness.root
    assert root.is_absolute() and root.resolve() == root
    assert fixture.is_absolute() and fixture.resolve() == fixture
    assert not evidence.exists() and evidence.resolve() == evidence
    target = host_platform(platform.system(), platform.machine())
    assert isinstance(target, HostPlatform)
    desktop_before = desktop_processes(root, target)
    if harness.sandbox is not None:
        assert not target.is_linux and harness.sandbox.is_file()
    else:
        assert not desktop_before, "Supply a macOS sandbox when desktop IDEA runs"
    evidence.mkdir(parents=True)

    def call(case: str, arguments: tuple[str, ...]) -> dict[object, object]:
        result = harness.run(case, fixture, arguments, evidence)
        value = fields(json.loads(result.stdout))
        assert (result.returncode == 0) == (value.get("type") == "complete"), value
        return value

    def stop(case: str) -> None:
        result = harness.run(case, fixture, ("stop",), evidence)
        assert result.returncode == 0, result.stderr

    def compile_edited_fixture(fresh: str, paths: WorkspacePaths) -> CompilerResult:
        kotlin_class = fixture / (
            "forwarding/build/classes/kotlin/main/fixture/staticcallbacks/forwarding/ForwardingKt.class"
        )
        java_class = fixture / (
            "invocation/build/classes/java/main/fixture/reference/usage/ReadJavaReferences.class"
        )
        assert not kotlin_class.exists() and not java_class.exists()
        result = subprocess.run(
            (
                *(("/usr/bin/sandbox-exec", "-f", str(harness.sandbox)) if harness.sandbox else ()),
                "./gradlew",
                "--no-daemon",
                "--max-workers=2",
                "--console=plain",
                "--info",
                "-Pkotlin.compiler.execution.strategy=in-process",
                ":forwarding:compileKotlin",
                ":invocation:compileJava",
            ),
            cwd=fixture,
            env={
                "PATH": os.defpath,
                "HOME": str(paths.home),
                "JAVA_HOME": str(paths.javaHome),
                "GRADLE_USER_HOME": str(paths.home / ".gradle"),
            },
            capture_output=True,
            timeout=1080,
            check=False,
        )
        _ = (evidence / "compiler-build.stdout").write_bytes(result.stdout)
        _ = (evidence / "compiler-build.stderr").write_bytes(result.stderr)
        assert result.returncode == 0, "See compiler-build output and error logs"
        java_home = str(paths.javaHome)
        output = result.stdout.decode()
        assert f"[KOTLIN] Kotlin compilation 'jdkHome' argument: {java_home}\n" in output
        assert f"Compiling with toolchain '{java_home}'." in output
        kotlin_bytes = kotlin_class.read_bytes()
        java_bytes = java_class.read_bytes()
        assert kotlin_bytes.startswith(b"\xca\xfe\xba\xbe") and fresh.encode() in kotlin_bytes
        assert java_bytes.startswith(b"\xca\xfe\xba\xbe")
        return CompilerResult(
            java_home,
            hashlib.sha256(kotlin_bytes).hexdigest(),
            hashlib.sha256(java_bytes).hexdigest(),
        )

    assert not harness.store.exists()
    discovery = harness.run("passive-discovery", fixture, ("describe", "--help"), evidence)
    assert discovery.returncode == 0, discovery.stderr
    assert not harness.store.exists(), "component discovery created a workspace store"
    admitted = harness.run("admit-components", fixture, ("admit",), evidence)
    assert admitted.returncode == 0, admitted.stderr

    try:
        names = caller_names(complete(call("callers", CALLERS_QUERY)), fixture)
        assert len(names) == 4 and set(names) == CALLERS, names
        paths = harness.inspect(fixture, evidence, "inspect")
        state = paths.workspaceState.parent
        original = live_host(
            state, fixture, target, evidence, "host", runtime_home=paths.runtimeHome
        )
        contexts = java_reference_contexts(
            complete(
                call(
                    "java-references",
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
                )
            ),
            fixture,
        )
        rejected = call(
            "unknown-result",
            ("result", "read", "--ref", "result:v1:00000000-0000-4000-8000-000000000000", "--json"),
        )
        assert rejected.get("type") == "rejected_document", rejected
        fresh = "freshExternalCaller"
        forwarding = (
            fixture / "forwarding/src/main/kotlin/fixture/staticcallbacks/forwarding/Forwarding.kt"
        )
        with forwarding.open("a") as source:
            _ = source.write(
                f"\nfun {fresh}(block: () -> String): String = invokeCallback(block)\n"
            )
        changed = caller_names(complete(call("external-edit", CALLERS_QUERY)), fixture)
        assert len(changed) == 5 and set(changed) == CALLERS | {fresh}, changed
        compiler = compile_edited_fixture(fresh, paths)
        reused = live_host(
            state, fixture, target, evidence, "reused-host", runtime_home=paths.runtimeHome
        )
        assert (reused.pid, reused.started, reused.lifetime) == (
            original.pid,
            original.started,
            original.lifetime,
        )
        assert reused.workspaceLifetime == original.workspaceLifetime
        stop("stop")
        for path in (
            endpoint(state, "ide-hosted", fixture),
            endpoint(state, "ide-lifecycle", Path(original.runtimeHome)),
        ):
            assert not path.exists() and not path.with_name("host.sock").exists()
        assert caller_names(complete(call("after-restart", CALLERS_QUERY)), fixture) == changed
        restarted = live_host(
            state, fixture, target, evidence, "restarted-host", runtime_home=paths.runtimeHome
        )
        assert restarted.lifetime != original.lifetime and restarted.started > original.started
        assert restarted.workspaceLifetime != original.workspaceLifetime
        gradle = project_gradle_inputs(paths.workspaceDirectory, fixture)
    except BaseException as failure:
        try:
            stop("failure-stop")
        except Exception as stop_failure:
            failure.add_note(f"Private Host cleanup failed: {stop_failure}")
        raise
    else:
        stop("final-stop")
    desktop_after = desktop_processes(root, target)
    assert desktop_after == desktop_before
    receipt = ProbeReceipt(
        str(root),
        str(fixture),
        names,
        contexts,
        True,
        True,
        fresh,
        original,
        restarted,
        gradle,
        compiler,
        desktop_before,
        desktop_after,
    )
    _ = (evidence / "probe.json").write_text(json.dumps(asdict(receipt), indent=2) + "\n")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "fixture", "evidence", "harness", "runtime-archive", "control"):
        _ = parser.add_argument("--" + name, type=Path, required=True)
    _ = parser.add_argument("--runtime-sha256", required=True)
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--control-sha256", required=True)
    _ = parser.add_argument("--source-revision", required=True)
    _ = parser.add_argument("--sandbox", type=Path)
    args = parser.parse_args()
    harness = ComponentHarness(
        args.harness,
        args.root,
        args.runtime_archive,
        args.runtime_sha256,
        args.version,
        args.control,
        args.control_sha256,
        args.source_revision,
        args.sandbox,
    )
    print(json.dumps(asdict(probe(harness, args.fixture, args.evidence)), indent=2))


if __name__ == "__main__":
    main()
