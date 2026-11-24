"""Composed workflow steps select platform behaviour from the explicit target."""

from __future__ import annotations

from pathlib import Path

import pytest

import composed_ci as ci
from composed_ci import Command, Failure, Plan, Rejected, Release, Target

REPOSITORY = Path("/work/kast")
SCRATCH = Path("/private/tmp")
RELEASE = Release("1.2.3", "a" * 40)
GRADLE = (
    "./gradlew",
    "--no-daemon",
    "--no-parallel",
    # Two workers let Gradle's parallel project configuration starve and never finish.
    "--max-workers=1",
    "-Dorg.gradle.jvmargs=-Xmx3g",
    "-Pversion=1.2.3",
    "-PkastSourceRevision=" + "a" * 40,
)


def rejected(outcome: object) -> Failure:
    assert isinstance(outcome, Rejected), outcome
    return outcome.failure


def planned(outcome: Plan | Rejected) -> Plan:
    assert isinstance(outcome, Plan), outcome
    return outcome


@pytest.mark.parametrize(
    ("version", "revision", "failure"),
    [
        ("1.2", "a" * 40, Failure.INVALID_VERSION),
        ("1.2.3", "HEAD", Failure.INVALID_REVISION),
        ("1.2.3", "A" * 40, Failure.INVALID_REVISION),
    ],
)
def test_release_identity_is_admitted_before_planning(
    version: str, revision: str, failure: Failure
) -> None:
    assert ci.admit_release("1.2.3", "a" * 40) == RELEASE
    assert rejected(ci.admit_release(version, revision)) == failure


def test_only_the_linux_release_runs_the_source_guards() -> None:
    linux = ci.release_plan(Target.LINUX_X86_64, RELEASE)
    mac = ci.release_plan(Target.MACOS_AARCH64, RELEASE)
    tasks = ("assembleRelease", ":runtime:hosted:headlessBootstrapPlugin")
    guards = ("verifyJsonContracts", "verifyKastArchitecture")
    assert linux.commands == (Command((*GRADLE, *tasks, *guards)),)
    assert mac.commands == (Command((*GRADLE, *tasks)),)
    assert ci.bootstrap_plan(RELEASE).commands == (
        Command((*GRADLE, ":runtime:hosted:headlessBootstrapPlugin")),
    )


def test_linux_support_is_rejected_for_macos_and_prepares_its_parent() -> None:
    assert rejected(ci.linux_support_plan(Target.MACOS_AARCH64, "python3", REPOSITORY)) == (
        Failure.LINUX_ONLY
    )
    plan = planned(ci.linux_support_plan(Target.LINUX_X86_64, "python3", REPOSITORY))
    assert plan.directories == (REPOSITORY / "build/cloud-input",)
    assert plan.commands == (
        Command(
            (
                "python3",
                "packaging/internal/composition/composed_release.py",
                "linux-support",
                "--output",
                "/work/kast/build/cloud-input/linux-support",
            )
        ),
    )


def test_assembly_downloads_the_donor_and_adds_linux_support_only_on_linux() -> None:
    donor = "idea-2026.2.2.tar.gz"
    linux = planned(ci.assembly_plan(Target.LINUX_X86_64, donor, RELEASE, REPOSITORY, SCRATCH))
    mac = planned(
        ci.assembly_plan(
            Target.MACOS_AARCH64, "idea-2026.2.2-aarch64.dmg", RELEASE, REPOSITORY, SCRATCH
        )
    )
    download, assemble = linux.commands
    assert linux.directories == (REPOSITORY / "build/cloud-input",)
    assert download.argv == (
        "curl",
        "--fail",
        "--location",
        "--retry",
        "3",
        "https://download.jetbrains.com/idea/idea-2026.2.2.tar.gz",
        "--output",
        "build/cloud-input/idea-2026.2.2.tar.gz",
    )
    assert assemble.argv == (
        "uv",
        "run",
        "--locked",
        "--project",
        "packaging",
        "python",
        "packaging/internal/composition/composed_release.py",
        "assemble",
        "--archive",
        "/work/kast/build/cloud-input/idea-2026.2.2.tar.gz",
        "--bootstrap",
        "/work/kast/runtime/hosted/build/distributions/kast-managed-headless-v1.2.3-idea-262.zip",
        "--assets",
        "/work/kast/build/release/v1.2.3",
        "--root",
        "/private/tmp/kp",
        "--output",
        "/work/kast/build/composed-release",
        "--version",
        "1.2.3",
        "--source-revision",
        "a" * 40,
        "--linux-support",
        "/work/kast/build/cloud-input/linux-support",
    )
    assert "--linux-support" not in mac.commands[1].argv
    assert mac.commands[0].argv[5].endswith("/idea-2026.2.2-aarch64.dmg")


@pytest.mark.parametrize("donor", ["../idea.tar.gz", "idea-1/x.dmg", "idea-1.zip", ""])
def test_assembly_rejects_a_donor_that_is_not_one_pinned_archive_name(donor: str) -> None:
    outcome = ci.assembly_plan(Target.LINUX_X86_64, donor, RELEASE, REPOSITORY, SCRATCH)
    assert rejected(outcome) == Failure.INVALID_DONOR


