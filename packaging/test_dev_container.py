"""Source image builds keep native inputs and report exact process failures."""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

import container_compile
import dev_container
from dev_container import (
    Architecture,
    Rejected,
    native_architecture,
    validate_vm,
)


@pytest.mark.parametrize(
    "arguments", [["run"], ["run", "--"], ["unknown"], ["shell", "--name", "../other"]]
)
def test_invalid_command_rejects_before_runtime_effects(
    arguments: list[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    def forbidden_runtime() -> dev_container.Runtime:
        pytest.fail("Invalid command reached the VM owner")

    monkeypatch.setattr(dev_container, "runtime", forbidden_runtime)
    monkeypatch.setattr(sys, "argv", ["kast-container", *arguments])
    with pytest.raises(SystemExit) as rejected:
        _ = dev_container.main()
    assert rejected.value.code == 2


@pytest.mark.parametrize("arguments", [[], ["shell"], ["status"], ["run", "--", "true"]])
def test_source_builder_rejects_task_commands_before_runtime_effects(
    arguments: list[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    def forbidden_runtime() -> dev_container.Runtime:
        pytest.fail("Retired task command reached the source-build VM")

    monkeypatch.setattr(dev_container, "runtime", forbidden_runtime)
    monkeypatch.setattr(sys, "argv", ["kast-container", *arguments])
    with pytest.raises(SystemExit) as rejected:
        _ = dev_container.main()
    assert rejected.value.code == 2


def test_source_help_needs_no_runtime_or_python_dependencies(tmp_path: Path) -> None:
    source = Path(__file__).resolve().parent.parent
    for relative in ("bin/kast-container", "bin/kast-container.lock", "packaging/dev_container.py"):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        _ = shutil.copyfile(source / relative, target)
    for arguments in (["--help"], ["build", "--help"]):
        result = subprocess.run(
            [sys.executable, "-I", str(tmp_path / "bin/kast-container"), *arguments],
            cwd=tmp_path.parent,
            env={"PATH": os.defpath, "HOME": str(tmp_path)},
            capture_output=True,
            text=True,
            check=False,
        )
        assert result.returncode == 0, result.stderr
        assert "kast-container" in result.stdout
    assert not list(tmp_path.rglob("__pycache__"))


def test_builder_rejects_missing_source_before_child_effects(
    tmp_path: Path,
) -> None:
    runtime = dev_container.Runtime(("must-not-run",), Architecture.ARM64, {})
    assert dev_container.build_image(runtime, tmp_path) == dev_container.Rejected(
        dev_container.Failure.INVALID_CHECKOUT, "Use kast-container build --checkout /path/to/kast"
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


def test_shell_adapter_preserves_repository_and_literal_arguments(tmp_path: Path) -> None:
    zsh = shutil.which("zsh")
    if zsh is None:
        pytest.skip("Zsh adapter requires the Zsh executable")
    home = tmp_path / "home"
    repository = tmp_path / "repo with spaces;literal"
    commands = tmp_path / "commands"
    functions = home / "functions"
    for path in (repository, commands, functions):
        path.mkdir(parents=True)
    _ = shutil.copyfile(dev_container.ROOT / "packaging/container/kastbox", functions / "kastbox")
    expected = [
        "container",
        "run",
        "--",
        "printf",
        "a b",
        ";not-a-shell",
    ]
    stub = commands / "kast"
    _ = stub.write_text(
        f"#!{sys.executable}\nimport os, sys\nassert sys.argv[1:] == {expected!r}, sys.argv\n"
        f"assert os.getcwd() == {str(repository)!r}\nraise SystemExit(23)\n"
    )
    stub.chmod(0o755)
    result = subprocess.run(
        [
            zsh,
            "-f",
            "-c",
            'fpath=("$HOME/functions"); autoload -Uz kastbox; '
            'kastbox run -- printf "a b" ";not-a-shell"',
        ],
        cwd=repository,
        env={
            "PATH": str(commands) + os.pathsep + os.defpath,
            "HOME": str(home),
        },
        capture_output=True,
        check=False,
    )
    assert result.returncode == 23, result.stderr.decode()


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


def test_source_build_uses_selected_checkout_and_preserves_gradle_failure(tmp_path: Path) -> None:
    checkout = tmp_path / "selected source"
    dockerfile = checkout / "packaging/container/Dockerfile"
    dockerfile.parent.mkdir(parents=True)
    _ = dockerfile.write_text("fixture input\n")
    gradle = checkout / "gradlew"
    _ = gradle.write_text("#!/bin/sh\npwd > selected-cwd\nexit 17\n")
    gradle.chmod(0o755)
    runtime = dev_container.Runtime(("must-not-run-docker",), Architecture.ARM64, {})
    result = dev_container.build_image(runtime, checkout)
    assert result == Rejected(dev_container.Failure.PROCESS_FAILED, "gradlew exited 17")
    assert (checkout / "selected-cwd").read_text().strip() == str(checkout.resolve())
