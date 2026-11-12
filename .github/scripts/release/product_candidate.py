"""Admission of retained complete products; never builds or changes archive bytes."""

from __future__ import annotations

import hashlib
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from collections.abc import Callable
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "packaging"))
import product_acceptance as acceptance

import build_candidate as candidate
import developer_catalog as catalog

product = catalog.product
type ObserveRun = Callable[[product.ProductProducer], object]


def read(directory: Path, *, accepted: bool = False) -> product.ProductRecord | candidate.Failure:
    try:
        record_path = directory / "build-record.json"
        payload = directory / "payload"
        if (
            directory.is_symlink()
            or record_path.is_symlink()
            or not record_path.is_file()
            or record_path.stat().st_size > product.MAXIMUM_RECORD_BYTES
            or payload.is_symlink()
            or not payload.is_dir()
            or {path.name for path in directory.iterdir()} != {"build-record.json", "payload"}
        ):
            return candidate.Failure.INVALID_RECORD
        record = product.decode_product_record(record_path.read_bytes())
        if isinstance(record, product.Failure):
            return candidate.Failure.INVALID_RECORD
        names = {path.name for path in payload.iterdir()}
        expected = {asset.name for asset in record.assets()}
        detached = {acceptance.filename(target) for target in product.PRODUCT_TARGETS}
        if names != expected and names != expected | detached:
            return candidate.Failure.PAYLOAD_MISMATCH
        if accepted or names == expected | detached:
            try:
                _ = acceptance.assets(directory, record)
            except (OSError, ValueError):
                return candidate.Failure.GATE_NOT_COMPLETE
        for asset in record.assets():
            path = payload / asset.name
            if (
                path.is_symlink()
                or not path.is_file()
                or path.stat().st_size != asset.bytes
                or product.file_digest(path) != asset.sha256
            ):
                return candidate.Failure.PAYLOAD_MISMATCH
        for target in record.targets:
            receipt_path = payload / target.qualification.receipt.name
            if receipt_path.stat().st_size > 1_048_576:
                return candidate.Failure.INVALID_RECORD
            evidence = product.document(receipt_path.read_bytes())
            if (
                not product.mapping(evidence)
                or evidence.get("qualification") != "COMPLETE"
                or evidence.get("qualificationStage") != "COMPONENT_NATIVE"
                or not isinstance(evidence.get("harnessSha256"), str)
                or not product.DIGEST.fullmatch(str(evidence.get("harnessSha256")))
                or evidence.get("sourceRevision") != record.sourceRevision
                or evidence.get("bundleSha256") != target.bundle.sha256
                or not product.mapping(evidence.get("probe"))
            ):
                return candidate.Failure.GATE_NOT_COMPLETE
            if not product.native_receipt_matches(target, evidence):
                return candidate.Failure.PAYLOAD_MISMATCH
            if target.runtimeBase is not None:
                from runtime_base import admit_archives

                admit_archives(payload, target)
            with zipfile.ZipFile(payload / target.runtime.name) as archive:
                manifests = [
                    item for item in archive.infolist() if item.filename == "manifest.json"
                ]
                if len(manifests) != 1 or manifests[0].file_size > 16_777_216:
                    return candidate.Failure.INVALID_RECORD
                manifest = product.document(archive.read(manifests[0]))
            if (
                not product.mapping(manifest)
                or manifest.get("schemaVersion") != 1
                or manifest.get("kind") != "KAST_RUNTIME_V1"
                or manifest.get("version") != record.version
                or manifest.get("platform") != target.target
            ):
                return candidate.Failure.PAYLOAD_MISMATCH
            with tarfile.open(payload / target.bundle.name, "r:gz") as archive:
                matches = [item for item in archive if item.name == "setup-kast/manifest.json"]
                if len(matches) != 1 or not matches[0].isreg() or matches[0].size > 16_777_216:
                    return candidate.Failure.INVALID_RECORD
                stream = archive.extractfile(matches[0])
                if stream is None:
                    return candidate.Failure.INVALID_RECORD
                with stream:
                    manifest = product.document(stream.read())
            if (
                not product.mapping(manifest)
                or manifest.get("schemaVersion") != 2
                or manifest.get("kind") != "KAST_COMPOSED_SETUP_V2"
                or manifest.get("version") != record.version
                or manifest.get("platform") != target.target
                or manifest.get("sourceDistributionSha256") != target.inputs.donorSha256
            ):
                return candidate.Failure.PAYLOAD_MISMATCH
        return record
    except (
        OSError,
        ValueError,
        KeyError,
        UnicodeError,
        RecursionError,
        tarfile.TarError,
        zipfile.BadZipFile,
    ):
        return candidate.Failure.INVALID_RECORD


def admit_producer(
    record: product.ProductRecord, producer: product.ProductProducer, value: object
) -> candidate.Failure | product.Admission:
    identity = admit_producer_identity(record, producer, value)
    if isinstance(identity, candidate.Failure):
        return identity
    if (
        not product.mapping(value)
        or value.get("status") != "completed"
        or value.get("conclusion") != "success"
    ):
        return candidate.Failure.PRODUCER_NOT_SUCCESSFUL
    return product.Admission.ADMITTED


