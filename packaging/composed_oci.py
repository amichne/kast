"""Select the exact successful Linux producer attempt and admit its qualified archive."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

from composed_bundle import sha256
from managed_headless import object_fields


class Rejection(StrEnum):
    PRODUCER = "PRODUCER_REJECTED"
    RECEIPT = "RECEIPT_REJECTED"
    DIGEST = "DIGEST_MISMATCH"
    GITHUB = "GITHUB_REQUEST_FAILED"
    INPUT = "INPUT_UNREADABLE"


@dataclass(frozen=True, slots=True)
class Producer:
    revision: str
    artifact: str


@dataclass(frozen=True, slots=True)
class Bundle:
    revision: str
    version: str
    digest: str


def admit_producer(raw: object, run_id: int, repository: str) -> Producer | Rejection:
    if not object_fields(raw):
        return Rejection.PRODUCER
    head = raw.get("head_repository")
    revision, attempt = raw.get("head_sha"), raw.get("run_attempt")
    if (
        raw.get("id") != run_id
        or raw.get("path") != ".github/workflows/composed-linux.yml"
        or raw.get("event") != "workflow_dispatch"
        or raw.get("status") != "completed"
        or raw.get("conclusion") != "success"
        or not object_fields(head)
        or head.get("full_name") != repository
        or not isinstance(revision, str)
        or not re.fullmatch(r"[0-9a-f]{40}", revision)
        or type(attempt) is not int
        or attempt < 1
    ):
        return Rejection.PRODUCER
    return Producer(revision, f"kast-composed-linux-{revision}-{run_id}-{attempt}")


def admit_bundle(producer: Producer, directory: Path) -> Bundle | Rejection:
    assembly: object = json.loads((directory / "assembly.json").read_text())
    qualification: object = json.loads((directory / "qualification.json").read_text())
    if not object_fields(assembly) or not object_fields(qualification):
        return Rejection.RECEIPT
    bundle, version = assembly.get("bundle"), assembly.get("version")
    if (
        assembly.get("sourceRevision") != producer.revision
        or qualification.get("sourceRevision") != producer.revision
        or qualification.get("qualification") != "COMPLETE"
        or not isinstance(version, str)
        or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
        or not object_fields(bundle)
    ):
        return Rejection.RECEIPT
    archive = directory / f"kast-composed-setup-v{version}-linux-x86_64.tar.gz"
    recorded, digest = bundle.get("archive"), bundle.get("sha256")
    if (
        not isinstance(recorded, str)
        or Path(recorded).name != archive.name
        or not isinstance(digest, str)
        or not re.fullmatch(r"[0-9a-f]{64}", digest)
        or qualification.get("bundleSha256") != digest
    ):
        return Rejection.RECEIPT
    if (
        archive.is_symlink()
        or not archive.is_file()
        or archive.stat().st_size != bundle.get("bytes")
        or sha256(archive) != digest
    ):
        return Rejection.DIGEST
    return Bundle(producer.revision, version, digest)


def download(run_id: int, repository: str, directory: Path) -> Bundle | Rejection:
    try:
        raw: object = json.loads(
            subprocess.check_output(
                ["gh", "api", f"repos/{repository}/actions/runs/{run_id}"], timeout=60
            )
        )
        producer = admit_producer(raw, run_id, repository)
        if isinstance(producer, Rejection):
            return producer
        _ = subprocess.run(
            [
                "gh",
                "run",
                "download",
                str(run_id),
                "--repo",
                repository,
                "--name",
                producer.artifact,
                "--dir",
                str(directory),
            ],
            check=True,
            timeout=300,
        )
        return admit_bundle(producer, directory)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return Rejection.GITHUB
    except (OSError, ValueError):
        return Rejection.INPUT


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("run_id", type=int)
    _ = parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    if args.run_id < 1:
        parser.error("Expected a positive producer run ID")
    result = download(args.run_id, os.environ["GITHUB_REPOSITORY"], args.directory)
    if isinstance(result, Rejection):
        print(result)
        return 1
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        _ = output.write(
            f"version={result.version}\nrevision={result.revision}\ndigest={result.digest}\n"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
