"""Fixture bytes exercise sealing; these tests are not native qualification."""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path

import product_candidate as candidate
import product_producer as producer
from test_product_candidate import Run, accepted_fixture, fixture, split_fixture

product = producer.product


@dataclass(frozen=True)
class WorkflowReference:
    path: str = "amichne/kast/.github/workflows/composed-linux.yml@refs/heads/main"
    sha: str = "a" * 40
    ref: str = "refs/heads/main"


@dataclass(frozen=True)
class ReusableRun(Run):
    referenced_workflows: list[WorkflowReference] = field(
        default_factory=lambda: [WorkflowReference()]
    )


@dataclass(frozen=True)
class SealJob:
    name: str = "Seal both native targets without rebuilding"
    run_id: int = 123
    head_sha: str = "a" * 40
    status: str = "completed"
    conclusion: str = "success"


@dataclass(frozen=True)
class Jobs:
    jobs: tuple[SealJob, ...] = (SealJob(),)
    total_count: int = 1


@dataclass(frozen=True)
class AssemblyReceipt:
    sourceRevision: str
    bundleSha256: str
    runtimeSha256: str
    controlSha256: str
    composition: product.NativeCompositionEvidence | None = None
    schemaVersion: int = 2
    qualificationStage: str = "COMPONENT_ASSEMBLY"
    qualification: str = "COMPLETE"


def captured(root: Path, *, split: bool = False) -> tuple[Path, tuple[producer.TargetRecord, ...]]:
    original = root / "original"
    record = split_fixture(original) if split else fixture(original)
    repository = root / "repository"
    # Independently named input bytes make the expected hashes explicit.
    for name in (
        "gradle/libs.versions.toml",
        "gradle/verification-metadata.xml",
        "gradle/wrapper/gradle-wrapper.properties",
        "mise.toml",
        "packaging/uv.lock",
        "packaging/internal/composition/composition_recipe.py",
    ):
        path = repository / name
        path.parent.mkdir(parents=True, exist_ok=True)
        _ = path.write_bytes(b"sealed input\n")
    java = root / "java-release"
    _ = java.write_bytes(b"JAVA_VERSION=25\n")
    inputs = root / "inputs"
    control = root / "control"
    _ = control.write_bytes(b"independently produced Control\n")
    records: list[producer.TargetRecord] = []
    for target in record.targets:
        destination = inputs / target.target
        destination.mkdir(parents=True)
        for asset in target.assets():
            if asset == target.qualification.receipt:
                continue
            _ = shutil.copyfile(original / "payload" / asset.name, destination / asset.name)
        receipt = root / f"assembly-{target.target}.json"
        _ = receipt.write_text(
            json.dumps(
                asdict(
                    AssemblyReceipt(
                        "a" * 40,
                        target.bundle.sha256,
                        target.qualification.composition.nativeRuntimeSha256
                        if target.qualification.composition
                        else target.runtime.sha256,
                        product.file_digest(control),
                        composition=target.qualification.composition,
                    )
                )
            )
        )
        records.append(
            producer.seal_target(
                repository,
                destination,
                receipt,
                java,
                "a" * 40,
                "b" * 40,
                "0.0.123",
                product.ProductProducer(123, 1, ".github/workflows/composed-linux.yml"),
                target.target,
                target.target,
                control=control,
            )
        )
    return inputs, tuple(records)