def test_qualification_uses_the_system_python_on_linux_and_the_candidate_on_macos() -> None:
    assert ci.qualification_interpreter(Target.LINUX_X86_64, "/opt/py") == "/usr/bin/python3"
    assert ci.qualification_interpreter(Target.MACOS_AARCH64, "/opt/py") == "/opt/py"
    assert rejected(ci.qualification_interpreter(Target.MACOS_AARCH64, "")) == (
        Failure.MISSING_INTERPRETER
    )
    plan = ci.qualification_plan(
        Target.MACOS_AARCH64, "/opt/py", RELEASE, ("b" * 64, "c" * 64), REPOSITORY, SCRATCH
    )
    assert plan.commands == (
        Command(("/opt/py", "--version")),
        Command(
            (
                "/opt/py",
                "-E",
                "-s",
                "-B",
                "packaging/internal/composition/composed_release.py",
                "qualify",
                "--archive",
                "/work/kast/build/composed-release/kast-composed-setup-v1.2.3-macos-aarch64.tar.gz",
                "--archive-sha256",
                "b" * 64,
                "--runtime-archive",
                "/work/kast/build/composed-release/kast-runtime-v1.2.3-macos-aarch64.zip",
                "--runtime-sha256",
                "c" * 64,
                "--root",
                "/private/tmp/kc",
                "--evidence",
                "/work/kast/build/composed-evidence",
                "--version",
                "1.2.3",
                "--source-revision",
                "a" * 40,
            )
        ),
    )


def test_digest_is_the_first_field_of_the_sidecar(tmp_path: Path) -> None:
    archive = tmp_path / "kast-runtime-v1.2.3-linux-x86_64.zip"
    assert rejected(ci.read_digest(archive)) == Failure.MISSING_DIGEST
    sidecar = tmp_path / "kast-runtime-v1.2.3-linux-x86_64.zip.sha256"
    _ = sidecar.write_text(f"{'d' * 64}  {archive.name}\n")
    assert ci.read_digest(archive) == "d" * 64
    _ = sidecar.write_text(f"{'d' * 63}  {archive.name}\n")
    assert rejected(ci.read_digest(archive)) == Failure.INVALID_DIGEST


def test_coordinator_is_linux_only_and_scopes_the_runtime_directory_to_systemctl() -> None:
    java = Path("/opt/java/release")
    assert rejected(ci.coordinator_plan(Target.MACOS_AARCH64, "runner", 1001, java, RELEASE)) == (
        Failure.LINUX_ONLY
    )
    plan = planned(ci.coordinator_plan(Target.LINUX_X86_64, "runner", 1001, java, RELEASE))
    linger, start, probe, stop, detached = plan.commands
    # The same probe repeats without a user manager, with its own evidence.
    assert stop == Command(
        ("sudo", "systemctl", "stop", "user@1001.service", "user-runtime-dir@1001.service")
    )
    assert detached == Command((*probe.argv[:-1], probe.argv[-1] + "-detached"))
    assert linger == Command(("sudo", "loginctl", "enable-linger", "runner"))
    assert start == Command(
        ("systemctl", "--user", "start", "default.target"),
        (("XDG_RUNTIME_DIR", "/run/user/1001"),),
    )
    assert probe.environment == ()
    assert probe.argv[6:] == (
        "packaging/internal/verification/linux_coordinator_probe.py",
        "--directory",
        "build/composed-release",
        "--receipt",
        "build/composed-evidence/qualification.json",
        "--control",
        "/tmp/kc/components/control/share/kast/libexec/kast-management",
        "--java-release",
        "/opt/java/release",
        "--version",
        "1.2.3",
        "--revision",
        "a" * 40,
        "--evidence",
        "build/composed-evidence/installed-coordinator",
    )


def test_receipt_is_copied_beside_the_target_products(tmp_path: Path) -> None:
    assert rejected(ci.retain_receipt(Target.LINUX_X86_64, tmp_path)) == Failure.MISSING_RECEIPT
    evidence = tmp_path / "build/composed-evidence"
    evidence.mkdir(parents=True)
    _ = (evidence / "qualification.json").write_text('{"receipt": 1}')
    assert rejected(ci.retain_receipt(Target.LINUX_X86_64, tmp_path)) == (
        Failure.MISSING_OUTPUT_DIRECTORY
    )
    (tmp_path / "build/composed-release").mkdir()
    assert ci.retain_receipt(Target.LINUX_X86_64, tmp_path) == ci.Completed("retain-receipt")
    copied = tmp_path / "build/composed-release/qualification-linux-x86_64.json"
    assert copied.read_text() == '{"receipt": 1}'


def test_execution_stops_at_the_first_failed_command_with_its_status(tmp_path: Path) -> None:
    created = tmp_path / "created"
    marker = tmp_path / "marker"
    plan = Plan(
        (created,),
        (
            Command(("sh", "-c", 'test "$PROBE" = scoped', "probe"), (("PROBE", "scoped"),)),
            Command(("sh", "-c", "exit 7")),
            Command(("touch", str(marker))),
        ),
    )
    outcome = ci.execute("probe", plan, tmp_path)
    assert outcome == Rejected(Failure.COMMAND_FAILED, "sh exit=7", 7)
    assert created.is_dir()
    assert not marker.exists()
    unstarted = Plan((), (Command((str(tmp_path / "absent"),)),))
    assert rejected(ci.execute("probe", unstarted, tmp_path)) == Failure.COMMAND_UNSTARTED