def admit_producer_identity(
    record: product.ProductRecord, producer: product.ProductProducer, value: object
) -> candidate.Failure | product.Admission:
    if not product.mapping(value):
        return candidate.Failure.WRONG_PRODUCER
    repository = value.get("repository")
    if (
        not product.mapping(repository)
        or repository.get("full_name") != record.repository
        or not producer_workflow(record, producer, value)
        or value.get("id") != producer.runId
        or type(value.get("id")) is not int
        or type(value.get("run_attempt")) is not int
        or value.get("run_attempt") != producer.runAttempt
        or value.get("head_sha") != record.sourceRevision
        or value.get("head_branch") != "main"
        or value.get("event") not in ("push", "workflow_dispatch")
    ):
        return candidate.Failure.WRONG_PRODUCER
    if producer == record.producer and record.version != f"0.0.{value.get('run_number')}":
        return candidate.Failure.INVALID_IDENTITY
    return product.Admission.ADMITTED


def admit_baseline(
    directory: Path,
    digest: str,
    revision: str,
    producer: product.ProductProducer,
    observed_run: object,
    observed_jobs: object,
) -> product.ProductRecord | candidate.Failure:
    """Admit a retained sealed baseline, not a published or installed-accepted product.

    The sealing job must have succeeded. The overall run may fail later at installed
    acceptance because no baseline existed yet; this does not erase component proof.
    """
    record = read(directory)
    if isinstance(record, candidate.Failure):
        return record
    if (
        record.producer != producer
        or record.sourceRevision != revision
        or product.file_digest(directory / "build-record.json") != digest
    ):
        return candidate.Failure.WRONG_PRODUCER
    for owner in (record.producer, *(target.qualification.producer for target in record.targets)):
        if (owner.runId, owner.runAttempt) != (producer.runId, producer.runAttempt):
            return candidate.Failure.WRONG_PRODUCER
        admitted = admit_producer_identity(record, owner, observed_run)
        if isinstance(admitted, candidate.Failure):
            return admitted
    if not product.mapping(observed_jobs):
        return candidate.Failure.INCOMPLETE_OBSERVATION
    jobs = observed_jobs.get("jobs")
    if (
        not product.sequence(jobs)
        or type(observed_jobs.get("total_count")) is not int
        or observed_jobs.get("total_count") != len(jobs)
        or not all(product.mapping(item) for item in jobs)
    ):
        return candidate.Failure.INCOMPLETE_OBSERVATION
    sealed = [
        item
        for item in jobs
        if product.mapping(item)
        and item.get("name") == "Seal both native targets without rebuilding"
    ]
    if (
        len(sealed) != 1
        or sealed[0].get("status") != "completed"
        or sealed[0].get("conclusion") != "success"
        or type(sealed[0].get("run_id")) is not int
        or sealed[0].get("run_id") != producer.runId
        or sealed[0].get("head_sha") != revision
    ):
        return candidate.Failure.GATE_NOT_COMPLETE
    return record


def producer_workflow(
    record: product.ProductRecord, producer: product.ProductProducer, run: dict[object, object]
) -> bool:
    if run.get("path") == producer.workflow:
        return True
    # A local reusable workflow runs under its caller's run identity. Require the
    # exact reusable source reference; caller success alone is not native proof.
    references = run.get("referenced_workflows")
    if (
        producer.workflow != ".github/workflows/composed-linux.yml"
        or run.get("path") != ".github/workflows/ci.yml"
        or (producer.runId, producer.runAttempt)
        != (record.producer.runId, record.producer.runAttempt)
        or not product.sequence(references)
    ):
        return False
    matches = [
        item
        for item in references
        if product.mapping(item)
        and isinstance(item.get("path"), str)
        and str(item["path"]).startswith(f"{record.repository}/{producer.workflow}@")
        and item.get("sha") == record.sourceRevision
    ]
    return len(matches) == 1


def observe(producer: product.ProductProducer) -> object:
    return candidate.observe(
        f"repos/{product.REPOSITORY}/actions/runs/{producer.runId}/attempts/{producer.runAttempt}",
        candidate.ObservationStage.WORKFLOW_RUN,
    )


