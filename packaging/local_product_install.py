"""Build a qualified local product or install it under an isolated contributor root.

Contributor-only. The checkout's own CI recipe builds, assembles and natively qualifies
a full macOS ARM64 composition, then the production activation installs it under an
explicit root. Only release acquisition is virtualized, as in `local_product_probe.py`.
This never replaces the user's installation and never establishes release qualification.

    mise exec -- uv run --locked --project packaging python packaging/local_product_install.py \
      install --root ~/.local/share/kast-local/pr-1226 [--source CHECKOUT]
    cd WORKSPACE && ~/.local/share/kast-local/pr-1226/bin/kast query --name MyClass --json
    mise exec -- uv run --locked --project packaging python packaging/local_product_install.py \
      remove --root ~/.local/share/kast-local/pr-1226
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
from collections.abc import Mapping
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal

MARKER = "local-product.json"
SCHEMA_VERSION = 1
DONOR = "idea-2026.2.2-aarch64.dmg"
EPOCH = 1_767_225_600  # 2026-01-01T00:00:00Z; versions count minutes after it.
GRADLE_TASKS = (
    "assembleRelease",
    ":runtime:hosted:headlessBootstrapPlugin",
    ":cli:nativeComponentQualificationCompile",
)
# Bound to the isolated root; every other inherited variable, including KAST_* settings,
# passes through so the installed product reads the caller's configuration.
BOUND = ("HOME", "KAST_INSTALL_ROOT", "KAST_BIN_DIR", "KAST_SESSION_ROOT", "VIRTUAL_ENV")
XDG = (
    ("XDG_CONFIG_HOME", ".config"),
    ("XDG_CACHE_HOME", ".cache"),
    ("XDG_DATA_HOME", ".local/share"),
    ("XDG_STATE_HOME", ".local/state"),
)


class Stage(StrEnum):
    SOURCE = "SOURCE"
    ROOT = "ROOT"
    INPUTS = "INPUTS"
    BUILD = "BUILD"
    ASSEMBLY = "ASSEMBLY"
    QUALIFICATION = "QUALIFICATION"
    SOURCE_READMISSION = "SOURCE_READMISSION"
    INSTALLATION = "INSTALLATION"
    REMOVAL = "REMOVAL"


class Failure(StrEnum):
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    VERSION_REJECTED = "VERSION_REJECTED"
    SOURCE_REJECTED = "SOURCE_REJECTED"
    SOURCE_CHANGED = "SOURCE_CHANGED"
    ROOT_REJECTED = "ROOT_REJECTED"
    DIGEST_MISMATCH = "DIGEST_MISMATCH"
    DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
    COMMAND_FAILED = "COMMAND_FAILED"
    INSTALLATION_REJECTED = "INSTALLATION_REJECTED"
    PROCESSES_REMAIN = "PROCESSES_REMAIN"


class Rejected(Exception):
    def __init__(self, stage: Stage, failure: Failure, log: Path | None = None):
        super().__init__(f"{stage}: {failure}")
        self.stage = stage
        self.failure = failure
        self.log = log


@dataclass(frozen=True)
class SourceIdentity:
    checkout: str
    revision: str
    tree: str


@dataclass(frozen=True)
class Receipt:
    version: str
    source: SourceIdentity
    root: str
    launcher: str
    controlSha256: str
    bundleSha256: str
    runtimeSha256: str
    qualificationSha256: str
    releaseQualification: str = "NOT_ESTABLISHED"
    schemaVersion: int = SCHEMA_VERSION


@dataclass(frozen=True)
class BuiltProduct:
    source: SourceIdentity
    version: str
    work: Path
    bundle: Path
    runtime: Path
    qualification: Path
    python_archive: Path
    dependency: Path


@dataclass(frozen=True)
class BuildReceipt:
    directory: str
    version: str
    sourceRevision: str
    sourceTree: str
    buildRecordSha256: str
    releaseQualification: Literal["NOT_ESTABLISHED"] = "NOT_ESTABLISHED"
    type: Literal["LOCAL_PRODUCT_BUILT"] = "LOCAL_PRODUCT_BUILT"


def event(stage: Stage, outcome: str, **detail: str) -> None:
    payload = {"event": "kast_local_install", "stage": stage, "outcome": outcome, **detail}
    print(json.dumps(payload), file=sys.stderr, flush=True)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def select_version(now: float) -> str:
    """A fresh positive patch number per minute keeps each build's identity immutable."""
    minutes = int(now - EPOCH) // 60
    if minutes < 1:
        raise ValueError("clock precedes the version epoch")
    return f"0.0.{minutes}"


