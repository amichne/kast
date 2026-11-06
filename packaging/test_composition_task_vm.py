"""The coordinator owns admission, effect order, copied inputs, and VM retirement."""

from __future__ import annotations

import io
import json
import os
import subprocess
import sys
import tarfile
from dataclasses import asdict, replace
from pathlib import Path
from typing import BinaryIO

import pytest

from composed_bundle import BundleManifest, BundleRejected, read_manifest, sha256
from composition_platform import HostPlatform
from task_vm import (
    Completed,
    Failure,
    HostFacts,
    Rejected,
    Request,
    Stage,
    admit,
    bundle_manifest,
    call,
    environment,
    launch_arguments,
    prepare_store,
    run,
)
from task_vm_guest import task_environment
from test_composition_bundle import manifest_bytes

HOST = HostFacts("Darwin", "arm64", 60 * 1024**3, 8)
IMAGE = "ghcr.io/example/macos-base@sha256:" + "a" * 64


def mac_manifest() -> BundleManifest:
    result = read_manifest(
        manifest_bytes()
        .replace(b"linux-x86_64", b"macos-aarch64")
        .replace(b"runtime/product-info.json", b"runtime/Resources/product-info.json")
    )
    assert not isinstance(result, BundleRejected)
    return result


def request(tmp_path: Path) -> Request:
    return Request(
        IMAGE,
        tmp_path / "bundle.tar.gz",
        "b" * 64,
        "0.0.0",
        tmp_path / "repo",
        "c" * 40,
        tmp_path / "output",
        tmp_path / "store",
        Path(sys.executable),
        "/opt/homebrew/bin/python3.13",
        ("printf", "%s", "a b;$(touch unexpected)"),
    )


def test_admission_keeps_the_macos_bundle_identity_and_limits(tmp_path: Path) -> None:
    result = admit(request(tmp_path), HOST, mac_manifest())
    assert not isinstance(result, Rejected)
    assert result.request.image == IMAGE
    assert result.request.command == ("printf", "%s", "a b;$(touch unexpected)")


@pytest.mark.parametrize(
    "changes",
    [
        {"image": "ghcr.io/example/macos-base:latest"},
        {"revision": "HEAD"},
        {"command": ()},
        {"cpus": 0},
        {"memory_mib": 1024},
        {"boot_seconds": 0},
        {"task_seconds": 86401},
        {"guest_python": "python3"},
        {"command": ("bad\x00",)},
    ],
)
def test_invalid_requests_reject_before_vm_effects(
    tmp_path: Path, changes: dict[str, object]
) -> None:
    # The malformed variants are deliberate external-input counterexamples.
    original = request(tmp_path)
    values = asdict(original) | changes
    result = admit(Request(**values), HOST, mac_manifest())
    assert result == Rejected(Stage.ADMISSION, Failure.INVALID_INPUT)
    assert not original.output.exists()


def test_platform_and_space_reject_without_output(tmp_path: Path) -> None:
    original = request(tmp_path)
    assert admit(original, replace(HOST, system="Linux"), mac_manifest()) == Rejected(
        Stage.ADMISSION, Failure.UNSUPPORTED_HOST
    )
    assert admit(original, replace(HOST, free_bytes=20 * 1024**3), mac_manifest()) == Rejected(
        Stage.ADMISSION, Failure.DISK_PRESSURE
    )
    assert admit(
        original, HOST, replace(mac_manifest(), platform=HostPlatform.LINUX_X86_64)
    ) == Rejected(Stage.ADMISSION, Failure.WRONG_BUNDLE)
    assert not original.output.exists()


def test_bundle_digest_rejects_before_tar_parsing(tmp_path: Path) -> None:
    archive = tmp_path / "bundle"
    _ = archive.write_bytes(b"not a tar")
    assert bundle_manifest(archive, "0" * 64) == Rejected(Stage.ADMISSION, Failure.WRONG_BUNDLE)


