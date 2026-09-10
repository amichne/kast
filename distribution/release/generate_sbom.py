#!/usr/bin/env python3
"""Inventory the release archives with checksum-pinned Syft."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

SYFT_VERSION = "1.51.1"
SYFT_ARCHIVE = f"syft_{SYFT_VERSION}_darwin_arm64.tar.gz"
SYFT_DIGEST = "sha256:ac063af3b9874769deb7ea1e6d76841e68f9e3bb50cd654226fc977de65532c1"


class SbomError(Exception):
    pass


def digest(path: Path) -> str:
    if not path.is_file() or path.is_symlink():
        raise SbomError(f"expected regular file: {path}")
    with path.open("rb") as stream:
        return "sha256:" + hashlib.file_digest(stream, "sha256").hexdigest()


def run(command: list[str], root: Path, environment: dict[str, str]) -> None:
    result = subprocess.run(command, cwd=root, env=environment, check=False)
    if result.returncode:
        raise SbomError(f"command failed with exit {result.returncode}: {command[0]}")


def read(path: Path) -> dict:
    value = json.loads(path.read_bytes())
    if not isinstance(value, dict):
        raise SbomError(f"expected JSON object: {path}")
    return value


def write(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n")


def scanner(root: Path) -> Path:
    cache = root / ".gradle/release-tools" / f"syft-{SYFT_VERSION}"
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / SYFT_ARCHIVE
    if not archive.exists():
        with tempfile.TemporaryDirectory(dir=cache) as temporary:
            run([
                "gh", "release", "download", f"v{SYFT_VERSION}",
                "--repo", "anchore/syft", "--pattern", SYFT_ARCHIVE,
                "--dir", temporary,
            ], root, os.environ.copy())
            downloaded = Path(temporary) / SYFT_ARCHIVE
            if digest(downloaded) != SYFT_DIGEST:
                raise SbomError("SBOM scanner archive checksum mismatch")
            downloaded.replace(archive)
    if digest(archive) != SYFT_DIGEST:
        raise SbomError("cached SBOM scanner archive checksum mismatch")

    executable = cache / "syft"
    with tarfile.open(archive, "r:gz") as payload:
        member = payload.getmember("syft")
        if not member.isfile():
            raise SbomError("SBOM scanner executable is not a regular archive member")
        source = payload.extractfile(member)
        if source is None:
            raise SbomError("SBOM scanner executable is absent")
        with executable.open("wb") as destination:
            shutil.copyfileobj(source, destination)
    executable.chmod(0o700)
    return executable


def generate(root: Path, directory: Path, version: str, sha: str) -> None:
    plugins = tuple(directory.glob(f"kast-ide-hosted-v{version}-idea-*.zip"))
    if len(plugins) != 1:
        raise SbomError("expected exactly one IDEA-build-specific hosted plugin")
    names = (
        f"kast-control-v{version}-macos-aarch64.tar.gz",
        f"kast-semantic-runtime-{version}-macos-aarch64.zip",
        plugins[0].name,
    )
    inputs = {name: digest(directory / name) for name in names}
    output = directory / f"kast-sbom-v{version}.cdx.json"
    executable = scanner(root)

    with tempfile.TemporaryDirectory(prefix="kast-sbom-") as temporary:
        source = Path(temporary)
        for name in names:
            shutil.copyfile(directory / name, source / name)
        run([
            str(executable), "scan", f"dir:{source}", "--base-path", str(source),
            "--source-name", "kast", "--source-version", version,
            "--parallelism", "2", "--quiet", "--output", f"cyclonedx-json={output}",
        ], root, {**os.environ, "SYFT_CHECK_FOR_APP_UPDATE": "false"})

    document = read(output)
    if document.get("bomFormat") != "CycloneDX" or not document.get("components"):
        raise SbomError("SBOM scanner returned no component inventory")
    metadata = document.get("metadata")
    if not isinstance(metadata, dict):
        raise SbomError("SBOM scanner returned no metadata object")
    metadata["properties"] = [{"name": "kast:source-revision", "value": sha}] + [
        {"name": f"kast:archive:{name}", "value": value} for name, value in inputs.items()
    ]
    write(output, document)
    if inputs != {name: digest(directory / name) for name in names}:
        raise SbomError("product archives changed during the SBOM scan")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--assets-directory", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision", required=True)
    args = parser.parse_args()
    try:
        generate(
            args.source_root.resolve(),
            args.assets_directory.resolve(),
            args.version,
            args.source_revision,
        )
    except (SbomError, OSError, ValueError, KeyError, tarfile.TarError) as error:
        raise SystemExit(f"release-sbom: rejected: {error}") from None
