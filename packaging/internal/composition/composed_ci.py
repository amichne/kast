"""Run one composed-candidate workflow step for an explicit native target.

Each subcommand is the body of one `composed:` mise task in `config/ci/tasks/composed.toml`.
CI and a local run invoke the same task, so `--target` selects platform behaviour here instead
of in workflow shell. Planning is pure; `main` performs the planned effects in order and stops
at the first failed command with its exit status.
"""

from __future__ import annotations

import argparse
import json
import os
import pwd
import re
import shlex
import shutil
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[3]
COMPOSED_RELEASE = "packaging/internal/composition/composed_release.py"
COORDINATOR_PROBE = "packaging/internal/verification/linux_coordinator_probe.py"
DONOR_ORIGIN = "https://download.jetbrains.com/idea/"
CONTROL = "/tmp/kc/components/control/share/kast/libexec/kast-management"
LINUX_PYTHON = "/usr/bin/python3"
GRADLE = (
    "./gradlew",
    "--no-daemon",
    "--no-parallel",
    "--max-workers=2",
    "-Dorg.gradle.jvmargs=-Xmx3g",
)
PACKAGING_PYTHON = ("uv", "run", "--locked", "--project", "packaging", "python")
BOOTSTRAP_TASK = ":runtime:hosted:headlessBootstrapPlugin"
LINUX_SOURCE_GUARDS = ("verifyJsonContracts", "verifyKastArchitecture")
_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
_REVISION = re.compile(r"[0-9a-f]{40}")
_DIGEST = re.compile(r"[0-9a-f]{64}")
_DONOR = re.compile(r"idea-[0-9A-Za-z][0-9A-Za-z._-]*\.(?:tar\.gz|dmg)")


class Target(StrEnum):
    LINUX_X86_64 = "linux-x86_64"
    MACOS_AARCH64 = "macos-aarch64"

    @property
    def is_linux(self) -> bool:
        return self is Target.LINUX_X86_64


class Failure(StrEnum):
    INVALID_VERSION = "INVALID_VERSION"
    INVALID_REVISION = "INVALID_REVISION"
    LINUX_ONLY = "LINUX_ONLY"
    INVALID_DONOR = "INVALID_DONOR"
    MISSING_INTERPRETER = "MISSING_INTERPRETER"
    MISSING_DIGEST = "MISSING_DIGEST"
    INVALID_DIGEST = "INVALID_DIGEST"
    MISSING_JAVA_RELEASE = "MISSING_JAVA_RELEASE"
    MISSING_RECEIPT = "MISSING_RECEIPT"
    MISSING_OUTPUT_DIRECTORY = "MISSING_OUTPUT_DIRECTORY"
    COMMAND_UNSTARTED = "COMMAND_UNSTARTED"
    COMMAND_FAILED = "COMMAND_FAILED"


@dataclass(frozen=True)
class Rejected:
    failure: Failure
    detail: str
    exit_code: int = 1


@dataclass(frozen=True)
class Release:
    """An admitted numeric version and full source revision."""

    version: str
    revision: str


@dataclass(frozen=True)
class Command:
    argv: tuple[str, ...]
    environment: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class Plan:
    """Directories to create, then commands to run in order from the repository root."""

    directories: tuple[Path, ...]
    commands: tuple[Command, ...]


@dataclass(frozen=True)
class Completed:
    step: str


def admit_release(version: str, revision: str) -> Release | Rejected:
    if _VERSION.fullmatch(version) is None:
        return Rejected(Failure.INVALID_VERSION, "numeric version required")
    if _REVISION.fullmatch(revision) is None:
        return Rejected(Failure.INVALID_REVISION, "one full Git identity required")
    return Release(version, revision)


def require_linux(target: Target, step: str) -> Rejected | None:
    return None if target.is_linux else Rejected(Failure.LINUX_ONLY, f"{step} on {target}")


def gradle(release: Release, tasks: Sequence[str]) -> Command:
    return Command(
        (
            *GRADLE,
            f"-Pversion={release.version}",
            f"-PkastSourceRevision={release.revision}",
            *tasks,
        )
    )


def bootstrap_plan(release: Release) -> Plan:
    return Plan((), (gradle(release, (BOOTSTRAP_TASK,)),))


def release_plan(target: Target, release: Release) -> Plan:
    guards = LINUX_SOURCE_GUARDS if target is Target.LINUX_X86_64 else ()
    return Plan((), (gradle(release, ("assembleRelease", BOOTSTRAP_TASK, *guards)),))


def linux_support_plan(target: Target, python: str, repository: Path) -> Plan | Rejected:
    rejected = require_linux(target, "linux-support")
    if rejected is not None:
        return rejected
    inputs = repository / "build/cloud-input"
    command = (python, COMPOSED_RELEASE, "linux-support", "--output", str(inputs / "linux-support"))
    return Plan((inputs,), (Command(command),))


