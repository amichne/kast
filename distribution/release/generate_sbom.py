#!/usr/bin/env python3
"""Inventory the two exact product archives with checksum-pinned Syft."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

import release_gate as gate

SYFT_VERSION = "1.51.1"
SYFT_ARCHIVE = f"syft_{SYFT_VERSION}_darwin_arm64.tar.gz"
SYFT_DIGEST = "sha256:ac063af3b9874769deb7ea1e6d76841e68f9e3bb50cd654226fc977de65532c1"


def scanner(root: Path) -> Path:
    cache = root / ".gradle/release-tools" / f"syft-{SYFT_VERSION}"
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / SYFT_ARCHIVE
    if not archive.exists():
        with tempfile.TemporaryDirectory(dir=cache) as temporary:
            gate.run(["gh", "release", "download", f"v{SYFT_VERSION}", "--repo", "anchore/syft",
                      "--pattern", SYFT_ARCHIVE, "--dir", temporary], root, os.environ.copy())
            downloaded = Path(temporary) / SYFT_ARCHIVE
            if gate.digest(downloaded) != SYFT_DIGEST:
                raise gate.GateRejected("SBOM scanner archive checksum mismatch")
            downloaded.replace(archive)
    if gate.digest(archive) != SYFT_DIGEST:
        raise gate.GateRejected("cached SBOM scanner archive checksum mismatch")
    executable = cache / "syft"
    # Re-extract verified bytes on every run; a cached executable is not authority.
    with tarfile.open(archive, "r:gz") as payload:
        member = payload.getmember("syft")
        if not member.isfile():
            raise gate.GateRejected("SBOM scanner executable is not a regular archive member")
        source = payload.extractfile(member)
        if source is None:
            raise gate.GateRejected("SBOM scanner executable is absent")
        with executable.open("wb") as destination:
            shutil.copyfileobj(source, destination)
    executable.chmod(0o700)
    return executable


def generate(root: Path, directory: Path, version: str, sha: str) -> None:
    executable = scanner(root)
    names = gate.product_asset_names(version)[:2]
    inputs = {name: gate.digest(directory / name) for name in names}
    output = directory / f"kast-sbom-v{version}.cdx.json"
    with tempfile.TemporaryDirectory(prefix="kast-sbom-") as temporary:
        source = Path(temporary)
        for name in names:
            shutil.copyfile(directory / name, source / name)
        environment = {**os.environ, "SYFT_CHECK_FOR_APP_UPDATE": "false"}
        gate.run([str(executable), "scan", f"dir:{source}", "--base-path", str(source),
                  "--source-name", "kast", "--source-version", version, "--parallelism", "2",
                  "--quiet", "--output", f"cyclonedx-json={output}"], root, environment)
    document = gate.read(output)
    if document.get("bomFormat") != "CycloneDX" or not document.get("components"):
        raise gate.GateRejected("SBOM scanner returned no component inventory")
    metadata = document["metadata"]
    metadata["properties"] = [{"name": "kast:source-revision", "value": sha}] + [
        {"name": f"kast:archive:{name}", "value": value} for name, value in inputs.items()
    ]
    gate.write(output, document)
    if inputs != {name: gate.digest(directory / name) for name in names}:
        raise gate.GateRejected("product archives changed during the SBOM scan")
    output.with_suffix(output.suffix + ".sha256").write_text(
        f"{gate.digest(output).removeprefix('sha256:')}  {output.name}\n")
    gate.write(root / "build/reports/release-gate/sbom.json", {
        "schemaVersion": 1, "status": "passed", "sourceRevision": sha,
        "scanner": {"name": "syft", "version": SYFT_VERSION, "archiveDigest": SYFT_DIGEST},
        "archives": inputs, "sbomDigest": gate.digest(output), "componentCount": len(document["components"]),
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--assets-directory", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision", required=True)
    args = parser.parse_args()
    try:
        gate.admit_source(args.source_root, args.source_revision)
        generate(args.source_root, args.assets_directory, args.version, args.source_revision)
    except (gate.GateRejected, OSError, ValueError, KeyError, tarfile.TarError) as error:
        raise SystemExit(f"release-sbom: rejected: {error}") from None
