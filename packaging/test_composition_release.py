"""Release orchestration rejects unqualified inputs before creating output."""

import hashlib
import io
import json
import subprocess
import tarfile
import tempfile
from pathlib import Path

import pytest

import composed_release
from composed_bundle import BundleFailure, BundleRejected, sha256
from composed_release import ReleaseFailure, ReleaseRejected, assemble_release, qualify_release
from managed_headless import Failure, Rejected


def test_assembly_cleanup_discards_read_only_directories_without_following_links(
    tmp_path: Path,
) -> None:
    root = tmp_path.resolve()
    assembly = root / "assembly"
    inputs = root / "inputs"
    archive = root / "candidate.zip"
    outside = root / "outside"
    host = assembly / "Host.app"
    host.mkdir(parents=True)
    inputs.mkdir()
    outside.mkdir(mode=0o500)
    _ = archive.write_bytes(b"candidate bytes")
    (host / "Contents").symlink_to(outside, target_is_directory=True)
    host.chmod(0o555)
    try:
        result = composed_release.discard_assembly_inputs(assembly, inputs)
        assert not assembly.exists()
        assert not inputs.exists()
        assert archive.read_bytes() == b"candidate bytes"
        assert outside.stat().st_mode & 0o777 == 0o500
        assert [space.path for space in result.space] == [str(root), str(root)]
    finally:
        if host.exists():
            host.chmod(0o755)
        outside.chmod(0o700)


def test_linux_support_includes_the_cpp_soname_required_by_the_arm_host() -> None:
    assert composed_release.LINUX_PACKAGES.get("libstdc++6") == ("libstdc++.so.6",)
    assert composed_release.LINUX_PACKAGES.get("gcc-12-base") == ()


@pytest.mark.parametrize(
    ("case", "failure"),
    [
        ("complete", None),
        ("missing", ReleaseFailure.LINUX_PACKAGE_NOTICE),
        ("escape", ReleaseFailure.LINUX_PACKAGE_LOCATION),
        ("file-escape", ReleaseFailure.LINUX_PACKAGE_LOCATION),
        ("version", ReleaseFailure.LINUX_PACKAGE_NOTICE),
        ("duplicate", ReleaseFailure.LINUX_PACKAGE_INVENTORY),
    ],
)
def test_package_local_payloads_and_shared_notices_keep_their_admitted_supplier(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    case: str,
    failure: ReleaseFailure | None,
) -> None:
    root = tmp_path.resolve()
    cpp, base = root / "cpp.deb", root / "base.deb"
    for archive in (cpp, base):
        _ = archive.write_bytes(archive.name.encode())
    files = root / "support"
    for name in ("lib", "licenses"):
        (files / name).mkdir(parents=True)
    sentinel = root / "foreign-notice"
    _ = sentinel.write_bytes(b"preserve")
    observations: dict[tuple[str, ...], str] = {("dpkg", "--print-architecture"): "arm64"}
    for archive, name in ((cpp, "libstdc++6"), (base, "gcc-12-base")):
        for field, value in (
            ("Package", name),
            ("Version", "different" if case == "version" and archive == base else "12.3.0"),
            ("Architecture", "arm64"),
            ("Source", "gcc-12"),
        ):
            observations[("dpkg-deb", "-f", str(archive), field)] = value
    extractions = [cpp, base]

    def output(arguments: list[str], *, text: bool) -> str:
        assert text
        return observations.pop(tuple(arguments))

    def extract(arguments: list[str], *, check: bool) -> subprocess.CompletedProcess[str]:
        assert check and not observations, "all metadata must be admitted before extraction"
        archive = extractions.pop(0)
        directory = archive.with_suffix("")
        assert arguments == ["dpkg-deb", "-x", str(archive), str(directory)]
        documentation = directory / "usr/share/doc"
        documentation.mkdir(parents=True)
        if archive == cpp:
            library = directory / "usr/lib/aarch64-linux-gnu/libstdc++.so.6"
            library.parent.mkdir(parents=True)
            if case == "file-escape":
                library.symlink_to(sentinel)
            else:
                _ = library.write_bytes(b"selected C++ runtime")
            (documentation / "libstdc++6").symlink_to(
                sentinel.parent if case == "escape" else Path("gcc-12-base")
            )
            if case == "duplicate":
                duplicate = directory / "other/libstdc++.so.6"
                duplicate.parent.mkdir()
                _ = duplicate.write_bytes(b"ambiguous")
        elif case != "missing":
            notice = documentation / "gcc-12-base/copyright"
            notice.parent.mkdir()
            _ = notice.write_bytes(b"selected GCC notice")
        return subprocess.CompletedProcess(arguments, 0)

    monkeypatch.setattr(composed_release.subprocess, "check_output", output)
    monkeypatch.setattr(composed_release.subprocess, "run", extract)
    result = composed_release.prepare_linux_packages((cpp, base), files)
    assert not observations and not extractions
    assert sentinel.read_bytes() == b"preserve"
    if failure is None:
        assert isinstance(result, tuple)
        assert tuple(item.package for item in result) == ("libstdc++6", "gcc-12-base")
        assert result[0].files == ("lib/libstdc++.so.6",)
        assert result[1].files == ()
        assert result[0].archiveSha256 == hashlib.sha256(b"cpp.deb").hexdigest()
        assert (files / "lib/libstdc++.so.6").read_bytes() == b"selected C++ runtime"
        for name in ("libstdc++6", "gcc-12-base"):
            assert (
                files / "licenses" / (name + ".copyright")
            ).read_bytes() == b"selected GCC notice"
    else:
        assert isinstance(result, ReleaseRejected)
        assert result.reason == failure
        assert list((files / "lib").iterdir()) == []
    events = [json.loads(line) for line in capsys.readouterr().err.splitlines()]
    assert [(event["stage"], event["outcome"]) for event in events] == [
        ("LINUX_PACKAGE_ADMISSION", "STARTED"),
        ("LINUX_PACKAGE_ADMISSION", "COMPLETE"),
        ("LINUX_PACKAGE_EXTRACTION", "STARTED"),
        ("LINUX_PACKAGE_EXTRACTION", "COMPLETE"),
        ("LINUX_PACKAGE_PAYLOAD", "STARTED"),
        ("LINUX_PACKAGE_PAYLOAD", "COMPLETE" if failure is None else "REJECTED"),
    ]


