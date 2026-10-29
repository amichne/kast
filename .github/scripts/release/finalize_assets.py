# /// script
# requires-python = ">=3.12"
# dependencies = []
# [tool.uv]
# no-build = true
# ///
"""Complete the existing release inventory after platform-specific assembly."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tarfile
from pathlib import Path
from typing import TypeGuard

ROOT = Path(__file__).resolve().parents[3]


def mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def finalize(version: str, revision: str) -> None:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version) or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise SystemExit("release-assets: invalid identity")
    directory = ROOT / f"build/release/v{version}"
    control = directory / f"kast-control-v{version}-macos-aarch64.tar.gz"
    knowledge = directory / f"kast-module-knowledge-v{version}.json"
    _ = shutil.copyfile(
        ROOT / "build/reports/kast-architecture/kast-module-knowledge.json", knowledge
    )
    catalog = directory / f"kast-hosted-catalog-v{version}.json"
    with tarfile.open(control) as archive:
        stream = archive.extractfile("share/kast/provider-catalog.json")
        if stream is None:
            raise SystemExit("release-assets: missing provider catalog")
        _ = catalog.write_bytes(stream.read())
    value: object = json.loads(catalog.read_bytes())
    if not mapping(value) or value.get("schemaVersion") != 1:
        raise SystemExit("release-assets: invalid provider catalog")
    projection = value.get("serverProjection")
    if (
        not mapping(projection)
        or projection.get("namespace") != "kast"
        or "cliInvocations" in projection
    ):
        raise SystemExit("release-assets: invalid server projection")
    hosted = projection.get("hostedBootstrap")
    if not mapping(hosted) or not hosted.get("tools"):
        raise SystemExit("release-assets: invalid hosted bootstrap")
    _ = subprocess.run(
        [
            "python3",
            "distribution/release/generate_sbom.py",
            "--source-root",
            str(ROOT),
            "--assets-directory",
            str(directory),
            "--version",
            version,
            "--source-revision",
            revision,
        ],
        cwd=ROOT,
        check=True,
    )
    for path in sorted(directory.iterdir()):
        if path.is_file() and not path.name.endswith(".sha256"):
            with path.open("rb") as source:
                digest = hashlib.file_digest(source, "sha256").hexdigest()
            _ = path.with_name(path.name + ".sha256").write_text(f"{digest}  {path.name}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--source-revision", required=True)
    arguments = parser.parse_args()
    finalize(arguments.version, arguments.source_revision)
