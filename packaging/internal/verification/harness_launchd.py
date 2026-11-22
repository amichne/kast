"""Retire semantic coordinator launchd jobs that a disposable harness HOME registered.

A harness that overrides HOME still submits its semantic coordinator to the real
user's `gui/<uid>` launchd domain. The job outlives the harness unless the harness
withdraws it. A harness wraps its run in `retiring_semantic_jobs(root)`: on success
and on failure it boots out every `io.github.amichne.kast.semantic.*` job whose
definition, executable and working directory all lie under `root`, then proves that
none remains.

As a command, this module lists the jobs that already leaked from recognized harness
roots, and boots them out with `--retire`. It never selects the real installation:

    python3 packaging/internal/verification/harness_launchd.py [--retire]
"""

from __future__ import annotations

import argparse
import os
import platform
import pwd
import re
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Protocol

LABEL_PREFIX = "io.github.amichne.kast.semantic."
LAUNCHCTL = "/bin/launchctl"
# launchctl reports an unknown service with this exit status.
SERVICE_NOT_FOUND = 113
INVOCATION_TIMEOUT_SECONDS = 15
# A completed bootout signals the job; launchd lists it until the coordinator exits.
ABSENCE_OBSERVATIONS = 100
ABSENCE_PAUSE_SECONDS = 0.1
ASSIGNMENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*=")


@dataclass(frozen=True)
class Invocation:
    code: int
    stdout: str


class Launchctl(Protocol):
    """One launchctl invocation with already-assembled arguments, without the executable."""

    def __call__(self, arguments: tuple[str, ...]) -> Invocation: ...


def native_launchctl(arguments: tuple[str, ...]) -> Invocation:
    result = subprocess.run(
        (LAUNCHCTL, *arguments),
        capture_output=True,
        text=True,
        timeout=INVOCATION_TIMEOUT_SECONDS,
        check=False,
    )
    return Invocation(result.returncode, result.stdout)


class Failure(StrEnum):
    LIST_REJECTED = "LIST_REJECTED"
    DEFINITION_REJECTED = "DEFINITION_REJECTED"
    BOOTOUT_REJECTED = "BOOTOUT_REJECTED"
    JOB_REMAINS = "JOB_REMAINS"


class LaunchdTeardownRejected(ValueError):
    """Harness callers already report ValueError; the failure and labels stay typed."""

    def __init__(self, failure: Failure, labels: tuple[str, ...]):
        super().__init__(f"semantic launchd teardown {failure}: {', '.join(labels) or '-'}")
        self.failure = failure
        self.labels = labels


@dataclass(frozen=True)
class SemanticJob:
    label: str
    definition: Path
    executable: Path
    directory: Path

    def lies_under(self, root: Path) -> bool:
        return all(
            path.is_relative_to(root) for path in (self.definition, self.executable, self.directory)
        )


def domain(uid: int) -> str:
    return f"gui/{uid}"


def semantic_labels(launchctl: Launchctl) -> tuple[str, ...]:
    listing = launchctl(("list",))
    if listing.code != 0:
        raise LaunchdTeardownRejected(Failure.LIST_REJECTED, ())
    labels: list[str] = []
    for line in listing.stdout.splitlines():
        fields = line.split("\t")
        if len(fields) == 3 and fields[2].startswith(LABEL_PREFIX):
            labels.append(fields[2])
    return tuple(labels)


def parse_definition(label: str, target: str, raw: str) -> SemanticJob:
    """Reads the fields the coordinator's launch contract writes; anything else is unproven."""
    lines = raw.splitlines()

    def single(prefix: str) -> Path:
        values = [line.removeprefix(prefix) for line in lines if line.startswith(prefix)]
        if len(values) != 1 or not values[0].startswith("/"):
            raise LaunchdTeardownRejected(Failure.DEFINITION_REJECTED, (label,))
        return Path(values[0])

    if not lines or lines[0] != f"{target} = {{":
        raise LaunchdTeardownRejected(Failure.DEFINITION_REJECTED, (label,))
    try:
        start = lines.index("\targuments = {") + 1
    except ValueError:
        raise LaunchdTeardownRejected(Failure.DEFINITION_REJECTED, (label,)) from None
    arguments: list[str] = []
    for line in lines[start:]:
        if not line.startswith("\t\t"):
            break
        arguments.append(line.removeprefix("\t\t"))
    # `/usr/bin/env -i NAME=value... EXECUTABLE ...`: the first non-assignment names the program.
    program = next((item for item in arguments[2:] if not ASSIGNMENT.match(item)), None)
    if arguments[:2] != ["/usr/bin/env", "-i"] or program is None or not program.startswith("/"):
        raise LaunchdTeardownRejected(Failure.DEFINITION_REJECTED, (label,))
    return SemanticJob(label, single("\tpath = "), Path(program), single("\tworking directory = "))


def describe(label: str, uid: int, launchctl: Launchctl) -> SemanticJob | None:
    target = f"{domain(uid)}/{label}"
    printed = launchctl(("print", target))
    if printed.code == SERVICE_NOT_FOUND:
        return None
    if printed.code != 0:
        raise LaunchdTeardownRejected(Failure.DEFINITION_REJECTED, (label,))
    return parse_definition(label, target, printed.stdout)


def semantic_jobs(uid: int, launchctl: Launchctl) -> tuple[SemanticJob, ...]:
    described = (describe(label, uid, launchctl) for label in semantic_labels(launchctl))
    return tuple(job for job in described if job is not None)


def jobs_under(root: Path, uid: int, launchctl: Launchctl) -> tuple[SemanticJob, ...]:
    return tuple(job for job in semantic_jobs(uid, launchctl) if job.lies_under(root))


