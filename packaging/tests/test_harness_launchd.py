"""Harness launchd teardown, observed only through a scripted launchctl boundary.

The script returns launchd's observations; the production module decides which jobs
belong to the harness, boots them out, and proves their absence. No case reaches the
real user's launchd domain.
"""

import json
import os
from collections.abc import Callable
from pathlib import Path

import pytest

import harness_launchd as launchd
import local_product_install as local

UID = 501
DOMAIN = f"gui/{UID}"
NOT_FOUND = launchd.Invocation(113, "")
LISTED = launchd.Invocation(0, "")


class ScriptedLaunchctl:
    """Answers exactly the expected invocations in order; anything else fails the case."""

    def __init__(self, expected: list[tuple[tuple[str, ...], launchd.Invocation]]):
        self.expected = list(expected)
        self.violations: list[str] = []

    def __call__(self, arguments: tuple[str, ...]) -> launchd.Invocation:
        if not self.expected or self.expected[0][0] != arguments:
            # Recorded separately: the product may catch the raised error, the case must not pass.
            self.violations.append(f"unexpected launchctl {arguments}")
            raise AssertionError(f"unexpected launchctl {arguments}")
        return self.expected.pop(0)[1]

    def verify(self) -> None:
        assert self.violations == []
        assert self.expected == [], f"unconsumed launchctl expectations: {self.expected}"


def label(digest: str) -> str:
    return launchd.LABEL_PREFIX + digest.ljust(32, "0")


def listing(*labels: str) -> launchd.Invocation:
    rows = ["PID\tStatus\tLabel", "-\t0\tcom.apple.unrelated", *(f"16471\t0\t{x}" for x in labels)]
    return launchd.Invocation(0, "\n".join(rows) + "\n")


def definition(name: str, installation: Path, home: Path, domain: str = DOMAIN) -> str:
    """The shape `launchctl print` reports for the coordinator's launch contract."""
    broker = installation / "state/broker/295bda7d89b4d1db"
    return "\n".join(
        (
            f"{domain}/{name} = {{",
            "\tactive count = 1",
            f"\tpath = {broker / 'semantic-service.plist'}",
            "\ttype = LaunchAgent",
            "",
            "\tprogram = /usr/bin/env",
            "\targuments = {",
            "\t\t/usr/bin/env",
            "\t\t-i",
            f"\t\tBROKER_READINESS_FILE={broker / 'service-readiness.json'}",
            f"\t\tHOME={home}",
            "\t\tPATH=/usr/bin:/bin:/Applications/VMware Fusion.app/Contents/Public",
            f"\t\t{installation / 'share/kast/libexec/kast-management'}",
            f"\t\t-Duser.home={home}",
            "\t\t--internal-entrypoint=daemon",
            "\t\t--semantic",
            "\t}",
            "",
            f"\tworking directory = {installation}",
            f"\tstdout path = {broker / 'semantic-service.log'}",
            "}",
        )
    )


def printed(
    name: str, installation: Path, home: Path, domain: str = DOMAIN
) -> tuple[tuple[str, ...], launchd.Invocation]:
    raw = definition(name, installation, home, domain)
    return ("print", f"{domain}/{name}"), launchd.Invocation(0, raw)


def installation_of(home: Path) -> Path:
    return home / ".local/share/kast/installation"


def no_pause() -> None:
    return None


def counting() -> tuple[list[int], Callable[[], None]]:
    pauses: list[int] = []
    return pauses, lambda: pauses.append(1)


def test_definition_names_plist_executable_and_directory(tmp_path: Path) -> None:
    home = tmp_path / "home"
    job = launchd.parse_definition(
        label("a"), f"{DOMAIN}/{label('a')}", definition(label("a"), installation_of(home), home)
    )
    installation = installation_of(home)
    assert job == launchd.SemanticJob(
        label("a"),
        installation / "state/broker/295bda7d89b4d1db/semantic-service.plist",
        installation / "share/kast/libexec/kast-management",
        installation,
    )
    assert job.lies_under(tmp_path)
    assert not job.lies_under(tmp_path / "other")


DAMAGED_DEFINITIONS: tuple[Callable[[str], str], ...] = (
    lambda text: text.replace("\t\t-i\n", ""),
    lambda text: text.replace("\tworking directory", "\tworking dir"),
    lambda text: text.replace(f"{DOMAIN}/", "gui/0/", 1),
    lambda text: text + "\n\tpath = /second\n",
)


@pytest.mark.parametrize("damage", DAMAGED_DEFINITIONS)
def test_an_unrecognized_definition_is_unproven_not_foreign(
    tmp_path: Path, damage: Callable[[str], str]
) -> None:
    name = label("b")
    raw = damage(definition(name, installation_of(tmp_path / "home"), tmp_path / "home"))
    with pytest.raises(launchd.LaunchdTeardownRejected) as rejected:
        _ = launchd.parse_definition(name, f"{DOMAIN}/{name}", raw)
    assert (rejected.value.failure, rejected.value.labels) == (
        launchd.Failure.DEFINITION_REJECTED,
        (name,),
    )


