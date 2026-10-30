# /// script
# requires-python = ">=3.12"
# dependencies = []
# [tool.uv]
# no-build = true
# ///
"""Transfer exact JVM inputs and both target-native executables between CI hosts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path, PurePosixPath
from typing import TypeGuard

ROOT = Path(__file__).resolve().parents[3]


class Kind(str, Enum):
    INPUTS = "inputs"
    NATIVE = "native"
    RELEASE = "release"


class Extraction(str, Enum):
    COMPLETE = "COMPLETE"


class Failure(str, Enum):
    INVALID_RECORD = "INVALID_RECORD"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    PAYLOAD_MISMATCH = "PAYLOAD_MISMATCH"
    UNSAFE_ARCHIVE = "UNSAFE_ARCHIVE"
    WRONG_PLATFORM = "WRONG_PLATFORM"
    TOOLCHAIN_MISMATCH = "TOOLCHAIN_MISMATCH"
    PROCESS_FAILED = "PROCESS_FAILED"
    EFFECT_FAILED = "EFFECT_FAILED"


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
    toolchainReleaseSha256: str


@dataclass(frozen=True)
class Admitted:
    archive: Path
    record: Record


def mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def decode(value: object) -> Record | Failure:
    if not mapping(value) or set(value) != {"kind", "identity", "sha256", "toolchainReleaseSha256"}:
        return Failure.INVALID_RECORD
    identity = value["identity"]
    if not mapping(identity) or set(identity) != {"revision", "tree", "version"}:
        return Failure.INVALID_RECORD
    revision, tree, version = identity["revision"], identity["tree"], identity["version"]
    kind, digest = value["kind"], value["sha256"]
    toolchain = value["toolchainReleaseSha256"]
    if (
        not isinstance(revision, str)
        or not isinstance(tree, str)
        or not isinstance(version, str)
        or not isinstance(kind, str)
        or not isinstance(digest, str)
        or not isinstance(toolchain, str)
    ):
        return Failure.INVALID_RECORD
    if (
        not all(re.fullmatch(r"[0-9a-f]{40}", sha) and sha != "0" * 40 for sha in (revision, tree))
        or not re.fullmatch(r"\d+\.\d+\.\d+", version)
        or not re.fullmatch(r"[0-9a-f]{64}", digest)
        or not re.fullmatch(r"[0-9a-f]{64}", toolchain)
    ):
        return Failure.INVALID_RECORD
    try:
        return Record(Kind(kind), Identity(revision, tree, version), digest, toolchain)
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
    record_path, archive = directory / "record.json", directory / "payload.tar.gz"
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


def record(
    archive: Path, destination: Path, identity: Identity, kind: Kind, toolchain: str
) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    _ = shutil.copyfile(archive, destination / "payload.tar.gz")
    value = Record(kind, identity, digest(destination / "payload.tar.gz"), toolchain)
    _ = (destination / "record.json").write_text(json.dumps(asdict(value), indent=2) + "\n")


def pack(
    directory: Path, destination: Path, identity: Identity, kind: Kind, toolchain: str
) -> None:
    with tempfile.TemporaryDirectory() as temporary:
        archive = Path(temporary) / "payload.tar.gz"
        with tarfile.open(archive, "w:gz") as payload:
            for path in sorted(directory.rglob("*")):
                if path.is_symlink():
                    raise SystemExit(Failure.UNSAFE_ARCHIVE.value)
                payload.add(path, arcname=path.relative_to(directory).as_posix(), recursive=False)
        record(archive, destination, identity, kind, toolchain)


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


def gradle(identity: Identity, *tasks: str) -> None:
    run(
        [
            "./gradlew",
            "--no-daemon",
            "--max-workers=2",
            "-Dorg.gradle.jvmargs=-Xmx5g",
            f"-Pversion={identity.version}",
            f"-PkastSourceRevision={identity.revision}",
            *tasks,
            "--console=plain",
            "--profile",
            "--info",
        ]
    )


def stage_inputs(identity: Identity, destination: Path) -> None:
    gradle(
        identity, ":distribution:cli:assembleNativeImageInputs", ":cli:assembleNativeImageInputs"
    )
    with tempfile.TemporaryDirectory(prefix="kast-native-inputs-") as temporary:
        inputs = Path(temporary)
        for name, module in (("kast", "distribution/cli"), ("kast-query", "cli")):
            failure = extract(
                ROOT / module / "build/distributions/native-inputs.tar.gz", inputs / name
            )
            if isinstance(failure, Failure):
                raise SystemExit(failure.value)
        pack(
            inputs,
            destination,
            identity,
            Kind.INPUTS,
            digest(Path(os.environ["JAVA_HOME"]) / "release"),
        )


def native(payload: Admitted, destination: Path) -> None:
    if (platform.system(), platform.machine()) != ("Darwin", "arm64"):
        raise SystemExit(Failure.WRONG_PLATFORM.value)
    with tempfile.TemporaryDirectory(prefix="kast-native-") as temporary:
        inputs = Path(temporary)
        failure = unpack(payload, inputs)
        if isinstance(failure, Failure):
            raise SystemExit(failure.value)
        if set(path.name for path in inputs.iterdir()) != {"kast", "kast-query"}:
            raise SystemExit(Failure.INVALID_RECORD.value)
        with tempfile.TemporaryDirectory(prefix="kast-native-output-") as output_directory:
            output = Path(output_directory)
            for name in ("kast", "kast-query"):
                executable_directory = inputs / name / "output"
                executable_directory.mkdir()
                executable = executable_directory / name
                compile_native(inputs / name, executable, payload.record.identity)
                _ = shutil.copyfile(executable, output / name)
            pack(
                output,
                destination,
                payload.record.identity,
                Kind.NATIVE,
                digest(Path(os.environ["JAVA_HOME"]) / "release"),
            )


def compile_native(inputs: Path, executable: Path, identity: Identity) -> None:
    properties = (inputs / "image.properties").read_text().splitlines()
    classes = [
        line.removeprefix("mainClass=") for line in properties if line.startswith("mainClass=")
    ]
    if len(classes) != 1 or not re.fullmatch(r"[A-Za-z0-9_.]+", classes[0]):
        raise SystemExit(Failure.INVALID_RECORD.value)
    report_directory = ROOT / "build/reports/native"
    if executable.name == "kast-query":
        report_directory = report_directory / "kast-query"
    metrics = report_directory / "build-output.json"
    metrics.parent.mkdir(parents=True, exist_ok=True)
    run(
        [
            str(Path(os.environ["JAVA_HOME"]) / "bin/native-image"),
            "@native-image.args",
            "@native-image-macos.args",
            "-cp",
            "lib/*",
            classes[0],
            "-o",
            str(executable),
            "-H:+UnlockExperimentalVMOptions",
            f"-H:BuildOutputJSONFile={metrics}",
            "-H:-UnlockExperimentalVMOptions",
        ],
        cwd=inputs,
    )
    query = executable.name == "kast-query"
    result = subprocess.check_output(
        [str(executable), "--help" if query else "--version"], text=True
    ).strip()
    if (query and not result.startswith("kast query [")) or (
        not query and result != identity.version
    ):
        raise SystemExit(Failure.IDENTITY_MISMATCH.value)
    target = subprocess.check_output(["xcrun", "vtool", "-show-build", str(executable)], text=True)
    _ = (metrics.parent / "macos-build.txt").write_text(target)
    if re.findall(r"^\s*minos\s+(\S+)", target, re.MULTILINE) != ["15.0"]:
        raise SystemExit(Failure.WRONG_PLATFORM.value)


def assemble(payload: Admitted, destination: Path) -> None:
    native_dir = ROOT / "build/imported-native"
    native_dir.mkdir(parents=True, exist_ok=False)
    failure = unpack(payload, native_dir)
    if isinstance(failure, Failure):
        raise SystemExit(failure.value)
    executables = (native_dir / "kast", native_dir / "kast-query")
    if set(native_dir.iterdir()) != set(executables):
        raise SystemExit(Failure.WRONG_PLATFORM.value)
    for executable in executables:
        with executable.open("rb") as stream:
            if stream.read(8) != bytes.fromhex("cffaedfe0c000001"):
                raise SystemExit(Failure.WRONG_PLATFORM.value)
        executable.chmod(0o755)
    gradle(
        payload.record.identity,
        f"-PkastNativeExecutable={executables[0]}",
        f"-PkastQueryNativeExecutable={executables[1]}",
        "-PkastControlPlatform=macos-aarch64",
        "assembleRelease",
        "generateKastModuleKnowledge",
    )
    run(
        [
            "uv",
            "run",
            "--locked",
            "--script",
            ".github/scripts/release/finalize_assets.py",
            "--version",
            payload.record.identity.version,
            "--source-revision",
            payload.record.identity.revision,
        ]
    )
    pack(
        ROOT / f"build/release/v{payload.record.identity.version}",
        destination,
        payload.record.identity,
        Kind.RELEASE,
        payload.record.toolchainReleaseSha256,
    )


def qualify(payload: Admitted) -> None:
    if (platform.system(), platform.machine()) != ("Darwin", "arm64"):
        raise SystemExit(Failure.WRONG_PLATFORM.value)
    if payload.record.toolchainReleaseSha256 != digest(Path(os.environ["JAVA_HOME"]) / "release"):
        raise SystemExit(Failure.TOOLCHAIN_MISMATCH.value)
    identity = payload.record.identity
    release = ROOT / f"build/release/v{identity.version}"
    release.mkdir(parents=True, exist_ok=False)
    failure = unpack(payload, release)
    if isinstance(failure, Failure):
        raise SystemExit(failure.value)
    control = release / f"kast-control-v{identity.version}-macos-aarch64.tar.gz"
    plugins = list(release.glob(f"kast-ide-hosted-v{identity.version}-idea-*.zip"))
    if len(plugins) != 1:
        raise SystemExit(Failure.INVALID_RECORD.value)
    with tempfile.TemporaryDirectory(prefix="kast-qualification-") as temporary:
        product = Path(temporary)
        with tarfile.open(control) as archive:
            members = archive.getmembers()
            if not safe_members(members):
                raise SystemExit(Failure.UNSAFE_ARCHIVE.value)
            archive.extractall(product, members=members, filter="data")
        run(
            ["python3", "packaging/run-installed-product.py"],
            environment={
                **os.environ,
                "KAST_INSTALLED_PRODUCT": str(product),
                "KAST_CONTROL_ARCHIVE": str(control),
                "KAST_HOSTED_PLUGIN_ARCHIVE": str(plugins[0]),
                "KAST_HOST_RELEASE_RECORD": str(
                    release / f"kast-host-release-v{identity.version}.json"
                ),
                "KAST_ACCEPTANCE_JAVA_EXECUTABLE": str(Path(os.environ["JAVA_HOME"]) / "bin/java"),
            },
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("stage", choices=("inputs", "native", "assemble", "qualify"))
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--input", type=Path, default=ROOT / "build/handoff-input")
    _ = parser.add_argument("--output", type=Path, default=ROOT / "build/handoff-output")
    args = parser.parse_args()
    identity = current_identity(args.version)
    if args.stage == "inputs":
        stage_inputs(identity, args.output)
    else:
        kind = {"native": Kind.INPUTS, "assemble": Kind.NATIVE, "qualify": Kind.RELEASE}[args.stage]
        payload = admit(args.input, identity, kind)
        if isinstance(payload, Failure):
            raise SystemExit(payload.value)
        if args.stage == "native":
            native(payload, args.output)
        elif args.stage == "assemble":
            assemble(payload, args.output)
        else:
            qualify(payload)
    if current_identity(args.version) != identity:
        raise SystemExit(Failure.IDENTITY_MISMATCH.value)


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.CalledProcessError, tarfile.TarError) as error:
        raise SystemExit(f"{Failure.EFFECT_FAILED.value}: {type(error).__name__}") from None