def test_launch_has_only_a_disconnected_socket_and_no_host_shares() -> None:
    assert launch_arguments("kt-example", 9) == (
        "run",
        "--no-graphics",
        "--no-audio",
        "--no-clipboard",
        "--no-usb-accessories",
        "--net-fd=9",
        "kt-example",
    )
    env = environment(Path("/owned/home"), Path("/owned/store"))
    assert env == {
        "PATH": os.defpath,
        "HOME": "/owned/home",
        "TART_HOME": "/owned/store",
        "TART_NO_AUTO_PRUNE": "1",
        "LC_ALL": "C",
        "TMPDIR": "/owned/home",
    }
    assert "SSH_AUTH_SOCK" not in env and "GITHUB_TOKEN" not in env


def test_task_environment_selects_only_private_home_host_and_jbr() -> None:
    env = task_environment(Path("/private/tmp/kt"))
    assert env["HOME"] == "/private/tmp/kt/home"
    assert (
        env["JAVA_HOME"]
        == "/private/tmp/kt/install/runtime/Kast Analysis Host.app/Contents/jbr/Contents/Home"
    )
    assert env["PATH"].startswith("/private/tmp/kt/install/bin:")
    assert "SSH_AUTH_SOCK" not in env and "TART_HOME" not in env


def test_store_does_not_adopt_an_existing_unowned_directory(tmp_path: Path) -> None:
    # Short roots also exercise Tart's socket-path limit.
    import tempfile

    with tempfile.TemporaryDirectory(prefix="kv-") as name:
        store = Path(name).resolve()
        assert prepare_store(store) == Rejected(Stage.ADMISSION, Failure.INVALID_INPUT)
        assert not (store / ".kast-task-vm-store").exists()


def test_real_process_adapter_preserves_literal_arguments_and_exit(tmp_path: Path) -> None:
    log = tmp_path / "child.log"
    with log.open("wb") as output:
        result = call(
            (
                sys.executable,
                "-c",
                "import sys;print(sys.argv[1]);sys.exit(23)",
                "a b;$(touch nope)",
            ),
            {"PATH": os.defpath},
            Stage.TASK,
            output,
        )
    assert result == Rejected(Stage.TASK, Failure.EFFECT_FAILED, 23)
    assert log.read_text() == "a b;$(touch nope)\n"
    assert not (tmp_path / "nope").exists()


def test_real_process_adapter_reports_missing_executable(tmp_path: Path) -> None:
    with (tmp_path / "child.log").open("wb") as output:
        assert call((str(tmp_path / "absent"),), {}, Stage.TASK, output) == Rejected(
            Stage.TASK, Failure.TOOL_UNAVAILABLE, 127
        )


def scripted_tart(tmp_path: Path, failures: dict[str, int]) -> Path:
    """An explicit external process fixture returns only scripted exit codes."""
    program = tmp_path / "scripted-tart"
    _ = program.write_text(f"""#!{sys.executable}
import json, pathlib, signal, socket, sys
args = sys.argv[1:]
log = pathlib.Path({str(tmp_path / "calls.jsonl")!r})
with log.open('a') as output:
    output.write(json.dumps(args) + '\\n')
failures = {failures!r}
if args[0] == 'run':
    fd = int(next(arg.split('=')[1] for arg in args if arg.startswith('--net-fd=')))
    channel = socket.socket(fileno=fd)
    assert channel.family == socket.AF_UNIX and channel.type == socket.SOCK_DGRAM
    pathlib.Path({str(tmp_path / "vm.pid")!r}).write_text(str(__import__('os').getpid()))
    signal.pause()
elif args[0] == 'exec':
    if '-i' in args:
        pathlib.Path({str(tmp_path / "received.tar")!r}).write_bytes(sys.stdin.buffer.read())
        stage = 'transfer'
    elif 'install' in args:
        stage = 'install'
    elif 'task' in args:
        stage = 'task'
    elif '/usr/bin/tar' in args:
        sys.stdout.buffer.write(b'workspace archive fixture')
        sys.stderr.write('export diagnostic\\n')
        stage = 'export'
    else:
        stage = 'boot'
    sys.exit(failures.get(stage, 0))
else:
    sys.exit(failures.get(args[0], 0))
""")
    program.chmod(0o700)
    return program