def bootout(
    job: SemanticJob,
    uid: int,
    launchctl: Launchctl,
    pause: Callable[[], None],
    observations: int = ABSENCE_OBSERVATIONS,
) -> None:
    """Only an observed absence proves withdrawal; a completed bootout alone does not."""
    retired = launchctl(("bootout", f"{domain(uid)}/{job.label}"))
    for index in range(observations):
        listed = launchctl(("list", job.label))
        if listed.code == SERVICE_NOT_FOUND:
            return
        if listed.code != 0:
            raise LaunchdTeardownRejected(Failure.LIST_REJECTED, (job.label,))
        if retired.code != 0:
            raise LaunchdTeardownRejected(Failure.BOOTOUT_REJECTED, (job.label,))
        if index + 1 < observations:
            pause()
    raise LaunchdTeardownRejected(Failure.JOB_REMAINS, (job.label,))


def require_none_under(root: Path, uid: int, launchctl: Launchctl) -> None:
    remaining = jobs_under(root, uid, launchctl)
    if remaining:
        raise LaunchdTeardownRejected(Failure.JOB_REMAINS, tuple(job.label for job in remaining))


def retire_jobs_under(
    root: Path,
    uid: int,
    launchctl: Launchctl,
    pause: Callable[[], None] = lambda: time.sleep(ABSENCE_PAUSE_SECONDS),
) -> tuple[SemanticJob, ...]:
    owned = jobs_under(root, uid, launchctl)
    for job in owned:
        bootout(job, uid, launchctl, pause)
    require_none_under(root, uid, launchctl)
    return owned


@contextmanager
def retiring_semantic_jobs(
    root: Path,
    launchctl: Launchctl | None = None,
    uid: int | None = None,
    pause: Callable[[], None] = lambda: time.sleep(ABSENCE_PAUSE_SECONDS),
) -> Iterator[None]:
    """Retires the harness's own jobs after success and after failure, then proves absence.

    Without an injected launchctl this is a no-op off macOS, which has no launchd domain.
    A body failure stays the raised failure; a teardown rejection is attached to it.
    """
    if launchctl is None and platform.system() != "Darwin":
        yield
        return
    selected = launchctl if launchctl is not None else native_launchctl
    owner = uid if uid is not None else os.getuid()
    owned = root.resolve()
    try:
        yield
    except BaseException as failure:
        try:
            _ = retire_jobs_under(owned, owner, selected, pause)
        except (LaunchdTeardownRejected, OSError, subprocess.SubprocessError) as teardown:
            failure.add_note(f"launchd teardown also rejected: {teardown}")
        raise
    _ = retire_jobs_under(owned, owner, selected, pause)


@dataclass(frozen=True)
class HarnessRoot:
    """Each direct child of `parent` whose name starts with `prefix` is one harness root."""

    parent: Path
    prefix: str

    def owner_of(self, path: Path) -> Path | None:
        if not path.is_relative_to(self.parent):
            return None
        parts = path.relative_to(self.parent).parts
        if len(parts) < 2 or not parts[0].startswith(self.prefix):
            return None
        return self.parent / parts[0]


def recognized_roots(home: Path, temporary: Path) -> tuple[HarnessRoot, ...]:
    """Harness roots by naming rule, so a job outliving its deleted root is still found."""
    private = Path("/private/tmp")
    return (
        HarnessRoot(home / ".cache", "kast-exp-home"),
        HarnessRoot(home / ".local/share/kast-local", ""),
        HarnessRoot(temporary, "kast-installed-native-"),
        HarnessRoot(temporary, "kast-local-installed-"),
        HarnessRoot(private, "kast-local-install-"),
        HarnessRoot(private, "kn -"),
    )


def leaked_jobs(
    roots: tuple[HarnessRoot, ...], uid: int, launchctl: Launchctl
) -> tuple[tuple[SemanticJob, Path], ...]:
    selected: list[tuple[SemanticJob, Path]] = []
    for job in semantic_jobs(uid, launchctl):
        for candidate in roots:
            owner = candidate.owner_of(job.definition)
            if owner is not None and job.lies_under(owner):
                selected.append((job, owner))
                break
    return tuple(selected)


def cleanup(
    retire: bool,
    roots: tuple[HarnessRoot, ...],
    uid: int,
    launchctl: Launchctl,
    pause: Callable[[], None] = lambda: time.sleep(ABSENCE_PAUSE_SECONDS),
) -> int:
    leaked = leaked_jobs(roots, uid, launchctl)
    for job, owner in leaked:
        if retire:
            bootout(job, uid, launchctl, pause)
        print(f"{'retired' if retire else 'leaked'}\t{job.label}\t{owner}", flush=True)
    if retire and leaked_jobs(roots, uid, launchctl):
        raise LaunchdTeardownRejected(Failure.JOB_REMAINS, tuple(job.label for job, _ in leaked))
    if not retire and leaked:
        print("rerun with --retire to boot out these jobs", file=sys.stderr)
    return 0


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument(
        "--retire", action="store_true", help="boot out the listed jobs instead of listing them"
    )
    selected = parser.parse_args(arguments)
    if platform.system() != "Darwin":
        print("harness launchd cleanup applies only to macOS", file=sys.stderr)
        return 2
    uid = os.getuid()
    # The account home, not $HOME, which a harness may have overridden.
    home = Path(pwd.getpwuid(uid).pw_dir).resolve()
    roots = recognized_roots(home, Path(tempfile.gettempdir()).resolve())
    try:
        return cleanup(bool(selected.retire), roots, uid, native_launchctl)
    except (LaunchdTeardownRejected, OSError, subprocess.SubprocessError) as failure:
        print(f"harness launchd cleanup rejected: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