def admit(
    directory: Path, observe_run: ObserveRun = observe
) -> product.ProductRecord | candidate.Failure:
    record = read(directory, accepted=True)
    if isinstance(record, candidate.Failure):
        return record
    for producer in (
        record.producer,
        *(target.qualification.producer for target in record.targets),
    ):
        observed_run = observe_run(producer)
        result = admit_producer(record, producer, observed_run)
        if isinstance(result, candidate.Failure):
            return result
        if producer == record.producer:
            if not product.mapping(observed_run):
                return candidate.Failure.WRONG_PRODUCER
            try:
                outcome = candidate.admit_validation(
                    record.repository, record.sourceRevision, observed_run, record.sourceTree
                )
            except candidate.Rejected as rejection:
                return rejection.failure
            if not isinstance(outcome, candidate.validation.ProductValidated):
                return candidate.Failure.SOURCE_NOT_QUALIFIED
    if candidate.tree(record.repository, record.sourceRevision) != record.sourceTree:
        return candidate.Failure.SOURCE_TREE_MISMATCH
    # Successful runs cannot attest caller-supplied toolchain or qualification fields.
    # Compare the sealed CI record and each target's exact retained native bytes.
    record_path = directory / "build-record.json"
    retained = [
        (
            record.producer,
            f"ci-product-candidate-{record.sourceRevision}-{record.producer.runAttempt}",
            (
                product.ProductAsset(
                    "build-record.json",
                    product.file_digest(record_path),
                    record_path.stat().st_size,
                ),
                *acceptance.assets(directory, record),
            ),
        ),
        *(
            (
                target.qualification.producer,
                f"kast-qualified-product-{target.target}-{record.sourceRevision}-"
                f"{target.qualification.producer.runId}-{target.qualification.producer.runAttempt}",
                target.assets(),
            )
            for target in record.targets
        ),
    ]
    for producer, name, assets in retained:
        observed: object = candidate.observe(
            f"repos/{record.repository}/actions/runs/{producer.runId}/artifacts?per_page=100",
            candidate.ObservationStage.RUN_ARTIFACTS,
        )
        if not product.mapping(observed):
            return candidate.Failure.INCOMPLETE_OBSERVATION
        artifacts = observed.get("artifacts")
        if (
            not product.sequence(artifacts)
            or type(observed.get("total_count")) is not int
            or observed.get("total_count") != len(artifacts)
        ):
            return candidate.Failure.INCOMPLETE_OBSERVATION
        matches = 0
        for artifact in artifacts:
            if not product.mapping(artifact):
                return candidate.Failure.INCOMPLETE_OBSERVATION
            if artifact.get("name") == name and artifact.get("expired") is False:
                matches += 1
        if matches != 1:
            return candidate.Failure.INVALID_RECORD
        with tempfile.TemporaryDirectory(prefix="kast-native-evidence-") as temporary:
            destination = Path(temporary)
            try:
                downloaded = subprocess.run(
                    [
                        "gh",
                        "run",
                        "download",
                        str(producer.runId),
                        "--repo",
                        record.repository,
                        "--name",
                        name,
                        "--dir",
                        temporary,
                    ],
                    check=False,
                    timeout=180,
                    capture_output=True,
                )
            except (OSError, subprocess.TimeoutExpired):
                return candidate.Failure.DOWNLOAD_FAILED
            if downloaded.returncode:
                return candidate.Failure.DOWNLOAD_FAILED
            for asset in assets:
                path = (
                    destination
                    / ("payload" if asset.name.startswith("acceptance-") else "")
                    / asset.name
                )
                if (
                    path.is_symlink()
                    or not path.is_file()
                    or path.stat().st_size != asset.bytes
                    or product.file_digest(path) != asset.sha256
                ):
                    return candidate.Failure.PAYLOAD_MISMATCH
    return record


def fetch_release(
    tag: str, expected_digest: str, directory: Path, source: product.ReleaseSource
) -> product.ProductRecord | candidate.Failure:
    if (
        not catalog.VERSION.fullmatch(tag.removeprefix("developer-v"))
        or not tag.startswith("developer-v")
        or not catalog.DIGEST.fullmatch(expected_digest)
        or directory.exists()
    ):
        return candidate.Failure.INVALID_IDENTITY
    base = f"{product.RELEASE_URL}/{tag}"
    raw = source.read(f"{base}/build-record.json", product.MAXIMUM_RECORD_BYTES)
    if isinstance(raw, product.TransferFailure):
        return candidate.Failure.DOWNLOAD_FAILED
    if hashlib.sha256(raw).hexdigest() != expected_digest:
        return candidate.Failure.PAYLOAD_MISMATCH
    record = product.decode_product_record(raw)
    if isinstance(record, product.Failure) or tag != f"developer-v{record.version}":
        return candidate.Failure.INVALID_RECORD
    directory.mkdir()
    (directory / "payload").mkdir()
    _ = (directory / "build-record.json").write_bytes(raw)
    for asset in record.assets():
        result = source.download(
            f"{base}/{asset.name}", directory / "payload" / asset.name, asset.bytes
        )
        if isinstance(result, product.TransferFailure):
            return candidate.Failure.DOWNLOAD_FAILED
    for target in product.PRODUCT_TARGETS:
        name = acceptance.filename(target)
        raw = source.read(f"{base}/{name}", acceptance.MAXIMUM_RECEIPT_BYTES)
        if isinstance(raw, product.TransferFailure):
            return candidate.Failure.DOWNLOAD_FAILED
        _ = (directory / "payload" / name).write_bytes(raw)
    return read(directory, accepted=True)
