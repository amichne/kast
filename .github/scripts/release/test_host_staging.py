"""Receipts must match both actual target archives before release files change."""

import json
import shutil
import sys
import tempfile
import unittest
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from unittest.mock import patch

import dual_candidate as candidate
import host_evidence as evidence
import host_staging as staging
import host_targets as hosts
import test_build_candidate as manifest
import test_host_evidence as fixtures

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "distribution/release"))
import generate_sbom as sbom


@dataclass(frozen=True, slots=True)
class ComponentFixture:
    name: str = "fixture-dependency"


@dataclass(frozen=True, slots=True)
class ScanFixture:
    bomFormat: str = "CycloneDX"
    metadata: manifest.Metadata = manifest.Metadata(())
    components: tuple[ComponentFixture, ...] = (ComponentFixture(),)


@dataclass(frozen=True, slots=True)
class HostRecord:
    type: str
    hostedPluginVersion: str
    artifact: staging.Artifact
    providedHostedContract: evidence.consumer.HostedContract
    supportedIntellijReleaseLine: str
    sourceRevision: str


class HostStagingTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.destination = self.root / "release"
        self.destination.mkdir()
        self.profile = hosts.Profile(
            1,
            "262",
            tuple(
                hosts.SdkTarget(line, f"{line}.123.45", f"{line}.123.45-IJ")
                for line in ("262", "263")
            ),
        )
        profile_path = self.root / "config/intellij-targets.json"
        profile_path.parent.mkdir()
        profile_path.write_text(json.dumps(asdict(self.profile)))
        self.identity = evidence.BuildIdentity("0.0.3", "a" * 40, "b" * 40)
        for name, content in (
            (evidence.QUERY_SCHEMA_PATH, fixtures.QUERY_SCHEMA_BYTES),
            (evidence.OPERATION_REGISTRY_PATH, fixtures.OPERATION_REGISTRY_BYTES),
        ):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        self.receipts: list[staging.Receipt] = []
        for target in self.profile.targets:
            fixture = fixtures.HostEvidenceTest()
            fixture.setUp()
            self.addCleanup(fixture.doCleanups)
            fixture.target = replace(
                fixture.target,
                intellijReleaseLine=target.intellijReleaseLine,
                ideBuild=target.ideBuild,
                kotlinPluginBuild=target.kotlinPluginBuild,
            )
            fixture.archive_fixture(descriptor_line=target.intellijReleaseLine)
            archive_name = f"kast-ide-hosted-v0.0.3-idea-{target.intellijReleaseLine}.zip"
            archive = (
                self.root
                / "runtime/hosted/build/ide-targets"
                / target.intellijReleaseLine
                / "distributions"
                / archive_name
            )
            archive.parent.mkdir(parents=True)
            shutil.copyfile(fixture.archive, archive)
            archive_reference = staging.Artifact(
                archive.name, "sha256:" + candidate.digest(archive), archive.stat().st_size
            )
            component = HostRecord(
                "HOST_RELEASE",
                "0.0.3",
                archive_reference,
                fixture.contract,
                target.intellijReleaseLine,
                self.identity.buildRevision,
            )
            output = self.root / "build/host-targets" / target.intellijReleaseLine
            record = (
                output
                / "component-release"
                / hosts.record_name("0.0.3", target.intellijReleaseLine)
            )
            record.parent.mkdir(parents=True)
            record.write_text(json.dumps(asdict(component)))
            receipt = staging.Receipt(
                1,
                "HOST_TARGET_BUILD",
                target.intellijReleaseLine,
                target.ideBuild,
                target.kotlinPluginBuild,
                "0.0.3",
                self.identity.buildRevision,
                self.identity.sourceTree,
                "sha256:" + candidate.digest(profile_path),
                archive_reference,
                staging.Artifact(
                    record.name, "sha256:" + candidate.digest(record), record.stat().st_size
                ),
                fixture.contract,
                staging.SdkEvidence("sha256:" + "e" * 64, "sha256:" + "f" * 64),
            )
            self.write_receipt(receipt)
            self.receipts.append(receipt)

    def write_receipt(self, receipt: staging.Receipt) -> None:
        (
            self.root / "build/host-targets" / receipt.intellijReleaseLine / "target-receipt.json"
        ).write_text(json.dumps(asdict(receipt)))

    def test_both_actual_archives_stage_with_exact_checksums_and_retained_receipts(self) -> None:
        self.assertIsNone(staging.stage(self.root, self.destination, self.identity))
        self.assertEqual(8, len(tuple(self.destination.iterdir())))
        for receipt in self.receipts:
            for asset in (receipt.archive, receipt.releaseRecord):
                output = self.destination / asset.fileName
                self.assertEqual(asset.sha256, "sha256:" + candidate.digest(output))
                self.assertEqual(
                    f"{asset.sha256[7:]}  {asset.fileName}\n",
                    output.with_name(output.name + ".sha256").read_text(),
                )
            retained = (
                self.root
                / "build/reports/host-targets"
                / receipt.intellijReleaseLine
                / "target-receipt.json"
            )
            self.assertEqual(receipt, staging.decode(json.loads(retained.read_text())))

    def test_second_target_identity_failure_leaves_first_target_destination_untouched(self) -> None:
        first = self.destination / self.receipts[0].archive.fileName
        first.write_bytes(b"original default target")
        self.write_receipt(replace(self.receipts[1], sourceTree="e" * 40))
        self.assertEqual(
            candidate.Failure.IDENTITY_MISMATCH,
            staging.stage(self.root, self.destination, self.identity),
        )
        self.assertEqual(b"original default target", first.read_bytes())
        self.assertEqual([first], list(self.destination.iterdir()))

    def test_raw_profile_drift_rejects_even_if_parsed_values_equal(self) -> None:
        profile = self.root / "config/intellij-targets.json"
        profile.write_text(profile.read_text() + "\n")
        self.assertEqual(
            candidate.Failure.IDENTITY_MISMATCH,
            staging.stage(self.root, self.destination, self.identity),
        )

    def test_unknown_receipt_fields_and_malformed_sdk_digests_reject(self) -> None:
        self.assertEqual(
            candidate.Failure.INVALID_RECORD,
            staging.decode(asdict(self.receipts[0]) | {"testsPassed": True}),
        )
        malformed = replace(
            self.receipts[0], sdk=staging.SdkEvidence("e" * 64, "sha256:" + "f" * 64)
        )
        self.assertEqual(candidate.Failure.INVALID_RECORD, staging.decode(asdict(malformed)))

    def test_dual_sbom_scans_both_archives_and_default_view_rejects_extra_host(self) -> None:
        self.assertIsNone(staging.stage(self.root, self.destination, self.identity))
        version = self.identity.version
        for name in (
            f"kast-control-v{version}-macos-aarch64.tar.gz",
            f"kast-plugin-v{version}.zip",
            f"kast-marketplace-v{version}.zip",
        ):
            (self.destination / name).write_bytes(name.encode())
        with self.assertRaises(sbom.SbomError), patch.object(sbom, "scanner") as scanner:
            sbom.generate(self.root, self.destination, version, self.identity.buildRevision)
        scanner.assert_not_called()
        scanned: list[str] = []

        def scan(command: list[str], root: Path, environment: dict[str, str]) -> None:
            source = Path(command[2].removeprefix("dir:"))
            scanned.extend(path.name for path in source.iterdir())
            output = Path(command[-1].removeprefix("cyclonedx-json="))
            output.write_text(json.dumps(asdict(ScanFixture())))

        with (
            patch.object(sbom, "scanner", return_value=self.root / "fixture-scanner"),
            patch.object(sbom, "run", side_effect=scan),
        ):
            sbom.generate(
                self.root, self.destination, version, self.identity.buildRevision, ("262", "263")
            )
        self.assertEqual(5, len(scanned))
        self.assertIn(f"kast-ide-hosted-v{version}-idea-263.zip", scanned)
        document = json.loads((self.destination / f"kast-sbom-v{version}.cdx.json").read_text())
        properties = {item["name"]: item["value"] for item in document["metadata"]["properties"]}
        for name in scanned:
            self.assertEqual(
                "sha256:" + candidate.digest(self.destination / name),
                properties[f"kast:archive:{name}"],
            )


if __name__ == "__main__":
    unittest.main()
