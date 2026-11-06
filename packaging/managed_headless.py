"""Assemble a private production Host and install Control through its existing installer."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import subprocess
import tarfile
import tempfile
import zipfile
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import TypeGuard


class Failure(StrEnum):
    INVALID_INPUT = "INVALID_INPUT"
    EXISTING_TARGET = "EXISTING_TARGET"
    DIGEST_MISMATCH = "DIGEST_MISMATCH"
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    INVALID_DISTRIBUTION = "INVALID_DISTRIBUTION"
    INSTALLER_FAILED = "INSTALLER_FAILED"
    EFFECT_FAILED = "EFFECT_FAILED"


@dataclass(frozen=True, slots=True)
class Rejected:
    reason: Failure


@dataclass(frozen=True, slots=True)
class AssemblyRecord:
    schemaVersion: int
    installation: str
    distributionSha256: str
    bootstrapSha256: str
    platformBuild: str
    kotlinPluginBuild: str


@dataclass(frozen=True, slots=True)
class Input:
    root: Path
    archive: Path
    archive_sha256: str
    bootstrap: Path
    bootstrap_sha256: str
    assets: Path
    version: str
    build: str


@dataclass(frozen=True, slots=True)
class Installed:
    root: str
    command: str
    assembly: AssemblyRecord
    hostReadiness: str = "NOT_RUN"


@dataclass(frozen=True, slots=True)
class _AdmittedInput:
    inputs: Input


def admit(inputs: Input) -> _AdmittedInput | Rejected:
    root = inputs.root
    if not root.is_absolute() or root != root.resolve() or root.parent.resolve() != root.parent:
        return Rejected(Failure.INVALID_INPUT)
    if root.exists():
        return Rejected(Failure.EXISTING_TARGET)
    socket = root / "state/home/.kast/ide-lifecycle" / ("0" * 32) / "host.sock"
    if len(os.fsencode(socket)) > 100:
        return Rejected(Failure.INVALID_INPUT)
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", inputs.version):
        return Rejected(Failure.INVALID_INPUT)
    if not re.fullmatch(r"262\.[0-9]+(?:\.[0-9]+)*", inputs.build):
        return Rejected(Failure.INVALID_INPUT)
    if not inputs.assets.is_dir():
        return Rejected(Failure.INVALID_INPUT)
    for path, expected in (
        (inputs.archive, inputs.archive_sha256),
        (inputs.bootstrap, inputs.bootstrap_sha256),
    ):
        if not path.is_file() or path.is_symlink() or not re.fullmatch(r"[0-9a-f]{64}", expected):
            return Rejected(Failure.INVALID_INPUT)
        with path.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != expected:
                return Rejected(Failure.DIGEST_MISMATCH)
    return _AdmittedInput(inputs)


def unpack_archive(archive: Path, target: Path, system: str) -> Path | Rejected:
    if system == "Linux":
        with tarfile.open(archive) as source:
            source.extractall(target, filter="data")
        children = list(target.iterdir())
        if len(children) != 1 or not children[0].is_dir():
            return Rejected(Failure.INVALID_DISTRIBUTION)
        return children[0]
    if system == "Darwin":
        with tempfile.TemporaryDirectory(prefix="kast-headless-mount-") as temporary:
            mount = Path(temporary)
            attached = subprocess.run(
                [
                    "hdiutil",
                    "attach",
                    "-readonly",
                    "-nobrowse",
                    "-mountpoint",
                    str(mount),
                    str(archive),
                ],
                check=False,
            )
            if attached.returncode != 0:
                return Rejected(Failure.INVALID_DISTRIBUTION)
            try:
                apps = list(mount.glob("*.app"))
                if len(apps) != 1:
                    return Rejected(Failure.INVALID_DISTRIBUTION)
                app = target / apps[0].name
                _ = subprocess.run(["ditto", str(apps[0]), str(app)], check=True)
                return app / "Contents"
            finally:
                _ = subprocess.run(["hdiutil", "detach", str(mount)], check=True)
    return Rejected(Failure.UNSUPPORTED_PLATFORM)


def add_bootstrap(home: Path, bootstrap: Path) -> str | Rejected:
    destination = home / "third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar"
    if destination.exists():
        return Rejected(Failure.EXISTING_TARGET)
    with zipfile.ZipFile(bootstrap) as source:
        files = [item for item in source.infolist() if not item.is_dir()]
        if (
            len(files) != 1
            or files[0].filename != "kast-managed-headless/lib/kast-managed-headless.jar"
        ):
            return Rejected(Failure.INVALID_INPUT)
        data = source.read(files[0])
    destination.parent.mkdir(parents=True)
    _ = destination.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def seal_runtime(home: Path) -> None:
    """Keep vendor bytes and execute bits while removing write permissions from this owned tree."""
    for path in (*home.rglob("*"), home):
        if not path.is_symlink():
            path.chmod(path.stat().st_mode & ~0o222)


def wrapper(home: Path, state: Path) -> str:
    executable = state / "home/.local/bin/kast"
    return (
        "#!/bin/sh\nset -eu\n"
        f"[ -f {shlex.quote(str(home / 'kast-headless.json'))} ] || exit 2\n"
        f"HOME={shlex.quote(str(state / 'home'))} "
        f"KAST_MANAGED_HEADLESS_STATE={shlex.quote(str(state))} "
        f'exec {shlex.quote(str(executable))} "$@"\n'
    )


def object_fields(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def install(inputs: Input, installer: Path) -> Installed | Rejected:
    admitted = admit(inputs)
    if isinstance(admitted, Rejected):
        return admitted
    return assemble(admitted, installer)


def assemble(admitted: _AdmittedInput, installer: Path) -> Installed | Rejected:
    inputs = admitted.inputs
    system = platform.system()
    if system not in {"Linux", "Darwin"}:
        return Rejected(Failure.UNSUPPORTED_PLATFORM)
    root = inputs.root
    root.mkdir(mode=0o700)
    _ = (root / ".kast-headless-owned").write_text("kast-managed-headless-v1\n")
    runtime = root / "runtime"
    runtime.mkdir()
    home = unpack_archive(inputs.archive, runtime, system)
    if isinstance(home, Rejected):
        return home
    metadata = home / ("Resources/product-info.json" if system == "Darwin" else "product-info.json")
    # The archive digest fixes every vendor byte. These literal product fields reject a wrong pin.
    product: object = json.loads(metadata.read_text())
    if not object_fields(product) or product.get("buildNumber") != inputs.build:
        return Rejected(Failure.INVALID_DISTRIBUTION)
    bootstrap = add_bootstrap(home, inputs.bootstrap)
    if isinstance(bootstrap, Rejected):
        return bootstrap
    record = AssemblyRecord(
        1, str(home), inputs.archive_sha256, bootstrap, inputs.build, inputs.build + "-IJ"
    )
    _ = (home / "kast-headless.json").write_text(json.dumps(asdict(record), indent=2) + "\n")
    seal_runtime(home)
    state = root / "state"
    for name in ("home", "system", "log", "tmp"):
        (state / name).mkdir(parents=True, mode=0o700)
    environment = {
        "PATH": os.defpath,
        "HOME": str(state / "home"),
        "TMPDIR": str(state / "tmp"),
        "KAST_VERSION": inputs.version,
        "KAST_HOST_VERSION": inputs.version,
        "KAST_INSTALL_ASSETS_DIRECTORY": str(inputs.assets),
        "KAST_JAVA_HOME": str(home / ("jbr/Contents/Home" if system == "Darwin" else "jbr")),
    }
    with (root / "installer.log").open("w") as log:
        result = subprocess.run(
            ["bash", str(installer), "--skip-codex-plugin", "--idea-home", str(home)],
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=False,
        )
    if result.returncode != 0:
        return Rejected(Failure.INSTALLER_FAILED)
    (root / "bin").mkdir()
    command = root / "bin/kast"
    _ = command.write_text(wrapper(home, state))
    command.chmod(0o700)
    return Installed(str(root), str(command), record)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "archive", "bootstrap", "assets"):
        _ = parser.add_argument("--" + name, type=Path, required=True)
    for name in ("archive-sha256", "bootstrap-sha256", "version", "build"):
        _ = parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    inputs = Input(
        args.root,
        args.archive,
        args.archive_sha256,
        args.bootstrap,
        args.bootstrap_sha256,
        args.assets,
        args.version,
        args.build,
    )
    try:
        outcome = install(inputs, Path(__file__).resolve().parent.parent / "install.sh")
    except (
        OSError,
        ValueError,
        tarfile.TarError,
        zipfile.BadZipFile,
        subprocess.CalledProcessError,
    ):
        outcome = Rejected(Failure.EFFECT_FAILED)
    print(json.dumps(asdict(outcome), indent=2))
    return 2 if isinstance(outcome, Rejected) else 0


if __name__ == "__main__":
    raise SystemExit(main())
