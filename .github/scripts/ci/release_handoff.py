# /// script
# requires-python = ">=3.12"
# dependencies = []
# [tool.uv]
# no-build = true
# ///
"""Transfer one exact assembled release between CI hosts and qualify its macOS Control product."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path, PurePosixPath
from typing import TypeGuard

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).resolve().parent))

import gradle_graph  # noqa: E402 - shares this directory


class Kind(str, Enum):
    RELEASE = "release"
    BOOTSTRAP = "bootstrap"

    @property
    def filename(self) -> str:
        match self:
            case Kind.RELEASE:
                return "payload.tar.gz"
            case Kind.BOOTSTRAP:
                return "payload.zip"


class Extraction(str, Enum):
    COMPLETE = "COMPLETE"


class Failure(str, Enum):
    INVALID_RECORD = "INVALID_RECORD"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    PAYLOAD_MISMATCH = "PAYLOAD_MISMATCH"
    UNSAFE_ARCHIVE = "UNSAFE_ARCHIVE"
    WRONG_PLATFORM = "WRONG_PLATFORM"
    PROCESS_FAILED = "PROCESS_FAILED"
    EFFECT_FAILED = "EFFECT_FAILED"
    GRADLE_FAILED = "GRADLE_FAILED"
    GRADLE_TIMED_OUT = "GRADLE_TIMED_OUT"
    GRADLE_CANCELLED = "GRADLE_CANCELLED"


class GradleStage(Enum):
    """Each Gradle stage has a budget below its CI job timeout and its own evidence directory.

    A stage that exceeds its budget is stopped with a process snapshot and Gradle daemon thread
    dumps under `build/reports/native/<stage>/`, so a hang names its blocked frame instead of
    ending as a silent job cancellation.
    """

    # Qualification runs on the macOS runner, where Gradle 9.8.2's parallel project configuration
    # intermittently starves: its thread dump shows the configuration coordinator waiting while no
    # worker configures a project. One worker selects sequential configuration, as the macOS reset
    # suites already do (#1302).
    ASSEMBLY = ("assembly", 3000, 2)
    QUALIFICATION = ("qualification", 1200, 1)

    def __init__(self, directory: str, seconds: int, workers: int) -> None:
        self.directory = directory
        self.seconds = seconds
        self.workers = workers

    @property
    def reports(self) -> Path:
        return ROOT / "build/reports/native" / self.directory


@dataclass(frozen=True)
class Identity:
    revision: str
    tree: str
    version: str


@dataclass(frozen=True)
class Record:
    kind: Kind
    identity: Identity
    sha256: str


@dataclass(frozen=True)
class Admitted:
    archive: Path
    record: Record


def mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def decode(value: object) -> Record | Failure:
    if not mapping(value) or set(value) != {"kind", "identity", "sha256"}:
        return Failure.INVALID_RECORD
    identity = value["identity"]
    if not mapping(identity) or set(identity) != {"revision", "tree", "version"}:
        return Failure.INVALID_RECORD
    revision, tree, version = identity["revision"], identity["tree"], identity["version"]
    kind, digest = value["kind"], value["sha256"]
    if (
        not isinstance(revision, str)
        or not isinstance(tree, str)
        or not isinstance(version, str)
        or not isinstance(kind, str)
        or not isinstance(digest, str)
    ):
        return Failure.INVALID_RECORD
    if (
        not all(re.fullmatch(r"[0-9a-f]{40}", sha) and sha != "0" * 40 for sha in (revision, tree))
        or not re.fullmatch(r"\d+\.\d+\.\d+", version)
        or not re.fullmatch(r"[0-9a-f]{64}", digest)
    ):
        return Failure.INVALID_RECORD
    try:
        return Record(Kind(kind), Identity(revision, tree, version), digest)
    except ValueError:
        return Failure.INVALID_RECORD


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("duplicate field")
    return result


def admit(directory: Path, expected: Identity, kind: Kind) -> Admitted | Failure:
    record_path, archive = directory / "record.json", directory / kind.filename
    if (
        record_path.is_symlink()
        or archive.is_symlink()
        or not record_path.is_file()
        or not archive.is_file()
        or record_path.stat().st_size > 4096
    ):
        return Failure.INVALID_RECORD
    try:
        document: object = json.loads(record_path.read_text(), object_pairs_hook=unique_object)
    except ValueError:
        return Failure.INVALID_RECORD
    record = decode(document)
    if isinstance(record, Failure):
        return record
    if record.identity != expected or record.kind is not kind:
        return Failure.IDENTITY_MISMATCH
    if digest(archive) != record.sha256:
        return Failure.PAYLOAD_MISMATCH
    return Admitted(archive, record)


def safe_members(members: list[tarfile.TarInfo]) -> bool:
    names: set[str] = set()
    for member in members:
        path = PurePosixPath(member.name)
        if (
            path.is_absolute()
            or ".." in path.parts
            or not path.parts
            or member.name in names
            or not (member.isfile() or member.isdir())
        ):
            return False
        names.add(member.name)
    return bool(members)


def unpack(payload: Admitted, destination: Path) -> Failure | Extraction:
    return extract(payload.archive, destination)


def extract(archive_path: Path, destination: Path) -> Failure | Extraction:
    with tarfile.open(archive_path, "r:gz") as archive:
        members = archive.getmembers()
        if not safe_members(members):
            return Failure.UNSAFE_ARCHIVE
        archive.extractall(destination, members=members, filter="data")
    return Extraction.COMPLETE


def record(archive: Path, destination: Path, identity: Identity, kind: Kind) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    _ = shutil.copyfile(archive, destination / kind.filename)
    value = Record(kind, identity, digest(destination / kind.filename))
    _ = (destination / "record.json").write_text(json.dumps(asdict(value), indent=2) + "\n")


def pack(directory: Path, destination: Path, identity: Identity, kind: Kind) -> None:
    with tempfile.TemporaryDirectory() as temporary:
        archive = Path(temporary) / "payload.tar.gz"
        with tarfile.open(archive, "w:gz") as payload:
            for path in sorted(directory.rglob("*")):
                if path.is_symlink():
                    raise SystemExit(Failure.UNSAFE_ARCHIVE.value)
                payload.add(path, arcname=path.relative_to(directory).as_posix(), recursive=False)
        record(archive, destination, identity, kind)


def run(command: list[str], *, cwd: Path = ROOT, environment: dict[str, str] | None = None) -> None:
    result = subprocess.run(command, cwd=cwd, env=environment, check=False)
    if result.returncode:
        raise SystemExit(Failure.PROCESS_FAILED.value)


def current_identity(version: str) -> Identity:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise SystemExit(Failure.INVALID_RECORD.value)
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, text=True).strip()
    run(
        [
            "bash",
            ".github/scripts/release/admit-source.sh",
            "--repository-root",
            str(ROOT),
            "--expected-source-revision",
            revision,
        ]
    )
    return Identity(revision, tree, version)


def gradle(identity: Identity, *tasks: str, stage: GradleStage) -> None:
    command = (
        "./gradlew",
        "--no-daemon",
        "--no-parallel",
        f"--max-workers={stage.workers}",
        "-Dorg.gradle.jvmargs=-Xmx5g",
        f"-Pversion={identity.version}",
        f"-PkastSourceRevision={identity.revision}",
        *tasks,
        "--console=plain",
        # Keep diagnostic HTML profiling outside artifact qualification.
        "--info",
    )
    result = gradle_graph.run(command, ROOT, stage.reports, gradle_graph.Budget(stage.seconds))
    print(
        json.dumps({"stage": stage.directory, **{key: str(value) for key, value in asdict(result).items()}}),
        file=sys.stderr,
    )
    match result.outcome:
        case gradle_graph.Outcome.SUCCEEDED:
            return
        case gradle_graph.Outcome.TIMED_OUT:
            raise SystemExit(Failure.GRADLE_TIMED_OUT.value)
        case gradle_graph.Outcome.CANCELLED:
            raise SystemExit(Failure.GRADLE_CANCELLED.value)
        case gradle_graph.Outcome.PROCESS_FAILED | gradle_graph.Outcome.START_FAILED:
            raise SystemExit(Failure.GRADLE_FAILED.value)


def assemble(identity: Identity, destination: Path) -> None:
    """The Control executable cross-compiles, so Linux assembles the macOS release without a Mac."""
    gradle(
        identity,
        "-PkastControlPlatform=macos-aarch64",
        "assembleRelease",
        ":runtime:hosted:headlessBootstrapPlugin",
        "generateKastModuleKnowledge",
        stage=GradleStage.ASSEMBLY,
    )
    run(
        [
            "uv",
            "run",
            "--locked",
            "--script",
            ".github/scripts/release/finalize_assets.py",
            "--version",
            identity.version,
            "--source-revision",
            identity.revision,
        ]
    )
    pack(ROOT / f"build/release/v{identity.version}", destination, identity, Kind.RELEASE)
    record(bootstrap_path(identity), destination / "bootstrap", identity, Kind.BOOTSTRAP)


def bootstrap_path(identity: Identity) -> Path:
    return ROOT / "runtime/hosted/build/distributions" / f"kast-managed-headless-v{identity.version}-idea-262.zip"


def restore_bootstrap(directory: Path, identity: Identity) -> None:
    payload = admit(directory / "bootstrap", identity, Kind.BOOTSTRAP)
    if isinstance(payload, Failure):
        raise SystemExit(payload.value)
    target = bootstrap_path(identity)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("xb") as output, payload.archive.open("rb") as source:
        shutil.copyfileobj(source, output)


def restore_release(payload: Admitted) -> Path:
    if (platform.system(), platform.machine()) != ("Darwin", "arm64"):
        raise SystemExit(Failure.WRONG_PLATFORM.value)
    identity = payload.record.identity
    release = ROOT / f"build/release/v{identity.version}"
    release.mkdir(parents=True, exist_ok=False)
    failure = unpack(payload, release)
    if isinstance(failure, Failure):
        raise SystemExit(failure.value)
    return release


def qualify(payload: Admitted) -> None:
    release = restore_release(payload)
    identity = payload.record.identity
    control = release / f"kast-control-v{identity.version}-macos-aarch64.tar.gz"
    with tempfile.TemporaryDirectory(prefix="kast-qualification-") as temporary:
        product = Path(temporary)
        with tarfile.open(control) as archive:
            members = archive.getmembers()
            if not safe_members(members):
                raise SystemExit(Failure.UNSAFE_ARCHIVE.value)
            archive.extractall(product, members=members, filter="data")
        # The suite runs the exact assembled Control executable and JVM libraries, not a local rebuild.
        gradle(identity, f"-PkastControlProduct={product}", "publicLauncherTest", stage=GradleStage.QUALIFICATION)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("stage", choices=("assemble", "restore", "qualify"))
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--input", type=Path, default=ROOT / "build/handoff-input")
    _ = parser.add_argument("--output", type=Path, default=ROOT / "build/handoff-output")
    args = parser.parse_args()
    identity = current_identity(args.version)
    if args.stage == "assemble":
        assemble(identity, args.output)
    else:
        payload = admit(args.input, identity, Kind.RELEASE)
        if isinstance(payload, Failure):
            raise SystemExit(payload.value)
        if args.stage == "restore":
            _ = restore_release(payload)
            restore_bootstrap(args.input, identity)
        else:
            qualify(payload)
    if current_identity(args.version) != identity:
        raise SystemExit(Failure.IDENTITY_MISMATCH.value)


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.CalledProcessError, tarfile.TarError) as error:
        raise SystemExit(f"{Failure.EFFECT_FAILED.value}: {type(error).__name__}") from None
