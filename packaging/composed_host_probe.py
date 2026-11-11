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
class ProbeReceipt:
    runtime: str
    fixture: str
    callers: tuple[str, ...]
    javaReferenceContexts: tuple[str, ...]
    unknownResultRejected: bool
    foreignWorkspaceRejected: bool
    ordinaryQueryGrantAbsent: bool
    freshCaller: str
    host: HostIdentity
    restartedHost: HostIdentity
    gradle: GradleInputs
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
    return root / "state/home/.kast" / kind / digest / "endpoint.json"


def live_host(
    root: Path, fixture: Path, target: HostPlatform, evidence: Path, case: str
) -> HostIdentity:
    home = root / target.layout.home
    launcher = home / ("MacOS/idea" if target == HostPlatform.MACOS_ARM64 else "bin/idea")
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
    if target == HostPlatform.LINUX_X86_64:
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
    if target == HostPlatform.LINUX_X86_64:
        assert str(home / "jbr/lib/server/libjvm.so") in libraries
        external = tuple(
            value
            for value in libraries
            if not Path(value).is_relative_to(root) and Path(value).name not in LINUX_CORE_LIBRARIES
        )
        assert not external, external
    return result


def desktop_processes(root: Path, target: HostPlatform) -> tuple[str, ...]:
    if target != HostPlatform.MACOS_ARM64:
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


def probe(root: Path, fixture: Path, evidence: Path, sandbox: Path | None) -> ProbeReceipt:
    """Exercise the task entry, exact Host ownership, and compiler results."""
    assert root.is_absolute() and root.resolve() == root
    assert fixture.is_absolute() and fixture.resolve() == fixture
    assert not evidence.exists() and evidence.resolve() == evidence
    target = host_platform(platform.system(), platform.machine())
    assert isinstance(target, HostPlatform)
    desktop_before = desktop_processes(root, target)
    if sandbox is not None:
        assert target == HostPlatform.MACOS_ARM64 and sandbox.is_file()
        prefix = ("/usr/bin/sandbox-exec", "-f", str(sandbox))
    else:
        assert not desktop_before, "Supply a macOS sandbox when desktop IDEA runs"
        prefix = ()
    evidence.mkdir(parents=True)
    task, kast = str(root / "bin/kast-task"), str(root / "bin/kast")
    environment = {
        "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
        "HOME": str(root / "state/home"),
        "TMPDIR": str(root / "state/tmp"),
    }

    def run(case: str, arguments: tuple[str, ...]) -> subprocess.CompletedProcess[bytes]:
        command = (*prefix, *arguments)
        result = subprocess.run(
            command,
            cwd=fixture,
            env=environment,
            capture_output=True,
            timeout=1080,
            start_new_session=True,
            check=False,
        )
        _ = (evidence / (case + ".json")).write_bytes(result.stdout)
        _ = (evidence / (case + ".stderr")).write_bytes(result.stderr)
        _ = (evidence / (case + ".command.json")).write_text(
            json.dumps(asdict(Invocation(command, result.returncode)), indent=2) + "\n"
        )
        return result

    def call(case: str, arguments: tuple[str, ...]) -> dict[object, object]:
        result = run(case, (task, str(fixture), "--", kast, *arguments))
        value = fields(json.loads(result.stdout))
        assert (result.returncode == 0) == (value.get("type") == "complete"), value
        return value

    def stop(case: str) -> None:
        result = run(case, (kast, "stop", "--json"))
        assert result.returncode == 0, result.stderr
        assert fields(json.loads(result.stdout)).get("type") == "STOPPED", result.stdout

    assert not tuple((root / "state/home").rglob("trusted-paths.xml"))
    assert not tuple((root / "state/home/.kast").glob("*/**/endpoint.json"))
    before_trust = run("ordinary-query-before-grant", (kast, *CALLERS_QUERY))
    assert rejected_reason(before_trust) == "daemon-operation-lifecycle-trust-required"
    assert not tuple((root / "state/home").rglob("trusted-paths.xml"))
    literal = "space ; $quoted"
    shell = run(
        "literal-task",
        (
            task,
            str(fixture),
            "--",
            "/bin/sh",
            "-c",
            'printf "%s\\n" "$PWD" "$HOME" "$GRADLE_USER_HOME" "$1"; exit 23',
            "kast-test",
            literal,
        ),
    )
    assert shell.returncode == 23
    assert shell.stdout.decode().splitlines() == [
        str(fixture),
        str(root / "state/home"),
        str(root / "state/home/.gradle"),
        literal,
    ]
    foreign = fixture / "other-workspace"
    foreign.mkdir()
    _ = (foreign / "settings.gradle.kts").write_text('rootProject.name = "other-workspace"\n')
    assert run("foreign-workspace", (task, str(foreign), "--", "/bin/true")).returncode != 0
    (grant,) = tuple((root / "state/home").rglob("trusted-paths.xml"))

    try:
        names = caller_names(complete(call("callers", CALLERS_QUERY)), fixture)
        assert len(names) == 4 and set(names) == CALLERS, names
        original = live_host(root, fixture, target, evidence, "host")
        granted = grant.read_bytes()
        nested = run(
            "ordinary-query-nested-workspace",
            (kast, *CALLERS_QUERY, "--workspace", str(foreign)),
        )
        assert rejected_reason(nested) == "daemon-operation-lifecycle-trust-required"
        assert not endpoint(root, "ide-hosted", foreign).exists()
        assert grant.read_bytes() == granted
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
        reused = live_host(root, fixture, target, evidence, "reused-host")
        assert (reused.pid, reused.started, reused.lifetime) == (
            original.pid,
            original.started,
            original.lifetime,
        )
        assert reused.workspaceLifetime == original.workspaceLifetime
        stop("stop")
        for path in (
            endpoint(root, "ide-hosted", fixture),
            endpoint(root, "ide-lifecycle", Path(original.runtimeHome)),
        ):
            assert not path.exists() and not path.with_name("host.sock").exists()
        fenced = run("ordinary-query-stopped", (kast, *CALLERS_QUERY))
        assert fenced.returncode != 0, fenced.stdout
        assert not endpoint(root, "ide-hosted", fixture).exists()
        assert caller_names(complete(call("after-restart", CALLERS_QUERY)), fixture) == changed
        restarted = live_host(root, fixture, target, evidence, "restarted-host")
        assert restarted.lifetime != original.lifetime and restarted.started > original.started
        assert restarted.workspaceLifetime != original.workspaceLifetime
        gradle = project_gradle_inputs(root, fixture)
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
        True,
        fresh,
        original,
        restarted,
        gradle,
        desktop_before,
        desktop_after,
    )
    _ = (evidence / "probe.json").write_text(json.dumps(asdict(receipt), indent=2) + "\n")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "fixture", "evidence"):
        _ = parser.add_argument("--" + name, type=Path, required=True)
    _ = parser.add_argument("--sandbox", type=Path)
    args = parser.parse_args()
    print(json.dumps(asdict(probe(args.root, args.fixture, args.evidence, args.sandbox)), indent=2))


if __name__ == "__main__":
    main()
