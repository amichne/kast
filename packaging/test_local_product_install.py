import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

import local_product_install as local
from managed_release import VERSION

INHERITED = {
    "PATH": os.environ["PATH"],
    "HOME": "/Users/someone",
    "KAST_INSTALL_ROOT": "/Users/someone/.local/share/kast",
    "KAST_SESSION_ROOT": "/tmp/session",
    "KAST_HOST_IDLE_SHUTDOWN": "5",
    "KAST_WORKSPACE_DEPENDENCY_SHARING": "SHARED",
    "GRADLE_USER_HOME": "/Users/someone/.gradle",
    "XDG_CONFIG_HOME": "/Users/someone/.config",
}


def test_launcher_binds_identity_and_forwards_settings(tmp_path: Path) -> None:
    root = tmp_path / "root"
    target = root / "home/.local/bin/kast"
    target.parent.mkdir(parents=True)
    _ = target.write_text(
        f"#!{sys.executable}\nimport json, os, sys\n"
        "print(json.dumps({'argv': sys.argv[1:], 'env': dict(os.environ)}))\n"
    )
    target.chmod(0o700)
    launcher = tmp_path / "kast"
    _ = launcher.write_text(local.launcher_source(root))

    result = subprocess.run(
        [sys.executable, str(launcher), "container", "create", "--json"],
        env=INHERITED,
        capture_output=True,
        text=True,
        check=True,
    )
    observed = json.loads(result.stdout)
    environment: dict[str, str] = observed["env"]

    assert observed["argv"] == ["container", "create", "--json"]
    home = root / "home"
    assert environment["HOME"] == str(home)
    assert environment["KAST_INSTALL_ROOT"] == str(home / ".local/share/kast")
    assert environment["XDG_CONFIG_HOME"] == str(home / ".config")
    assert "KAST_SESSION_ROOT" not in environment
    assert environment["KAST_HOST_IDLE_SHUTDOWN"] == "5"
    assert environment["KAST_WORKSPACE_DEPENDENCY_SHARING"] == "SHARED"
    assert environment["GRADLE_USER_HOME"] == "/Users/someone/.gradle"
    # Installation and later launches must observe the same environment. The OS may add
    # locale variables to a child process, so compare every variable Kast selects.
    expected = local.installed_environment(root, INHERITED)
    assert {key: environment[key] for key in expected} == expected
    assert {key for key in environment if key.startswith(("KAST_", "XDG_", "HOME"))} == {
        key for key in expected if key.startswith(("KAST_", "XDG_", "HOME"))
    }


def test_new_root_must_be_absent_under_an_owned_directory(tmp_path: Path) -> None:
    assert local.admit_new_root(tmp_path / "fresh") == tmp_path.resolve() / "fresh"
    (tmp_path / "taken").mkdir()
    (tmp_path / "link").symlink_to(tmp_path / "elsewhere")
    for name in ("taken", "link"):
        with pytest.raises(local.Rejected) as rejected:
            _ = local.admit_new_root(tmp_path / name)
        assert rejected.value.failure == local.Failure.ROOT_REJECTED


def test_removal_requires_a_marker_naming_this_root(tmp_path: Path) -> None:
    root = tmp_path.resolve() / "root"
    root.mkdir()
    with pytest.raises(local.Rejected):
        _ = local.admit_owned_root(root)
    marker = {"schemaVersion": 1, "root": str(tmp_path / "other"), "launcher": "", "version": ""}
    _ = (root / local.MARKER).write_text(json.dumps(marker))
    with pytest.raises(local.Rejected):
        _ = local.admit_owned_root(root)
    _ = (root / local.MARKER).write_text(json.dumps({**marker, "root": str(root)}))
    assert local.admit_owned_root(root) == (root, Path(""), "")


def test_discard_tree_removes_read_only_runtime_without_following_links(tmp_path: Path) -> None:
    outside = tmp_path / "outside"
    outside.mkdir()
    _ = (outside / "kept").write_text("kept")
    outside.chmod(0o500)
    contents = tmp_path / "root/runtime/Kast Analysis Host.app/Contents"
    contents.mkdir(parents=True)
    _ = (contents / "Info.plist").write_text("plist")
    (contents / "escape").symlink_to(outside)
    for path in (contents / "Info.plist", contents, contents.parent):
        path.chmod(0o500 if path.is_dir() else 0o400)

    local.discard_tree(tmp_path / "root")

    assert not (tmp_path / "root").exists()
    assert (outside / "kept").read_text() == "kept"
    # Permission repair stops at the link: the outside directory keeps its read-only mode.
    assert outside.stat().st_mode & 0o777 == 0o500
    outside.chmod(0o700)


def test_versions_are_admitted_and_fresh_each_minute() -> None:
    first = local.select_version(local.EPOCH + 60 * 455_000 + 59)
    second = local.select_version(local.EPOCH + 60 * 455_001)
    assert (first, second) == ("0.0.455000", "0.0.455001")
    assert VERSION.fullmatch(first) and VERSION.fullmatch(second)
    with pytest.raises(ValueError):
        _ = local.select_version(local.EPOCH + 59)


def test_removal_stdout_carries_only_its_own_receipt(
    tmp_path: Path, capfd: pytest.CaptureFixture[str]
) -> None:
    root = tmp_path.resolve() / "root"
    executable = root / "home/.local/bin/kast"
    executable.parent.mkdir(parents=True)
    _ = executable.write_text("installed")
    launcher = root / "bin/kast"
    launcher.parent.mkdir()
    # Stands in for `stop --all --json` and `uninstall`, which both report on stdout.
    _ = launcher.write_text(
        'import sys\nprint(\'{"type":"complete","step":"\' + sys.argv[1] + \'"}\')\n'
    )
    marker = {"schemaVersion": 1, "root": str(root), "launcher": str(launcher), "version": "0.0.1"}
    _ = (root / local.MARKER).write_text(json.dumps(marker))

    assert local.main(["remove", "--root", str(root)]) == 0

    captured = capfd.readouterr()
    assert [json.loads(line) for line in captured.out.splitlines()] == [
        {"type": "removed", "root": str(root), "version": "0.0.1"}
    ]
    assert '"step":"stop"' in captured.err and '"step":"uninstall"' in captured.err
    assert not root.exists()
