"""Source admission rejects changed bytes before composition effects."""

import hashlib
import json
from dataclasses import asdict, dataclass, replace
from pathlib import Path

import pytest

from composed_host import (
    CompositionFailure,
    CompositionRejected,
    Launch,
    Product,
    examine_source,
    product,
    source_inventory,
)
from composition_recipe import ARCHIVE_SHA256, BUILD
from managed_headless import AssemblyEntryKind, AssemblyFile, object_fields


@dataclass(frozen=True)
class SourceManifest:
    schemaVersion: int
    distributionSha256: str
    retainedFiles: tuple[AssemblyFile, ...]


@dataclass(frozen=True)
class VendorMetadata:
    name: str = "IntelliJ IDEA"
    version: str = "2026.2.2"
    buildNumber: str = BUILD
    productCode: str = "IU"
    productVendor: str = "JetBrains"
    envVarBaseName: str = "IDEA"
    dataDirectoryName: str = "IntelliJIdea2026.2"
    launch: tuple[Launch, ...] = (
        Launch(
            "macOS",
            "aarch64",
            "../MacOS/idea",
            "../jbr/Contents/Home/bin/java",
            "../bin/idea.vmoptions",
            ("core.jar", "unselected.jar"),
            (),
            "com.intellij.idea.Main",
        ),
    )


def test_native_launcher_keeps_required_vendor_field_and_selected_classpath() -> None:
    result = product(json.dumps(asdict(VendorMetadata())).encode(), frozenset({"lib/core.jar"}), ())
    assert isinstance(result, Product)
    encoded: object = json.loads(json.dumps(asdict(result)))
    assert object_fields(encoded)
    assert encoded.get("productVendor") == "JetBrains"
    assert result.launch[0].bootClassPathJarNames == (
        "core.jar",
        "ext/platform-main.jar",
        "kast-product.jar",
    )


def test_macos_x86_launcher_preserves_the_pinned_vendor_amd64_identity() -> None:
    launch = replace(VendorMetadata().launch[0], arch="amd64")
    raw = json.dumps(asdict(replace(VendorMetadata(), launch=(launch,)))).encode()
    result = product(raw, frozenset({"lib/core.jar"}), ())
    assert isinstance(result, Product), result
    assert result.launch[0].os == "macOS"
    assert result.launch[0].arch == "amd64"
    assert result.launch[0].launcherPath == "../MacOS/idea"


def test_linux_launcher_keeps_native_paths_and_selected_classpath() -> None:
    launch = replace(
        VendorMetadata().launch[0],
        os="Linux",
        arch="amd64",
        launcherPath="bin/idea",
        javaExecutablePath="jbr/bin/java",
        vmOptionsFilePath="bin/idea64.vmoptions",
    )
    raw = json.dumps(asdict(replace(VendorMetadata(), launch=(launch,)))).encode()
    result = product(raw, frozenset({"lib/core.jar"}), ())
    assert isinstance(result, Product), result
    assert result.launch[0].launcherPath == "bin/idea"
    assert result.launch[0].vmOptionsFilePath == "bin/idea64.vmoptions"
    assert result.launch[0].bootClassPathJarNames == (
        "core.jar",
        "ext/platform-main.jar",
        "kast-product.jar",
    )


def manifest(tmp_path: Path, record: AssemblyFile) -> tuple[Path, str]:
    path = tmp_path / "source-manifest.json"
    raw = json.dumps(asdict(SourceManifest(1, ARCHIVE_SHA256, (record,)))).encode()
    _ = path.write_bytes(raw)
    return path, hashlib.sha256(raw).hexdigest()


def test_admission_keeps_identity_and_digest_then_rejects_changed_source(tmp_path: Path) -> None:
    home = tmp_path / "source"
    (home / "lib").mkdir(parents=True)
    raw = b"pinned vendor bytes"
    path = home / "lib/owner.jar"
    _ = path.write_bytes(raw)
    record = AssemblyFile(
        "lib/owner.jar", AssemblyEntryKind.FILE, len(raw), hashlib.sha256(raw).hexdigest()
    )
    manifest_path, sha = manifest(tmp_path, record)
    result = source_inventory(home, manifest_path, sha)
    assert isinstance(result, tuple) and len(result) == 1
    admitted = examine_source(result[0])
    assert not isinstance(admitted, CompositionRejected)
    assert admitted.record == record and admitted.source == home
    _ = path.write_bytes(b"changed vendor bytes")
    assert examine_source(result[0]) == CompositionRejected(
        CompositionFailure.SOURCE_DIGEST_MISMATCH,
        "lib/owner.jar",
    )


@pytest.mark.parametrize(
    "path", ["../outside.jar", "/absolute.jar", "lib//alias.jar", "lib/./alias.jar"]
)
def test_source_manifest_cannot_authorize_path_escapes(tmp_path: Path, path: str) -> None:
    home = tmp_path / "source"
    home.mkdir()
    record = AssemblyFile(path, AssemblyEntryKind.FILE, 0, "0" * 64)
    manifest_path, sha = manifest(tmp_path, record)
    assert source_inventory(home, manifest_path, sha) == CompositionRejected(
        CompositionFailure.INVALID_SOURCE_MANIFEST,
        "file fields",
    )


def test_manifest_identity_needs_the_supplied_digest(tmp_path: Path) -> None:
    home = tmp_path / "source"
    home.mkdir()
    manifest_path, _ = manifest(
        tmp_path, AssemblyFile("lib/p.jar", AssemblyEntryKind.FILE, 0, "0" * 64)
    )
    assert source_inventory(home, manifest_path, "f" * 64) == CompositionRejected(
        CompositionFailure.SOURCE_DIGEST_MISMATCH,
        str(manifest_path),
    )


def test_malformed_product_is_a_typed_rejection() -> None:
    assert product(b"{", frozenset(), ()) == CompositionRejected(
        CompositionFailure.INVALID_PRODUCT,
        "product-info",
    )
