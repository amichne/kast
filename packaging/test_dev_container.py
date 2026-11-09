"""A usable development shell must not silently emulate or share host state."""

import json
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path
from typing import BinaryIO

import pytest

import container_compile
import dev_container
from dev_container import (
    Architecture,
    Rejected,
    container_arguments,
    native_architecture,
    validate_vm,
)


@pytest.mark.parametrize("machine", ["arm64", "aarch64"])
def test_native_arm_host_selects_linux_arm_container(machine: str) -> None:
    assert native_architecture(machine) == Architecture.ARM64


def test_unknown_cpu_cannot_silently_select_amd64() -> None:
    assert isinstance(native_architecture("riscv64"), Rejected)


def test_ambient_git_and_docker_selectors_cannot_redirect_effects() -> None:
    source = {
        "PATH": "/usr/bin",
        "HOME": "/case/home",
        "GIT_DIR": "/other/git",
        "GIT_WORK_TREE": "/other/tree",
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": "core.worktree",
        "GIT_CONFIG_VALUE_0": "/other/tree",
        "DOCKER_HOST": "tcp://other-host",
        "DOCKER_DEFAULT_PLATFORM": "linux/amd64",
    }
    assert dev_container.effect_environment(source) == {"PATH": "/usr/bin", "HOME": "/case/home"}
    assert source["GIT_DIR"] == "/other/git"


def test_dev_defaults_allow_network_without_host_mounts_or_credentials() -> None:
    args = container_arguments("kast-work", "kast-dev:arm64", "repo-key", 4, 8, False)
    assert args[:2] == ["create", "--init"]
    assert "--network=bridge" in args
    assert "--user=10001:10001" in args
    assert "--cap-drop=ALL" in args
    assert "--security-opt=no-new-privileges" in args
    assert "--memory=8g" in args
    assert "--cpus=4" in args
    assert "--pids-limit=2048" in args
    assert not any(
        value.startswith(("--mount", "--volume", "--env", "--publish")) for value in args
    )
    assert args[-3:] == ["kast-dev:arm64", "sleep", "infinity"]


def test_offline_is_an_explicit_option_not_a_default_hindrance() -> None:
    assert "--network=none" in container_arguments("work", "image", "key", 4, 8, True)


def test_vm_rejects_host_sharing_and_forwarded_agent(tmp_path: Path) -> None:
    config = tmp_path / "lima.yaml"
    _ = config.write_text("arch: aarch64\nssh:\n  forwardAgent: false\n")
    assert validate_vm(config, Architecture.ARM64) == Architecture.ARM64
    _ = config.write_text("arch: aarch64\nmounts:\n  - location: /Users\n")
    assert isinstance(validate_vm(config, Architecture.ARM64), Rejected)
    _ = config.write_text("arch: aarch64\nssh:\n  forwardAgent: true\n")
    assert isinstance(validate_vm(config, Architecture.ARM64), Rejected)
    _ = config.write_text("arch: x86_64\nssh:\n  forwardAgent: false\n")
    assert isinstance(validate_vm(config, Architecture.ARM64), Rejected)


