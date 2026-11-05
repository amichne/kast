#!/usr/bin/env python3
"""Qualify one clean source identity under the local-only version 0.0.0."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import tempfile
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSION = "0.0.0"


class Outcome(StrEnum):
    STARTED = "STARTED"
    SOURCE_REJECTED = "SOURCE_REJECTED"
    GRADLE_FAILED = "GRADLE_FAILED"
    IDENTITY_REJECTED = "IDENTITY_REJECTED"
    DRY_RUN = "DRY_RUN"
    VALIDATED = "VALIDATED"


@dataclass(frozen=True)
class Source:
    revision: str
    tree: str


def source_identity(environment: dict[str, str]) -> Source | Outcome:
    observed = subprocess.run(
        ["git", "rev-parse", "HEAD", "HEAD^{tree}"],
        cwd=ROOT,
        env=environment,
        text=True,
        capture_output=True,
        timeout=30,
        check=False,
    )
    fields = observed.stdout.splitlines()
    if (
        observed.returncode != 0
        or len(fields) != 2
        or any(re.fullmatch(r"[0-9a-f]{40}", value) is None for value in fields)
    ):
        return Outcome.SOURCE_REJECTED
    admitted = subprocess.run(
        [
            "bash",
            ".github/scripts/release/admit-source.sh",
            "--repository-root",
            str(ROOT),
            "--expected-source-revision",
            fields[0],
        ],
        cwd=ROOT,
        env=environment,
        stdout=subprocess.DEVNULL,
        timeout=30,
        check=False,
    )
    return Source(*fields) if admitted.returncode == 0 else Outcome.SOURCE_REJECTED


@dataclass(frozen=True)
class Record:
    outcome: Outcome
    exitCode: int
    sourceRevision: str | None = None
    sourceTree: str | None = None
    version: str = VERSION
    role: str = "LOCAL_VALIDATION"


def retain(outcome: Outcome, code: int, source: Source | None = None) -> int:
    record = Record(
        outcome, code, source.revision if source else None, source.tree if source else None
    )
    destination = ROOT / "build/reports/local-product-gate.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(asdict(record), sort_keys=True) + "\n"
    with tempfile.TemporaryDirectory(
        dir=destination.parent, prefix=".local-product-gate-"
    ) as temporary:
        staged = Path(temporary) / "record.json"
        staged.write_text(encoded)
        staged.replace(destination)
    print("product-gate: " + encoded.strip(), flush=True)
    return code


def run_gate(*, dry_run: bool = False) -> int:
    # Git hook context must not redirect either admission or Gradle's source observations.
    environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    environment.update(
        GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1", GIT_NO_REPLACE_OBJECTS="1"
    )
    retain(Outcome.STARTED, 1)
    source = source_identity(environment)
    if isinstance(source, Outcome):
        return retain(source, 1)
    command = [
        "./gradlew",
        "--max-workers=2",
        "-Dorg.gradle.jvmargs=-Xmx5g",
        f"-Pversion={VERSION}",
        f"-PcontrolVersion={VERSION}",
        f"-PhostedPluginVersion={VERSION}",
        f"-PkastSourceRevision={source.revision}",
        "productBuildGate",
        "--console=plain",
        "--no-daemon",
    ]
    if dry_run:
        command.extend(("--dry-run", "--info"))
    print(
        f"product-gate: local validation {source.revision} tree {source.tree}; version {VERSION}",
        flush=True,
    )
    code = subprocess.run(command, cwd=ROOT, env=environment, check=False).returncode
    if code != 0:
        return retain(Outcome.GRADLE_FAILED, 128 - code if code < 0 else code, source)
    if source_identity(environment) != source:
        return retain(Outcome.SOURCE_REJECTED, 1, source)
    if dry_run:
        return retain(Outcome.DRY_RUN, 0, source)
    try:
        identity = json.loads(
            (ROOT / "build/control-product/share/kast/build-identity.json").read_text()
        )
    except (OSError, ValueError):
        return retain(Outcome.IDENTITY_REJECTED, 1, source)
    if identity != {
        "version": VERSION,
        "sourceRevision": source.revision,
        "sourceTree": source.tree,
    }:
        return retain(Outcome.IDENTITY_REJECTED, 1, source)
    return retain(Outcome.VALIDATED, 0, source)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        return run_gate(dry_run=args.dry_run)
    except (OSError, ValueError, subprocess.TimeoutExpired) as failure:
        raise SystemExit(str(failure)) from None


if __name__ == "__main__":
    raise SystemExit(main())
