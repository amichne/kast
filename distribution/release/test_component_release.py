#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import asdict, dataclass, field
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

import component_release as release


@dataclass(frozen=True)
class Contract:
    type: str = "HOSTED_CONTRACT"
    runtimeProtocolIdentity: str = "kast.ide-hosted.runtime.v2"
    operationRegistryDigest: str = "sha256:" + "a" * 64
    wireSchemaDigest: str = "sha256:" + "b" * 64
    capabilities: tuple[str, ...] = ("query.run",)


@dataclass(frozen=True)
class Artifact:
    fileName: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class ControlRelease:
    artifact: Artifact
    type: str = "CONTROL_RELEASE"
    controlVersion: str = "2.1.0"
    requiredHostedContract: Contract = field(default_factory=Contract)
    sourceRevision: str = "a" * 40


@dataclass(frozen=True)
class HostRelease:
    artifact: Artifact
    type: str = "HOST_RELEASE"
    hostedPluginVersion: str = "2.1.0"
    providedHostedContract: Contract = field(default_factory=Contract)
    supportedIntellijReleaseLine: str = "262"
    sourceRevision: str = "a" * 40


@dataclass(frozen=True)
class ControlMetadata:
    productVersion: str = "2.1.0"
    requiredHostedContract: Contract = field(default_factory=Contract)


class ComponentReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="kast-component-release-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def fixture(self, component: release.Component) -> Path:
        if component is release.Component.CONTROL:
            archive = self.root / "kast-control-v2.1.0-macos-aarch64.tar.gz"
            with tarfile.open(archive, "w:gz") as stream:
                for name, content in (
                    ("bin/kast", b"launcher"), ("share/kast/libexec/kast-management", b"management"),
                    ("share/kast/ide-host.json", json.dumps(asdict(ControlMetadata())).encode()),
                ):
                    info = tarfile.TarInfo(name)
                    info.size = len(content)
                    stream.addfile(info, io.BytesIO(content))
            for name in ("skill", "plugin", "marketplace"):
                (self.root / f"kast-{name}-v2.1.0.zip").write_bytes(b"agent attachment")
            record = ControlRelease(Artifact(archive.name, "sha256:" + release.digest(archive), archive.stat().st_size))
        else:
            archive = self.root / "kast-ide-hosted-v2.1.0-idea-262.zip"
            jar = io.BytesIO()
            with zipfile.ZipFile(jar, "w") as plugin:
                plugin.writestr("META-INF/plugin.xml", "<idea-plugin><version>2.1.0</version></idea-plugin>")
                plugin.writestr("hosted-contract.json", json.dumps(asdict(Contract())))
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("kast-ide-hosted/lib/kast-ide-hosted-2.1.0.jar", jar.getvalue())
            (self.root / "host-installation.py").write_text("host installer")
            record = HostRelease(Artifact(archive.name, "sha256:" + release.digest(archive), archive.stat().st_size))
        path = self.root / f"{component.record_prefix}-v2.1.0.json"
        path.write_text(json.dumps(asdict(record)))
        self.checksums()
        return path

    def checksums(self) -> None:
        for asset in tuple(self.root.iterdir()):
            if not asset.name.endswith(".sha256"):
                (self.root / f"{asset.name}.sha256").write_text(f"{release.digest(asset)}  {asset.name}\n")

    def assertRejected(self, component: release.Component, failure: release.ReleaseFailure) -> None:
        with self.assertRaises(release.ReleaseRejected) as rejected:
            release.admit(self.root, component, "2.1.0", "a" * 40)
        self.assertEqual(rejected.exception.failure, failure)

    def test_canonical_schema_and_complete_examples_are_valid(self) -> None:
        from jsonschema import Draft202012Validator
        from referencing import Registry, Resource
        schema = json.loads(Path(__file__).with_name("component-release.schema.json").read_text())
        hosted_path = Path(__file__).resolve().parents[2] / "protocol/contract/src/main/resources/ide-hosted/hosted-endpoint.schema.json"
        hosted_schema = json.loads(hosted_path.read_text())
        Draft202012Validator.check_schema(schema)
        registry = Registry().with_resource(hosted_schema["$id"], Resource.from_contents(hosted_schema))
        validator = Draft202012Validator(schema, registry=registry)
        for example in schema["examples"]:
            validator.validate(example)

    def test_control_release_needs_no_host_archive(self) -> None:
        self.fixture(release.Component.CONTROL)
        admitted = release.admit(self.root, release.Component.CONTROL, "2.1.0", "a" * 40)
        self.assertEqual(len(admitted.assets), 10)
        self.assertFalse(any("ide-hosted" in asset.name for asset in admitted.assets))

    def test_host_release_needs_no_control_archive(self) -> None:
        self.fixture(release.Component.HOST)
        admitted = release.admit(self.root, release.Component.HOST, "2.1.0", "a" * 40)
        self.assertEqual(len(admitted.assets), 6)
        self.assertFalse(any("control" in asset.name for asset in admitted.assets))

    def test_component_version_does_not_accept_other_service_field(self) -> None:
        path = self.fixture(release.Component.CONTROL)
        record = json.loads(path.read_text())
        record["hostedPluginVersion"] = "2.1.0"
        path.write_text(json.dumps(record))
        self.checksums()
        self.assertRejected(release.Component.CONTROL, release.ReleaseFailure.INVALID_RECORD)

    def test_missing_host_evidence_is_rejected(self) -> None:
        path = self.fixture(release.Component.HOST)
        record = json.loads(path.read_text())
        del record["providedHostedContract"]
        path.write_text(json.dumps(record))
        self.checksums()
        self.assertRejected(release.Component.HOST, release.ReleaseFailure.INVALID_RECORD)

    def test_post_checksum_payload_mutation_is_rejected(self) -> None:
        self.fixture(release.Component.HOST)
        (self.root / "host-installation.py").write_text("different bytes")
        self.assertRejected(release.Component.HOST, release.ReleaseFailure.CHECKSUM_MISMATCH)

    def test_unexpected_other_component_artifact_is_rejected(self) -> None:
        self.fixture(release.Component.CONTROL)
        (self.root / "kast-ide-hosted-v2.1.0-idea-262.zip").write_text("other service")
        self.assertRejected(release.Component.CONTROL, release.ReleaseFailure.INVENTORY_MISMATCH)

    def test_wrong_source_revision_is_rejected(self) -> None:
        path = self.fixture(release.Component.CONTROL)
        record = json.loads(path.read_text())
        record["sourceRevision"] = "b" * 40
        path.write_text(json.dumps(record))
        self.checksums()
        self.assertRejected(release.Component.CONTROL, release.ReleaseFailure.WRONG_IDENTITY)

    def test_record_contract_must_equal_archived_contract(self) -> None:
        path = self.fixture(release.Component.HOST)
        record = json.loads(path.read_text())
        record["providedHostedContract"]["wireSchemaDigest"] = "sha256:" + "c" * 64
        path.write_text(json.dumps(record))
        self.checksums()
        self.assertRejected(release.Component.HOST, release.ReleaseFailure.WRONG_IDENTITY)


if __name__ == "__main__":
    unittest.main()
