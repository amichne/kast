"""Scripted evidence tests admission rules, not native product qualification."""

from __future__ import annotations

import hashlib
import io
import json
import subprocess
import tarfile
import tempfile
import unittest
import zipfile
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from unittest.mock import patch

import runtime_base
from composition_platform import HostPlatform
from runtime_archive import RuntimeFile
from runtime_archive import RuntimeManifest as FullRuntimeManifest
from validation_outcome import Outcome, ValidationRecord
from validation_scope import Scope

import developer_catalog as catalog
import product_candidate as candidate
import publish_developer as publisher
from test_developer_publication import Release, RemoteAsset, Script, Step, response

product = catalog.product


@dataclass(frozen=True)
class RuntimeManifest:
    version: str
    platform: str
    schemaVersion: int = 1
    kind: str = "KAST_RUNTIME_V1"


@dataclass(frozen=True)
class BundleManifest:
    version: str
    platform: str
    sourceDistributionSha256: str = "4" * 64
    schemaVersion: int = 2
    kind: str = "KAST_COMPOSED_SETUP_V2"


@dataclass(frozen=True)
class ContainerReceipt:
    runtimeSha256: str
    controlSha256: str = "8" * 64


@dataclass(frozen=True)
class Probe:
    ordinaryQueryGrantAbsent: bool = True


@dataclass(frozen=True)
class Receipt:
    bundleSha256: str
    containers: ContainerReceipt
    sourceRevision: str = "a" * 40
    qualification: str = "COMPLETE"
    qualificationStage: str = "COMPONENT_NATIVE"
    harnessSha256: str = "d" * 64
    probe: Probe = Probe()
    composition: product.NativeCompositionEvidence | None = None


@dataclass(frozen=True)
class Repository:
    full_name: str = "amichne/kast"


@dataclass(frozen=True)
class Run:
    id: int = 123
    run_attempt: int = 1
    run_number: int = 123
    path: str = ".github/workflows/ci.yml"
    head_sha: str = "a" * 40
    head_branch: str = "main"
    event: str = "push"
    status: str = "completed"
    conclusion: str = "success"
    repository: Repository = Repository()


@dataclass(frozen=True)
class Artifact:
    name: str
    expired: bool = False


@dataclass(frozen=True)
class Artifacts:
    artifacts: list[Artifact]
    total_count: int


def fixture(
    directory: Path, bundle_manifest: str = "setup-kast/manifest.json"
) -> product.ProductRecord:
    payload = directory / "payload"
    payload.mkdir(parents=True)
    targets: list[product.ProductTarget] = []

    def asset(path: Path) -> product.ProductAsset:
        return product.ProductAsset(path.name, product.file_digest(path), path.stat().st_size)

    for target in ("linux-x86_64", "macos-aarch64"):
        bundle = payload / f"kast-composed-setup-v0.0.123-{target}.tar.gz"
        raw = json.dumps(asdict(BundleManifest("0.0.123", target))).encode()
        with tarfile.open(bundle, "w:gz") as archive:
            member = tarfile.TarInfo(bundle_manifest)
            member.size = len(raw)
            archive.addfile(member, io.BytesIO(raw))
        runtime = payload / f"kast-runtime-v0.0.123-{target}.zip"
        with zipfile.ZipFile(runtime, "w") as archive:
            archive.writestr(
                "manifest.json", json.dumps(asdict(RuntimeManifest("0.0.123", target)))
            )
        bundle_asset, runtime_asset = asset(bundle), asset(runtime)
        receipt = payload / f"qualification-{target}.json"
        _ = receipt.write_text(
            json.dumps(asdict(Receipt(bundle_asset.sha256, ContainerReceipt(runtime_asset.sha256))))
        )
        targets.append(
            product.ProductTarget(
                target,
                product.ProductInputs("1" * 64, "2" * 64, "3" * 64, "4" * 64),
                bundle_asset,
                runtime_asset,
                product.NativeQualification(
                    asset(receipt),
                    product.ProductProducer(456, 2, ".github/workflows/composed-linux.yml"),
                    bundle_asset.sha256,
                    runtime_asset.sha256,
                    "8" * 64,
                ),
            )
        )
    record = product.ProductRecord(
        2,
        "amichne/kast",
        "a" * 40,
        "b" * 40,
        "0.0.123",
        product.ProductProducer(123, 1, ".github/workflows/ci.yml"),
        tuple(targets),
    )
    _ = (directory / "build-record.json").write_text(json.dumps(asdict(record)))
    return record


