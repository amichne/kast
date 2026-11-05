"""Actual paired archives remain bound to immutable source pins through publication."""

import base64
import json
import shutil
import unittest
from dataclasses import asdict, dataclass, replace
from unittest.mock import patch

import build_candidate as legacy
import developer_catalog as catalog
import dual_candidate as dual
import host_targets as hosts
import publish_developer as publisher
import test_build_candidate as manifest
import test_host_staging as staging
from validation_outcome import ProductValidated


@dataclass(frozen=True, slots=True)
class ControlRecord:
    type: str
    controlVersion: str
    artifact: staging.staging.Artifact
    requiredHostedContract: staging.evidence.consumer.HostedContract
    sourceRevision: str


@dataclass(frozen=True, slots=True)
class SourceContents:
    content: str
    size: int
    type: str = "file"
    encoding: str = "base64"


class DualProducerTest(unittest.TestCase):
    def setUp(self) -> None:
        fixture = staging.HostStagingTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.root, self.profile = fixture.root, fixture.profile
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        self.payload = self.bundle / "payload"
        self.payload.mkdir()
        self.identity = fixture.identity
        self.assertIsNone(staging.staging.stage(self.root, self.payload, fixture.identity))
        version = self.identity.version
        control_name = f"kast-control-v{version}-macos-aarch64.tar.gz"
        (self.payload / control_name).write_bytes(b"qualified native image fixture")
        record = ControlRecord(
            "CONTROL_RELEASE",
            version,
            staging.staging.Artifact(
                control_name,
                "sha256:" + dual.digest(self.payload / control_name),
                (self.payload / control_name).stat().st_size,
            ),
            fixture.receipts[0].providedHostedContract,
            self.identity.buildRevision,
        )
        (self.payload / f"kast-control-release-v{version}.json").write_text(
            json.dumps(asdict(record))
        )
        for name in dual.base_names(version):
            if not (self.payload / name).exists():
                (self.payload / name).write_bytes(name.encode())
        archives = (*dual.base_names(version)[:4], f"kast-ide-hosted-v{version}-idea-263.zip")
        sbom = manifest.Sbom(
            "CycloneDX",
            manifest.Metadata(
                (
                    manifest.Property("kast:source-revision", self.identity.buildRevision),
                    *(
                        manifest.Property(
                            f"kast:archive:{name}", "sha256:" + dual.digest(self.payload / name)
                        )
                        for name in archives
                    ),
                )
            ),
        )
        (self.payload / f"kast-sbom-v{version}.cdx.json").write_text(json.dumps(asdict(sbom)))
        for path in tuple(self.payload.iterdir()):
            if not path.name.endswith(".sha256"):
                path.with_name(path.name + ".sha256").write_text(
                    f"{dual.digest(path)}  {path.name}\n"
                )
        targets = tuple(
            hosts.HostTarget(
                target.intellijReleaseLine,
                target.ideBuild,
                target.kotlinPluginBuild,
                catalog.RecordReference(
                    hosts.record_name(version, target.intellijReleaseLine),
                    dual.digest(
                        self.payload / hosts.record_name(version, target.intellijReleaseLine)
                    ),
                ),
            )
            for target in self.profile.targets
        )
        self.current = dual.Candidate(
            2,
            "amichne/kast",
            self.identity.buildRevision,
            self.identity.sourceTree,
            version,
            3,
            3,
            1,
            "pull_request",
            "macos-aarch64",
            legacy.Toolchain("25.0.2", "Oracle", "d" * 64),
            tuple(
                legacy.Asset(path.name, dual.digest(path), path.stat().st_size)
                for path in sorted(self.payload.iterdir())
            ),
            targets,
        )
        self.old = dual.projection(self.current)
        (self.bundle / "candidate.json").write_text(json.dumps(asdict(self.old)))
        (self.bundle / "candidate-v2.json").write_text(json.dumps(asdict(self.current)))
        self.promotion = legacy.Promotion(
            1,
            "amichne/kast",
            self.identity.buildRevision,
            "c" * 40,
            self.identity.sourceTree,
            version,
            3,
            1,
            456,
            1,
            dual.digest(self.bundle / "candidate.json"),
        )
        self.promotion_path = self.root / "promotion.json"
        self.promotion_path.write_text(json.dumps(asdict(self.promotion)))
        self.root.joinpath("promotion-v2.json").write_text(
            json.dumps(
                asdict(
                    replace(
                        self.promotion,
                        schemaVersion=2,
                        candidateSha256=dual.digest(self.bundle / "candidate-v2.json"),
                    )
                )
            )
        )

    def observe(self, route: str, stage: legacy.ObservationStage) -> object:
        if stage is legacy.ObservationStage.SOURCE_PROFILE:
            self.assertEqual(
                f"repos/amichne/kast/contents/config/intellij-targets.json?ref={self.identity.buildRevision}",
                route,
            )
            raw = json.dumps(asdict(self.profile)).encode()
            return asdict(SourceContents(base64.b64encode(raw).decode(), len(raw)))
        if stage is legacy.ObservationStage.SOURCE_RESOURCES:
            prefix = "repos/amichne/kast/contents/"
            suffix = f"?ref={self.identity.buildRevision}"
            self.assertTrue(route.startswith(prefix) and route.endswith(suffix))
            path = route.removeprefix(prefix).removesuffix(suffix)
            raw = (self.root / path).read_bytes()
            return asdict(SourceContents(base64.b64encode(raw).decode(), len(raw)))
        if route.endswith("/456"):
            return asdict(
                replace(
                    manifest.Run(),
                    id=456,
                    run_number=3,
                    event="push",
                    head_sha="c" * 40,
                    head_branch="main",
                )
            )
        if route.endswith("/3"):
            return asdict(replace(manifest.Run(), id=3, run_number=3))
        raise AssertionError(f"unexpected observation: {stage} {route}")

    def test_real_host_archives_and_source_profile_admit_exact_28_file_publication(self) -> None:
        with (
            patch.object(legacy, "observe", side_effect=self.observe),
            patch.object(legacy, "tree", return_value=self.identity.sourceTree),
            patch.object(
                legacy,
                "admit_validation",
                return_value=ProductValidated(manifest.validation_record()),
            ),
        ):
            result = publisher.prepare_dual(
                "amichne/kast",
                self.identity.buildRevision,
                self.identity.version,
                self.payload,
                self.promotion_path,
            )
        self.assertIsInstance(result, publisher.PreparedPublication)
        assert isinstance(result, publisher.PreparedPublication)
        self.assertEqual(28, len(result.assets))
        self.assertIsNotNone(result.paired)
        # The old reader still admits these same twenty bytes, including the dual SBOM.
        projected = self.root / "legacy-reader"
        (projected / "payload").mkdir(parents=True)
        shutil.copyfile(self.bundle / "candidate.json", projected / "candidate.json")
        for asset in self.old.assets:
            shutil.copyfile(self.payload / asset.name, projected / "payload" / asset.name)
        self.assertEqual(self.old, legacy.read(projected))

    def test_consistent_candidate_record_and_zip_pins_cannot_override_source_profile(self) -> None:
        self.profile = replace(
            self.profile,
            targets=(
                self.profile.targets[0],
                replace(
                    self.profile.targets[1],
                    ideBuild="263.123.46",
                    kotlinPluginBuild="263.123.46-IJ",
                ),
            ),
        )
        with (
            patch.object(legacy, "observe", side_effect=self.observe),
            self.assertRaises(legacy.Rejected),
        ):
            legacy.read_retained(self.bundle)

    def test_other_candidate_promotion_cannot_be_published_with_the_pair(self) -> None:
        path = self.root / "promotion-v2.json"
        bad = replace(
            self.promotion, schemaVersion=2, candidateSha256=self.promotion.candidateSha256
        )
        path.write_text(json.dumps(asdict(bad)))
        with (
            patch.object(legacy, "observe", side_effect=self.observe),
            patch.object(legacy, "tree", return_value=self.identity.sourceTree),
            patch.object(
                legacy,
                "admit_validation",
                return_value=ProductValidated(manifest.validation_record()),
            ),
        ):
            result = publisher.prepare_dual(
                "amichne/kast",
                self.identity.buildRevision,
                self.identity.version,
                self.payload,
                self.promotion_path,
            )
        self.assertIsInstance(result, publisher.Rejected)


if __name__ == "__main__":
    unittest.main()