def installed_environment(root: Path, inherited: Mapping[str, str]) -> dict[str, str]:
    home = root / "home"
    value = {key: item for key, item in inherited.items() if key not in BOUND}
    value["HOME"] = str(home)
    value["KAST_INSTALL_ROOT"] = str(home / ".local/share/kast")
    for variable, suffix in XDG:
        value[variable] = str(home / suffix)
    return value


def launcher_source(root: Path) -> str:
    """Self-contained: the launcher must not import this checkout, which may move or change."""
    home = root / "home"
    bound = {"HOME": str(home), "KAST_INSTALL_ROOT": str(home / ".local/share/kast")}
    bound.update({variable: str(home / suffix) for variable, suffix in XDG})
    removed = [key for key in BOUND if key not in bound]
    return (
        "#!/bin/sh\nset -eu\n"
        + "".join(f"export {key}={shlex.quote(value)}\n" for key, value in bound.items())
        + f"unset {' '.join(removed)}\n"
        + f'exec {shlex.quote(str(home / ".local/bin/kast"))} "$@"\n'
    )


def sandbox_profile(home: Path) -> str:
    """Native qualification may not read or write the caller's home, profile or installation."""
    return f'(version 1)\n(allow default)\n(deny file-read-data file-write* (subpath "{home}"))\n'


def admit_new_root(root: Path) -> Path:
    try:
        parent = root.parent.resolve()
        missing: list[Path] = []
        ancestor = parent
        while not os.path.lexists(ancestor):
            missing.append(ancestor)
            ancestor = ancestor.parent
        if not ancestor.is_dir() or ancestor.stat().st_uid != os.getuid():
            raise Rejected(Stage.ROOT, Failure.ROOT_REJECTED)
        for directory in reversed(missing):
            directory.mkdir(mode=0o700)
        if parent.is_symlink() or not parent.is_dir() or parent.stat().st_uid != os.getuid():
            raise Rejected(Stage.ROOT, Failure.ROOT_REJECTED)
    except OSError:
        raise Rejected(Stage.ROOT, Failure.ROOT_REJECTED) from None
    selected = parent / root.name
    if os.path.lexists(selected):
        raise Rejected(Stage.ROOT, Failure.ROOT_REJECTED)
    return selected


def admit_owned_root(root: Path) -> tuple[Path, Path, str]:
    """Only a root whose marker names itself is ours to stop, uninstall and delete."""
    selected = root.resolve(strict=True)
    marker = selected / MARKER
    if (
        selected != root.absolute()
        or selected.stat().st_uid != os.getuid()
        or marker.is_symlink()
        or not marker.is_file()
    ):
        raise Rejected(Stage.ROOT, Failure.ROOT_REJECTED)
    value: object = json.loads(marker.read_bytes())
    match value:
        case {
            "schemaVersion": 1,
            "root": str(named),
            "launcher": str(launcher),
            "version": str(version),
        } if named == str(selected):
            return selected, Path(launcher), version
        case _:
            raise Rejected(Stage.ROOT, Failure.ROOT_REJECTED)


def discard_tree(path: Path) -> None:
    """Assembled runtimes are deliberately read-only; restore owner access only on owned nodes."""
    uid = os.getuid()
    for directory, names, _ in os.walk(path, topdown=True, followlinks=False):
        for name in [".", *names]:
            node = Path(directory, name)
            status = node.lstat()
            if stat.S_ISLNK(status.st_mode):
                continue
            if status.st_uid != uid:
                raise Rejected(Stage.REMOVAL, Failure.ROOT_REJECTED)
            node.chmod(stat.S_IMODE(status.st_mode) | stat.S_IRWXU)
    shutil.rmtree(path)


def run_logged(stage: Stage, command: list[str], cwd: Path, log: Path) -> None:
    event(stage, "STARTED", log=str(log))
    with log.open("xb") as output:
        result = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise Rejected(stage, Failure.COMMAND_FAILED, log)
    event(stage, "COMPLETED")


def git(checkout: Path, *arguments: str) -> str:
    return subprocess.check_output(["git", "-C", str(checkout), *arguments], text=True).strip()