def assembly_plan(
    target: Target, donor: str, release: Release, repository: Path, scratch: Path
) -> Plan | Rejected:
    if _DONOR.fullmatch(donor) is None:
        return Rejected(Failure.INVALID_DONOR, "one pinned IDEA archive file name required")
    inputs = repository / "build/cloud-input"
    download = Command(
        (
            "curl",
            "--fail",
            "--location",
            "--retry",
            "3",
            DONOR_ORIGIN + donor,
            "--output",
            f"build/cloud-input/{donor}",
        )
    )
    distributions = repository / "runtime/hosted/build/distributions"
    bootstrap = distributions / f"kast-managed-headless-v{release.version}-idea-262.zip"
    support = ("--linux-support", str(inputs / "linux-support")) if target.is_linux else ()
    assemble = Command(
        (
            *PACKAGING_PYTHON,
            COMPOSED_RELEASE,
            "assemble",
            "--archive",
            str(inputs / donor),
            "--bootstrap",
            str(bootstrap),
            "--assets",
            str(repository / f"build/release/v{release.version}"),
            "--root",
            str(scratch / "kp"),
            "--output",
            str(repository / "build/composed-release"),
            "--version",
            release.version,
            "--source-revision",
            release.revision,
            *support,
        )
    )
    return Plan((inputs,), (download, assemble))


def discard_plan(repository: Path, scratch: Path) -> Plan:
    command = (
        *PACKAGING_PYTHON,
        COMPOSED_RELEASE,
        "discard-assembly-inputs",
        "--root",
        str(scratch / "kp"),
        "--inputs",
        str(repository / "build/cloud-input"),
    )
    return Plan((), (Command(command),))


def qualification_interpreter(target: Target, candidate: str) -> str | Rejected:
    """Linux qualifies with the system interpreter; macOS with the provisioned candidate."""
    if target.is_linux:
        return LINUX_PYTHON
    if not candidate:
        return Rejected(Failure.MISSING_INTERPRETER, "candidate Python path required")
    return candidate


def product_archives(target: Target, release: Release, repository: Path) -> tuple[Path, Path]:
    products = repository / "build/composed-release"
    return (
        products / f"kast-composed-setup-v{release.version}-{target}.tar.gz",
        products / f"kast-runtime-v{release.version}-{target}.zip",
    )


def read_digest(archive: Path) -> str | Rejected:
    """The first field of the archive's `.sha256` file, which must be one SHA-256 digest."""
    record = archive.with_name(archive.name + ".sha256")
    try:
        fields = record.read_text().split(" ", 1)
    except OSError:
        return Rejected(Failure.MISSING_DIGEST, record.name)
    if _DIGEST.fullmatch(fields[0]) is None:
        return Rejected(Failure.INVALID_DIGEST, record.name)
    return fields[0]


def qualification_plan(
    target: Target,
    python: str,
    release: Release,
    digests: tuple[str, str],
    repository: Path,
    scratch: Path,
) -> Plan:
    archive, runtime = product_archives(target, release, repository)
    qualify = (
        python,
        "-E",
        "-s",
        "-B",
        COMPOSED_RELEASE,
        "qualify",
        "--archive",
        str(archive),
        "--archive-sha256",
        digests[0],
        "--runtime-archive",
        str(runtime),
        "--runtime-sha256",
        digests[1],
        "--root",
        str(scratch / "kc"),
        "--evidence",
        str(repository / "build/composed-evidence"),
        "--version",
        release.version,
        "--source-revision",
        release.revision,
    )
    return Plan((), (Command((python, "--version")), Command(qualify)))


def coordinator_plan(
    target: Target, user: str, uid: int, java_release: Path, release: Release
) -> Plan | Rejected:
    rejected = require_linux(target, "coordinator")
    if rejected is not None:
        return rejected
    probe = (
        *PACKAGING_PYTHON,
        COORDINATOR_PROBE,
        "--directory",
        "build/composed-release",
        "--receipt",
        "build/composed-evidence/qualification.json",
        "--control",
        CONTROL,
        "--java-release",
        str(java_release),
        "--version",
        release.version,
        "--revision",
        release.revision,
        "--evidence",
    )
    evidence = "build/composed-evidence/installed-coordinator"
    # The second probe has no user manager, as in most containers, so it starts detached.
    return Plan(
        (),
        (
            Command(("sudo", "loginctl", "enable-linger", user)),
            Command(
                ("systemctl", "--user", "start", "default.target"),
                (("XDG_RUNTIME_DIR", f"/run/user/{uid}"),),
            ),
            Command((*probe, evidence)),
            Command(
                (
                    "sudo",
                    "systemctl",
                    "stop",
                    f"user@{uid}.service",
                    f"user-runtime-dir@{uid}.service",
                )
            ),
            Command((*probe, f"{evidence}-detached")),
        ),
    )


