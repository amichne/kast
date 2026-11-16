"""Pinned, relocatable installer execution; no package solver or ambient interpreter."""

from __future__ import annotations

import hashlib
import json
import shutil
import tarfile
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class InterpreterPin:
    target: str
    triple: str
    sha256: str

    @property
    def url(self) -> str:
        return (
            "https://github.com/astral-sh/python-build-standalone/releases/download/20260211/"
            f"cpython-3.12.12%2B20260211-{self.triple}-install_only_stripped.tar.gz"
        )


PINS = (
    InterpreterPin(
        "macos-aarch64",
        "aarch64-apple-darwin",
        "22625deaf5757e7c266cf1a096c9151a06b598b1e14632a2ec9993d58ec5fe84",
    ),
    InterpreterPin(
        "linux-x86_64",
        "x86_64-unknown-linux-gnu",
        "1dbaa624a09e15afe7efbdac08d42993135a68db8d34f986ef6977a6d77bdc3c",
    ),
)
DEPENDENCY_URL = (
    "https://files.pythonhosted.org/packages/07/6c/"
    "aa3f2f849e01cb6a001cd8554a88d4c77c5c1a31c95bdf1cf9301e6d9ef4/"
    "defusedxml-0.7.1-py2.py3-none-any.whl"
)
DEPENDENCY_SHA256 = "a352e7e428770286cc899e2542b6cdaedb2b4953ff269a210103ec58f6198a61"


@dataclass(frozen=True)
class InterpreterReceipt:
    target: str
    archiveSha256: str
    dependencySha256: str = DEPENDENCY_SHA256
    version: str = "3.12.12"
    schemaVersion: int = 1


@dataclass(frozen=True)
class PrivateInterpreter:
    archive: Path
    dependency: Path
    pin: InterpreterPin

    @staticmethod
    def admit(archive: Path, dependency: Path, target: str) -> PrivateInterpreter:
        pin = next((item for item in PINS if item.target == target), None)
        if pin is None or digest(archive) != pin.sha256 or digest(dependency) != DEPENDENCY_SHA256:
            raise ValueError("private interpreter identity")
        return PrivateInterpreter(archive, dependency, pin)

    def stage(self, metadata: Path) -> None:
        _ = PrivateInterpreter.admit(self.archive, self.dependency, self.pin.target)
        archive_path, dependency_path = (
            metadata / "private-python.tar.gz",
            metadata / "defusedxml.whl",
        )
        _ = shutil.copyfile(self.archive, archive_path)
        _ = shutil.copyfile(self.dependency, dependency_path)
        _ = PrivateInterpreter.admit(archive_path, dependency_path, self.pin.target)
        # Materialize internal links as regular files so the existing complete Control
        # payload inventory and replacement journal remain the sole ownership authority.
        with tempfile.TemporaryDirectory(prefix=".python-", dir=metadata) as temporary:
            extracted = Path(temporary)
            with tarfile.open(archive_path, "r:gz") as archive:
                archive.extractall(extracted, filter="data")
            root = extracted / "python"
            if {item.name for item in extracted.iterdir()} != {"python"} or root.is_symlink():
                raise ValueError("private interpreter layout")
            for path in root.rglob("*"):
                if not path.resolve(strict=True).is_relative_to(root):
                    raise ValueError("private interpreter link")
            _ = shutil.copytree(root, metadata / "private-python", symlinks=False)
        receipt = InterpreterReceipt(self.pin.target, self.pin.sha256, digest(dependency_path))
        _ = (metadata / "private-python.json").write_text(json.dumps(asdict(receipt)) + "\n")


def digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ValueError("private input ownership")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()
