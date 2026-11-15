"""Portable distribution contracts keep explicit identity and reject malformed archives."""

import hashlib
import io
import json
import subprocess
import tarfile
from dataclasses import asdict, dataclass
from pathlib import Path

import pytest

from composed_bundle import (
    BundleFailure,
    BundleManifest,
    BundleRejected,
    extract_bundle,
    read_manifest,
    sha256,
)
from composition_platform import HostPlatform
from managed_headless import AssemblyEntryKind, AssemblyFile

FILES = (
    "bootstrap.zip",
    "composition-manifest.json",
    "install.sh",
    "runtime/product-info.json",
    "runtime/lib/kast-product.jar",
    "runtime/plugins/plugin-classpath.txt",
)


def manifest_bytes() -> bytes:
    return json.dumps(
        asdict(
            BundleManifest(
                "0.0.0",
                "262.10315.125",
                "a" * 64,
                "b" * 64,
                "c" * 64,
                tuple(
                    AssemblyFile(name, AssemblyEntryKind.FILE, 0, hashlib.sha256(b"").hexdigest())
                    for name in FILES
                ),
            )
        )
    ).encode()


def test_manifest_encodes_fixed_bundle_identity_and_all_required_files() -> None:
    raw = manifest_bytes()
    result = read_manifest(raw)
    assert isinstance(result, BundleManifest)
    assert b'"kind": "KAST_COMPOSED_SETUP_V2"' in raw
    assert b'"schemaVersion": 2' in raw
    assert b'"platform": "linux-x86_64"' in raw
    assert {record.path for record in result.files} == set(FILES)


def test_boolean_schema_version_does_not_become_integer_one() -> None:
    raw = manifest_bytes().replace(b'"schemaVersion": 2', b'"schemaVersion": true')
    assert read_manifest(raw) == BundleRejected(BundleFailure.INVALID_MANIFEST, "identity")


def test_manifest_rejects_missing_current_format_identity() -> None:
    assert read_manifest(b'{"files": []}') == BundleRejected(
        BundleFailure.INVALID_MANIFEST,
        "identity",
    )


def test_archive_parent_escape_rejects_without_an_exterior_write(tmp_path: Path) -> None:
    archive = tmp_path / "bad.tar.gz"
    with tarfile.open(archive, "w:gz") as out:
        info = tarfile.TarInfo("setup-kast/../outside")
        info.size = 3
        out.addfile(info, io.BytesIO(b"bad"))
    destination = tmp_path / "staging"
    destination.mkdir()
    assert extract_bundle(archive, destination, sha256(archive)) == BundleRejected(
        BundleFailure.UNSAFE_PATH,
        "setup-kast/../outside",
    )
    assert not (tmp_path / "outside").exists()


def test_archive_duplicate_path_rejects_instead_of_replacing_bytes(tmp_path: Path) -> None:
    archive = tmp_path / "duplicate.tar"
    with tarfile.open(archive, "w") as out:
        for raw in (b"first", b"other"):
            info = tarfile.TarInfo("setup-kast/item")
            info.size = len(raw)
            out.addfile(info, io.BytesIO(raw))
    destination = tmp_path / "staging"
    destination.mkdir()
    assert extract_bundle(archive, destination, sha256(archive)) == BundleRejected(
        BundleFailure.UNSAFE_PATH,
        "setup-kast/item",
    )
    assert (destination / "setup-kast/item").read_bytes() == b"first"


def test_bad_digest_rejects_before_archive_extraction(tmp_path: Path) -> None:
    archive = tmp_path / "archive"
    _ = archive.write_bytes(b"not an archive")
    staging = tmp_path / "staging"
    staging.mkdir()
    assert extract_bundle(archive, staging, "0" * 64) == BundleRejected(
        BundleFailure.DIGEST_MISMATCH,
        "bundle archive",
    )
    assert not list(staging.iterdir())


def test_macos_manifest_keeps_native_layout_and_platform_identity() -> None:
    raw = (
        manifest_bytes()
        .replace(b"linux-x86_64", b"macos-aarch64")
        .replace(b"runtime/product-info.json", b"runtime/Resources/product-info.json")
    )
    result = read_manifest(raw)
    assert isinstance(result, BundleManifest)
    assert result.platform == "macos-aarch64"
    assert "runtime/Resources/product-info.json" in {item.path for item in result.files}


def test_macos_manifest_rejects_linux_product_layout() -> None:
    raw = manifest_bytes().replace(b"linux-x86_64", b"macos-aarch64")
    assert read_manifest(raw) == BundleRejected(
        BundleFailure.INVALID_MANIFEST, "missing required files"
    )