class ProductProducerTest(unittest.TestCase):
    def test_assembly_baseline_needs_installed_acceptance_but_not_publication(
        self,
    ) -> None:
        import json

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs, _ = captured(root)
            directory = root / "sealed"
            record = producer.seal(
                inputs,
                directory,
                "a" * 40,
                "b" * 40,
                "0.0.123",
                product.ProductProducer(123, 1, producer.CI_WORKFLOW),
            )
            digest = product.file_digest(directory / "build-record.json")
            run = asdict(ReusableRun(conclusion="failure"))
            jobs = product.document(json.dumps(asdict(Jobs())).encode())
            self.assertEqual(
                producer.Failure.GATE_NOT_COMPLETE,
                candidate.admit_baseline(directory, digest, "a" * 40, record.producer, run, jobs),
            )
            accepted_fixture(directory, record)
            self.assertEqual(
                record,
                candidate.admit_baseline(directory, digest, "a" * 40, record.producer, run, jobs),
            )
            self.assertEqual(record, candidate.read(directory, accepted=True))
            for observed in (
                Jobs((SealJob(conclusion="failure"),)),
                Jobs(total_count=2),
                Jobs((SealJob(run_id=124),)),
                Jobs((SealJob(head_sha="c" * 40),)),
            ):
                with self.subTest(observed=observed):
                    self.assertIsInstance(
                        candidate.admit_baseline(
                            directory,
                            digest,
                            "a" * 40,
                            record.producer,
                            run,
                            product.document(json.dumps(asdict(observed)).encode()),
                        ),
                        producer.Failure,
                    )
            self.assertEqual(
                producer.Failure.WRONG_PRODUCER,
                candidate.admit_baseline(
                    directory,
                    "9" * 64,
                    "a" * 40,
                    record.producer,
                    run,
                    jobs,
                ),
            )

    def test_acceptance_join_preserves_record_and_rejects_partial_or_rebound_evidence(self) -> None:
        for fault in (
            "none",
            "missing",
            "record",
            "attempt",
            "control",
            "target",
            "cases",
            "baseline",
            "different-baselines",
        ):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = root / "sealed"
                record = fixture(directory)
                original = (directory / "build-record.json").read_bytes()
                accepted_fixture(directory, record)
                receipts = root / "receipts"
                receipts.mkdir()
                for target in product.PRODUCT_TARGETS:
                    name = candidate.acceptance.filename(target)
                    _ = (directory / "payload" / name).rename(receipts / name)
                path = receipts / "acceptance-linux-x86_64.json"
                receipt = candidate.acceptance.decode(
                    path.read_bytes(), record, hashlib.sha256(original).hexdigest(), "linux-x86_64"
                )
                changed = {
                    "none": receipt,
                    "missing": receipt,
                    "record": replace(receipt, buildRecordSha256="9" * 64),
                    "attempt": replace(receipt, producer=replace(receipt.producer, runAttempt=2)),
                    "control": replace(receipt, controlSha256="9" * 64),
                    "target": replace(receipt, target="macos-aarch64"),
                    "cases": replace(receipt, completedCases=receipt.completedCases[:-1]),
                    "baseline": replace(receipt, baselineRecordSha256=receipt.buildRecordSha256),
                    "different-baselines": replace(receipt, baselineRecordSha256="3" * 64),
                }[fault]
                _ = path.write_bytes(candidate.acceptance.encode(changed))
                if fault == "missing":
                    path.unlink()
                output = root / "accepted"
                if fault == "none":
                    self.assertEqual(record, producer.accept(directory, receipts, output))
                    self.assertEqual(original, (output / "build-record.json").read_bytes())
                    self.assertEqual(record, candidate.read(output, accepted=True))
                    for receipt_path in receipts.iterdir():
                        self.assertEqual(
                            receipt_path.read_bytes(),
                            (output / "payload" / receipt_path.name).read_bytes(),
                        )
                else:
                    with self.assertRaises(producer.Rejected):
                        _ = producer.accept(directory, receipts, output)
                    self.assertFalse(output.exists())
                self.assertEqual(original, (directory / "build-record.json").read_bytes())

    def test_macos_only_candidate_needs_the_mac_receipt_and_retains_legacy_target_rules(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs, _ = captured(root, split=True)
            (inputs / "linux-x86_64").rename(root / "excluded-linux")
            output = root / "sealed"
            owner = product.ProductProducer(123, 1, producer.CI_WORKFLOW)
            with self.assertRaises(producer.Rejected):
                producer.seal(inputs, output, "a" * 40, "b" * 40, "0.0.123", owner)
            record = producer.seal(
                inputs, output, "a" * 40, "b" * 40, "0.0.123", owner, macos_only=True
            )
            self.assertEqual(5, record.schemaVersion)
            self.assertEqual(["macos-aarch64"], [item.target for item in record.targets])
            self.assertEqual(record, candidate.read(output))
            receipts = root / "receipts"
            receipts.mkdir()
            with self.assertRaises(producer.Rejected):
                producer.accept(output, receipts, root / "accepted")
            accepted_fixture(output, record)
            name = candidate.acceptance.filename("macos-aarch64")
            (output / "payload" / name).rename(receipts / name)
            self.assertEqual(record, producer.accept(output, receipts, root / "accepted"))
            self.assertEqual(record, candidate.read(root / "accepted", accepted=True))

    def test_split_targets_seal_without_assembling_or_substituting_base_qualification(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs, targets = captured(root, split=True)
            output = root / "complete"
            sealed = producer.seal(
                inputs,
                output,
                "a" * 40,
                "b" * 40,
                "0.0.123",
                product.ProductProducer(123, 1, ".github/workflows/ci.yml"),
            )
            self.assertEqual(3, sealed.schemaVersion)
            self.assertEqual(tuple(item.target for item in targets), sealed.targets)
            self.assertEqual(
                hashlib.sha256(b"independently produced Control\n").hexdigest(),
                sealed.targets[0].qualification.controlSha256,
            )
            self.assertEqual(8, len(sealed.assets()))
            self.assertEqual(sealed, candidate.read(output))
            assert sealed.targets[0].runtimeBase is not None
            (inputs / sealed.targets[0].target / sealed.targets[0].runtimeBase.name).unlink()
            with self.assertRaises(producer.Rejected):
                _ = producer.seal(
                    inputs,
                    root / "missing",
                    "a" * 40,
                    "b" * 40,
                    "0.0.123",
                    product.ProductProducer(123, 1, ".github/workflows/ci.yml"),
                )
            self.assertFalse((root / "missing").exists())

    def test_both_native_targets_seal_exact_bytes_and_toolchain_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs, targets = captured(root)
            before = {
                path.relative_to(inputs): path.read_bytes()
                for path in inputs.rglob("*")
                if path.is_file()
            }
            output = root / "complete"
            sealed = producer.seal(
                inputs,
                output,
                "a" * 40,
                "b" * 40,
                "0.0.123",
                product.ProductProducer(123, 1, ".github/workflows/ci.yml"),
            )
            self.assertEqual(
                ["linux-x86_64", "macos-aarch64"], [target.target for target in sealed.targets]
            )
            self.assertEqual(tuple(item.target for item in targets), sealed.targets)
            self.assertEqual(
                hashlib.sha256(b"JAVA_VERSION=25\n").hexdigest(),
                sealed.targets[0].inputs.javaReleaseSha256,
            )
            self.assertEqual(
                hashlib.sha256(b"sealed input\n").hexdigest(),
                sealed.targets[1].inputs.compositionRecipeSha256,
            )
            self.assertEqual(sealed, candidate.read(output))
            self.assertEqual(
                {"build-record.json", "payload"}, {path.name for path in output.iterdir()}
            )
            for item in sealed.assets():
                self.assertEqual(
                    next(inputs.glob(f"*/{item.name}")).read_bytes(),
                    (output / "payload" / item.name).read_bytes(),
                )
            self.assertEqual(
                before,
                {
                    path.relative_to(inputs): path.read_bytes()
                    for path in inputs.rglob("*")
                    if path.is_file()
                },
            )
            record_bytes = (output / "build-record.json").read_bytes()
            with self.assertRaises(producer.Rejected) as retry:
                _ = producer.seal(inputs, output, "a" * 40, "b" * 40, "0.0.123", sealed.producer)
            self.assertEqual(producer.Failure.DESTINATION_NOT_EMPTY, retry.exception.failure)
            self.assertEqual(record_bytes, (output / "build-record.json").read_bytes())

    def test_partial_changed_or_foreign_target_never_exposes_complete_output(self) -> None:
        for fault in (
            "missing-target",
            "missing-runtime",
            "changed-runtime",
            "attempt",
            "source",
            "recipe",
        ):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                inputs, records = captured(root)
                target = records[1]
                directory = inputs / "macos-aarch64"
                if fault == "missing-target":
                    shutil.rmtree(directory)
                elif fault == "missing-runtime":
                    (directory / target.target.runtime.name).unlink()
                elif fault == "changed-runtime":
                    _ = (directory / target.target.runtime.name).write_bytes(
                        b"unqualified replacement"
                    )
                else:
                    changed = (
                        replace(target, sourceRevision="c" * 40)
                        if fault == "source"
                        else replace(
                            target,
                            target=replace(
                                target.target,
                                qualification=replace(
                                    target.target.qualification,
                                    producer=replace(
                                        target.target.qualification.producer, runAttempt=2
                                    ),
                                ),
                            ),
                        )
                        if fault == "attempt"
                        else replace(
                            target,
                            target=replace(
                                target.target,
                                inputs=replace(
                                    target.target.inputs, compositionRecipeSha256="9" * 64
                                ),
                            ),
                        )
                    )
                    _ = (directory / "target-record-macos-aarch64.json").write_bytes(
                        producer.encoded(changed)
                    )
                output = root / "complete"
                with self.assertRaises(producer.Rejected):
                    _ = producer.seal(
                        inputs,
                        output,
                        "a" * 40,
                        "b" * 40,
                        "0.0.123",
                        product.ProductProducer(123, 1, ".github/workflows/ci.yml"),
                    )
                self.assertFalse(output.exists())
                self.assertEqual([], list(root.glob("kast-product-seal-*")))

    def test_target_capture_rejects_control_bytes_different_from_the_assembly_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs, _ = captured(root)
            target = "linux-x86_64"
            _ = (root / "control").write_bytes(b"different produced Control\n")
            with self.assertRaises(producer.Rejected) as rejection:
                _ = producer.seal_target(
                    root / "repository",
                    inputs / target,
                    root / f"assembly-{target}.json",
                    root / "java-release",
                    "a" * 40,
                    "b" * 40,
                    "0.0.123",
                    product.ProductProducer(123, 1, producer.NATIVE_WORKFLOW),
                    target,
                    target,
                    control=root / "control",
                )
            self.assertEqual(producer.Failure.PAYLOAD_MISMATCH, rejection.exception.failure)

    def test_incomplete_receipt_cannot_be_relabelled_by_target_capture(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            record = fixture(root / "candidate")
            receipt = root / "candidate/payload" / record.targets[0].qualification.receipt.name
            _ = receipt.write_bytes(receipt.read_bytes().replace(b'"COMPLETE"', b'"NOT_RUN"'))
            with self.assertRaises(producer.Rejected) as rejection:
                _ = producer.seal_target(
                    root,
                    root / "candidate/payload",
                    receipt,
                    root / "missing-java",
                    "a" * 40,
                    "b" * 40,
                    "0.0.123",
                    product.ProductProducer(123, 1, producer.NATIVE_WORKFLOW),
                    "linux-x86_64",
                    "linux-x86_64",
                    control=root / "missing-control",
                )
            self.assertEqual(producer.Failure.GATE_NOT_COMPLETE, rejection.exception.failure)
            self.assertFalse((root / "candidate/payload/target-record-linux-x86_64.json").exists())

    def test_legacy_or_installed_receipts_cannot_be_relabelled_as_assembly(self) -> None:
        for original, replacement in (
            (b'"COMPONENT_NATIVE"', b'"INSTALLED_PRODUCT_NATIVE"'),
            (b'"harnessSha256": "' + b"d" * 64 + b'"', b'"harnessSha256": "unknown"'),
        ):
            with self.subTest(replacement=replacement), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                record = fixture(root / "candidate")
                receipt = root / "candidate/payload" / record.targets[0].qualification.receipt.name
                self.assertIn(original, receipt.read_bytes())
                _ = receipt.write_bytes(receipt.read_bytes().replace(original, replacement))
                with self.assertRaises(producer.Rejected) as rejection:
                    _ = producer.seal_target(
                        root,
                        root / "candidate/payload",
                        receipt,
                        root / "missing-java",
                        "a" * 40,
                        "b" * 40,
                        "0.0.123",
                        product.ProductProducer(123, 1, producer.NATIVE_WORKFLOW),
                        "linux-x86_64",
                        "linux-x86_64",
                        control=root / "missing-control",
                    )
                self.assertEqual(producer.Failure.GATE_NOT_COMPLETE, rejection.exception.failure)

    def test_reusable_native_workflow_requires_exact_caller_and_reference(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            record = fixture(Path(temporary) / "candidate")
            native = product.ProductProducer(123, 1, producer.NATIVE_WORKFLOW)
            self.assertEqual(
                product.Admission.ADMITTED,
                candidate.admit_producer(record, native, asdict(ReusableRun())),
            )
            for observed in (
                ReusableRun(referenced_workflows=[]),
                ReusableRun(referenced_workflows=[WorkflowReference(sha="c" * 40)]),
                ReusableRun(
                    referenced_workflows=[
                        WorkflowReference(
                            path="foreign/kast/.github/workflows/composed-linux.yml@main"
                        )
                    ]
                ),
                ReusableRun(run_attempt=2),
                ReusableRun(id=456),
                ReusableRun(event="pull_request"),
            ):
                with self.subTest(observed=observed):
                    self.assertEqual(
                        producer.Failure.WRONG_PRODUCER,
                        candidate.admit_producer(record, native, asdict(observed)),
                    )

    def test_untrusted_cli_context_rejects_before_checkout_or_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(producer.__file__)),
                    "seal",
                    "--root",
                    str(root),
                    "--directory",
                    str(root / "missing"),
                    "--output",
                    str(root / "complete"),
                ],
                env={
                    "GITHUB_REPOSITORY": "amichne/kast",
                    "GITHUB_SHA": "a" * 40,
                    "GITHUB_REF": "refs/pull/123/merge",
                    "GITHUB_EVENT_NAME": "pull_request_target",
                    "GITHUB_RUN_ID": "123",
                    "GITHUB_RUN_ATTEMPT": "1",
                    "GITHUB_RUN_NUMBER": "123",
                },
                check=False,
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(1, result.returncode)
            self.assertEqual("product-producer: WRONG_PRODUCER\n", result.stderr)
            self.assertEqual([], list(root.iterdir()))

    def test_ci_joins_mac_acceptance_before_validation_and_keeps_promotion_build_free(
        self,
    ) -> None:
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        ci = (workflows / "ci.yml").read_text()
        jobs = dict(
            re.findall(r"(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)", ci.split("\njobs:\n", 1)[1])
        )
        self.assertNotIn("composed-products", jobs)
        mac = jobs["composed-macos"]
        # Qualification runs beside source validation; sealing keeps the source proof.
        self.assertIn("needs: [scope, assembly]", mac)
        self.assertNotIn("needs.source-work", mac)
        self.assertIn("complete_product: true", mac)
        self.assertIn("release_target: macos-aarch64", mac)
        join = jobs["sealed-product"]
        self.assertIn("needs: [scope, source-work, composed-macos]", join)
        self.assertIn(
            "name: kast-qualified-product-macos-aarch64-"
            "${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}",
            join,
        )
        self.assertIn("name: ci-product-sealed-${{ github.sha }}-${{ github.run_attempt }}", join)
        accepted = jobs["complete-product"]
        self.assertIn("needs: [sealed-product, installed-product]", accepted)
        self.assertIn("product_producer.py accept", accepted)
        self.assertIn("name: ci-product-candidate-", accepted)
        self.assertNotIn("ci-product-candidate-", join)
        self.assertIn("needs: sealed-product", jobs["installed-product"])
        self.assertIn("product_lifecycle_probe.py", jobs["installed-product"])
        self.assertIn("kotlin, complete-product]", jobs["required"])
        self.assertLess(
            jobs["required"].index('test "$COMPLETE_PRODUCT_RESULT" = success'),
            jobs["required"].index("validation_outcome.py"),
        )
        composed = (workflows / "composed-linux.yml").read_text()
        self.assertNotIn("nativeComponentQualification", composed)
        self.assertNotIn("--harness", composed)
        self.assertIn(
            "--control /tmp/kc/components/control/share/kast/libexec/kast-management", composed
        )
        self.assertLess(
            composed.index("packaging/internal/composition/composed_release.py qualify"),
            composed.index("product_producer.py target"),
        )
        self.assertLess(
            composed.index("product_producer.py target"),
            composed.index("name: kast-qualified-product-"),
        )
        for consumer in (
            join,
            accepted,
            (workflows / "release.yml").read_text(),
            (workflows / "developer-release.yml").read_text(),
        ):
            for forbidden in ("./gradlew", "assembleRelease", "composed_release.py assemble"):
                self.assertNotIn(forbidden, consumer)


if __name__ == "__main__":
    unittest.main()
