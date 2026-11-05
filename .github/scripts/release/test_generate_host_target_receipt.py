"""Exercise clean-source sealing with real Git, SDK JARs and independent Host ZIP metadata."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from unittest.mock import patch

import generate_host_target_receipt as generator
import host_evidence as evidence
import host_staging as staging
import host_targets as hosts
import test_host_evidence as fixtures
import test_host_staging as records


@dataclass(frozen=True, slots=True)
class ProductInfo:
    buildNumber: str


class HostTargetReceiptTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.container = Path(temporary.name).resolve()
        self.root = self.container / "repo with spaces $(literal)"
        self.root.mkdir()
        self.git_environment = dict(
            os.environ,
            GIT_CONFIG_GLOBAL=os.devnull,
            GIT_CONFIG_NOSYSTEM="1",
            GIT_AUTHOR_NAME="Fixture",
            GIT_AUTHOR_EMAIL="fixture@example.invalid",
            GIT_COMMITTER_NAME="Fixture",
            GIT_COMMITTER_EMAIL="fixture@example.invalid",
        )
        self.profile = hosts.Profile(
            1,
            "262",
            tuple(
                hosts.SdkTarget(line, f"{line}.123.45", f"{line}.123.45-IJ") for line in hosts.LINES
            ),
        )
        for relative, content in (
            ("config/intellij-targets.json", json.dumps(asdict(self.profile)).encode()),
            (evidence.QUERY_SCHEMA_PATH, fixtures.QUERY_SCHEMA_BYTES),
            (evidence.OPERATION_REGISTRY_PATH, fixtures.OPERATION_REGISTRY_BYTES),
            (".gitignore", b"build/\n"),
            (
                ".github/scripts/release/admit-source.sh",
                Path(generator.__file__).with_name("admit-source.sh").read_bytes(),
            ),
        ):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        self.git("init", "-q")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "source fixture")
        self.identity = evidence.BuildIdentity(
            "0.0.3",
            self.git("rev-parse", "HEAD").strip(),
            self.git("rev-parse", "HEAD^{tree}").strip(),
        )
        self.fixture = fixtures.HostEvidenceTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.identity = self.identity
        self.request = self.prepare_target("263")

    def git(self, *arguments: str) -> str:
        result = subprocess.run(
            ["git", "-C", str(self.root), *arguments],
            capture_output=True,
            text=True,
            timeout=10,
            check=True,
            env=self.git_environment,
        )
        return result.stdout

    def prepare_target(self, line: str) -> generator.Request:
        target = next(
            target for target in self.profile.targets if target.intellijReleaseLine == line
        )
        self.fixture.target = replace(
            self.fixture.target,
            intellijReleaseLine=line,
            ideBuild=target.ideBuild,
            kotlinPluginBuild=target.kotlinPluginBuild,
        )
        sdk = self.container / f"SDK {line} $(literal)"
        sdk.mkdir(exist_ok=True)
        (sdk / "product-info.json").write_text(json.dumps(asdict(ProductInfo(target.ideBuild))))
        jar = sdk / "plugins/Kotlin/lib/kotlin-plugin.jar"
        jar.parent.mkdir(parents=True, exist_ok=True)
        self.plugin(jar, target.kotlinPluginBuild)
        self.fixture.archive_fixture(descriptor_line=line)
        output = self.root / "build/host-targets" / line
        output.mkdir(parents=True, exist_ok=True)
        archive = output / f"kast-ide-hosted-v0.0.3-idea-{line}.zip"
        shutil.copyfile(self.fixture.archive, archive)
        record = output / "component-release" / hosts.record_name("0.0.3", line)
        record.parent.mkdir(exist_ok=True)
        self.record(record, archive, line)
        contract = output / "hosted-contract.json"
        contract.write_text(json.dumps(asdict(self.fixture.contract)))
        return generator.Request(self.root, sdk, line, self.identity, archive, record, contract)

    @staticmethod
    def plugin(jar: Path, version: str) -> None:
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr(
                "META-INF/plugin.xml",
                "<idea-plugin><id>org.jetbrains.kotlin</id>"
                f"<version>{version}</version></idea-plugin>",
            )

    def record(self, record: Path, archive: Path, line: str) -> None:
        record.write_text(
            json.dumps(
                asdict(
                    records.HostRecord(
                        "HOST_RELEASE",
                        "0.0.3",
                        staging.Artifact(
                            archive.name,
                            "sha256:" + generator.candidate.digest(archive),
                            archive.stat().st_size,
                        ),
                        self.fixture.contract,
                        line,
                        self.identity.buildRevision,
                    )
                )
            )
        )

    def test_both_targets_seal_actual_sdk_hashes_and_independent_query_resource_identity(
        self,
    ) -> None:
        for line in hosts.LINES:
            with self.subTest(line=line):
                request = self.prepare_target(line)
                result = generator.write(request)
                self.assertIsInstance(result, staging.Receipt)
                self.assertNotIsInstance(
                    staging.decode(json.loads(request.output.read_bytes())),
                    generator.candidate.Failure,
                )
                receipt = json.loads(request.output.read_bytes())
                self.assertEqual("HOST_TARGET_BUILD", receipt["type"])
                self.assertEqual(line, receipt["intellijReleaseLine"])
                self.assertEqual(self.identity.buildRevision, receipt["sourceRevision"])
                self.assertEqual(self.identity.sourceTree, receipt["sourceTree"])
                self.assertEqual(
                    "sha256:"
                    + generator.candidate.digest(
                        request.sdk / "plugins/Kotlin/lib/kotlin-plugin.jar"
                    ),
                    receipt["sdk"]["kotlinPluginJarSha256"],
                )
                self.assertNotEqual(
                    self.fixture.resources.querySchemaSha256, self.fixture.contract.wireSchemaDigest
                )

    def test_dirty_source_removes_owned_prior_success_and_never_reads_sdk(self) -> None:
        self.assertIsInstance(generator.write(self.request), staging.Receipt)
        (self.root / ".gitignore").write_text("build/\n# modified source\n")
        with patch.object(
            generator, "sdk_evidence", side_effect=AssertionError("SDK read after source rejection")
        ):
            self.assertEqual(generator.Failure.SOURCE_REJECTED, generator.write(self.request))
        self.assertFalse(self.request.output.exists())

    def test_untracked_source_and_wrong_head_cannot_seal(self) -> None:
        (self.root / "new-source.kt").write_text("uncommitted")
        self.assertEqual(generator.Failure.SOURCE_REJECTED, generator.write(self.request))
        (self.root / "new-source.kt").unlink()
        request = replace(self.request, identity=replace(self.identity, buildRevision="a" * 40))
        self.assertEqual(generator.Failure.SOURCE_REJECTED, generator.write(request))
        self.assertFalse(self.request.output.exists())

    def test_wrong_sdk_product_or_kotlin_descriptor_rejects(self) -> None:
        metadata = self.request.sdk / "product-info.json"
        metadata.write_text(json.dumps(asdict(ProductInfo("262.123.45"))))
        self.assertEqual(generator.Failure.SDK_REJECTED, generator.write(self.request))
        metadata.write_text(json.dumps(asdict(ProductInfo("263.123.45"))))
        self.plugin(self.request.sdk / "plugins/Kotlin/lib/kotlin-plugin.jar", "263.123.46-IJ")
        self.assertEqual(generator.Failure.SDK_REJECTED, generator.write(self.request))
        self.assertFalse(self.request.output.exists())

    def test_ambiguous_vendor_metadata_and_duplicate_kotlin_version_cannot_seal(self) -> None:
        duplicate = self.request.sdk / "Resources/product-info.json"
        duplicate.parent.mkdir()
        duplicate.write_text(json.dumps(asdict(ProductInfo("263.123.45"))))
        self.assertEqual(generator.Failure.SDK_REJECTED, generator.write(self.request))
        duplicate.unlink()
        with zipfile.ZipFile(
            self.request.sdk / "plugins/Kotlin/lib/kotlin-plugin.jar", "w"
        ) as archive:
            archive.writestr(
                "META-INF/plugin.xml",
                "<idea-plugin><id>org.jetbrains.kotlin</id>"
                "<version>263.123.45-IJ</version><version>263.123.45-IJ</version></idea-plugin>",
            )
        self.assertEqual(generator.Failure.SDK_REJECTED, generator.write(self.request))
        self.assertFalse(self.request.output.exists())

    def test_oversized_record_rejects_before_component_parser_reads_it(self) -> None:
        self.request.record.write_bytes(b"x" * (evidence.consumer.MAXIMUM_RECORD_BYTES + 1))
        with patch.object(
            evidence.consumer,
            "parse_component",
            side_effect=AssertionError("oversized record was parsed"),
        ):
            self.assertEqual(generator.Failure.COMPONENT_REJECTED, generator.write(self.request))
        self.assertFalse(self.request.output.exists())

    def test_forged_archive_properties_are_rejected_after_record_digest_is_updated(self) -> None:
        self.fixture.archive_fixture(property_change="ideBuild=262.123.45", descriptor_line="263")
        shutil.copyfile(self.fixture.archive, self.request.archive)
        self.record(self.request.record, self.request.archive, "263")
        self.assertEqual(generator.Failure.ARCHIVE_REJECTED, generator.write(self.request))
        self.assertFalse(self.request.output.exists())

    def test_component_or_canonical_contract_substitution_rejects(self) -> None:
        self.request.contract.write_text(
            json.dumps(
                asdict(replace(self.fixture.contract, wireSchemaDigest="sha256:" + "e" * 64))
            )
        )
        self.assertEqual(generator.Failure.COMPONENT_REJECTED, generator.write(self.request))
        self.assertFalse(self.request.output.exists())

    def test_sdk_mutation_during_archive_inspection_cannot_reuse_earlier_hash(self) -> None:
        original = generator.evidence.inspect

        def mutate(
            archive: Path,
            target: hosts.HostTarget,
            identity: evidence.BuildIdentity,
            contract: evidence.consumer.HostedContract,
            resources: evidence.SourceResources,
        ) -> evidence.consumer.Admission | generator.candidate.Failure:
            result = original(archive, target, identity, contract, resources)
            plugin = self.request.sdk / "plugins/Kotlin/lib/kotlin-plugin.jar"
            plugin.write_bytes(plugin.read_bytes() + b"changed")
            return result

        with patch.object(generator.evidence, "inspect", side_effect=mutate):
            self.assertEqual(generator.Failure.INPUT_CHANGED, generator.write(self.request))
        self.assertFalse(self.request.output.exists())

    def test_source_change_during_archive_inspection_cannot_seal(self) -> None:
        original = generator.evidence.inspect

        def mutate(
            archive: Path,
            target: hosts.HostTarget,
            identity: evidence.BuildIdentity,
            contract: evidence.consumer.HostedContract,
            resources: evidence.SourceResources,
        ) -> evidence.consumer.Admission | generator.candidate.Failure:
            result = original(archive, target, identity, contract, resources)
            (self.root / "late-source.kt").write_text("uncommitted")
            return result

        with patch.object(generator.evidence, "inspect", side_effect=mutate):
            self.assertEqual(generator.Failure.SOURCE_REJECTED, generator.write(self.request))
        self.assertFalse(self.request.output.exists())

    def test_unsafe_target_and_symlinked_output_parent_never_delete_foreign_receipt(self) -> None:
        protected = self.container / "protected"
        protected.mkdir()
        receipt = protected / "target-receipt.json"
        receipt.write_text("preserve")
        invalid = replace(self.request, line="../../../protected")
        self.assertEqual(receipt, invalid.output.resolve())
        self.assertEqual(generator.Failure.INVALID_INPUT, generator.write(invalid))
        shutil.rmtree(self.request.output.parent)
        self.request.output.parent.symlink_to(protected, target_is_directory=True)
        self.assertEqual(generator.Failure.INVALID_INPUT, generator.write(self.request))
        self.assertEqual("preserve", receipt.read_text())

    def test_real_cli_uses_literal_paths_and_prints_finite_rejection_without_traceback(
        self,
    ) -> None:
        command = [
            sys.executable,
            generator.__file__,
            "--repository-root",
            str(self.root),
            "--sdk-home",
            str(self.request.sdk),
            "--target",
            "263",
            "--version",
            "0.0.3",
            "--source-revision",
            self.identity.buildRevision,
            "--source-tree",
            self.identity.sourceTree,
            "--archive",
            str(self.request.archive),
            "--release-record",
            str(self.request.record),
            "--hosted-contract",
            str(self.request.contract),
        ]
        passed = subprocess.run(command, capture_output=True, timeout=20, check=False)
        self.assertEqual(0, passed.returncode, passed.stderr.decode())
        self.assertEqual(
            {"type": "HOST_TARGET_BUILD_WRITTEN", "file": str(self.request.output)},
            json.loads(passed.stdout),
        )
        (self.root / "new-source.kt").write_text("uncommitted")
        rejected = subprocess.run(command, capture_output=True, timeout=20, check=False)
        self.assertEqual(1, rejected.returncode)
        self.assertEqual(
            {"type": "HOST_TARGET_BUILD_REJECTED", "reason": "SOURCE_REJECTED"},
            json.loads(rejected.stdout),
        )
        self.assertEqual(b"", rejected.stderr)
        self.assertFalse(self.request.output.exists())
