import hashlib
import json
import os
import subprocess
import sys
import zipfile
from dataclasses import asdict
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
    launcher.chmod(0o700)

    result = subprocess.run(
        [str(launcher), "container", "create", "--json"],
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


def test_launcher_runs_without_ambient_tools_and_preserves_literal_arguments(
    tmp_path: Path,
) -> None:
    root = tmp_path / "root 'quoted' $value; space"
    target = root / "home/.local/bin/kast"
    target.parent.mkdir(parents=True)
    # A real child at the native executable boundary, using only shell builtins.
    _ = target.write_text(
        '#!/bin/sh\nprintf "%s\\000" "$HOME" "$KAST_INSTALL_ROOT" '
        '"${KAST_BIN_DIR-unset}" "${KAST_SESSION_ROOT-unset}" "${VIRTUAL_ENV-unset}" "$@"\n'
        "exit 23\n"
    )
    target.chmod(0o700)
    launcher = tmp_path / "kast"
    _ = launcher.write_text(local.launcher_source(root))
    launcher.chmod(0o700)
    empty_path = tmp_path / "no-tools"
    empty_path.mkdir()
    arguments = ["query", "", "with space", "'quoted'", "$(touch forbidden);*", "line\nbreak"]

    result = subprocess.run(
        [str(launcher), *arguments],
        cwd=tmp_path,
        env={
            **INHERITED,
            "PATH": str(empty_path),
            "KAST_BIN_DIR": "/foreign/bin",
            "VIRTUAL_ENV": "/foreign/venv",
        },
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 23, result.stderr
    assert result.stdout.split("\0") == [
        str(root / "home"),
        str(root / "home/.local/share/kast"),
        "unset",
        "unset",
        "unset",
        *arguments,
        "",
    ]
    assert not (tmp_path / "forbidden").exists()


def test_new_root_must_be_absent_under_an_owned_directory(tmp_path: Path) -> None:
    assert local.admit_new_root(tmp_path / "fresh") == tmp_path.resolve() / "fresh"
    nested = tmp_path / "missing" / "parent" / "fresh"
    assert local.admit_new_root(nested) == nested.resolve()
    assert nested.parent.is_dir()
    assert nested.parent.stat().st_mode & 0o777 == 0o700
    assert not nested.exists()
    (tmp_path / "taken").mkdir()
    (tmp_path / "link").symlink_to(tmp_path / "elsewhere")
    _ = (tmp_path / "file").write_text("protected")
    for name in ("taken", "link", "file/missing/fresh"):
        with pytest.raises(local.Rejected) as rejected:
            _ = local.admit_new_root(tmp_path / name)
        assert rejected.value.failure == local.Failure.ROOT_REJECTED
    assert (tmp_path / "file").read_text() == "protected"


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
    tmp_path: Path, capfd: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path.resolve() / "root"
    executable = root / "home/.local/bin/kast"
    executable.parent.mkdir(parents=True)
    # Stands in for `stop --all --json` and `uninstall`, which both report on stdout.
    _ = executable.write_text('#!/bin/sh\nprintf \'{"type":"complete","step":"%s"}\\n\' "$1"\n')
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

    assert local.main(["remove", "--root", str(root)]) == 0

    captured = capfd.readouterr()
    assert [json.loads(line) for line in captured.out.splitlines()] == [
        {"type": "removed", "root": str(root), "version": "0.0.1"}
    ]
    assert '"step":"stop"' in captured.err and '"step":"uninstall"' in captured.err
    assert not root.exists()


def test_export_requires_native_evidence_and_records_only_the_built_platform(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        sys,
        "path",
        [str(Path(__file__).resolve().parents[2] / ".github/scripts/release"), *sys.path],
    )
    from product_producer import RESOLUTION_INPUTS
    from test_product_candidate import fixture
    from test_product_producer import AssemblyReceipt

    import managed_release as release

    original = tmp_path / "original"
    recorded = fixture(original)
    native = recorded.targets[1]
    payload = original / "payload"
    bundle, runtime = payload / native.bundle.name, payload / native.runtime.name
    with zipfile.ZipFile(runtime, "a") as archive:
        archive.writestr("host/Kast.app/Contents/jbr/Contents/Home/release", b"JAVA_VERSION=25\n")
    (payload / native.qualification.receipt.name).unlink()
    work = tmp_path / "work"
    (work / "qualification").mkdir(parents=True)
    (work / "logs").mkdir()
    qualification = work / "qualification/qualification.json"
    control = work / "qualification-root/components/control/share/kast/libexec/kast-management"
    control.parent.mkdir(parents=True)
    _ = control.write_bytes(b"produced Control\n")
    checkout = tmp_path / "source"
    for name in (*RESOLUTION_INPUTS, "packaging/internal/composition/composition_recipe.py"):
        path = checkout / name
        path.parent.mkdir(parents=True, exist_ok=True)
        _ = path.write_bytes(b"declared source input\n")
    built = local.BuiltProduct(
        local.SourceIdentity(str(checkout), "a" * 40, "b" * 40),
        "0.0.123",
        work,
        bundle,
        runtime,
        qualification,
        tmp_path / "unused-python",
        tmp_path / "unused-dependency",
    )
    destination = tmp_path / "local-product"
    for outcome in ("NOT_RUN", "COMPLETE"):
        _ = qualification.write_text(
            json.dumps(
                asdict(
                    AssemblyReceipt(
                        "a" * 40,
                        hashlib.sha256(bundle.read_bytes()).hexdigest(),
                        hashlib.sha256(runtime.read_bytes()).hexdigest(),
                        hashlib.sha256(control.read_bytes()).hexdigest(),
                        qualification=outcome,
                    )
                )
            )
        )
        if outcome == "NOT_RUN":
            with pytest.raises(local.Rejected) as rejected:
                _ = local.export_product(built, destination)
            assert rejected.value.stage == local.Stage.QUALIFICATION
            assert not destination.exists()
        else:
            result = local.export_product(built, destination)
            assert result.releaseQualification == "NOT_ESTABLISHED"
    raw = (destination / "build-record.json").read_bytes()
    record = release.decode_installed_product_record(raw)
    assert isinstance(record, release.LocalProductRecord)
    assert record.producer == release.LocalProductProducer()
    assert [target.target for target in record.targets] == ["macos-aarch64"]
    assert record.targets[0].qualification.producer == release.LocalProductProducer()
    assert {path.name for path in (destination / "payload").iterdir()} == {
        native.bundle.name,
        native.runtime.name,
        native.qualification.receipt.name,
    }
    assert release.decode_product_record(raw) == release.Failure.ARTIFACT_INVALID
