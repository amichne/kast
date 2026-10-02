#!/usr/bin/env python3
"""Admit exact-source component artifacts before publishing their independent release."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from pathlib import Path
import re

from jsonschema import Draft202012Validator
from referencing import Registry, Resource


class Component(str, Enum):
    CONTROL = "control"
    HOST = "host"

    @property
    def record_prefix(self) -> str:
        return "kast-control-release" if self is Component.CONTROL else "kast-host-release"


@dataclass(frozen=True)
class AdmittedRelease:
    component: Component
    version: str
    source_revision: str
    assets: tuple[Path, ...]


class ReleaseFailure(str, Enum):
    INVALID_RECORD = "INVALID_RECORD"
    WRONG_IDENTITY = "WRONG_IDENTITY"
    WRONG_OWNER = "WRONG_OWNER"
    INVENTORY_MISMATCH = "INVENTORY_MISMATCH"
    CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
    INVALID_ARCHIVE = "INVALID_ARCHIVE"


class ReleaseRejected(Exception):
    def __init__(self, failure: ReleaseFailure):
        super().__init__(failure.value)
        self.failure = failure


def digest(path: Path) -> str:
    if not path.is_file() or path.is_symlink():
        raise ReleaseRejected(ReleaseFailure.INVENTORY_MISMATCH)
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def admit(directory: Path, component: Component, version: str, revision: str) -> AdmittedRelease:
    if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is None or re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        raise ReleaseRejected(ReleaseFailure.WRONG_IDENTITY)
    source = Path(__file__).resolve().parents[2]
    schema = json.loads(Path(__file__).with_name("component-release.schema.json").read_text())
    hosted_schema = json.loads((source / "protocol/contract/src/main/resources/ide-hosted/hosted-endpoint.schema.json").read_text())
    registry = Registry().with_resource(hosted_schema["$id"], Resource.from_contents(hosted_schema))
    record_path = directory / f"{component.record_prefix}-v{version}.json"
    try:
        record = json.loads(record_path.read_text())
    except (OSError, ValueError):
        raise ReleaseRejected(ReleaseFailure.INVALID_RECORD) from None
    if not Draft202012Validator(schema, registry=registry).is_valid(record):
        raise ReleaseRejected(ReleaseFailure.INVALID_RECORD)
    version_field = "controlVersion" if component is Component.CONTROL else "hostedPluginVersion"
    if record["type"] != f"{component.value.upper()}_RELEASE":
        raise ReleaseRejected(ReleaseFailure.WRONG_OWNER)
    if record[version_field] != version or record["sourceRevision"] != revision:
        raise ReleaseRejected(ReleaseFailure.WRONG_IDENTITY)
    archive_name = record["artifact"]["fileName"]
    if component is Component.CONTROL:
        expected_archive = f"kast-control-v{version}-macos-aarch64.tar.gz"
        companion_names = {f"kast-{name}-v{version}.zip" for name in ("skill", "plugin", "marketplace")}
    else:
        expected_archive = f"kast-ide-hosted-v{version}-idea-{record['supportedIntellijReleaseLine']}.zip"
        companion_names = {"host-installation.py"}
    if archive_name != expected_archive:
        raise ReleaseRejected(ReleaseFailure.WRONG_OWNER)
    names = {archive_name, record_path.name} | companion_names
    expected_names = names | {f"{name}.sha256" for name in names}
    if not directory.is_dir() or directory.is_symlink() or {item.name for item in directory.iterdir()} != expected_names:
        raise ReleaseRejected(ReleaseFailure.INVENTORY_MISMATCH)
    for name in names:
        asset = directory / name
        expected_digest = digest(asset)
        checksum_path = directory / f"{name}.sha256"
        digest(checksum_path)
        if checksum_path.read_text().strip() != f"{expected_digest}  {name}":
            raise ReleaseRejected(ReleaseFailure.CHECKSUM_MISMATCH)
    primary = directory / archive_name
    if (record["artifact"]["sha256"] != "sha256:" + digest(primary)
            or record["artifact"]["bytes"] != primary.stat().st_size):
        raise ReleaseRejected(ReleaseFailure.CHECKSUM_MISMATCH)
    verify_archive(primary, component, record)
    return AdmittedRelease(component, version, revision, tuple(directory / name for name in sorted(expected_names)))


def verify_archive(archive: Path, component: Component, record: dict) -> None:
    import io
    import tarfile
    import zipfile
    import xml.etree.ElementTree as ET
    try:
        if component is Component.HOST:
            with zipfile.ZipFile(archive) as stream:
                names = stream.namelist()
                if not names or any(not name.startswith("kast-ide-hosted/") or ".." in Path(name).parts for name in names):
                    raise ReleaseRejected(ReleaseFailure.INVALID_ARCHIVE)
                if "kast-ide-hosted/lib/" not in names and not any(name.startswith("kast-ide-hosted/lib/") for name in names):
                    raise ReleaseRejected(ReleaseFailure.INVALID_ARCHIVE)
                plugin_jar = f"kast-ide-hosted/lib/kast-ide-hosted-{record['hostedPluginVersion']}.jar"
                with zipfile.ZipFile(io.BytesIO(stream.read(plugin_jar))) as plugin:
                    metadata = ET.fromstring(plugin.read("META-INF/plugin.xml"))
                    if metadata.findtext("version") != record["hostedPluginVersion"]:
                        raise ReleaseRejected(ReleaseFailure.WRONG_IDENTITY)
                    provided = json.loads(plugin.read("hosted-contract.json"))
                    if provided != record["providedHostedContract"]:
                        raise ReleaseRejected(ReleaseFailure.WRONG_IDENTITY)
        else:
            with tarfile.open(archive, "r:gz") as stream:
                names = [entry.name for entry in stream.getmembers()]
                if any(Path(name).is_absolute() or ".." in Path(name).parts for name in names):
                    raise ReleaseRejected(ReleaseFailure.INVALID_ARCHIVE)
                if not {"bin/kast", "share/kast/ide-host.json", "share/kast/libexec/kast-management"}.issubset(names):
                    raise ReleaseRejected(ReleaseFailure.INVALID_ARCHIVE)
                if any("kast-ide-hosted" in name or "/plugins/" in name and not name.startswith("share/kast/agent-tools/") for name in names):
                    raise ReleaseRejected(ReleaseFailure.WRONG_OWNER)
                manifest_stream = stream.extractfile("share/kast/ide-host.json")
                if manifest_stream is None:
                    raise ReleaseRejected(ReleaseFailure.INVALID_ARCHIVE)
                with manifest_stream:
                    manifest = json.load(manifest_stream)
                if (manifest.get("productVersion") != record["controlVersion"]
                        or manifest.get("requiredHostedContract") != record["requiredHostedContract"]):
                    raise ReleaseRejected(ReleaseFailure.WRONG_IDENTITY)
    except (OSError, ValueError, KeyError, tarfile.TarError, zipfile.BadZipFile, ET.ParseError):
        raise ReleaseRejected(ReleaseFailure.INVALID_ARCHIVE) from None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--component", required=True, choices=[item.value for item in Component])
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--directory", required=True, type=Path)
    args = parser.parse_args()
    try:
        admitted = admit(args.directory, Component(args.component), args.version, args.source_revision)
    except ReleaseRejected as rejection:
        raise SystemExit(rejection.failure.value) from None
    print(f"admitted {admitted.component.value}-v{admitted.version}: {len(admitted.assets)} assets")


if __name__ == "__main__":
    main()