def test_forced_failure_retires_only_the_harness_job_and_keeps_the_failure(
    tmp_path: Path,
) -> None:
    root = tmp_path.resolve() / "kast-installed-native-x"
    harness_home = root / "home"
    user_home = tmp_path.resolve() / "Users/someone"
    owned, user = label("01"), label("02")
    pauses, pause = counting()
    script = ScriptedLaunchctl(
        [
            (("list",), listing(user, owned)),
            printed(user, installation_of(user_home), user_home),
            printed(owned, installation_of(harness_home), harness_home),
            (("bootout", f"{DOMAIN}/{owned}"), LISTED),
            # launchd keeps listing a draining coordinator until it exits.
            (("list", owned), LISTED),
            (("list", owned), NOT_FOUND),
            # The teardown assertion observes the domain afresh.
            (("list",), listing(user)),
            printed(user, installation_of(user_home), user_home),
        ]
    )

    with pytest.raises(ValueError, match="^forced harness failure$") as raised:
        with launchd.retiring_semantic_jobs(root, script, UID, pause):
            raise ValueError("forced harness failure")

    script.verify()
    assert not isinstance(raised.value, launchd.LaunchdTeardownRejected)
    assert getattr(raised.value, "__notes__", []) == []
    assert pauses == [1]


def test_a_teardown_rejection_is_attached_to_the_forced_failure(tmp_path: Path) -> None:
    root = tmp_path.resolve() / "root"
    owned = label("03")
    script = ScriptedLaunchctl(
        [
            (("list",), listing(owned)),
            printed(owned, installation_of(root / "home"), root / "home"),
            (("bootout", f"{DOMAIN}/{owned}"), launchd.Invocation(5, "")),
            (("list", owned), LISTED),
        ]
    )

    with pytest.raises(RuntimeError) as raised:
        with launchd.retiring_semantic_jobs(root, script, UID, no_pause):
            raise RuntimeError("forced")

    script.verify()
    assert raised.value.args == ("forced",)
    assert raised.value.__notes__ == [
        f"launchd teardown also rejected: semantic launchd teardown BOOTOUT_REJECTED: {owned}"
    ]


def test_success_fails_closed_when_a_harness_job_outlives_bootout(tmp_path: Path) -> None:
    root = tmp_path.resolve() / "root"
    owned = label("04")
    observations = launchd.ABSENCE_OBSERVATIONS
    pauses, pause = counting()
    script = ScriptedLaunchctl(
        [
            (("list",), listing(owned)),
            printed(owned, installation_of(root / "home"), root / "home"),
            (("bootout", f"{DOMAIN}/{owned}"), LISTED),
            *[(("list", owned), LISTED)] * observations,
        ]
    )

    with pytest.raises(launchd.LaunchdTeardownRejected) as rejected:
        with launchd.retiring_semantic_jobs(root, script, UID, pause):
            pass

    script.verify()
    assert (rejected.value.failure, rejected.value.labels) == (
        launchd.Failure.JOB_REMAINS,
        (owned,),
    )
    assert len(pauses) == observations - 1


def test_an_unreadable_domain_fails_teardown_closed(tmp_path: Path) -> None:
    script = ScriptedLaunchctl([(("list",), launchd.Invocation(1, ""))])
    with pytest.raises(launchd.LaunchdTeardownRejected) as rejected:
        with launchd.retiring_semantic_jobs(tmp_path, script, UID, no_pause):
            pass
    script.verify()
    assert rejected.value.failure == launchd.Failure.LIST_REJECTED


def test_a_job_gone_between_list_and_print_needs_no_bootout(tmp_path: Path) -> None:
    gone = label("05")
    script = ScriptedLaunchctl(
        [
            (("list",), listing(gone)),
            (("print", f"{DOMAIN}/{gone}"), NOT_FOUND),
            (("list",), listing()),
        ]
    )
    with launchd.retiring_semantic_jobs(tmp_path, script, UID, no_pause):
        pass
    script.verify()


def test_without_a_supervisor_off_macos_teardown_invokes_nothing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    def rejected(arguments: tuple[str, ...]) -> launchd.Invocation:
        raise AssertionError(f"unexpected launchctl {arguments}")

    monkeypatch.setattr(launchd.platform, "system", lambda: "Linux")
    monkeypatch.setattr(launchd, "native_launchctl", rejected)
    with launchd.retiring_semantic_jobs(tmp_path):
        pass


