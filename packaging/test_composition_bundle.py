"""Portable distribution contracts keep explicit identity and reject malformed archives."""

import hashlib
import io
import json
import tarfile
from dataclasses import asdict, dataclass
from pathlib import Path

from composed_bundle import (
    BundleFailure,
    BundleManifest,
    BundleRejected,
    ConfiguredTrust,
    admit_trust_workspace,
    configure_trusted_workspace,
    extract_bundle,
    read_manifest,
    sha256,
)
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
    tmp_path: Path,
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
    result = configure_trusted_workspace(state, admitted)
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
