"""Publish the Go bootstrap executables that install.sh pins, as one immutable pre-release.

The tag names the Git tree of `manager/`, so one source has one release. A repeated run changes
nothing when the release already holds the same bytes. Any other observation stops before another
effect.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
REPOSITORY = "amichne/kast"
NAMES = (
    "kast-bootstrap-THIRD_PARTY_NOTICES.txt",
    "kast-bootstrap-linux-x86_64",
    "kast-bootstrap-macos-aarch64",
)
REVISION = re.compile(r"[0-9a-f]{40}\Z")
STABLE_TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+\Z")
MAXIMUM_BYTES = 64 << 20


class Failure(StrEnum):
    SOURCE_REJECTED = "SOURCE_REJECTED"
    FILES_REJECTED = "FILES_REJECTED"
    OBSERVATION_FAILED = "OBSERVATION_FAILED"
    TAG_EXISTS = "TAG_EXISTS"
    INCOMPLETE_DRAFT = "INCOMPLETE_DRAFT"
    RELEASE_MISMATCH = "RELEASE_MISMATCH"
    PUBLICATION_FAILED = "PUBLICATION_FAILED"
    LATEST_RELEASE_CHANGED = "LATEST_RELEASE_CHANGED"


class Effect(StrEnum):
    PUBLISHED = "PUBLISHED"
    UNCHANGED = "UNCHANGED"


class Rejected(Exception):
    def __init__(self, failure: Failure, detail: str) -> None:
        super().__init__(f"{failure.value}: {detail}")
        self.failure = failure


@dataclass(frozen=True, slots=True)
class Completed:
    code: int
    stdout: str
    stderr: str


type Run = Callable[[Sequence[str]], Completed]


@dataclass(frozen=True, slots=True)
class File:
    name: str
    sha256: str
    bytes: int


@dataclass(frozen=True, slots=True)
class Bootstrap:
    tag: str
    revision: str
    files: tuple[File, ...]


@dataclass(frozen=True, slots=True)
class Outcome:
    effect: Effect
    bootstrap: Bootstrap


def execute(command: Sequence[str]) -> Completed:
    result = subprocess.run(
        command,
        cwd=ROOT,
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        timeout=300,
        check=False,
    )
    return Completed(result.returncode, result.stdout, result.stderr)


def read(directory: Path, run: Run) -> Bootstrap:
    """Admit exactly the built files of a clean checkout."""
    source = run(("git", "rev-parse", "HEAD", "HEAD:manager"))
    changes = run(("git", "status", "--porcelain", "--untracked-files=normal", "--", "manager"))
    identities = source.stdout.split()
    if (
        source.code != 0
        or changes.code != 0
        or changes.stdout
        or len(identities) != 2
        or any(not REVISION.match(item) for item in identities)
    ):
        raise Rejected(Failure.SOURCE_REJECTED, "manager/ is not one clean committed tree")
    revision, tree = identities
    if directory.is_symlink() or not directory.is_dir():
        raise Rejected(Failure.FILES_REJECTED, "not a directory")
    paths = sorted(directory.iterdir())
    if tuple(path.name for path in paths) != NAMES:
        raise Rejected(Failure.FILES_REJECTED, "unexpected files")
    files: list[File] = []
    for path in paths:
        if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAXIMUM_BYTES:
            raise Rejected(Failure.FILES_REJECTED, path.name)
        raw = path.read_bytes()
        files.append(File(path.name, hashlib.sha256(raw).hexdigest(), len(raw)))
    return Bootstrap(f"bootstrap-{tree[:12]}", revision, tuple(files))


def observe(bootstrap: Bootstrap, run: Run) -> bool | None:
    """None without a release, else whether it is published. Other facts are a rejection."""
    result = run(
        (
            "gh",
            "release",
            "view",
            bootstrap.tag,
            "--repo",
            REPOSITORY,
            "--json",
            "isDraft,isPrerelease,assets",
        )
    )
    if result.code != 0:
        if result.stderr.strip() == "release not found":
            return None
        raise Rejected(Failure.OBSERVATION_FAILED, "release view")
    try:
        value = json.loads(result.stdout)
        assets = {
            item["name"]: (item["digest"], item["size"], item["state"]) for item in value["assets"]
        }
        draft, prerelease, count = value["isDraft"], value["isPrerelease"], len(value["assets"])
    except (ValueError, KeyError, TypeError) as problem:
        raise Rejected(Failure.OBSERVATION_FAILED, "release view") from problem
    expected = {
        item.name: (f"sha256:{item.sha256}", item.bytes, "uploaded") for item in bootstrap.files
    }
    if (
        type(draft) is not bool
        or prerelease is not True
        or assets != expected
        or count != len(expected)
    ):
        raise Rejected(
            Failure.INCOMPLETE_DRAFT if draft is True else Failure.RELEASE_MISMATCH,
            f"{bootstrap.tag} does not hold exactly these files",
        )
    return not draft


def require_stable_latest(run: Run) -> None:
    """The stable channel reads GitHub's latest release, so a bootstrap must never become it."""
    result = run(("gh", "api", f"repos/{REPOSITORY}/releases/latest", "--jq", ".tag_name"))
    if result.code != 0:
        raise Rejected(Failure.OBSERVATION_FAILED, "latest release")
    if not STABLE_TAG.match(result.stdout.strip()):
        raise Rejected(Failure.LATEST_RELEASE_CHANGED, result.stdout.strip()[:80])