@pytest.mark.parametrize(
    ("name", "version", "architecture"),
    [
        ("unreviewed", "12.3.0", "arm64"),
        ("libstdc++6", "", "arm64"),
        ("libstdc++6", "12.3.0", "amd64"),
    ],
)
def test_package_identity_rejects_before_extraction_or_copy(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    name: str,
    version: str,
    architecture: str,
) -> None:
    archive = tmp_path / "unread.deb"
    observations: dict[tuple[str, ...], str] = {
        ("dpkg", "--print-architecture"): "arm64",
        ("dpkg-deb", "-f", str(archive), "Package"): name,
        ("dpkg-deb", "-f", str(archive), "Version"): version,
        ("dpkg-deb", "-f", str(archive), "Architecture"): architecture,
    }

    def output(arguments: list[str], *, text: bool) -> str:
        assert text
        return observations.pop(tuple(arguments))

    def forbidden(arguments: list[str], *, check: bool) -> subprocess.CompletedProcess[str]:
        pytest.fail("rejected metadata cannot acquire an extraction capability")

    monkeypatch.setattr(composed_release.subprocess, "check_output", output)
    monkeypatch.setattr(composed_release.subprocess, "run", forbidden)
    result = composed_release.prepare_linux_packages((archive,), tmp_path / "support")
    assert result == ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_IDENTITY, "Linux support")
    assert not observations and list(tmp_path.iterdir()) == []


@pytest.mark.parametrize(
    ("error", "failure"),
    [
        (FileNotFoundError(), ReleaseFailure.LINUX_PACKAGE_IO),
        (subprocess.CalledProcessError(2, "dpkg"), ReleaseFailure.LINUX_PACKAGE_PROCESS),
    ],
)
def test_package_observation_failure_keeps_its_typed_stage(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    error: OSError | subprocess.SubprocessError,
    failure: ReleaseFailure,
) -> None:
    calls = 0

    def unavailable(arguments: list[str], *, text: bool) -> str:
        nonlocal calls
        calls += 1
        assert calls == 1 and arguments == ["dpkg", "--print-architecture"] and text
        raise error

    monkeypatch.setattr(composed_release.subprocess, "check_output", unavailable)
    result = composed_release.prepare_linux_packages(
        (tmp_path / "input.deb",), tmp_path / "support"
    )
    assert result == ReleaseRejected(failure, "Linux support")
    assert calls == 1 and list(tmp_path.iterdir()) == []
    events = [json.loads(line) for line in capsys.readouterr().err.splitlines()]
    assert [(event["stage"], event["outcome"]) for event in events] == [
        ("LINUX_PACKAGE_ADMISSION", "STARTED"),
        ("LINUX_PACKAGE_ADMISSION", "REJECTED"),
    ]


