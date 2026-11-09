#!/usr/bin/env python3
"""Install a pinned composed bundle, then verify it before per-task activation."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shlex
import subprocess
import sys
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from composed_bundle import BundleInstalled, BundleRejected, install_bundle
from managed_headless import Rejected, object_fields


class Failure(StrEnum):
    INVALID_PIN = "INVALID_PIN"
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    UNSAFE_ROOT = "UNSAFE_ROOT"
    INCOMPLETE_INSTALLATION = "INCOMPLETE_INSTALLATION"
    PIN_MISMATCH = "PIN_MISMATCH"
    VERSION_FAILED = "VERSION_FAILED"
    IO_FAILED = "IO_FAILED"


@dataclass(frozen=True)
class ImageRejected:
    reason: Failure
    type: Literal["rejected"] = "rejected"


@dataclass(frozen=True)
class ImagePin:
    root: Path
    version: str
    sha256: str


@dataclass(frozen=True)
class VerifiedImage:
    root: str
    version: str
    bundleSha256: str
    semanticReadiness: Literal["NOT_RUN"] = "NOT_RUN"
    type: Literal["verified"] = "verified"


def admit_pin(root: Path, version: str, digest: str) -> ImagePin | ImageRejected:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or not re.fullmatch(
        r"[a-f0-9]{64}", digest
    ):
        return ImageRejected(Failure.INVALID_PIN)
    try:
        safe_root = root.is_absolute() and root.resolve() == root and root != Path("/")
    except (OSError, RuntimeError):
        return ImageRejected(Failure.UNSAFE_ROOT)
    if not safe_root:
        return ImageRejected(Failure.UNSAFE_ROOT)
    return ImagePin(root, version, digest)


def verify(pin: ImagePin) -> VerifiedImage | ImageRejected:
    root = pin.root
    try:
        marker = root / ".kast-headless-owned"
        receipt = root / "bundle-installation.json"
        command = root / "bin/kast"
        if any(path.is_symlink() for path in (root, marker, receipt, command)):
            return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
        if marker.read_text() != "kast-managed-headless-v1\n":
            return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
        record: object = json.loads(receipt.read_text())
        if not object_fields(record) or not isinstance(record.get("bundleSha256"), str):
            return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
        if record["bundleSha256"] != pin.sha256:
            return ImageRejected(Failure.PIN_MISMATCH)
        if not command.is_file() or not os.access(command, os.X_OK):
            return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
        observed = subprocess.run(
            [str(command), "--version"],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, ValueError):
        return ImageRejected(Failure.INCOMPLETE_INSTALLATION)
    except subprocess.TimeoutExpired:
        return ImageRejected(Failure.VERSION_FAILED)
    if observed.returncode != 0 or observed.stdout.strip() != pin.version:
        return ImageRejected(Failure.VERSION_FAILED)
    return VerifiedImage(str(root), pin.version, pin.sha256)


def prepare(
    pin: ImagePin, archive: Path
) -> VerifiedImage | ImageRejected | BundleRejected | Rejected:
    if pin.root.exists() or pin.root.is_symlink():
        # A matching installation is reusable. Never replace partial or foreign state.
        return verify(pin)
    installed = install_bundle(archive, pin.sha256, pin.root, pin.version)
    if not isinstance(installed, BundleInstalled):
        return installed
    return verify(pin)


def activation(verified: VerifiedImage) -> str:
    # Proxy, CA, HOME and JAVA_HOME values belong to the current task, not this image.
    return "export PATH=" + shlex.quote(str(Path(verified.root) / "bin")) + ':"$PATH"\n'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("command", choices=("install", "verify", "activate"))
    _ = parser.add_argument("--root", type=Path, required=True)
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--sha256", required=True)
    _ = parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    if args.command == "install" and args.archive is None:
        parser.error("install requires --archive")
    if args.command != "install" and args.archive is not None:
        parser.error("--archive is only valid for install")
    result: ImagePin | VerifiedImage | ImageRejected | BundleRejected | Rejected
    if (platform.system(), platform.machine()) != ("Linux", "x86_64"):
        result = ImageRejected(Failure.UNSUPPORTED_PLATFORM)
    else:
        result = admit_pin(args.root, args.version, args.sha256)
        if isinstance(result, ImagePin):
            try:
                result = (
                    prepare(result, args.archive) if args.command == "install" else verify(result)
                )
            except OSError:
                result = ImageRejected(Failure.IO_FAILED)
    if isinstance(result, VerifiedImage) and args.command == "activate":
        print(activation(result), end="")
    else:
        print(
            json.dumps(asdict(result)),
            file=sys.stderr if args.command == "activate" else sys.stdout,
        )
    return 0 if isinstance(result, VerifiedImage) else 1


if __name__ == "__main__":
    raise SystemExit(main())
