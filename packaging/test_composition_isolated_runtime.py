"""The real installed launchers use one private profile and exec literal arguments."""

import json
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from composed_bundle import zipapp_bytes
from composition_platform import UnsupportedHost, host_platform
from isolated_runtime import IDENTITY, write_launchers


@pytest.fixture
def installed(tmp_path: Path) -> tuple[Path, Path, Path]:
    target = host_platform(platform.system(), platform.machine())
    assert not isinstance(target, UnsupportedHost)
    root = tmp_path / "root"
    root.mkdir(mode=0o700)
    state = root / "state"
    private_home = state / "home"
    host = root / target.layout.home
    java = host / ("jbr/Contents/Home" if target.layout.launch_os == "macOS" else "jbr")
    control = private_home / ".local/share/kast/installation/config"
    plugins = private_home / "private/profile/plugins"
    for path in (
        state / "tmp",
        state / "system",
        state / "log",
        private_home / ".gradle",
        java,
        plugins,
        control,
        root / "bin",
        root / "libexec",
    ):
        path.mkdir(parents=True)
    _ = (host / "kast-headless.json").write_text(json.dumps({"installation": str(host)}))
    _ = (control / "selected-ide.json").write_text(
        json.dumps(
            {
                "type": "resolved",
                "home": str(host),
                "hostPluginTarget": {"type": "RECORDED", "root": str(plugins)},
            }
        )
    )
    _ = (control / "environment").write_text("fixture")
    public_control = private_home / ".local/bin/kast"
    public_control.parent.mkdir()
    _ = public_control.write_text(
        '#!/bin/sh\nprintf \'%s\\n\' "$HOME" "$KAST_CONFIGURATION_FILE"\n'
    )
    public_control.chmod(0o700)
    zipapp = tmp_path / "fixture.pyz"
    _ = zipapp.write_bytes(zipapp_bytes(Path(__file__).resolve().parent))
    write_launchers(root, target, "0.0.0", zipapp)
    workspace = tmp_path / "project with spaces"
    workspace.mkdir()
    return root, workspace, plugins.parent / "options/trusted-paths.xml"


def run(command: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(command), *arguments],
        env={
            "PATH": "/usr/bin:/bin",
            "HOME": "/unrelated",
            "GRADLE_USER_HOME": "/unrelated/gradle",
            "KAST_CONFIGURATION_FILE": "/unrelated/config",
            "XDG_RUNTIME_DIR": "/unrelated/run",
            "JAVA_HOME": "/unrelated/jdk",
            "IDEA_VM_OPTIONS": "/unrelated/idea",
        },
        capture_output=True,
        text=True,
        check=False,
    )


def test_only_task_grants_exact_workspace_and_execs_the_command(
    installed: tuple[Path, Path, Path],
) -> None:
    root, workspace, trust = installed
    ordinary = run(root / "bin/kast", "--version")
    assert ordinary.returncode == 0, ordinary.stderr
    assert ordinary.stdout.splitlines() == [
        str(root / "state/home"),
        str(root / "state/home/.local/share/kast/installation/config/environment"),
    ]
    assert not trust.exists()

    task = root / "bin/kast-task"
    assert run(task, str(workspace), "/usr/bin/true").returncode == 2
    assert not trust.exists()
    command = (
        "import json, os, sys; "
        "keys = ('HOME', 'GRADLE_USER_HOME', 'XDG_RUNTIME_DIR', 'KAST_CONFIGURATION_FILE', "
        "'IDEA_VM_OPTIONS'); "
        "print(json.dumps([os.getcwd(), sys.argv[1:], [os.getenv(key) for key in keys]])); "
        "raise SystemExit(37)"
    )
    args = ("--flag", "", "literal ; $(false) 'quotes'")
    result = run(task, str(workspace), "--", sys.executable, "-I", "-c", command, *args)
    assert result.returncode == 37, result.stderr
    assert json.loads(result.stdout) == [
        str(workspace),
        list(args),
        [
            str(root / "state/home"),
            str(root / "state/home/.gradle"),
            str(root / "state/tmp"),
            None,
            None,
        ],
    ]
    original = trust.read_bytes()
    assert run(task, str(workspace), "--", "/usr/bin/true").returncode == 0
    other = workspace.parent / "another-project"
    other.mkdir()
    assert run(task, str(other), "--", "/usr/bin/true").returncode == 2
    assert trust.read_bytes() == original


def test_copied_moved_and_invalid_installations_reject_before_trust(
    installed: tuple[Path, Path, Path],
) -> None:
    root, workspace, trust = installed
    copied = root.parent / "copy"
    _ = shutil.copytree(root, copied)
    assert run(copied / "bin/kast", "--version").returncode == 2
    assert run(copied / "bin/kast-task", str(workspace), "--", "/usr/bin/true").returncode == 2
    assert not (copied / trust.relative_to(root)).exists()
    moved = root.parent / "moved"
    _ = root.rename(moved)
    assert run(moved / "bin/kast", "--version").returncode == 2
    _ = moved.rename(root)
    (root / IDENTITY).unlink()
    _ = (root / IDENTITY).write_text('{"schemaVersion":true}')
    assert run(root / "bin/kast-task", str(workspace), "--", "/usr/bin/true").returncode == 2
    assert not trust.exists()
    assert not os.environ.get("HOME", "").startswith(str(root))