def publish(directory: Path, run: Run) -> Outcome:
    bootstrap = read(directory, run)
    state = observe(bootstrap, run)
    if state is True:
        require_stable_latest(run)
        return Outcome(Effect.UNCHANGED, bootstrap)
    if state is False:
        raise Rejected(
            Failure.INCOMPLETE_DRAFT, f"delete the draft {bootstrap.tag}, then run again"
        )
    tag = run(("gh", "api", f"repos/{REPOSITORY}/git/ref/tags/{bootstrap.tag}"))
    if tag.code == 0:
        raise Rejected(Failure.TAG_EXISTS, bootstrap.tag)
    if "(HTTP 404)" not in tag.stderr:
        raise Rejected(Failure.OBSERVATION_FAILED, "tag")
    flags = ("--prerelease", "--latest=false")
    paths = tuple(str(directory / item.name) for item in bootstrap.files)
    target = ("--repo", REPOSITORY, "--target", bootstrap.revision)
    title = ("--title", f"Kast bootstrap {bootstrap.tag.removeprefix('bootstrap-')}")
    notes = ("--notes", "Executables that install.sh pins by digest. Not a Kast product release.")
    create = ("gh", "release", "create", bootstrap.tag, *paths, *target, "--draft", *flags)
    created = run((*create, *title, *notes))
    if created.code != 0 or observe(bootstrap, run) is not False:
        raise Rejected(Failure.PUBLICATION_FAILED, "draft")
    promoted = run(
        ("gh", "release", "edit", bootstrap.tag, "--repo", REPOSITORY, "--draft=false", *flags)
    )
    if promoted.code != 0 or observe(bootstrap, run) is not True:
        raise Rejected(Failure.PUBLICATION_FAILED, "promotion")
    require_stable_latest(run)
    return Outcome(Effect.PUBLISHED, bootstrap)


def main(arguments: Sequence[str] | None = None, run: Run = execute) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args(arguments)
    if (
        os.environ.get("GITHUB_REPOSITORY") != REPOSITORY
        or os.environ.get("GITHUB_REF") != "refs/heads/main"
    ):
        print("publish-bootstrap: publication runs only on main of the repository", file=sys.stderr)
        return 1
    try:
        outcome = publish(args.directory, run)
    except Rejected as rejection:
        print(f"publish-bootstrap: {rejection}", file=sys.stderr)
        return 1
    print(json.dumps(asdict(outcome), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