def accepted_fixture(directory: Path, record: product.ProductRecord) -> None:
    """Scripted receipts exercise admission only, never native qualification."""
    acceptance = candidate.acceptance
    for target in record.targets:
        receipt = acceptance.Receipt(
            product.file_digest(directory / "build-record.json"),
            record.sourceRevision,
            target.target,
            target.qualification.controlSha256,
            record.producer,
            "e" * 64,
            "f" * 40,
            "1" * 64,
            "2" * 64,
            "3" * 64,
            tuple(acceptance.Case),
        )
        _ = (directory / "payload" / acceptance.filename(target.target)).write_bytes(
            acceptance.encode(receipt)
        )


def split_fixture(directory: Path) -> product.ProductRecord:
    original = fixture(directory)
    payload = directory / "payload"
    targets: list[product.ProductTarget] = []
    for target in original.targets:
        platform = HostPlatform(target.target)
        prefix = runtime_base.prefix(platform)
        vendor = RuntimeFile(prefix + "/bin/java", 6, hashlib.sha256(b"vendor").hexdigest(), True)
        base_manifest = runtime_base.BaseManifest(
            target.target,
            "4" * 64,
            tuple(
                runtime_base.RecipeInput(name, hashlib.sha256(b"sealed input\n").hexdigest())
                for name in runtime_base.RECIPE_INPUTS
            ),
            (),
            (vendor,),
        )
        temporary = payload / "base.zip"
        runtime_base.write_zip(
            temporary,
            (
                (runtime_base.BASE_MANIFEST, runtime_base.encoded(base_manifest), 0o100444),
                (vendor.path, b"vendor", 0o100555),
            ),
        )
        base_digest = product.file_digest(temporary)
        base = product.ProductAsset(
            f"kast-runtime-base-{target.target}-{base_digest}.zip",
            base_digest,
            temporary.stat().st_size,
        )
        _ = temporary.rename(payload / base.name)
        marker = runtime_base.Composition(target.target, base)
        boot = RuntimeFile(
            prefix + "/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar",
            4,
            hashlib.sha256(b"boot").hexdigest(),
            False,
        )
        host = RuntimeFile(
            "components/plugins/kast-ide-hosted/lib/host.jar",
            4,
            hashlib.sha256(b"host").hexdigest(),
            False,
        )
        manifest = FullRuntimeManifest(
            "0.0.123", platform, "262.1", "4" * 64, boot.sha256, (vendor, boot, host)
        )
        with zipfile.ZipFile(payload / target.runtime.name, "w") as archive:
            archive.writestr("manifest.json", json.dumps(asdict(manifest)))
            archive.writestr(runtime_base.MARKER, runtime_base.encoded(marker))
            archive.writestr(boot.path, b"boot")
            archive.writestr(host.path, b"host")
        with tarfile.open(payload / target.bundle.name, "w:gz") as archive:
            for name, raw in (
                (
                    "manifest.json",
                    json.dumps(asdict(BundleManifest("0.0.123", target.target))).encode(),
                ),
                (runtime_base.MARKER, runtime_base.encoded(marker)),
            ):
                member = tarfile.TarInfo("setup-kast/" + name)
                member.size = len(raw)
                archive.addfile(member, io.BytesIO(raw))

        def asset(name: str) -> product.ProductAsset:
            path = payload / name
            return product.ProductAsset(name, product.file_digest(path), path.stat().st_size)

        bundle_asset, runtime_asset = asset(target.bundle.name), asset(target.runtime.name)
        witness = product.NativeCompositionEvidence(
            base.sha256, "6" * 64, "7" * 64, runtime_asset.sha256
        )
        receipt = Receipt(
            bundle_asset.sha256, ContainerReceipt(witness.nativeRuntimeSha256), composition=witness
        )
        _ = (payload / target.qualification.receipt.name).write_text(json.dumps(asdict(receipt)))
        targets.append(
            replace(
                target,
                inputs=replace(
                    target.inputs,
                    compositionRecipeSha256=hashlib.sha256(b"sealed input\n").hexdigest(),
                ),
                bundle=bundle_asset,
                runtime=runtime_asset,
                runtimeBase=base,
                qualification=replace(
                    target.qualification,
                    receipt=asset(target.qualification.receipt.name),
                    bundleSha256=bundle_asset.sha256,
                    runtimeSha256=runtime_asset.sha256,
                    composition=witness,
                ),
            )
        )
    record = replace(original, schemaVersion=3, targets=tuple(targets))
    _ = (directory / "build-record.json").write_text(json.dumps(asdict(record)))
    return record