def admit_source(checkout: Path, stage: Stage = Stage.SOURCE) -> SourceIdentity:
    """Reuse release source admission: one exact commit with no staged, unstaged or new files."""
    root = Path(git(checkout, "rev-parse", "--show-toplevel")).resolve()
    revision = git(root, "rev-parse", "--verify", "HEAD^{commit}")
    admitted = subprocess.run(
        [
            "bash",
            str(root / ".github/scripts/release/admit-source.sh"),
            "--expected-source-revision",
            revision,
            "--repository-root",
            str(root),
        ],
        capture_output=True,
        text=True,
    )
    if admitted.returncode or admitted.stdout.strip() != revision:
        _ = sys.stderr.write(admitted.stderr)
        raise Rejected(stage, Failure.SOURCE_REJECTED)
    return SourceIdentity(str(root), revision, git(root, "rev-parse", "HEAD^{tree}"))


def fetch(cache: Path, name: str, url: str, expected: str) -> Path:
    target = cache / expected / name
    if target.is_file() and not target.is_symlink():
        if digest(target) == expected:
            return target
        raise Rejected(Stage.INPUTS, Failure.DIGEST_MISMATCH)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    partial = target.with_name(f".{name}.{os.getpid()}.partial")
    event(Stage.INPUTS, "DOWNLOADING", name=name)
    try:
        with urllib.request.urlopen(url, timeout=120) as response, partial.open("xb") as output:
            _ = shutil.copyfileobj(response, output, 1 << 20)
    except OSError:
        partial.unlink(missing_ok=True)
        raise Rejected(Stage.INPUTS, Failure.DOWNLOAD_FAILED) from None
    if digest(partial) != expected:
        partial.unlink()
        raise Rejected(Stage.INPUTS, Failure.DIGEST_MISMATCH)
    _ = partial.rename(target)
    return target


def build_product(arguments: argparse.Namespace) -> BuiltProduct:
    if (platform.system(), platform.machine()) != ("Darwin", "arm64"):
        raise Rejected(Stage.SOURCE, Failure.UNSUPPORTED_PLATFORM)
    source = admit_source(arguments.source)
    checkout = Path(source.checkout)
    version = arguments.version or select_version(time.time())
    if not re.fullmatch(r"0\.0\.[1-9][0-9]*", version):
        raise Rejected(Stage.SOURCE, Failure.VERSION_REJECTED)
    event(Stage.SOURCE, "ADMITTED", revision=source.revision, version=version)

    # Pins and adapters come from the checkout being installed, never from this script's copy.
    sys.path[:0] = [str(checkout / "packaging"), str(checkout / ".github/scripts/release")]
    from composition_platform import HostPlatform
    from private_interpreter import DEPENDENCY_SHA256, DEPENDENCY_URL, PINS

    cache = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "kast-local-product"
    pin = next(pin for pin in PINS if pin.target == HostPlatform.MACOS_ARM64.value)
    donor = fetch(
        cache,
        DONOR,
        f"https://download.jetbrains.com/idea/{DONOR}",
        HostPlatform.MACOS_ARM64.layout.archive_sha256,
    )
    python_archive = fetch(cache, "python.tar.gz", pin.url, pin.sha256)
    dependency = fetch(cache, "defusedxml.whl", DEPENDENCY_URL, DEPENDENCY_SHA256)
    event(Stage.INPUTS, "COMPLETED")

    # Qualification runs inside a sandbox that denies the caller's home, so the work stays out.
    work = Path(tempfile.mkdtemp(prefix="kast-local-install-", dir="/private/tmp"))
    logs = work / "logs"
    logs.mkdir()
    event(Stage.ROOT, "WORK", path=str(work))
    run_logged(
        Stage.BUILD,
        [
            "mise",
            "exec",
            "--",
            "./gradlew",
            "--console=plain",
            f"-Pversion={version}",
            f"-PkastSourceRevision={source.revision}",
            *GRADLE_TASKS,
        ],
        checkout,
        logs / "build.log",
    )
    product = work / "product"
    run_logged(
        Stage.ASSEMBLY,
        [
            sys.executable,
            str(checkout / "packaging/composed_release.py"),
            "assemble",
            "--archive",
            str(donor),
            "--bootstrap",
            str(
                checkout
                / "runtime/hosted/build/distributions"
                / f"kast-managed-headless-v{version}-idea-262.zip"
            ),
            "--assets",
            str(checkout / f"build/release/v{version}"),
            "--root",
            str(work / "assembly"),
            "--output",
            str(product),
            "--version",
            version,
            "--source-revision",
            source.revision,
            "--full-native-archives",
        ],
        checkout,
        logs / "assembly.log",
    )
    bundle = product / f"kast-composed-setup-v{version}-macos-aarch64.tar.gz"
    runtime = product / f"kast-runtime-v{version}-macos-aarch64.zip"
    harness = work / "kast-component-qualification"
    _ = shutil.copy2(
        checkout
        / "cli/build/native/nativeComponentQualificationCompile"
        / "kast-component-qualification",
        harness,
    )
    profile = work / "isolation.sb"
    _ = profile.write_text(sandbox_profile(Path.home().resolve()))
    run_logged(
        Stage.QUALIFICATION,
        [
            sys.executable,
            str(checkout / "packaging/composed_release.py"),
            "qualify",
            "--archive",
            str(bundle),
            "--archive-sha256",
            digest(bundle),
            "--runtime-archive",
            str(runtime),
            "--runtime-sha256",
            digest(runtime),
            "--root",
            str(work / "qualification-root"),
            "--fixture",
            str(work / "qualification-fixture"),
            "--evidence",
            str(work / "qualification"),
            "--version",
            version,
            "--source-revision",
            source.revision,
            "--harness",
            str(harness),
            "--harness-sha256",
            digest(harness),
            "--sandbox",
            str(profile),
        ],
        checkout,
        logs / "qualification.log",
    )
    if admit_source(checkout, Stage.SOURCE_READMISSION) != source:
        raise Rejected(Stage.SOURCE_READMISSION, Failure.SOURCE_CHANGED)

    return BuiltProduct(
        source,
        version,
        work,
        bundle,
        runtime,
        work / "qualification/qualification.json",
        python_archive,
        dependency,
    )


