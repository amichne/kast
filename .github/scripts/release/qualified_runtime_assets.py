# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = []
# [tool.uv]
# no-build = true
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""List stable release assets only after all runtime bytes match native qualification."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path
from typing import TypeGuard

TARGETS = ("linux-x86_64", "macos-aarch64")
RELEASE_ASSET_LIMIT = 2 * 1024**3
MANIFEST_LIMIT = 16_777_216
RECEIPT_LIMIT = 1_048_576


def mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def admit_runtime(
    artifact: Path, name: str, version: str, target: str, revision: str
) -> tuple[Path, Path]:
    archive = artifact / "composed-release" / name
    checksum = archive.with_name(name + ".sha256")
    receipt = artifact / "composed-evidence/qualification.json"
    for path in (archive, checksum, receipt):
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"missing regular file {path.name}")
    if not 0 < archive.stat().st_size < RELEASE_ASSET_LIMIT:
        raise ValueError("runtime ZIP exceeds GitHub release asset limits or is empty")
    if receipt.stat().st_size > RECEIPT_LIMIT:
        raise ValueError("qualification receipt exceeds size limit")

    evidence: object = json.loads(receipt.read_bytes())
    if (
        not mapping(evidence)
        or evidence.get("sourceRevision") != revision
        or evidence.get("qualification") != "COMPLETE"
    ):
        raise ValueError("qualification receipt lacks COMPLETE for the release source")
    containers = evidence.get("containers")
    if not mapping(containers):
        raise ValueError("qualification receipt lacks container evidence")
    expected = containers.get("runtimeSha256")
    if not isinstance(expected, str) or re.fullmatch(r"[0-9a-f]{64}", expected) is None:
        raise ValueError("qualification receipt lacks a runtime digest")
    with checksum.open("rb") as stream:
        if stream.read(1025) != f"{expected}  {name}\n".encode("ascii"):
            raise ValueError("published checksum differs from qualification digest")
    with archive.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
            raise ValueError("runtime ZIP differs from qualification digest")
        with zipfile.ZipFile(stream) as bundle:
            entries = [entry for entry in bundle.infolist() if entry.filename == "manifest.json"]
            if len(entries) != 1 or entries[0].file_size > MANIFEST_LIMIT:
                raise ValueError("invalid runtime manifest entry")
            manifest: object = json.loads(bundle.read(entries[0]))
    if (
        not mapping(manifest)
        or type(manifest.get("schemaVersion")) is not int
        or manifest.get("schemaVersion") != 1
        or manifest.get("kind") != "KAST_RUNTIME_V1"
        or manifest.get("version") != version
        or manifest.get("platform") != target
    ):
        raise ValueError("runtime manifest differs from release version or target")
    return archive, checksum


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--directory", type=Path, required=True)
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--source-revision", required=True)
    args = parser.parse_args()
    root: Path = args.directory
    version: str = args.version
    revision: str = args.source_revision
    if (
        re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is None
        or re.fullmatch(r"[0-9a-f]{40}", revision) is None
    ):
        raise SystemExit("qualified-runtime-assets: invalid release identity")

    assets: list[str] = []
    for target in TARGETS:
        artifact = Path(f"kast-release-runtime-v{version}-{target}-{revision}")
        name = f"kast-runtime-v{version}-{target}.zip"
        try:
            admitted = admit_runtime(root / artifact, name, version, target, revision)
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            raise SystemExit(f"qualified-runtime-assets: {target}: {error}") from None
        assets.extend(path.relative_to(root).as_posix() for path in admitted)
    print("\n".join(assets))


if __name__ == "__main__":
    main()