@pytest.mark.parametrize("invalid", [b"[]", b"{}", b"null", b"7", b'"other"'])
def test_unknown_platform_values_remain_typed_rejections(invalid: bytes) -> None:
    raw = manifest_bytes().replace(b'"linux-x86_64"', invalid)
    assert read_manifest(raw) == BundleRejected(BundleFailure.INVALID_MANIFEST, "identity")


@pytest.mark.parametrize("target", tuple(HostPlatform))
def test_bundle_round_trip_keeps_each_native_layout_without_an_alternate_installer(
    monkeypatch: pytest.MonkeyPatch, target: HostPlatform
) -> None:
    import tempfile

    import composed_bundle
    from composed_bundle import PackedBundle, pack_bundle
    from composed_host import CompositionReceipt
    from managed_headless import file_record

    with tempfile.TemporaryDirectory(prefix="kb-", dir="/tmp") as name:
        owned = Path(name).resolve()
        producer = owned / "producer"
        home = producer / target.layout.home
        product = home / target.layout.product
        product.parent.mkdir(parents=True)
        _ = product.write_text(json.dumps(asdict(BundleProduct("262.10315.125"))))
        for relative in ("lib/kast-product.jar", "plugins/plugin-classpath.txt"):
            item = home / relative
            item.parent.mkdir(parents=True, exist_ok=True)
            _ = item.write_bytes(b"fixture vendor bytes")
        bootstrap = owned / "bootstrap.zip"
        _ = bootstrap.write_bytes(b"fixture bootstrap")
        assets = owned / "assets"
        assets.mkdir()
        _ = (assets / "asset").write_bytes(b"fixture native asset")
        records = tuple(
            file_record(path, home) for path in sorted(home.rglob("*")) if path.is_file()
        )
        receipt = producer / "composition-manifest.json"
        _ = receipt.write_text(
            json.dumps(
                asdict(
                    CompositionReceipt(
                        schemaVersion=1,
                        distributionSha256=target.layout.archive_sha256,
                        sourceManifestSha256="a" * 64,
                        bootstrapArchiveSha256=sha256(bootstrap),
                        assemblerFiles=(),
                        roots=(),
                        modules=(),
                        plugins=(),
                        embeddedArchiveModules=(),
                        omittedRegistrations=(),
                        omittedKotlinFragments=(),
                        sourceInputs=(),
                        vendorFiles=records[:-1],
                        generatedFiles=records[-1:],
                    )
                )
            )
        )
        archive = owned / "bundle.tar.gz"
        support: Path | None = None
        if target.is_linux:
            support = owned / "support"
            for name in (
                "lib/libfontconfig.so.1",
                "fonts/font.ttf",
                "licenses/copyright",
                "fonts.conf",
            ):
                item = support / name
                item.parent.mkdir(exist_ok=True, parents=True)
                _ = item.write_bytes(b"fixture support")
        packed = pack_bundle(
            producer, assets, bootstrap, sha256(receipt), archive, "0.0.0", linux_support=support
        )
        assert isinstance(packed, PackedBundle)
        entry = owned / "install.sh"
        with tarfile.open(archive) as source:
            names = source.getnames()
            assert not any(name.endswith(".pyz") for name in names)
            installer_entry = source.extractfile("setup-kast/install.sh")
            assert installer_entry is not None
            _ = entry.write_bytes(installer_entry.read())
        assert (
            entry.read_bytes()
            == Path(__file__).resolve().parents[1].joinpath("install.sh").read_bytes()
        )
        help_result = subprocess.run(
            ["/bin/bash", str(entry), "--help"],
            cwd=owned,
            capture_output=True,
            text=True,
            check=False,
        )
        assert help_result.returncode == 0, help_result.stderr
        assert "No Python, uv, or IDEA" in help_result.stdout
        destination = owned / "r"
        destination.mkdir()
        extracted = extract_bundle(archive, destination, packed.sha256)
        assert not isinstance(extracted, BundleRejected)
        metadata = read_manifest((extracted.source / "manifest.json").read_bytes())
        assert isinstance(metadata, BundleManifest)
        assert not isinstance(
            composed_bundle.examine_files(extracted.source, metadata.files), BundleRejected
        )
        expected = extracted.source / "runtime"
        assert (expected / target.layout.product).read_bytes() == product.read_bytes()
        assert (expected / "lib/kast-product.jar").read_bytes() == b"fixture vendor bytes"


@dataclass(frozen=True)
class BundleProduct:
    buildNumber: str