def export_product(built: BuiltProduct, destination: Path) -> BuildReceipt:
    """Seal actual native evidence without inventing CI runs or another platform."""
    import product_producer

    import managed_release as release

    java_release = built.work / "java-release"
    with zipfile.ZipFile(built.runtime) as archive:
        _ = java_release.write_bytes(
            archive.read("host/Kast.app/Contents/jbr/Contents/Home/release")
        )
    try:
        target = product_producer.seal_target(
            Path(built.source.checkout),
            built.bundle.parent,
            built.qualification,
            java_release,
            built.source.revision,
            built.source.tree,
            built.version,
            release.LocalProductProducer(),
            "macos-aarch64",
            "macos-aarch64",
        ).target
    except product_producer.Rejected:
        raise Rejected(Stage.QUALIFICATION, Failure.INSTALLATION_REJECTED) from None
    record = release.LocalProductRecord(
        4,
        release.REPOSITORY,
        built.source.revision,
        built.source.tree,
        built.version,
        release.LocalProductProducer(),
        (target,),
    )
    raw = (json.dumps(asdict(record), separators=(",", ":")) + "\n").encode()
    if release.decode_installed_product_record(raw) != record:
        raise Rejected(Stage.QUALIFICATION, Failure.INSTALLATION_REJECTED)
    destination.mkdir(mode=0o700)
    payload = destination / "payload"
    payload.mkdir(mode=0o700)
    for asset in record.assets():
        _ = shutil.copyfile(built.bundle.parent / asset.name, payload / asset.name)
    _ = (destination / "build-record.json").write_bytes(raw)
    evidence = destination / "evidence"
    _ = shutil.copytree(built.work / "qualification", evidence / "qualification")
    _ = shutil.copytree(built.work / "logs", evidence / "logs")
    return BuildReceipt(
        str(destination),
        built.version,
        built.source.revision,
        built.source.tree,
        hashlib.sha256(raw).hexdigest(),
    )


def build(arguments: argparse.Namespace) -> BuildReceipt:
    destination = admit_new_root(arguments.output)
    built = build_product(arguments)
    result = export_product(built, destination)
    discard_tree(built.work)
    return result