def test_copy_preserves_working_files_without_history_ignored_secrets_or_symlink_targets(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    environment = {"PATH": os.defpath, "HOME": str(tmp_path)}
    repository = tmp_path / "repo with spaces"
    repository.mkdir()
    for args in (
        ["init"],
        [
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@invalid",
            "commit",
            "--allow-empty",
            "-m",
            "baseline",
        ],
    ):
        _ = subprocess.run(
            ["git", *args], cwd=repository, env=environment, check=True, capture_output=True
        )
    _ = (repository / "tracked.kt").write_text("before")
    _ = subprocess.run(["git", "add", "tracked.kt"], cwd=repository, env=environment, check=True)
    _ = (repository / "tracked.kt").write_text("dirty contents")
    _ = (repository / ".gitignore").write_text("secret.env\n")
    _ = (repository / "secret.env").write_text("must not copy")
    _ = (repository / "new file;literal.txt").write_text("new contents")
    outside = tmp_path / "outside-secret"
    _ = outside.write_text("must not dereference")
    (repository / "link").symlink_to(outside)
    runtime = dev_container.Runtime(("docker",), Architecture.ARM64, environment)
    selected = dev_container.Workspace(repository, "fixture-key", "fixture-container")
    actual_invoke = dev_container.invoke
    effects: list[list[str]] = []

    def scripted(
        args: list[str],
        env: dict[str, str],
        *,
        capture: bool = False,
        stdin: BinaryIO | None = None,
        stdout: BinaryIO | None = None,
        cwd: Path = dev_container.ROOT,
    ) -> subprocess.CompletedProcess[bytes] | Rejected:
        if args[0] == "git":
            return actual_invoke(args, env, capture=capture, stdin=stdin, stdout=stdout, cwd=cwd)
        effects.append(args)
        if len(effects) == 1:
            assert args == [
                "docker",
                "exec",
                "-i",
                "fixture-container",
                "tar",
                "--no-same-owner",
                "-xf",
                "-",
                "-C",
                "/work",
            ]
            assert stdin is not None
            with tarfile.open(fileobj=stdin) as archive:
                assert set(archive.getnames()) == {
                    ".gitignore",
                    "tracked.kt",
                    "new file;literal.txt",
                    "link",
                }
                assert archive.getmember("link").issym()
                contents = archive.extractfile("tracked.kt")
                assert contents is not None and contents.read() == b"dirty contents"
        else:
            assert args[:3] == ["docker", "exec", "fixture-container"]
        return subprocess.CompletedProcess(args, 0, b"", b"")

    monkeypatch.setattr(dev_container, "invoke", scripted)
    assert dev_container.copy_workspace(runtime, selected) is None
    assert len(effects) == 6
    assert effects[1][3:] == ["git", "init", "--quiet", "--initial-branch=work"]
    assert effects[2][3:] == ["git", "add", "--all", "--force"]
    assert effects[3][-1] == "Copied workspace baseline"
    assert effects[4][3:] == [
        "python3",
        "/opt/kast/packaging/composed_bundle.py",
        "trust",
        "--root",
        "/opt/kast",
        "--workspace",
        "/work",
    ]
    assert effects[5][3:5] == ["python3", "-c"]
    assert (repository / "tracked.kt").read_text() == "dirty contents"
    assert outside.read_text() == "must not dereference"


def test_daemon_failure_is_not_a_missing_workspace(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []

    def failed(
        args: list[str],
        environment: dict[str, str],
        *,
        capture: bool = False,
    ) -> Rejected:
        assert capture
        assert environment == {}
        calls.append(args)
        return Rejected(dev_container.Failure.PROCESS_FAILED, "daemon unavailable")

    monkeypatch.setattr(dev_container, "invoke", failed)
    result = dev_container.open_workspace(
        dev_container.Runtime(("docker",), Architecture.ARM64, {}),
        dev_container.Workspace(tmp_path, "key", "workspace"),
        "image",
        4,
        8,
        False,
    )
    assert result == Rejected(dev_container.Failure.PROCESS_FAILED, "daemon unavailable")
    assert len(calls) == 1
    assert calls[0][1:3] == ["container", "ls"]


def test_first_workspace_creation_cannot_inherit_host_docker_proxy_config(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []
    private_configs: list[Path] = []
    environment = {"DOCKER_CONFIG": str(tmp_path / "host-client-with-credentials")}

    def scripted(
        args: list[str],
        env: dict[str, str],
        *,
        capture: bool = False,
    ) -> subprocess.CompletedProcess[bytes] | Rejected:
        calls.append(args)
        if len(calls) == 1:
            assert capture and env == environment
            return subprocess.CompletedProcess(args, 0, b"", b"")
        assert len(calls) == 2
        assert args[1] == "create"
        config = Path(env["DOCKER_CONFIG"])
        assert config.is_dir() and not tuple(config.iterdir())
        assert env["DOCKER_CONFIG"] != environment["DOCKER_CONFIG"]
        private_configs.append(config)
        return Rejected(dev_container.Failure.PROCESS_FAILED, "scripted creation rejection")

    monkeypatch.setattr(dev_container, "invoke", scripted)
    result = dev_container.open_workspace(
        dev_container.Runtime(("docker",), Architecture.ARM64, environment),
        dev_container.Workspace(tmp_path, "key", "workspace"),
        "image",
        4,
        8,
        False,
    )
    assert result == Rejected(dev_container.Failure.PROCESS_FAILED, "scripted creation rejection")
    assert len(calls) == 2 and len(private_configs) == 1
    assert not private_configs[0].exists()


def test_shell_adapter_preserves_repository_and_literal_arguments(tmp_path: Path) -> None:
    zsh = shutil.which("zsh")
    if zsh is None:
        pytest.skip("Zsh adapter requires the Zsh executable")
    home = tmp_path / "home"
    checkout = tmp_path / "kast checkout"
    repository = tmp_path / "repo with spaces;literal"
    commands = tmp_path / "commands"
    functions = home / "functions"
    for path in (checkout / "bin", repository, commands, functions):
        path.mkdir(parents=True)
    _ = (checkout / "bin/kast-container").write_text("fixture launcher")
    _ = shutil.copyfile(dev_container.ROOT / "packaging/container/kastbox", functions / "kastbox")
    expected = [
        "-C",
        str(checkout),
        "exec",
        "--",
        "uv",
        "run",
        "--locked",
        "--directory",
        str(repository),
        "--script",
        str(checkout / "bin/kast-container"),
        "--",
        "printf",
        "a b",
        ";not-a-shell",
    ]
    stub = commands / "mise"
    _ = stub.write_text(
        f"#!{sys.executable}\nimport sys\nassert sys.argv[1:] == {expected!r}, sys.argv\n"
    )
    stub.chmod(0o755)
    result = subprocess.run(
        [
            zsh,
            "-f",
            "-c",
            'fpath=("$HOME/functions"); autoload -Uz kastbox; '
            'kastbox -- printf "a b" ";not-a-shell"',
        ],
        cwd=repository,
        env={
            "PATH": str(commands) + os.pathsep + os.defpath,
            "HOME": str(home),
            "KAST_CONTAINER_CHECKOUT": str(checkout),
        },
        capture_output=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr.decode()


def test_container_compiler_uses_the_shared_liberica_nik_pin() -> None:
    pins = json.loads((dev_container.ROOT / "packaging/cloud/pins.json").read_text())
    arch, digest = container_compile.NIK["x86_64"]
    assert container_compile.archive_url(arch) == pins["artifacts"]["graalvm"]["url"]
    assert digest == pins["artifacts"]["graalvm"]["sha256"]
    assert container_compile.NIK["aarch64"] == (
        "aarch64",
        "0b022d9fd134677a1cacccb2e26fcd3353f08876232a327148c67c3de096f5c3",
    )


def test_image_assembly_does_not_pregrant_a_workspace() -> None:
    source = (dev_container.ROOT / "packaging/container_install.py").read_text()
    assert "configure_trusted_workspace" not in source
    assert "admit_trust_workspace" not in source
    assert "trust_installed_workspace" not in source
    image = (dev_container.ROOT / "packaging/container/Dockerfile").read_text()
    assert "packaging/composed_bundle.py" in image
    assert "/opt/kast/packaging/" in image


def test_copy_rejects_an_indexed_file_beneath_a_symlinked_directory(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    repository = tmp_path / "repository"
    repository.mkdir()
    environment = {"PATH": os.defpath, "HOME": str(tmp_path)}
    source = repository / "src"
    source.mkdir()
    tracked = source / "config.env"
    _ = tracked.write_text("tracked contents")
    for args in (["init", "--quiet"], ["add", "src/config.env"]):
        _ = subprocess.run(["git", *args], cwd=repository, env=environment, check=True)
    tracked.unlink()
    source.rmdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    secret = outside / "config.env"
    _ = secret.write_text("outside private contents")
    source.symlink_to(outside, target_is_directory=True)
    actual_invoke = dev_container.invoke
    effects: list[list[str]] = []

    def scripted(
        args: list[str],
        env: dict[str, str],
        *,
        capture: bool = False,
        stdin: BinaryIO | None = None,
        stdout: BinaryIO | None = None,
        cwd: Path = dev_container.ROOT,
    ) -> subprocess.CompletedProcess[bytes] | Rejected:
        if args[0] == "git":
            return actual_invoke(args, env, capture=capture, stdin=stdin, stdout=stdout, cwd=cwd)
        effects.append(args)
        return subprocess.CompletedProcess(args, 0, b"", b"")

    monkeypatch.setattr(dev_container, "invoke", scripted)
    result = dev_container.copy_workspace(
        dev_container.Runtime(("docker",), Architecture.ARM64, environment),
        dev_container.Workspace(repository, "fixture-key", "fixture-container"),
    )
    assert isinstance(result, Rejected)
    assert result.reason is dev_container.Failure.INVALID_WORKSPACE
    assert effects == []
    assert secret.read_text() == "outside private contents"
