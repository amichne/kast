"""Check builder isolation with tiny archives, never an installed SDK or compiler."""

from __future__ import annotations

import hashlib
import importlib.util
import os
import subprocess
import sys
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass
from importlib.machinery import SourceFileLoader
from pathlib import Path
from typing import Protocol, TextIO, cast

import pytest


class ArchiveReceipt(Protocol):
    name: str
    original_sha256: str
    patched_sha256: str
    entries: tuple[str, ...]


class Prototype(Protocol):
    def build(self, home: Path, output: Path, workspace_jar: Path) -> object: ...

    def replace_archive(
        self, source: Path, target: Path, changes: dict[str, Path]
    ) -> ArchiveReceipt: ...


def load_prototype() -> Prototype:
    path = Path(__file__).with_name("prototype")
    loader = SourceFileLoader("psi_provenance_prototype", str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[loader.name] = module
    loader.exec_module(module)
    return cast(Prototype, module)


prototype = load_prototype()
CLASS = "com/intellij/psi/impl/Existing.class"


@dataclass(frozen=True)
class Inputs:
    home: Path
    workspace: Path


@pytest.fixture
def inputs(tmp_path: Path) -> Inputs:
    home = tmp_path / "SDK home ;$literal"
    (home / "lib").mkdir(parents=True)
    with zipfile.ZipFile(home / "lib" / "platform.jar", "w") as archive:
        archive.writestr(CLASS, b"original platform")
        archive.writestr("unrelated.txt", b"keep platform resource")
    workspace = tmp_path / "workspace ;$literal.jar"
    with zipfile.ZipFile(workspace, "w") as archive:
        archive.writestr("Adapter.class", b"original adapter")
    return Inputs(home, workspace)


@pytest.fixture
def forbid_process(monkeypatch: pytest.MonkeyPatch) -> None:
    def unexpected(*args: object, **kwargs: object) -> None:
        pytest.fail(f"rejected build invoked a process: {args!r}, {kwargs!r}")

    monkeypatch.setattr(subprocess, "run", unexpected)


@pytest.fixture
def compiler(
    inputs: Inputs,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    request: pytest.FixtureRequest,
) -> Iterator[None]:
    output = tmp_path / "output ;$literal"
    sources = Path(__file__).parent
    classpath = str(inputs.home / "lib" / "platform.jar")
    runtime_classpath = os.pathsep.join((str(output / "patcher"), classpath))
    expected = [
        [
            "javac",
            "-cp",
            classpath,
            "-d",
            str(output / "patcher"),
            str(sources / "ProvenancePatch.java"),
        ],
        [
            "java",
            "-cp",
            runtime_classpath,
            "ProvenancePatch",
            str(inputs.home),
            str(output / "classes"),
        ],
        [
            "javac",
            "--release",
            "21",
            "-cp",
            os.pathsep.join((str(output / "classes"), classpath)),
            "-d",
            str(output / "classes"),
            str(sources / "PsiContentProvenance.java"),
        ],
        [
            "java",
            "-cp",
            runtime_classpath,
            "ProvenancePatch",
            "--adapter",
            str(inputs.workspace),
            str(output / "adapter"),
        ],
    ]
    calls: list[list[str]] = []

    def run(
        command: list[str], *, check: bool, stdout: TextIO | None = None
    ) -> subprocess.CompletedProcess[bytes]:
        index = len(calls)
        assert index < len(expected), "unexpected extra compiler call"
        assert command == expected[index]
        assert check is True
        assert (stdout is not None) == (index == 1)
        calls.append(command)
        if index in (1, 3):
            target = output / ("classes" if index == 1 else "adapter")
            target /= CLASS if index == 1 else "Adapter.class"
            target.parent.mkdir(parents=True)
            target.write_bytes(b"patched fixture bytes")
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(subprocess, "run", run)
    yield
    expected_count = cast(int, request.param)
    assert calls == expected[:expected_count], "unconsumed compiler expectations"


@pytest.mark.usefixtures("forbid_process")
@pytest.mark.parametrize("location", ["same", "inside", "ancestor"])
def test_overlapping_output_is_rejected_without_effects(inputs: Inputs, location: str) -> None:
    outputs = {
        "same": inputs.home,
        "inside": inputs.home / "output",
        "ancestor": inputs.home.parent,
    }
    before = (inputs.home / "lib" / "platform.jar").read_bytes()
    with pytest.raises(ValueError, match="disjoint"):
        prototype.build(inputs.home, outputs[location], inputs.workspace)
    assert (inputs.home / "lib" / "platform.jar").read_bytes() == before
    assert not (inputs.home / "output").exists()
    assert not (outputs[location] / "LOCAL_PROVENANCE_PROTOTYPE_V1").exists()


@pytest.mark.usefixtures("forbid_process")
@pytest.mark.parametrize("kind", ["directory", "file", "symlink", "dangling-symlink"])
def test_existing_output_is_rejected_without_effects(
    inputs: Inputs, tmp_path: Path, kind: str
) -> None:
    output = tmp_path / "existing"
    destination = tmp_path / "destination"
    if kind == "directory":
        output.mkdir()
        (output / "sentinel").write_bytes(b"keep directory")
    elif kind == "file":
        output.write_bytes(b"keep file")
    else:
        if kind == "symlink":
            destination.mkdir()
            (destination / "sentinel").write_bytes(b"keep symlink destination")
        output.symlink_to(destination, target_is_directory=True)
    with pytest.raises(FileExistsError):
        prototype.build(inputs.home, output, inputs.workspace)
    if kind == "directory":
        assert sorted(p.name for p in output.iterdir()) == ["sentinel"]
        assert (output / "sentinel").read_bytes() == b"keep directory"
    elif kind == "file":
        assert output.read_bytes() == b"keep file"
    else:
        assert output.is_symlink()
        assert output.readlink() == destination
        if kind == "symlink":
            assert sorted(p.name for p in destination.iterdir()) == ["sentinel"]
            assert (destination / "sentinel").read_bytes() == b"keep symlink destination"
        else:
            assert not destination.exists()


@pytest.mark.usefixtures("forbid_process")
@pytest.mark.parametrize("alias", ["home", "output-parent"])
def test_symlink_alias_cannot_bypass_disjoint_check(
    inputs: Inputs, tmp_path: Path, alias: str
) -> None:
    link = tmp_path / "alias"
    link.symlink_to(inputs.home, target_is_directory=True)
    home = link if alias == "home" else inputs.home
    output = inputs.home / "output" if alias == "home" else link / "output"
    with pytest.raises(ValueError, match="disjoint"):
        prototype.build(home, output, inputs.workspace)
    assert not (inputs.home / "output").exists()


def test_archive_preserves_unrelated_members_metadata_and_comment(tmp_path: Path) -> None:
    source = tmp_path / "source.jar"
    original = zipfile.ZipInfo("keep/data.bin", (2020, 2, 3, 4, 5, 6))
    original.compress_type = zipfile.ZIP_DEFLATED
    original.comment = b"member comment"
    original.extra = b"\xfe\xca\x02\x00hi"
    original.create_system = 3
    original.external_attr = 0o100640 << 16
    original.internal_attr = 1
    replacement = zipfile.ZipInfo(CLASS, (2021, 3, 4, 5, 6, 8))
    replacement.compress_type = zipfile.ZIP_DEFLATED
    with zipfile.ZipFile(source, "w") as archive:
        archive.comment = b"archive comment"
        archive.writestr(original, b"unrelated\x00binary\xffpayload" * 20)
        archive.writestr(replacement, b"old class")
    before = source.read_bytes()
    payload = tmp_path / "patch.class"
    payload.write_bytes(b"new class")
    target = tmp_path / "patched.jar"
    receipt = prototype.replace_archive(
        source, target, {CLASS: payload, "z/New.class": payload, "a/New.class": payload}
    )
    assert source.read_bytes() == before
    with zipfile.ZipFile(target) as archive:
        assert archive.namelist() == ["keep/data.bin", CLASS, "a/New.class", "z/New.class"]
        assert archive.read(original.filename) == b"unrelated\x00binary\xffpayload" * 20
        saved = archive.getinfo(original.filename)
        assert saved.date_time == (2020, 2, 3, 4, 5, 6)
        assert saved.compress_type == zipfile.ZIP_DEFLATED
        assert saved.comment == b"member comment"
        assert saved.extra == b"\xfe\xca\x02\x00hi"
        assert saved.create_system == 3
        assert saved.external_attr == 0o100640 << 16
        assert saved.internal_attr == 1
        assert archive.getinfo(CLASS).date_time == (2021, 3, 4, 5, 6, 8)
        assert archive.getinfo(CLASS).compress_type == zipfile.ZIP_DEFLATED
        assert all(
            archive.read(name) == b"new class" for name in (CLASS, "a/New.class", "z/New.class")
        )
        assert archive.comment == b"archive comment"
    assert receipt.name == "source.jar"
    assert receipt.entries == ("a/New.class", CLASS, "z/New.class")
    assert receipt.original_sha256 == hashlib.sha256(before).hexdigest()
    assert receipt.patched_sha256 == hashlib.sha256(target.read_bytes()).hexdigest()


def test_duplicate_archive_rejection_does_not_create_output(tmp_path: Path) -> None:
    source = tmp_path / "duplicate.jar"
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr(CLASS, b"first")
        with pytest.warns(UserWarning, match="Duplicate name"):
            archive.writestr(CLASS, b"second")
    before = source.read_bytes()
    target = tmp_path / "patched.jar"
    with pytest.raises(ValueError, match="duplicate archive entries"):
        prototype.replace_archive(source, target, {})
    assert source.read_bytes() == before
    assert not target.exists()


@pytest.mark.parametrize("kind", ["same", "hardlink", "symlink", "unrelated"])
def test_existing_archive_target_is_never_truncated(
    inputs: Inputs, tmp_path: Path, kind: str
) -> None:
    source = inputs.home / "lib" / "platform.jar"
    target = tmp_path / "existing.jar"
    if kind == "same":
        target = source
    elif kind == "hardlink":
        target.hardlink_to(source)
    elif kind == "symlink":
        target.symlink_to(source)
    else:
        target.write_bytes(b"keep existing target")
    source_before, target_before = source.read_bytes(), target.read_bytes()
    try:
        with pytest.raises(FileExistsError):
            prototype.replace_archive(source, target, {})
    finally:
        assert source.read_bytes() == source_before
        assert target.read_bytes() == target_before


def test_archive_rejects_symlinked_write_parent(inputs: Inputs, tmp_path: Path) -> None:
    link = tmp_path / "linked-lib"
    link.symlink_to(inputs.home / "lib", target_is_directory=True)
    target = link / "patched.jar"
    with pytest.raises(ValueError, match="symlink"):
        prototype.replace_archive(inputs.workspace, target, {})
    assert not (inputs.home / "lib" / "patched.jar").exists()


@pytest.mark.usefixtures("compiler")
@pytest.mark.parametrize("compiler", [0], indirect=True)
def test_copied_symlinked_lib_cannot_change_input_bytes_or_mode(
    inputs: Inputs, tmp_path: Path
) -> None:
    lib = inputs.home / "lib"
    real_lib = inputs.home / "real-lib"
    lib.rename(real_lib)
    lib.symlink_to(real_lib, target_is_directory=True)
    real_lib.chmod(0o555)
    before = (real_lib / "platform.jar").read_bytes()
    mode = real_lib.stat().st_mode
    output = tmp_path / "output ;$literal"
    try:
        with pytest.raises(ValueError, match="symlink"):
            prototype.build(inputs.home, output, inputs.workspace)
    finally:
        after = (real_lib / "platform.jar").read_bytes()
        after_mode = real_lib.stat().st_mode
        real_lib.chmod(0o755)
        assert after == before
        assert after_mode == mode


@pytest.mark.usefixtures("compiler")
@pytest.mark.parametrize("compiler", [3], indirect=True)
def test_copied_staging_symlink_cannot_truncate_protected_file(
    inputs: Inputs, tmp_path: Path
) -> None:
    protected = tmp_path / "protected.jar"
    protected.write_bytes(b"protected original bytes")
    (inputs.home / "lib" / "platform.staging").symlink_to(protected)
    output = tmp_path / "output ;$literal"
    try:
        with pytest.raises(FileExistsError):
            prototype.build(inputs.home, output, inputs.workspace)
    finally:
        assert protected.read_bytes() == b"protected original bytes"


@pytest.mark.usefixtures("compiler")
@pytest.mark.parametrize("compiler", [3], indirect=True)
def test_duplicate_platform_archive_rejects_before_staging(inputs: Inputs, tmp_path: Path) -> None:
    source = inputs.home / "lib" / "platform.jar"
    with zipfile.ZipFile(source, "a") as archive:
        with pytest.warns(UserWarning, match="Duplicate name"):
            archive.writestr(CLASS, b"duplicate platform class")
    before = source.read_bytes()
    output = tmp_path / "output ;$literal"
    with pytest.raises(ValueError, match="duplicate archive entries"):
        prototype.build(inputs.home, output, inputs.workspace)
    assert source.read_bytes() == before
    assert not (output / "idea" / "lib" / "platform.staging").exists()
    assert not (output / "receipt.json").exists()


@pytest.mark.usefixtures("compiler")
@pytest.mark.parametrize("compiler", [4], indirect=True)
@pytest.mark.parametrize("archive_link", [False, True])
def test_disjoint_copy_patches_only_output_and_preserves_safe_archive_symlinks(
    inputs: Inputs, tmp_path: Path, archive_link: bool
) -> None:
    source = inputs.home / "lib" / "platform.jar"
    if archive_link:
        real_archive = inputs.home / "original-platform.jar"
        source.rename(real_archive)
        source.symlink_to(real_archive)
    source_before = source.read_bytes()
    workspace_before = inputs.workspace.read_bytes()
    lib = inputs.home / "lib"
    lib.chmod(0o555)
    mode = lib.stat().st_mode
    output = tmp_path / "output ;$literal"
    try:
        prototype.build(inputs.home, output, inputs.workspace)
        assert lib.stat().st_mode == mode
        assert source.read_bytes() == source_before
        assert inputs.workspace.read_bytes() == workspace_before
        assert source.is_symlink() is archive_link
        patched = output / "idea" / "lib" / "platform.jar"
        assert not patched.is_symlink()
        with zipfile.ZipFile(patched) as archive:
            assert archive.read(CLASS) == b"patched fixture bytes"
            assert archive.read("unrelated.txt") == b"keep platform resource"
        with zipfile.ZipFile(output / "workspace-intellij-read.jar") as archive:
            assert archive.read("Adapter.class") == b"patched fixture bytes"
        assert (output / "LOCAL_PROVENANCE_PROTOTYPE_V1").read_text() == "not-a-release\n"
        assert (output / "receipt.json").is_file()
    finally:
        lib.chmod(0o755)
