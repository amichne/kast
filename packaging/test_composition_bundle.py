"""Portable distribution contracts keep explicit identity and reject malformed archives."""

import hashlib
import io
import json
import sys
import tarfile
from dataclasses import asdict, dataclass
from pathlib import Path

import pytest

from composed_bundle import (
    BundleFailure,
    BundleManifest,
    BundleRejected,
    ConfiguredTrust,
    TrustMode,
    admit_trust_workspace,
    configure_trusted_workspace,
    extract_bundle,
    main,
    read_manifest,
    sha256,
    trust_installed_workspace,
)
from composition_platform import HostPlatform
from managed_headless import AssemblyEntryKind, AssemblyFile, Failure, Rejected

FILES = (
    "bootstrap.zip",
    "composition-manifest.json",
    "install.sh",
    "runtime/product-info.json",
    "runtime/lib/kast-product.jar",
    "runtime/plugins/plugin-classpath.txt",
    "bin/setup-kast",
    "bin/setup-kast.lock",
    "packaging/managed_headless.py",
    "packaging/composed_bundle.py",
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
    assert b'"kind": "KAST_COMPOSED_SETUP_V1"' in raw
    assert b'"schemaVersion": 1' in raw
    assert b'"platform": "linux-x86_64"' in raw
    assert {record.path for record in result.files} == set(FILES)


def test_boolean_schema_version_does_not_become_integer_one() -> None:
    raw = manifest_bytes().replace(b'"schemaVersion": 1', b'"schemaVersion": true')
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


@dataclass(frozen=True)
class PluginTarget:
    root: str
    type: str = "RECORDED"


@dataclass(frozen=True)
class Selection:
    hostPluginTarget: PluginTarget
    type: str = "resolved"


def test_explicit_trust_uses_only_the_private_profile_and_preserves_existing_settings(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    state = tmp_path / "install/state"
    plugins = state / "home/profile/plugins"
    plugins.mkdir(parents=True)
    selection = state / "home/.local/share/kast/installation/config/selected-ide.json"
    selection.parent.mkdir(parents=True)
    _ = selection.write_text(json.dumps(asdict(Selection(PluginTarget(str(plugins))))))
    workspace = tmp_path / "repository"
    workspace.mkdir()
    admitted = admit_trust_workspace(workspace, tmp_path / "install")
    assert not isinstance(admitted, BundleRejected)
    _ = (state.parent / ".kast-headless-owned").write_text("kast-managed-headless-v1\n")
    result = trust_installed_workspace(state.parent, workspace)
    assert isinstance(result, ConfiguredTrust)
    assert (
        result.workspace == workspace
        and result.settings == plugins.parent / "options/trusted-paths.xml"
    )
    expected = (
        '<application><component name="Trusted.Paths"><option name="TRUSTED_PROJECT_PATHS">'
        '<map><entry key="' + str(workspace) + '" value="true" /></map></option>'
        "</component></application>"
    ).encode()
    assert result.settings.read_bytes() == expected
    assert result.sha256 == hashlib.sha256(expected).hexdigest()
    assert configure_trusted_workspace(state, admitted) == Rejected(Failure.INVALID_COMPONENT)
    assert result.settings.read_bytes() == expected
    formatted = b'<?xml version="1.0" encoding="UTF-8"?>\n' + expected.replace(b"><", b">\n<")
    _ = result.settings.write_bytes(formatted)
    reused = trust_installed_workspace(state.parent, workspace, TrustMode.REUSE_EXACT)
    assert reused == ConfiguredTrust(
        workspace, result.settings, hashlib.sha256(formatted).hexdigest()
    )
    assert result.settings.read_bytes() == formatted
    result.settings.unlink()
    monkeypatch.setattr(
        sys,
        "argv",
        ["composed_bundle.py", "trust", "--root", str(state.parent), "--workspace", str(workspace)],
    )
    assert main() == 0
    assert json.loads(capsys.readouterr().out) == {
        "workspace": str(workspace),
        "settings": str(result.settings),
        "sha256": hashlib.sha256(expected).hexdigest(),
    }


def test_trust_cannot_include_the_installation_state(tmp_path: Path) -> None:
    assert admit_trust_workspace(tmp_path, tmp_path / "install") == BundleRejected(
        BundleFailure.UNSAFE_PATH,
        "trusted workspace",
    )


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
def test_bundle_round_trip_keeps_each_native_layout_before_installer_effects(
    monkeypatch: pytest.MonkeyPatch, target: HostPlatform
) -> None:
    import tempfile

    import composed_bundle
    from composed_bundle import PackedBundle, install_bundle, pack_bundle
    from composed_host import CompositionReceipt
    from managed_headless import AssemblyManifest, file_record

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
        packed = pack_bundle(producer, assets, bootstrap, sha256(receipt), archive, "0.0.0")
        assert isinstance(packed, PackedBundle)
        destination = owned / "r"
        visited: list[Path] = []

        def installer(
            _admitted: object,
            installed_home: Path,
            script: Path,
            manifest: AssemblyManifest | None = None,
        ) -> Rejected:
            assert script.is_file()
            assert manifest is None
            visited.append(installed_home)
            return Rejected(Failure.INSTALLER_FAILED)

        monkeypatch.setattr(composed_bundle, "install_prepared_runtime", installer)
        monkeypatch.setattr(
            composed_bundle.platform,
            "system",
            lambda: "Darwin" if target.value == "macos-aarch64" else "Linux",
        )
        monkeypatch.setattr(
            composed_bundle.platform,
            "machine",
            lambda: "x86_64" if target == HostPlatform.LINUX_X86_64 else "arm64",
        )
        assert install_bundle(archive, packed.sha256, destination, "0.0.0") == Rejected(
            Failure.INSTALLER_FAILED
        )
        expected = destination / (
            "runtime/Kast Analysis Host.app/Contents"
            if target.value == "macos-aarch64"
            else "runtime/kast-analysis-host"
        )
        assert visited == [expected]
        assert (expected / target.layout.product).read_bytes() == product.read_bytes()
        assert (expected / "lib/kast-product.jar").read_bytes() == b"fixture vendor bytes"


@dataclass(frozen=True)
class BundleProduct:
    buildNumber: str


def test_runtime_trust_rejects_an_unowned_installation(tmp_path: Path) -> None:
    root = tmp_path / "install"
    workspace = tmp_path / "repository"
    root.mkdir()
    workspace.mkdir()
    assert trust_installed_workspace(root, workspace) == BundleRejected(
        BundleFailure.UNSAFE_PATH,
        "installed root",
    )