class ProductCandidateTest(unittest.TestCase):
    def test_component_candidate_cannot_be_promoted_without_installed_acceptance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "candidate"
            record = fixture(directory)
            self.assertEqual(record, candidate.read(directory))
            self.assertIsInstance(
                publisher.prepare_stable(
                    directory, "1.2.3", product.file_digest(directory / "build-record.json")
                ),
                publisher.Rejected,
            )
            self.assertFalse((directory.parent / "release-mapping.json").exists())

    def test_split_product_requires_both_compositions_and_retained_base_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            record = split_fixture(directory)
            with (
                patch.object(
                    runtime_base,
                    "project_native",
                    side_effect=AssertionError("promotion cannot compose"),
                ),
                patch.object(
                    runtime_base,
                    "extract_base",
                    side_effect=AssertionError("promotion cannot extract"),
                ),
            ):
                self.assertEqual(record, candidate.read(directory))
            target = record.targets[0]
            assert target.runtimeBase is not None
            (directory / "payload" / target.runtimeBase.name).unlink()
            self.assertEqual(
                candidate.candidate.Failure.PAYLOAD_MISMATCH, candidate.read(directory)
            )
            incomplete = replace(
                target, qualification=replace(target.qualification, composition=None)
            )
            self.assertIsInstance(
                product.decode_product_record(
                    json.dumps(
                        asdict(replace(record, targets=(incomplete, record.targets[1])))
                    ).encode()
                ),
                product.Failure,
            )

    def test_root_level_bundle_manifest_is_not_the_composed_archive_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "candidate"
            _ = fixture(directory, "manifest.json")
            self.assertEqual(candidate.candidate.Failure.INVALID_RECORD, candidate.read(directory))

    def test_retained_inventory_and_native_receipt_bind_the_same_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "candidate"
            record = fixture(directory)
            self.assertEqual(record, candidate.read(directory))
            runtime = directory / "payload" / record.targets[0].runtime.name
            _ = runtime.write_bytes(b"other runtime")
            self.assertEqual(
                candidate.candidate.Failure.PAYLOAD_MISMATCH, candidate.read(directory)
            )

    def test_rebound_runtime_digest_cannot_change_qualification(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "candidate"
            record = fixture(directory)
            target = record.targets[0]
            changed = replace(
                target, qualification=replace(target.qualification, controlSha256="9" * 64)
            )
            _ = (directory / "build-record.json").write_text(
                json.dumps(asdict(replace(record, targets=(changed, record.targets[1]))))
            )
            self.assertEqual(
                candidate.candidate.Failure.PAYLOAD_MISMATCH, candidate.read(directory)
            )

    def test_untrusted_attempt_source_workflow_event_and_failed_producer_reject(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            record = fixture(Path(temporary) / "candidate")
            self.assertEqual(
                product.Admission.ADMITTED,
                candidate.admit_producer(record, record.producer, asdict(Run())),
            )
            for run in (
                replace(Run(), run_attempt=2),
                replace(Run(), head_sha="c" * 40),
                replace(Run(), event="pull_request"),
                replace(Run(), path="other.yml"),
                replace(Run(), repository=Repository("foreign/kast")),
                replace(Run(), conclusion="failure"),
            ):
                with self.subTest(run=run):
                    self.assertIsInstance(
                        candidate.admit_producer(record, record.producer, asdict(run)),
                        candidate.candidate.Failure,
                    )

    def test_missing_record_is_not_a_complete_product_and_has_no_remote_effect(self) -> None:
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(candidate, "observe") as remote,
        ):
            self.assertIsInstance(candidate.admit(Path(temporary)), candidate.candidate.Failure)
            remote.assert_not_called()

    def test_stable_promotion_only_adds_mapping_and_retries_same_sealed_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "candidate"
            record = fixture(directory)
            accepted_fixture(directory, record)
            original = {path.name: path.read_bytes() for path in (directory / "payload").iterdir()}
            record_bytes = (directory / "build-record.json").read_bytes()
            digest = hashlib.sha256(record_bytes).hexdigest()
            first = publisher.prepare_stable(directory, "1.2.3", digest)
            assert isinstance(first, publisher.PreparedPublication)
            self.assertEqual("v1.2.3", first.build.releaseTag)
            self.assertEqual("0.0.123", first.build.version)
            self.assertEqual(first, publisher.prepare_stable(directory, "1.2.3", digest))
            self.assertEqual(
                {"schemaVersion": 1, "publicVersion": "1.2.3", "buildRecordSha256": digest},
                json.loads((directory.parent / "release-mapping.json").read_bytes()),
            )
            self.assertEqual(record_bytes, (directory / "build-record.json").read_bytes())
            self.assertEqual(
                original,
                {path.name: path.read_bytes() for path in (directory / "payload").iterdir()},
            )
            self.assertIsInstance(
                publisher.prepare_stable(directory, "1.2.4", digest), publisher.Rejected
            )
            (directory / "payload" / record.targets[1].runtime.name).unlink()
            self.assertIsInstance(
                publisher.prepare_stable(directory, "1.2.3", digest), publisher.Rejected
            )

    def test_successful_runs_require_the_exact_retained_record_and_target_bytes(self) -> None:
        def run_case(fault: str) -> None:
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary) / "candidate"
                record = fixture(directory)
                accepted_fixture(directory, record)
                ci_name = f"ci-product-candidate-{'a' * 40}-1"
                validation_name = f"ci-validation-outcome-{'a' * 40}-1"
                validation = ValidationRecord(
                    1,
                    Outcome.SOURCE_VALIDATED
                    if fault == "source-only"
                    else Outcome.PRODUCT_VALIDATED,
                    Scope.SOURCE_FULL if fault == "source-only" else Scope.FULL,
                    "amichne/kast",
                    "a" * 40,
                    "b" * 40,
                    "c" * 40,
                    123,
                    123,
                    1,
                    "push",
                )
                retained = {
                    validation_name: {"validation.json": json.dumps(asdict(validation)).encode()},
                    ci_name: {
                        "build-record.json": (directory / "build-record.json").read_bytes(),
                        **{
                            f"payload/{asset.name}": (
                                directory / "payload" / asset.name
                            ).read_bytes()
                            for asset in candidate.acceptance.assets(directory, record)
                        },
                    },
                }
                for target in record.targets:
                    name = f"kast-qualified-product-{target.target}-{'a' * 40}-456-2"
                    retained[name] = {
                        asset.name: (directory / "payload" / asset.name).read_bytes()
                        for asset in (target.bundle, target.runtime, target.qualification.receipt)
                    }
                if fault == "toolchain":
                    first = record.targets[0]
                    changed = replace(
                        first, inputs=replace(first.inputs, javaReleaseSha256="9" * 64)
                    )
                    _ = (directory / "build-record.json").write_text(
                        json.dumps(asdict(replace(record, targets=(changed, record.targets[1]))))
                    )
                    accepted_fixture(directory, record)
                if fault == "acceptance":
                    retained[ci_name]["payload/acceptance-linux-x86_64.json"] = b"other receipt"
                downloads = (
                    list(retained)
                    if fault in ("none", "missing-native")
                    else (
                        [validation_name, ci_name]
                        if fault in ("toolchain", "acceptance")
                        else [validation_name]
                    )
                )
                if fault == "missing-native":
                    retained[downloads[-1]].pop(record.targets[-1].runtime.name)
                observations = (
                    [123, 123, 456, 456]
                    if fault in ("none", "missing-native")
                    else [123]
                    if fault == "source-only"
                    else [123, 123]
                )

                def observe_artifacts(
                    route: str, stage: candidate.candidate.ObservationStage
                ) -> object:
                    self.assertEqual(candidate.candidate.ObservationStage.RUN_ARTIFACTS, stage)
                    run_id = observations.pop(0)
                    self.assertEqual(
                        f"repos/amichne/kast/actions/runs/{run_id}/artifacts?per_page=100", route
                    )
                    names = [validation_name, ci_name] if run_id == 123 else list(retained)[2:]
                    return asdict(
                        Artifacts(
                            [
                                Artifact(name, fault == "expired" and name == ci_name)
                                for name in names
                            ],
                            len(names),
                        )
                    )

                def download(
                    arguments: list[str], *, check: bool, timeout: int, capture_output: bool = False
                ) -> subprocess.CompletedProcess[bytes]:
                    name = downloads.pop(0)
                    self.assertEqual(
                        [
                            "gh",
                            "run",
                            "download",
                            "123" if name in (validation_name, ci_name) else "456",
                            "--repo",
                            "amichne/kast",
                            "--name",
                            name,
                            "--dir",
                        ],
                        arguments[:-1],
                    )
                    self.assertEqual(
                        (False, 180, name != validation_name), (check, timeout, capture_output)
                    )
                    for filename, raw in retained[name].items():
                        path = Path(arguments[-1]) / filename
                        path.parent.mkdir(parents=True, exist_ok=True)
                        _ = path.write_bytes(raw)
                    return subprocess.CompletedProcess(arguments, 0, b"", b"")

                runs = [
                    Run(),
                    replace(
                        Run(), id=456, run_attempt=2, path=".github/workflows/composed-linux.yml"
                    ),
                ]
                expected_runs = [123] if fault == "source-only" else [123, 456, 456]

                def observe_run(producer: product.ProductProducer) -> object:
                    self.assertEqual(expected_runs.pop(0), producer.runId)
                    return asdict(runs[0 if producer.runId == 123 else 1])

                with (
                    patch.object(candidate.candidate, "tree", return_value="b" * 40) as tree,
                    patch.object(candidate.candidate, "observe", side_effect=observe_artifacts),
                    patch.object(candidate.subprocess, "run", side_effect=download),
                ):
                    result = candidate.admit(directory, observe_run)
                if fault == "source-only":
                    tree.assert_not_called()
                else:
                    tree.assert_called_once_with("amichne/kast", "a" * 40)
                self.assertEqual([], observations)
                self.assertEqual([], downloads)
                self.assertEqual([], expected_runs)
                self.assertEqual(
                    record
                    if fault == "none"
                    else (
                        candidate.candidate.Failure.INVALID_RECORD
                        if fault == "expired"
                        else candidate.candidate.Failure.SOURCE_NOT_QUALIFIED
                        if fault == "source-only"
                        else candidate.candidate.Failure.PAYLOAD_MISMATCH
                    ),
                    result,
                )

        for fault in (
            "none",
            "toolchain",
            "acceptance",
            "expired",
            "missing-native",
            "source-only",
        ):
            run_case(fault)

    def test_stable_publication_retry_reads_exact_inventory_without_catalog_or_build_effects(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "candidate"
            record = fixture(directory)
            accepted_fixture(directory, record)
            prepared = publisher.prepare_stable(
                directory, "1.2.3", product.file_digest(directory / "build-record.json")
            )
            assert isinstance(prepared, publisher.PreparedPublication)
            assets = tuple(
                RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
                for asset in prepared.assets
            )
            document = Release("v1.2.3", "a" * 40, assets, prerelease=False)
            script = Script(Step("GET", "releases/tags/v1.2.3", response(document)))
            result = publisher.publish(
                prepared, publisher.RemotePublisher("amichne/kast", prepared.build, script.run)
            )
            self.assertEqual(catalog.CommitResult.COMMITTED, result)
            script.consumed()

    def test_catalog_preserves_complete_targets_and_keeps_legacy_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "candidate"
            record = fixture(directory)
            entry = catalog.ProductBuild(
                "a" * 40,
                "developer-v0.0.123",
                record,
                product.file_digest(directory / "build-record.json"),
            )
            document = catalog.Catalog(2, (entry,))
            self.assertEqual(document, catalog.decode(catalog.encode(document)))
            self.assertEqual(document, catalog.merge(catalog.Catalog(1, ()), entry, ("a" * 40,)))
            self.assertFalse(catalog.valid(replace(entry, mainRevision="c" * 40)))


if __name__ == "__main__":
    unittest.main()