def task_case(tmp_path: Path, failures: dict[str, int]) -> Request:
    original = request(tmp_path)
    original.repository.mkdir()
    _ = (original.repository / "input.txt").write_text("committed bytes\n")
    for command in (
        ("git", "init", "-q", str(original.repository)),
        ("git", "-C", str(original.repository), "add", "."),
        (
            "git",
            "-C",
            str(original.repository),
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@localhost",
            "-c",
            "commit.gpgsign=false",
            "-c",
            "core.hooksPath=/dev/null",
            "commit",
            "-qm",
            "fixture",
        ),
    ):
        _ = subprocess.run(command, check=True)
    revision = subprocess.check_output(
        ("git", "-C", str(original.repository), "rev-parse", "HEAD"), text=True
    ).strip()
    _ = (original.repository / "ignored-secret").write_text("untracked sentinel")
    _ = original.bundle.write_bytes(b"fixture bundle transport bytes")
    original.output.mkdir()
    return replace(
        original,
        revision=revision,
        tart=scripted_tart(tmp_path, failures),
        bundle_sha256=sha256(original.bundle),
    )


@pytest.mark.parametrize(
    "failed_stage,code",
    [("task", 23), ("install", 19), ("set", 17), ("clone", 13), ("export", 11), ("", 0)],
)
def test_real_children_keep_order_copy_inputs_and_retire_only_the_owned_vm(
    tmp_path: Path,
    failed_stage: str,
    code: int,
) -> None:
    original = task_case(tmp_path, {failed_stage: code} if failed_stage else {})
    admitted = admit(original, HOST, mac_manifest())
    assert not isinstance(admitted, Rejected)
    result = run(admitted)
    if failed_stage:
        assert isinstance(result.result, Rejected)
        assert result.result.exitCode == code
    else:
        assert result.result == Completed(0)
    assert all(isinstance(event.result, Completed) for event in result.cleanup)
    calls: list[list[str]] = [
        json.loads(line) for line in (tmp_path / "calls.jsonl").read_text().splitlines()
    ]
    clones = [args for args in calls if args[0] == "clone"]
    assert clones == [["clone", IMAGE, result.taskName]]
    deletions = [args for args in calls if args[0] == "delete"]
    assert deletions == ([] if failed_stage == "clone" else [["delete", result.taskName]])
    stages = [event.stage for event in result.events]
    if failed_stage == "clone":
        assert stages == [Stage.CLONE]
    elif failed_stage == "set":
        assert stages == [Stage.CLONE, Stage.CONFIGURE, Stage.DELETE]
    else:
        assert stages[:4] == [Stage.CLONE, Stage.CONFIGURE, Stage.BOOT, Stage.TRANSFER]
        assert Stage.STOP in stages and Stage.DELETE in stages
        with tarfile.open(tmp_path / "received.tar") as archive:
            assert set(archive.getnames()) == {
                "source.tar",
                "bundle.tar.gz",
                "task_vm_guest.py",
                "composed_bundle.py",
                "composition_platform.py",
                "managed_headless.py",
            }
            stream = archive.extractfile("source.tar")
            assert stream is not None
            with tarfile.open(fileobj=io.BytesIO(stream.read())) as source:
                assert source.getnames() == ["input.txt"]
        if failed_stage != "install":
            assert stages.index(Stage.TASK) < stages.index(Stage.EXPORT) < stages.index(Stage.STOP)
            assert (
                original.output / "workspace.tar.gz"
            ).read_bytes() == b"workspace archive fixture"
            assert (original.output / "export.log").read_text() == "export diagnostic\n"
        else:
            assert Stage.TASK not in stages and Stage.EXPORT not in stages
    assert (original.repository / "ignored-secret").read_text() == "untracked sentinel"
    assert result.snapshotSha256 == sha256(original.output / "source.tar")
    assert (
        json.loads((original.output / "receipt.json").read_text())["nativeAcceptance"]
        == "NOT_ASSERTED"
    )


