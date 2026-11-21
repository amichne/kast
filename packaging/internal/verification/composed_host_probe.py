"""Exercise compiler callers, Java references, and rejection through an assembled Host."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

if not sys.flags.isolated:
    sys.path[:0] = [
        str(Path(__file__).resolve().parents[1] / "composition"),
        str(Path(__file__).resolve().parents[2]),
    ]

from composition_platform import HostPlatform
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
    gradle_native: Path | None = None,
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
    # Hosts no longer advertise a mode; a prior managed Host still names MANAGED_HEADLESS.
    assert identity.get("mode", "MANAGED_HEADLESS") == "MANAGED_HEADLESS", "Host mode"
    assert identity.get("ideaHome") == str(home), "Host IDEA home"
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
            and not (gradle_native is not None and Path(value).is_relative_to(gradle_native))
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