def retain_receipt(target: Target, repository: Path) -> Completed | Rejected:
    receipt = repository / "build/composed-evidence/qualification.json"
    products = repository / "build/composed-release"
    if not receipt.is_file():
        return Rejected(Failure.MISSING_RECEIPT, str(receipt.relative_to(repository)))
    if not products.is_dir():
        return Rejected(Failure.MISSING_OUTPUT_DIRECTORY, str(products.relative_to(repository)))
    _ = shutil.copy(receipt, products / f"qualification-{target}.json")
    return Completed("retain-receipt")


def execute(step: str, plan: Plan, repository: Path) -> Completed | Rejected:
    for directory in plan.directories:
        directory.mkdir(parents=True, exist_ok=True)
    for index, command in enumerate(plan.commands, start=1):
        print(
            f"composed-ci: {step} {index}/{len(plan.commands)} {shlex.join(command.argv)}",
            flush=True,
        )
        environment = {**os.environ, **dict(command.environment)}
        try:
            result = subprocess.run(command.argv, cwd=repository, env=environment, check=False)
        except OSError as error:
            return Rejected(Failure.COMMAND_UNSTARTED, f"{command.argv[0]}: {type(error).__name__}")
        if result.returncode != 0:
            code = result.returncode if result.returncode > 0 else 128 - result.returncode
            return Rejected(Failure.COMMAND_FAILED, f"{command.argv[0]} exit={code}", code)
    return Completed(step)


def scratch_root() -> Path:
    """The physical `/tmp` that assembly and qualification share (`/private/tmp` on macOS)."""
    return Path("/tmp").resolve()


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    steps = root.add_subparsers(dest="step", required=True)

    def step(name: str, *, target: bool = True, release: bool = True) -> argparse.ArgumentParser:
        command = steps.add_parser(name, allow_abbrev=False)
        if target:
            _ = command.add_argument("--target", type=Target, choices=tuple(Target), required=True)
        if release:
            _ = command.add_argument("--version", required=True)
            _ = command.add_argument("--source-revision", required=True)
        return command

    _ = step("linux-support", release=False)
    _ = step("bootstrap", target=False)
    _ = step("release")
    assemble = step("assemble")
    _ = assemble.add_argument("--donor", required=True)
    _ = step("discard", target=False, release=False)
    qualify = step("qualify")
    _ = qualify.add_argument("--candidate-python", required=True)
    coordinator = step("coordinator")
    _ = coordinator.add_argument("--java-release", type=Path, required=True)
    _ = step("retain-receipt", release=False)
    return root


def run(arguments: argparse.Namespace, repository: Path) -> Completed | Rejected:
    step: str = arguments.step
    if step == "linux-support":
        planned = linux_support_plan(arguments.target, sys.executable, repository)
        return planned if isinstance(planned, Rejected) else execute(step, planned, repository)
    if step == "discard":
        return execute(step, discard_plan(repository, scratch_root()), repository)
    if step == "retain-receipt":
        return retain_receipt(arguments.target, repository)
    release = admit_release(arguments.version, arguments.source_revision)
    if isinstance(release, Rejected):
        return release
    if step == "bootstrap":
        return execute(step, bootstrap_plan(release), repository)
    if step == "release":
        return execute(step, release_plan(arguments.target, release), repository)
    if step == "assemble":
        planned = assembly_plan(
            arguments.target, arguments.donor, release, repository, scratch_root()
        )
        return planned if isinstance(planned, Rejected) else execute(step, planned, repository)
    if step == "qualify":
        return qualify(arguments.target, arguments.candidate_python, release, repository)
    java_release: Path = arguments.java_release
    if not java_release.is_file():
        return Rejected(Failure.MISSING_JAVA_RELEASE, str(java_release))
    account = pwd.getpwuid(os.getuid())
    planned = coordinator_plan(
        arguments.target, account.pw_name, account.pw_uid, java_release, release
    )
    return planned if isinstance(planned, Rejected) else execute(step, planned, repository)


def qualify(
    target: Target, candidate: str, release: Release, repository: Path
) -> Completed | Rejected:
    python = qualification_interpreter(target, candidate)
    if isinstance(python, Rejected):
        return python
    digests: list[str] = []
    for archive in product_archives(target, release, repository):
        digest = read_digest(archive)
        if isinstance(digest, Rejected):
            return digest
        digests.append(digest)
    plan = qualification_plan(
        target, python, release, (digests[0], digests[1]), repository, scratch_root()
    )
    return execute("qualify", plan, repository)


def main(arguments: Sequence[str] | None = None) -> int:
    outcome = run(parser().parse_args(arguments), REPOSITORY)
    if isinstance(outcome, Rejected):
        print(json.dumps({"composed-ci": asdict(outcome)}), file=sys.stderr)
        return outcome.exit_code
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
