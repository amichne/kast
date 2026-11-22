"""Admit the inputs of one isolated runtime candidate before any toolchain runs.

The candidate needs a numeric version, an empty retired archive-action revision, a known native
target, and, when the caller names a release target, that exact target. The workflow runs this
before Python is provisioned, so it keeps to the standard library and to Python 3.9 syntax.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from enum import Enum

TARGETS = ("linux-x86_64", "macos-aarch64")
_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")


class Failure(str, Enum):  # noqa: UP042 - the runner's system Python may predate StrEnum.
    INVALID_VERSION = "INVALID_VERSION"
    RETIRED_ACTION_REVISION = "RETIRED_ACTION_REVISION"
    UNKNOWN_TARGET = "UNKNOWN_TARGET"
    RELEASE_TARGET_MISMATCH = "RELEASE_TARGET_MISMATCH"


@dataclass(frozen=True)
class Rejected:
    failure: Failure
    detail: str


@dataclass(frozen=True)
class Admitted:
    version: str
    target: str


def admit(
    version: str, action_revision: str, target: str, release_target: str
) -> Admitted | Rejected:
    if _VERSION.fullmatch(version) is None:
        return Rejected(Failure.INVALID_VERSION, "numeric version required")
    if action_revision:
        return Rejected(
            Failure.RETIRED_ACTION_REVISION,
            "archive-action contract retired; action_revision must be empty",
        )
    if target not in TARGETS:
        return Rejected(Failure.UNKNOWN_TARGET, f"target must be one of {', '.join(TARGETS)}")
    if release_target and release_target != target:
        return Rejected(
            Failure.RELEASE_TARGET_MISMATCH, f"release target {release_target} is not {target}"
        )
    return Admitted(version, target)


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--action-revision", required=True)
    _ = parser.add_argument("--target", required=True)
    _ = parser.add_argument("--release-target", default="")
    args = parser.parse_args(arguments)
    outcome = admit(args.version, args.action_revision, args.target, args.release_target)
    if isinstance(outcome, Rejected):
        print(f"candidate-admission: {outcome.failure.value}: {outcome.detail}", file=sys.stderr)
        return 1
    print(f"candidate-admission: ADMITTED version={outcome.version} target={outcome.target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
