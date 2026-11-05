#!/usr/bin/env python3
"""Build the checkout using a fresh observation of Kast's latest stable release."""
from __future__ import annotations

import argparse
from enum import Enum
import json
from pathlib import Path
import subprocess

from resolve_version import Version, observe_releases, published_versions

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "amichne/kast"


class ReleaseFailure(str, Enum):
    NO_PUBLISHED_STABLE_RELEASE = "no-published-stable-release"


def latest_version(releases: list[dict]) -> Version | ReleaseFailure:
    # Both flags must be observed explicitly before a release can authorize a build version.
    stable = [release for release in releases
              if release.get("draft") is False and release.get("prerelease") is False]
    return max(published_versions(stable), default=ReleaseFailure.NO_PUBLISHED_STABLE_RELEASE)


def run_gate(*, dry_run: bool = False) -> int:
    version = latest_version(observe_releases(REPOSITORY))
    if isinstance(version, ReleaseFailure):
        raise SystemExit(version.value)
    print(f"product-gate: latest published stable release v{version} from {REPOSITORY}; building current checkout",
          flush=True)
    command = ["./gradlew", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx5g",
               f"-Pversion={version}", "productBuildGate", "--console=plain"]
    if dry_run:
        command.append("--dry-run")
    return subprocess.run(command, cwd=ROOT, check=False).returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        return run_gate(dry_run=args.dry_run)
    except (RuntimeError, OSError, ValueError, json.JSONDecodeError, subprocess.TimeoutExpired) as failure:
        raise SystemExit(str(failure)) from None


if __name__ == "__main__":
    raise SystemExit(main())
