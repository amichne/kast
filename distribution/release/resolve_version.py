#!/usr/bin/env python3
"""Resolve the next stable Kast release version from the published release catalog."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from enum import Enum
import json
import os
from pathlib import Path
import re
import subprocess


SEMVER = re.compile(r"^v(?P<major>\d+)\.(?P<minor>\d+)\.(?P<patch>\d+)$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class Bump(str, Enum):
    MAJOR = "major"
    MINOR = "minor"
    PATCH = "patch"


@dataclass(frozen=True, order=True)
class Version:
    major: int
    minor: int
    patch: int

    @classmethod
    def parse_tag(cls, tag: str) -> Version | None:
        match = SEMVER.fullmatch(tag)
        if match is None:
            return None
        return cls(*(int(match.group(name)) for name in ("major", "minor", "patch")))

    def bump(self, kind: Bump) -> Version:
        match kind:
            case Bump.MAJOR:
                return Version(self.major + 1, 0, 0)
            case Bump.MINOR:
                return Version(self.major, self.minor + 1, 0)
            case Bump.PATCH:
                return Version(self.major, self.minor, self.patch + 1)

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


def published_versions(releases: list[dict]) -> list[Version]:
    versions: list[Version] = []
    for release in releases:
        if release.get("draft") is True or release.get("prerelease") is True:
            continue
        tag = release.get("tag_name")
        if not isinstance(tag, str):
            continue
        version = Version.parse_tag(tag)
        if version is not None:
            versions.append(version)
    return versions


def next_version(releases: list[dict], bump: Bump) -> Version:
    current = max(published_versions(releases), default=Version(0, 0, 0))
    return current.bump(bump)


def observe_releases(repository: str) -> list[dict]:
    result = subprocess.run(
        ["gh", "api", "--paginate", f"repos/{repository}/releases?per_page=100"],
        text=True,
        capture_output=True,
        timeout=60,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("release-catalog-unavailable")

    releases: list[dict] = []
    for line in result.stdout.splitlines():
        page = json.loads(line)
        if not isinstance(page, list):
            raise RuntimeError("release-catalog-invalid")
        releases.extend(item for item in page if isinstance(item, dict))
    return releases


def emit(version: Version) -> None:
    value = str(version)
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with Path(output_path).open("a", encoding="utf-8") as output:
            output.write(f"version={value}\n")
    print(value)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--bump", required=True, choices=[item.value for item in Bump])
    args = parser.parse_args()

    if REPOSITORY.fullmatch(args.repository) is None:
        raise SystemExit("invalid repository")

    try:
        emit(next_version(observe_releases(args.repository), Bump(args.bump)))
    except (RuntimeError, OSError, ValueError, json.JSONDecodeError, subprocess.TimeoutExpired) as failure:
        raise SystemExit(str(failure)) from None


if __name__ == "__main__":
    main()