def test_cleanup_selects_only_jobs_wholly_under_a_recognized_harness_root(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    home = tmp_path.resolve() / "Users/someone"
    temporary = tmp_path.resolve() / "var/folders/T"
    roots = launchd.recognized_roots(home, temporary)
    experiment = home / ".cache/kast-exp-home2"
    contributor = home / ".local/share/kast-local/jvm-control"
    native = temporary / "kast-installed-native-abc"
    user, mixed = label("10"), label("11")
    leaks = {
        label("12"): experiment,
        label("13"): contributor / "home",
        label("14"): native / "home",
    }

    def observed() -> list[tuple[tuple[str, ...], launchd.Invocation]]:
        mixed_text = definition(mixed, installation_of(experiment), experiment).replace(
            f"\t\t{installation_of(experiment) / 'share/kast/libexec/kast-management'}",
            f"\t\t{installation_of(home) / 'share/kast/libexec/kast-management'}",
        )
        return [
            (("list",), listing(user, mixed, *leaks)),
            # The real installation lives beside, never below, the contributor roots.
            printed(user, installation_of(home), home),
            (("print", f"{DOMAIN}/{mixed}"), launchd.Invocation(0, mixed_text)),
            *(printed(name, installation_of(owner), owner) for name, owner in leaks.items()),
        ]

    listed = ScriptedLaunchctl(observed())
    assert launchd.cleanup(False, roots, UID, listed, no_pause) == 0
    listed.verify()
    assert capsys.readouterr().out.splitlines() == [
        f"leaked\t{label('12')}\t{experiment}",
        f"leaked\t{label('13')}\t{contributor}",
        f"leaked\t{label('14')}\t{native}",
    ]

    retirements: list[tuple[tuple[str, ...], launchd.Invocation]] = []
    for name in leaks:
        retirements += [(("bootout", f"{DOMAIN}/{name}"), LISTED), (("list", name), NOT_FOUND)]
    retired = ScriptedLaunchctl(
        [
            *observed(),
            *retirements,
            (("list",), listing(user, mixed)),
            printed(user, installation_of(home), home),
            (("print", f"{DOMAIN}/{mixed}"), launchd.Invocation(0, observed()[2][1].stdout)),
        ]
    )
    assert launchd.cleanup(True, roots, UID, retired, no_pause) == 0
    retired.verify()
    assert [line.split("\t")[0] for line in capsys.readouterr().out.splitlines()] == ["retired"] * 3


def test_failed_local_product_removal_retires_its_job_and_keeps_the_root(
    tmp_path: Path, capfd: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path.resolve() / "root"
    executable = root / "home/.local/bin/kast"
    executable.parent.mkdir(parents=True)
    # Forced failure: the product's stop step rejects, so uninstall never runs.
    _ = executable.write_text("#!/bin/sh\nexit 7\n")
    executable.chmod(0o700)
    launcher = root / "bin/kast"
    launcher.parent.mkdir()
    _ = launcher.write_text(local.launcher_source(root))
    launcher.chmod(0o700)
    marker = {"schemaVersion": 1, "root": str(root), "launcher": str(launcher), "version": "0.0.1"}
    _ = (root / local.MARKER).write_text(json.dumps(marker))
    empty_path = tmp_path / "no-tools"
    empty_path.mkdir()
    monkeypatch.setenv("PATH", str(empty_path))
    owned = label("20")
    # Removal selects the caller's own launchd domain.
    caller = f"gui/{os.getuid()}"
    script = ScriptedLaunchctl(
        [
            (("list",), listing(owned)),
            printed(owned, installation_of(root / "home"), root / "home", caller),
            (("bootout", f"{caller}/{owned}"), LISTED),
            (("list", owned), NOT_FOUND),
            (("list",), listing()),
        ]
    )

    assert local.main(["remove", "--root", str(root)], script) == 1

    script.verify()
    assert json.loads(capfd.readouterr().out) == {
        "type": "rejected",
        "stage": "REMOVAL",
        "failure": "COMMAND_FAILED",
    }
    assert (root / local.MARKER).is_file()


def test_local_product_removal_rejects_a_job_that_survives_uninstall(
    tmp_path: Path, capfd: pytest.CaptureFixture[str]
) -> None:
    root = tmp_path.resolve() / "root"
    marker = {"schemaVersion": 1, "root": str(root), "launcher": "", "version": "0.0.1"}
    root.mkdir()
    _ = (root / local.MARKER).write_text(json.dumps(marker))
    owned = label("21")
    caller = f"gui/{os.getuid()}"
    script = ScriptedLaunchctl(
        [
            (("list",), listing(owned)),
            printed(owned, installation_of(root / "home"), root / "home", caller),
            (("bootout", f"{caller}/{owned}"), launchd.Invocation(5, "")),
            (("list", owned), LISTED),
        ]
    )

    assert local.main(["remove", "--root", str(root)], script) == 1

    script.verify()
    assert json.loads(capfd.readouterr().out)["failure"] == "LAUNCHD_JOBS_REMAIN"
    assert (root / local.MARKER).is_file()