@pytest.mark.parametrize("reason", [Failure.TIMED_OUT, Failure.CANCELLED])
def test_timeout_or_cancellation_stops_vm_before_further_task_effects(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, reason: Failure
) -> None:
    import task_vm

    original = task_case(tmp_path, {})
    admitted = admit(original, HOST, mac_manifest())
    assert not isinstance(admitted, Rejected)
    real_call = task_vm.call

    def interrupted_call(
        command: tuple[str, ...],
        env: dict[str, str],
        stage: Stage,
        output: BinaryIO,
        *,
        timeout: int = 180,
        source: BinaryIO | None = None,
        errors: BinaryIO | None = None,
    ) -> Completed | Rejected:
        if stage == Stage.TASK:
            if reason == Failure.CANCELLED:
                raise KeyboardInterrupt
            return Rejected(stage, reason, 124)
        return real_call(command, env, stage, output, timeout=timeout, source=source, errors=errors)

    monkeypatch.setattr(task_vm, "call", interrupted_call)
    result = run(admitted)
    assert result.result == Rejected(
        Stage.TASK, reason, 124 if reason == Failure.TIMED_OUT else 130
    )
    assert Stage.EXPORT not in {event.stage for event in result.events}
    assert [event.stage for event in result.cleanup] == [Stage.STOP, Stage.DELETE]
    assert all(isinstance(event.result, Completed) for event in result.cleanup)


def test_cleanup_failure_preserves_the_successful_task_and_its_failed_retirement(
    tmp_path: Path,
) -> None:
    original = task_case(tmp_path, {"delete": 31})
    admitted = admit(original, HOST, mac_manifest())
    assert not isinstance(admitted, Rejected)
    result = run(admitted)
    assert result.result == Completed(0)
    assert result.cleanup[-1].result == Rejected(Stage.DELETE, Failure.EFFECT_FAILED, 31)


def test_guest_task_uses_real_child_literal_arguments_and_private_environment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capfd: pytest.CaptureFixture[str]
) -> None:
    from task_vm_guest import run_task

    (tmp_path / "repository").mkdir()
    (tmp_path / "install/bin").mkdir(parents=True)
    _ = (tmp_path / "install/bin/kast").write_text("fixture installer result")
    monkeypatch.setenv("KAST_TASK_PRIVATE_SENTINEL", "host-only")
    command = (
        sys.executable,
        "-c",
        "import os,sys; assert 'KAST_TASK_PRIVATE_SENTINEL' not in os.environ; "
        "assert os.environ['HOME'] == sys.argv[1]; print(sys.argv[2]); sys.exit(29)",
        str(tmp_path / "home"),
        "a b;$(touch forbidden)",
    )
    assert run_task(tmp_path, command) == 29
    assert capfd.readouterr().out == "a b;$(touch forbidden)\n"
    assert not (tmp_path / "repository/forbidden").exists()


def test_stop_log_failure_cannot_skip_retirement(tmp_path: Path) -> None:
    import signal

    original = task_case(tmp_path, {})
    (original.output / "stop.log").mkdir()
    admitted = admit(original, HOST, mac_manifest())
    assert not isinstance(admitted, Rejected)
    try:
        result = run(admitted)
        assert result.result == Completed(0)
        assert result.cleanup[0].result == Rejected(Stage.STOP, Failure.EFFECT_FAILED)
        assert result.cleanup[-1].stage == Stage.DELETE
        assert result.cleanup[-1].result == Completed(0)
    finally:
        marker = tmp_path / "vm.pid"
        if marker.exists():
            try:
                os.kill(int(marker.read_text()), signal.SIGTERM)
            except ProcessLookupError:
                pass
