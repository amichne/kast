"""Seal native target evidence and join both targets without building or repacking."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path

import product_candidate as candidate

product = candidate.product
Failure = candidate.candidate.Failure
Rejected = candidate.candidate.Rejected
CI_WORKFLOW = ".github/workflows/ci.yml"
NATIVE_WORKFLOW = ".github/workflows/composed-linux.yml"
# This repository uses version pins and verification metadata, not Gradle lockfiles.
# Hash their named bytes together; never claim that this digest proves resolution.
RESOLUTION_INPUTS = (
    "gradle/libs.versions.toml",
    "gradle/verification-metadata.xml",
    "gradle/wrapper/gradle-wrapper.properties",
    "mise.toml",
    "packaging/uv.lock",
)


@dataclass(frozen=True)
class InputFile:
    path: str
    sha256: str


@dataclass(frozen=True)
class TargetRecord:
    schemaVersion: int
    repository: str
    sourceRevision: str
    sourceTree: str
    version: str
    target: product.ProductTarget


def encoded(value: TargetRecord | product.ProductRecord | tuple[InputFile, ...]) -> bytes:
    document = [asdict(item) for item in value] if isinstance(value, tuple) else asdict(value)
    return (json.dumps(document, separators=(",", ":")) + "\n").encode()


def regular(path: Path) -> Path:
    if path.is_symlink() or not path.is_file():
        raise Rejected(Failure.INVALID_RECORD)
    return path


def resolution_digest(root: Path) -> str:
    inputs = tuple(
        InputFile(name, product.file_digest(regular(root / name))) for name in RESOLUTION_INPUTS
    )
    return hashlib.sha256(encoded(inputs)).hexdigest()


def checkout(root: Path, revision: str) -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD", "HEAD^{tree}"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    ).stdout.splitlines()
    dirty = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=normal"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    ).stdout
    if len(result) != 2 or result[0] != revision or not product.revision(result[1]) or dirty:
        raise Rejected(Failure.SOURCE_TREE_MISMATCH)
    return result[1]


def asset(path: Path) -> product.ProductAsset:
    path = regular(path)
    return product.ProductAsset(path.name, product.file_digest(path), path.stat().st_size)


def seal_target(
    root: Path,
    directory: Path,
    receipt: Path,
    java_release: Path,
    revision: str,
    tree: str,
    version: str,
    producer: product.ProductProducer,
    target: str,
    native_target: str,
) -> TargetRecord:
    """The caller must run native qualification first; this records rather than grants proof."""
    if target not in product.PRODUCT_TARGETS or target != native_target:
        raise Rejected(Failure.INVALID_IDENTITY)
    if not product.revision(revision) or not product.revision(tree):
        raise Rejected(Failure.INVALID_IDENTITY)
    bundle = asset(directory / f"kast-composed-setup-v{version}-{target}.tar.gz")
    runtime = asset(directory / f"kast-runtime-v{version}-{target}.zip")
    if regular(receipt).stat().st_size > 1_048_576:
        raise Rejected(Failure.INVALID_RECORD)
    evidence = product.document(receipt.read_bytes())
    if not product.mapping(evidence) or not product.mapping(evidence.get("containers")):
        raise Rejected(Failure.GATE_NOT_COMPLETE)
    containers = evidence["containers"]
    assert product.mapping(containers)
    control = containers.get("controlSha256")
    from runtime_base import read_reference

    base_reference = read_reference(directory / runtime.name)
    composition: product.NativeCompositionEvidence | None = None
    if base_reference is not None:
        supplied = evidence.get("composition")
        if not product.mapping(supplied):
            raise Rejected(Failure.GATE_NOT_COMPLETE)
        witnesses: list[str] = []
        for key in ("baseSha256", "nativeRuntimeSha256", "nativeBundleSha256", "runtimeSha256"):
            value = supplied.get(key)
            if not isinstance(value, str) or not product.DIGEST.fullmatch(value):
                raise Rejected(Failure.GATE_NOT_COMPLETE)
            witnesses.append(value)
        composition = product.NativeCompositionEvidence(*witnesses)
        if (
            composition.baseSha256 != base_reference.base.sha256
            or composition.runtimeSha256 != runtime.sha256
        ):
            raise Rejected(Failure.PAYLOAD_MISMATCH)
    if (
        evidence.get("qualification") != "COMPLETE"
        or evidence.get("qualificationStage") != "COMPONENT_NATIVE"
        or not isinstance(evidence.get("harnessSha256"), str)
        or not product.DIGEST.fullmatch(str(evidence.get("harnessSha256")))
        or evidence.get("sourceRevision") != revision
        or evidence.get("bundleSha256") != bundle.sha256
        or containers.get("runtimeSha256")
        != (composition.nativeRuntimeSha256 if composition else runtime.sha256)
        or not product.mapping(evidence.get("probe"))
        or not isinstance(control, str)
    ):
        raise Rejected(Failure.GATE_NOT_COMPLETE)
    with tarfile.open(directory / bundle.name, "r:gz") as archive:
        manifests = [item for item in archive if item.name == "setup-kast/manifest.json"]
        if len(manifests) != 1 or not manifests[0].isreg() or manifests[0].size > 16_777_216:
            raise Rejected(Failure.INVALID_RECORD)
        stream = archive.extractfile(manifests[0])
        if stream is None:
            raise Rejected(Failure.INVALID_RECORD)
        with stream:
            manifest = product.document(stream.read())
    if not product.mapping(manifest) or not isinstance(
        donor := manifest.get("sourceDistributionSha256"), str
    ):
        raise Rejected(Failure.INVALID_RECORD)
    qualification = product.NativeQualification(
        product.ProductAsset(
            f"qualification-{target}.json", product.file_digest(receipt), receipt.stat().st_size
        ),
        producer,
        bundle.sha256,
        runtime.sha256,
        control,
        composition=composition,
    )
    facts = product.ProductTarget(
        target,
        product.ProductInputs(
            product.file_digest(regular(java_release)),
            resolution_digest(root),
            product.file_digest(regular(root / "packaging/composition_recipe.py")),
            donor,
        ),
        bundle,
        runtime,
        qualification,
        base_reference.base if base_reference else None,
    )
    if isinstance(product.product_target(asdict(facts), version), product.Failure):
        raise Rejected(Failure.INVALID_RECORD)
    if facts.runtimeBase is not None:
        from runtime_base import admit_archives

        if asset(directory / facts.runtimeBase.name) != facts.runtimeBase:
            raise Rejected(Failure.PAYLOAD_MISMATCH)
        admit_archives(directory, facts)
    record = TargetRecord(1, product.REPOSITORY, revision, tree, version, facts)
    record_path = directory / f"target-record-{target}.json"
    receipt_path = directory / qualification.receipt.name
    if (
        record_path.exists()
        or record_path.is_symlink()
        or receipt_path.exists()
        or receipt_path.is_symlink()
    ):
        raise Rejected(Failure.DESTINATION_NOT_EMPTY)
    _ = shutil.copyfile(receipt, receipt_path)
    with record_path.open("xb") as output:
        _ = output.write(encoded(record))
    return record


def read_target(path: Path) -> TargetRecord:
    if regular(path).stat().st_size > product.MAXIMUM_RECORD_BYTES:
        raise Rejected(Failure.INVALID_RECORD)
    value = product.document(path.read_bytes())
    if (
        not product.mapping(value)
        or set(value)
        != {"schemaVersion", "repository", "sourceRevision", "sourceTree", "version", "target"}
        or type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 1
    ):
        raise Rejected(Failure.INVALID_RECORD)
    revision, tree, version = value["sourceRevision"], value["sourceTree"], value["version"]
    if (
        value["repository"] != product.REPOSITORY
        or not product.revision(revision)
        or not product.revision(tree)
        or not isinstance(version, str)
    ):
        raise Rejected(Failure.INVALID_IDENTITY)
    target = product.product_target(value["target"], version)
    if isinstance(target, product.Failure):
        raise Rejected(Failure.INVALID_RECORD)
    return TargetRecord(1, product.REPOSITORY, revision, tree, version, target)


def seal(
    inputs: Path,
    output: Path,
    revision: str,
    tree: str,
    version: str,
    producer: product.ProductProducer,
) -> product.ProductRecord:
    if output.exists() or output.is_symlink() or inputs.is_symlink():
        raise Rejected(Failure.DESTINATION_NOT_EMPTY)
    if {path.name for path in inputs.iterdir()} != set(product.PRODUCT_TARGETS):
        raise Rejected(Failure.GATE_NOT_COMPLETE)
    targets: list[product.ProductTarget] = []
    for target in product.PRODUCT_TARGETS:
        source = inputs / target
        if source.is_symlink() or not source.is_dir():
            raise Rejected(Failure.INVALID_RECORD)
        record = read_target(source / f"target-record-{target}.json")
        facts = record.target
        if (
            record.sourceRevision != revision
            or record.sourceTree != tree
            or record.version != version
            or facts.target != target
            or facts.qualification.producer
            != product.ProductProducer(producer.runId, producer.runAttempt, NATIVE_WORKFLOW)
        ):
            raise Rejected(Failure.WRONG_PRODUCER)
        if {path.name for path in source.iterdir()} != {
            f"target-record-{target}.json",
            *(item.name for item in facts.assets()),
        }:
            raise Rejected(Failure.PAYLOAD_MISMATCH)
        targets.append(facts)
    if (
        len(
            {
                (item.inputs.dependencyLocksSha256, item.inputs.compositionRecipeSha256)
                for item in targets
            }
        )
        != 1
    ):
        raise Rejected(Failure.SOURCE_TREE_MISMATCH)
    complete = product.ProductRecord(
        3 if any(item.runtimeBase for item in targets) else 2,
        product.REPOSITORY,
        revision,
        tree,
        version,
        producer,
        tuple(targets),
    )
    if product.decode_product_record(encoded(complete)) != complete:
        raise Rejected(Failure.INVALID_RECORD)
    # Validate in an owned sibling before publishing the complete retained inventory.
    with tempfile.TemporaryDirectory(prefix="kast-product-seal-", dir=output.parent) as temporary:
        staged = Path(temporary) / "candidate"
        payload = staged / "payload"
        payload.mkdir(parents=True)
        for target in targets:
            for item in target.assets():
                _ = shutil.copyfile(
                    regular(inputs / target.target / item.name), payload / item.name
                )
        _ = (staged / "build-record.json").write_bytes(encoded(complete))
        if candidate.read(staged) != complete:
            raise Rejected(Failure.PAYLOAD_MISMATCH)
        staged.rename(output)
    return complete


def accept(directory: Path, receipts: Path, output: Path) -> product.ProductRecord:
    """Join detached native receipts without changing a byte of the sealed record."""
    record = candidate.read(directory)
    if isinstance(record, Failure):
        raise Rejected(record)
    if output.exists() or output.is_symlink() or receipts.is_symlink():
        raise Rejected(Failure.DESTINATION_NOT_EMPTY)
    names = {candidate.acceptance.filename(target) for target in product.PRODUCT_TARGETS}
    if {path.name for path in receipts.iterdir()} != names:
        raise Rejected(Failure.GATE_NOT_COMPLETE)
    digest = product.file_digest(directory / "build-record.json")
    for target in product.PRODUCT_TARGETS:
        path = regular(receipts / candidate.acceptance.filename(target))
        if path.stat().st_size > candidate.acceptance.MAXIMUM_RECEIPT_BYTES:
            raise Rejected(Failure.INVALID_RECORD)
        try:
            _ = candidate.acceptance.decode(path.read_bytes(), record, digest, target)
        except ValueError as failure:
            raise Rejected(Failure.GATE_NOT_COMPLETE) from failure
    with tempfile.TemporaryDirectory(prefix="kast-product-accept-", dir=output.parent) as temporary:
        staged = Path(temporary) / "candidate"
        _ = shutil.copytree(directory, staged)
        for name in names:
            _ = shutil.copyfile(receipts / name, staged / "payload" / name)
        if candidate.read(staged, accepted=True) != record:
            raise Rejected(Failure.PAYLOAD_MISMATCH)
        staged.rename(output)
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("action", choices=("target", "seal", "accept"))
    _ = parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    _ = parser.add_argument("--directory", type=Path, required=True)
    _ = parser.add_argument("--output", type=Path)
    _ = parser.add_argument("--receipt", type=Path)
    _ = parser.add_argument("--java-release", type=Path)
    _ = parser.add_argument("--target")
    args = parser.parse_args()
    try:
        revision = os.environ["GITHUB_SHA"]
        run, attempt, number = (
            int(os.environ[key])
            for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_RUN_NUMBER")
        )
        if (
            os.environ["GITHUB_REPOSITORY"] != product.REPOSITORY
            or os.environ["GITHUB_REF"] != "refs/heads/main"
            or os.environ["GITHUB_EVENT_NAME"] not in ("push", "workflow_dispatch")
            or min(run, attempt, number) <= 0
        ):
            raise Rejected(Failure.WRONG_PRODUCER)
        tree = checkout(args.root, revision)
        version = f"0.0.{number}"
        if args.action == "target":
            if args.receipt is None or args.java_release is None or args.target is None:
                raise Rejected(Failure.INVALID_IDENTITY)
            native = {
                ("Darwin", "arm64"): "macos-aarch64",
                ("Linux", "x86_64"): "linux-x86_64",
            }.get((platform.system(), platform.machine()), "unsupported")
            _ = seal_target(
                args.root,
                args.directory,
                args.receipt,
                args.java_release,
                revision,
                tree,
                version,
                product.ProductProducer(run, attempt, NATIVE_WORKFLOW),
                args.target,
                native,
            )
        elif args.action == "accept":
            if args.output is None or args.receipt is None:
                raise Rejected(Failure.INVALID_IDENTITY)
            record = candidate.read(args.directory)
            if (
                isinstance(record, Failure)
                or record.sourceRevision != revision
                or record.sourceTree != tree
                or record.producer != product.ProductProducer(run, attempt, CI_WORKFLOW)
            ):
                raise Rejected(Failure.WRONG_PRODUCER)
            _ = accept(args.directory, args.receipt, args.output)
        else:
            if args.output is None:
                raise Rejected(Failure.INVALID_IDENTITY)
            _ = seal(
                args.directory,
                args.output,
                revision,
                tree,
                version,
                product.ProductProducer(run, attempt, CI_WORKFLOW),
            )
        return 0
    except Rejected as rejection:
        print(f"product-producer: {rejection.failure.value}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, tarfile.TarError, subprocess.SubprocessError):
        print(f"product-producer: {Failure.INVALID_RECORD.value}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