def install(arguments: argparse.Namespace) -> Receipt:
    root = admit_new_root(arguments.root)
    built = build_product(arguments)
    source, version, work = built.source, built.version, built.work
    bundle, runtime = built.bundle, built.runtime
    python_archive, dependency = built.python_archive, built.dependency
    event(Stage.INSTALLATION, "STARTED", root=str(root))
    from local_product_probe import prepare_inputs
    from product_lifecycle_probe import Probe

    qualification = work / "qualification/qualification.json"
    record = prepare_inputs(bundle, runtime, qualification, work)
    root.mkdir(mode=0o700)
    for name in ("home", "evidence", "bin"):
        (root / name).mkdir(mode=0o700)
    launcher = root / "bin/kast"
    _ = launcher.write_text(launcher_source(root))
    launcher.chmod(0o700)
    # A pending marker lets `remove` reclaim a partial installation under the same authority.
    pending = {"root": str(root), "launcher": str(launcher), "version": version}
    _ = (root / MARKER).write_text(json.dumps({**pending, "schemaVersion": SCHEMA_VERSION}))
    environment = installed_environment(root, os.environ)
    os.environ.clear()
    os.environ.update(environment)
    probe = Probe(
        root / "home/.local/share/kast",
        root / "home",
        root / "evidence",
        python_archive,
        dependency,
        environment,
    )
    try:
        # Activation reports on stdout; this command's stdout carries only its own receipt.
        with contextlib.redirect_stdout(sys.stderr):
            probe.install(work / "inputs", record)
    except ValueError:
        raise Rejected(Stage.INSTALLATION, Failure.INSTALLATION_REJECTED) from None
    receipt = Receipt(
        version,
        source,
        str(root),
        str(launcher),
        digest(probe.control),
        digest(bundle),
        digest(runtime),
        digest(qualification),
    )
    _ = shutil.copytree(work / "qualification", root / "evidence/qualification")
    _ = (root / MARKER).write_text(json.dumps(asdict(receipt), indent=2) + "\n")
    event(Stage.INSTALLATION, "COMPLETED")
    discard_tree(work)
    return receipt


def remaining_processes(root: Path) -> list[str]:
    """Owned processes run below the isolated HOME; this command's own argv names only the root."""
    listing = subprocess.check_output(["/bin/ps", "-axo", "pid=,command="], text=True)
    return [line.strip() for line in listing.splitlines() if str(root / "home") in line]


def remove(arguments: argparse.Namespace) -> dict[str, str]:
    root, launcher, version = admit_owned_root(arguments.root)
    event(Stage.REMOVAL, "STARTED", root=str(root))
    if (root / "home/.local/bin/kast").exists():
        for step in (["stop", "--all", "--json"], ["uninstall"]):
            # Child reports go to stderr; this command's stdout carries only its own receipt.
            if subprocess.run([str(launcher), *step], stdout=sys.stderr).returncode:
                raise Rejected(Stage.REMOVAL, Failure.COMMAND_FAILED)
    if remaining_processes(root):
        raise Rejected(Stage.REMOVAL, Failure.PROCESSES_REMAIN)
    discard_tree(root)
    event(Stage.REMOVAL, "COMPLETED")
    return {"type": "removed", "root": str(root), "version": version}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Build a qualified local product or install an isolated contributor product."
    )
    commands = parser.add_subparsers(dest="command", required=True)
    selected = commands.add_parser("install")
    _ = selected.add_argument("--root", type=Path, required=True)
    _ = selected.add_argument("--source", type=Path, default=Path.cwd())
    _ = selected.add_argument("--version")
    produced = commands.add_parser("build")
    _ = produced.add_argument("--output", type=Path, required=True)
    _ = produced.add_argument("--source", type=Path, default=Path.cwd())
    _ = produced.add_argument("--version")
    removed = commands.add_parser("remove")
    _ = removed.add_argument("--root", type=Path, required=True)
    arguments = parser.parse_args(argv)
    try:
        result: object
        match arguments.command:
            case "build":
                result = asdict(build(arguments))
            case "install":
                result = asdict(install(arguments))
            case "remove":
                result = remove(arguments)
            case _:
                parser.error("unknown local product command")
    except Rejected as rejected:
        detail = {"log": str(rejected.log)} if rejected.log is not None else {}
        event(rejected.stage, rejected.failure, **detail)
        print(
            json.dumps(
                {"type": "rejected", "stage": rejected.stage, "failure": rejected.failure, **detail}
            )
        )
        return 1
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