@pytest.mark.parametrize(
    ("version", "revision", "failure"),
    [
        ("latest", "a" * 40, ReleaseFailure.INVALID_IDENTITY),
        ("0.1.2", "main", ReleaseFailure.INVALID_IDENTITY),
        ("0.1.2", "0" * 40, ReleaseFailure.INVALID_IDENTITY),
    ],
)
def test_invalid_release_identity_has_no_filesystem_effects(
    tmp_path: Path, version: str, revision: str, failure: ReleaseFailure
) -> None:
    result = assemble_release(
        archive=tmp_path / "missing.tar.gz",
        bootstrap=tmp_path / "missing.zip",
        assets=tmp_path / "missing-assets",
        root=tmp_path / "producer",
        output=tmp_path / "output",
        version=version,
        revision=revision,
    )
    assert result == ReleaseRejected(failure, "identity")
    assert list(tmp_path.iterdir()) == []


def test_existing_work_is_preserved_before_reading_inputs(tmp_path: Path) -> None:
    root = tmp_path / "producer"
    root.mkdir()
    sentinel = root / "sentinel"
    _ = sentinel.write_text("preserve")
    result = assemble_release(
        archive=tmp_path / "missing.tar.gz",
        bootstrap=tmp_path / "missing.zip",
        assets=tmp_path / "missing-assets",
        root=root,
        output=tmp_path / "output",
        version="0.1.2",
        revision="a" * 40,
    )
    assert result == ReleaseRejected(ReleaseFailure.EXISTING_OUTPUT, "root or output")
    assert sentinel.read_text() == "preserve"
    assert not (tmp_path / "output").exists()


@pytest.mark.parametrize(("system", "machine"), [("Linux", "x86_64"), ("Darwin", "arm64")])
def test_wrong_donor_digest_rejects_before_unpacking(
    monkeypatch: pytest.MonkeyPatch,
    system: str,
    machine: str,
) -> None:
    # The only virtualized input is the external machine observation. Admission
    # hashes actual files and must not create a donor or invoke an installer.
    monkeypatch.setattr(composed_release.platform, "system", lambda: system)
    monkeypatch.setattr(composed_release.platform, "machine", lambda: machine)
    # macOS resolves /tmp to /private/tmp; leave room for the native socket suffix.
    with tempfile.TemporaryDirectory(prefix="", dir="/tmp") as temporary:
        owned = Path(temporary).resolve()
        archive, bootstrap, assets = (
            owned / "donor.tar.gz",
            owned / "bootstrap.zip",
            owned / "assets",
        )
        _ = archive.write_bytes(b"not the pinned vendor archive")
        _ = bootstrap.write_bytes(b"unused bootstrap")
        assets.mkdir()
        result = assemble_release(
            archive=archive,
            bootstrap=bootstrap,
            assets=assets,
            root=owned / "r",
            output=owned / "out",
            version="0.1.2",
            revision="a" * 40,
        )
        assert result == Rejected(Failure.DIGEST_MISMATCH)
        assert not (owned / "r").exists()
        assert not (owned / "out").exists()


def test_wrong_bundle_digest_never_creates_native_qualification(tmp_path: Path) -> None:
    archive = tmp_path / "bundle.tar.gz"
    _ = archive.write_bytes(b"changed bundle")
    result = qualify_release(
        archive=archive,
        expected="b" * 64,
        version="0.1.2",
        revision="a" * 40,
        root=tmp_path / "r",
        fixture=tmp_path / "workspace",
        evidence=tmp_path / "evidence",
    )
    assert result == BundleRejected(BundleFailure.DIGEST_MISMATCH, "bundle archive")
    assert not (tmp_path / "r").exists()
    assert not (tmp_path / "evidence").exists()
    assert not (tmp_path / "workspace").exists()


@pytest.mark.parametrize("entry_count", [0, 2])
def test_authenticated_archive_rejects_missing_or_duplicate_zipapp(
    tmp_path: Path, entry_count: int
) -> None:
    archive = tmp_path / "candidate.tar.gz"
    entry = "setup-kast/bin/setup-kast.pyz"
    with tarfile.open(archive, "w:gz") as bundle:
        for _ in range(entry_count):
            info = tarfile.TarInfo(entry)
            info.size = 1
            bundle.addfile(info, io.BytesIO(b"x"))
    result = qualify_release(
        archive=archive,
        expected=sha256(archive),
        version="0.1.2",
        revision="a" * 40,
        root=tmp_path / "r",
        fixture=tmp_path / "workspace",
        evidence=tmp_path / "evidence",
    )
    reason = BundleFailure.INVALID_MANIFEST if entry_count == 0 else BundleFailure.UNSAFE_PATH
    assert result == BundleRejected(reason, entry)
    assert list(tmp_path.iterdir()) == [archive]
