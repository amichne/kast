"""Admit one captured release composition before effects on an installed Kast."""

from __future__ import annotations

import hashlib
import io
import json
import os
import platform
import re
import shutil
import ssl
import stat
import struct
import subprocess
import tarfile
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as XML
import zipfile
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from functools import cache
from http.client import HTTPMessage
from pathlib import Path, PurePosixPath
from typing import IO, TYPE_CHECKING, Literal, Protocol, TypeGuard, override

from managed_update_journal import BuildIdentity, PinnedBuild, TrackMain, TrackStable

if TYPE_CHECKING:
    from managed_product import AdmittedProduct

MAXIMUM_ENTRIES = 16_384
MAXIMUM_PAYLOAD_BYTES = 1_073_741_824
MAXIMUM_RECORD_BYTES = 65_536
PLATFORM = "macos-aarch64"
REPOSITORY = "amichne/kast"
CATALOG_URL = f"https://raw.githubusercontent.com/{REPOSITORY}/developer-latest/builds.json"
STABLE_URL = f"https://api.github.com/repos/{REPOSITORY}/releases/latest"
RELEASE_URL = f"https://github.com/{REPOSITORY}/releases/download"
VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")


class Failure(StrEnum):
    SELECTORS_CONFLICT = "SELECTORS_CONFLICT"
    SELECTOR_INVALID = "SELECTOR_INVALID"
    AMBIGUOUS_COMMIT = "AMBIGUOUS_COMMIT"
    BINARY_UNAVAILABLE = "BINARY_UNAVAILABLE"
    PLATFORM_UNAVAILABLE = "PLATFORM_UNAVAILABLE"
    CATALOG_UNAVAILABLE = "CATALOG_UNAVAILABLE"
    ARTIFACT_INVALID = "ARTIFACT_INVALID"
    INSTALLER_CONTRACT_UNSUPPORTED = "INSTALLER_CONTRACT_UNSUPPORTED"
    LOCAL_ARTIFACT_REQUIRED = "LOCAL_ARTIFACT_REQUIRED"


class Stage(StrEnum):
    SELECTION = "SELECTION"
    RECORDS = "RECORDS"
    DOWNLOAD = "DOWNLOAD"
    ARCHIVE = "ARCHIVE"
    CONTRACT = "CONTRACT"
    STAGING = "STAGING"


class Admission(StrEnum):
    ADMITTED = "ADMITTED"


@dataclass(frozen=True, slots=True)
class Rejected:
    reason: Failure
    stage: Stage
    requested: str
    platform: str = PLATFORM
    selectable: tuple[str, ...] = ()
    transfer: TransferRejected | None = None


type UpdatePolicy = TrackStable | TrackMain | PinnedBuild


class StablePolicy(StrEnum):
    TRACK = "TrackStable"
    PIN = "PinnedBuild"


@dataclass(frozen=True, slots=True)
class StableSelection:
    version: str
    releaseTag: str
    policy: StablePolicy
    type: Literal["Stable"] = "Stable"


@dataclass(frozen=True, slots=True)
class RecordReference:
    name: str
    sha256: str


@dataclass(frozen=True, slots=True)
class MainEntry:
    mainRevision: str
    buildRevision: str
    sourceTree: str
    releaseTag: str
    version: str
    platformTargets: tuple[str, ...]
    candidate: RecordReference
    promotion: RecordReference

    def identity(self) -> BuildIdentity:
        return BuildIdentity(
            self.releaseTag, self.version, self.buildRevision, self.sourceTree, self.mainRevision
        )


@dataclass(frozen=True, slots=True)
class MainSelection:
    identity: BuildIdentity
    policy: TrackMain | PinnedBuild
    catalogEntry: MainEntry
    type: Literal["Main"] = "Main"

    @property
    def version(self) -> str:
        return self.identity.version

    @property
    def releaseTag(self) -> str:
        return self.identity.releaseTag


type Selection = StableSelection | MainSelection


@dataclass(frozen=True, slots=True)
class DownloadedFile:
    path: Path
    sha256: str
    bytes: int


class TransferFailure(StrEnum):
    NOT_FOUND = "NOT_FOUND"
    UNAVAILABLE = "UNAVAILABLE"
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    TLS_CERTIFICATE_UNTRUSTED = "TLS_CERTIFICATE_UNTRUSTED"
    TLS_FAILED = "TLS_FAILED"
    CONNECTION_FAILED = "CONNECTION_FAILED"
    TIMEOUT = "TIMEOUT"
    HTTP_STATUS = "HTTP_STATUS"


class TrustRoots(StrEnum):
    """Certificate authorities behind one process's HTTPS context."""

    PLATFORM = "PLATFORM"
    ENVIRONMENT = "ENVIRONMENT"
    KEYCHAIN = "KEYCHAIN"


@dataclass(frozen=True, slots=True)
class TransferRejected:
    """Bounded transfer cause; httpStatus is present only for HTTP_STATUS."""

    cause: TransferFailure
    trust: TrustRoots | None = None
    httpStatus: int | None = None


type TransferRejection = TransferFailure | TransferRejected
TRANSFER_REJECTIONS = (TransferFailure, TransferRejected)


def transfer(rejection: TransferRejection) -> TransferRejected:
    return rejection if isinstance(rejection, TransferRejected) else TransferRejected(rejection)


class ReleaseSource(Protocol):
    def read(self, url: str, limit: int) -> bytes | TransferRejection: ...
    def download(
        self, url: str, destination: Path, limit: int
    ) -> DownloadedFile | TransferRejection: ...


@cache
def process_trust() -> tuple[ssl.SSLContext, TrustRoots]:
    return ssl.create_default_context(), (
        TrustRoots.ENVIRONMENT
        if "SSL_CERT_FILE" in os.environ or "SSL_CERT_DIR" in os.environ
        else TrustRoots.PLATFORM
    )


def run_native_https(
    command: tuple[str, ...], output: IO[bytes]
) -> subprocess.CompletedProcess[bytes]:
    """Apple curl delegates certificate evaluation to SecureTransport, not exported anchors."""
    return subprocess.run(
        command,
        stdin=subprocess.DEVNULL,
        stdout=output,
        stderr=subprocess.PIPE,
        timeout=1800,
        check=False,
        env={
            **{key: value for key, value in os.environ.items() if key != "CURL_CA_BUNDLE"},
            "CURL_SSL_BACKEND": "secure-transport",
        },
    )


def transfer_failure(error: OSError, trust: TrustRoots) -> TransferRejection:
    if isinstance(error, urllib.error.HTTPError):
        if error.code == 404:
            return TransferFailure.NOT_FOUND
        if 100 <= error.code <= 599:
            return TransferRejected(TransferFailure.HTTP_STATUS, trust, error.code)
        return TransferRejected(TransferFailure.UNAVAILABLE, trust)
    wrapped = isinstance(error, urllib.error.URLError)
    cause = error.reason if isinstance(error, urllib.error.URLError) else error
    return TransferRejected(
        TransferFailure.TLS_CERTIFICATE_UNTRUSTED
        if isinstance(cause, ssl.SSLCertVerificationError)
        else TransferFailure.TLS_FAILED
        if isinstance(cause, ssl.SSLError)
        else TransferFailure.TIMEOUT
        if isinstance(cause, TimeoutError)
        else TransferFailure.CONNECTION_FAILED
        if isinstance(cause, ConnectionError) or (wrapped and isinstance(cause, OSError))
        else TransferFailure.UNAVAILABLE,
        trust,
    )


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    @override
    def redirect_request(
        self,
        req: urllib.request.Request,
        fp: IO[bytes],
        code: int,
        msg: str,
        headers: HTTPMessage,
        newurl: str,
    ) -> urllib.request.Request | None:
        if not newurl.startswith("https://"):
            raise urllib.error.URLError("release redirect does not use HTTPS")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class HttpReleaseSource:
    def __init__(
        self,
        trust: Callable[[], tuple[ssl.SSLContext, TrustRoots]] = process_trust,
        *,
        system: str | None = None,
        environment: Mapping[str, str] | None = None,
        native: Callable[
            [tuple[str, ...], IO[bytes]], subprocess.CompletedProcess[bytes]
        ] = run_native_https,
    ) -> None:
        self.trust = trust
        environment = os.environ if environment is None else environment
        self.native = native
        self.use_native = (
            (platform.system() if system is None else system) == "Darwin"
            and "SSL_CERT_FILE" not in environment
            and "SSL_CERT_DIR" not in environment
        )

    def open(self, url: str) -> IO[bytes]:
        context, _ = self.trust()
        request = urllib.request.Request(
            url, headers={"Cache-Control": "no-cache", "User-Agent": "kast-install"}
        )
        opener = urllib.request.build_opener(
            HttpsRedirects(), urllib.request.HTTPSHandler(context=context)
        )
        stream: IO[bytes] = opener.open(request, timeout=30)
        return stream

    def read(self, url: str, limit: int) -> bytes | TransferRejection:
        try:
            if self.use_native:
                with tempfile.TemporaryDirectory() as directory:
                    destination = Path(directory) / "response"
                    result = self.native_download(url, destination, limit)
                    return (
                        result
                        if isinstance(result, TRANSFER_REJECTIONS)
                        else destination.read_bytes()
                    )
            with self.open(url) as stream:
                data: bytes = stream.read(limit + 1)
            return data if len(data) <= limit else TransferFailure.LIMIT_EXCEEDED
        except OSError as failure:
            return transfer_failure(failure, self.trust()[1])

    def download(
        self, url: str, destination: Path, limit: int
    ) -> DownloadedFile | TransferRejection:
        if self.use_native:
            return self.native_download(url, destination, limit)
        digest, size = hashlib.sha256(), 0
        try:
            with self.open(url) as stream, destination.open("xb") as output:
                while block := stream.read(min(65_536, limit - size + 1)):
                    size += len(block)
                    if size > limit:
                        return TransferFailure.LIMIT_EXCEEDED
                    digest.update(block)
                    _ = output.write(block)
            return DownloadedFile(destination, digest.hexdigest(), size)
        except OSError as failure:
            return transfer_failure(failure, self.trust()[1])

    def native_download(
        self, url: str, destination: Path, limit: int
    ) -> DownloadedFile | TransferRejection:
        # Disable curlrc, pin Apple's transport and prohibit cleartext redirects.
        # No PEM export can retain SecTrust's deny, policy and hostname constraints.
        command = (
            "/usr/bin/curl",
            "--disable",
            "--silent",
            "--fail",
            "--location",
            "--proto",
            "=https",
            "--proto-redir",
            "=https",
            "--max-redirs",
            "10",
            "--connect-timeout",
            "30",
            "--speed-time",
            "30",
            "--speed-limit",
            "1",
            "--max-time",
            "1800",
            "--max-filesize",
            str(limit),
            "--header",
            "Cache-Control: no-cache",
            "--user-agent",
            "kast-install",
            "--write-out",
            "%{stderr}%{http_code}",
            "--url",
            url,
        )
        try:
            with destination.open("xb") as output:
                result = self.native(command, output)
            if result.returncode != 0:
                return native_transfer_failure(result.returncode, result.stderr)
            size = destination.stat().st_size
            if size > limit:
                return TransferFailure.LIMIT_EXCEEDED
            return DownloadedFile(destination, file_digest(destination), size)
        except subprocess.TimeoutExpired:
            return TransferRejected(TransferFailure.TIMEOUT, TrustRoots.KEYCHAIN)
        except OSError:
            return TransferRejected(TransferFailure.UNAVAILABLE, TrustRoots.KEYCHAIN)


def native_transfer_failure(code: int, status: bytes) -> TransferRejection:
    if len(status) == 3 and status.isdigit() and 400 <= int(status) <= 599:
        return (
            TransferFailure.NOT_FOUND
            if status == b"404"
            else TransferRejected(TransferFailure.HTTP_STATUS, TrustRoots.KEYCHAIN, int(status))
        )
    cause = {
        5: TransferFailure.CONNECTION_FAILED,
        6: TransferFailure.CONNECTION_FAILED,
        7: TransferFailure.CONNECTION_FAILED,
        28: TransferFailure.TIMEOUT,
        35: TransferFailure.TLS_FAILED,
        60: TransferFailure.TLS_CERTIFICATE_UNTRUSTED,
        63: TransferFailure.LIMIT_EXCEEDED,
    }.get(code, TransferFailure.UNAVAILABLE)
    return TransferRejected(cause, TrustRoots.KEYCHAIN)


class LocalReleaseSource:
    """Transport local product bytes; normal product admission remains authoritative."""

    def __init__(self, directory: Path):
        self.directory = directory.absolute()

    def path(self, url: str) -> Path | TransferFailure:
        name = url.rsplit("/", 1)[-1]
        if not name or name in {".", ".."} or "\\" in name:
            return TransferFailure.NOT_FOUND
        parent = self.directory if name == "build-record.json" else self.directory / "payload"
        path = parent / name
        if (
            self.directory.is_symlink()
            or parent.is_symlink()
            or not parent.is_dir()
            or path.is_symlink()
            or not path.is_file()
        ):
            return TransferFailure.NOT_FOUND
        return path

    def read(self, url: str, limit: int) -> bytes | TransferFailure:
        try:
            path = self.path(url)
            if isinstance(path, TransferFailure):
                return path
            with path.open("rb") as stream:
                raw = stream.read(limit + 1)
            return raw if len(raw) <= limit else TransferFailure.LIMIT_EXCEEDED
        except OSError:
            return TransferFailure.UNAVAILABLE

    def download(self, url: str, destination: Path, limit: int) -> DownloadedFile | TransferFailure:
        size, digest = 0, hashlib.sha256()
        try:
            path = self.path(url)
            if isinstance(path, TransferFailure):
                return path
            with path.open("rb") as stream, destination.open("xb") as output:
                while block := stream.read(min(65_536, limit - size + 1)):
                    size += len(block)
                    if size > limit:
                        return TransferFailure.LIMIT_EXCEEDED
                    digest.update(block)
                    _ = output.write(block)
            return DownloadedFile(destination, digest.hexdigest(), size)
        except OSError:
            return TransferFailure.UNAVAILABLE

    def selection(self) -> ProductSelection | Rejected:
        raw = self.read("build-record.json", MAXIMUM_RECORD_BYTES)
        if isinstance(raw, TRANSFER_REJECTIONS):
            return Rejected(Failure.ARTIFACT_INVALID, Stage.RECORDS, str(self.directory))
        record = decode_installed_product_record(raw)
        if isinstance(record, Failure):
            return Rejected(Failure.ARTIFACT_INVALID, Stage.RECORDS, str(self.directory))
        return ProductSelection(
            f"developer-v{record.version}",
            record,
            hashlib.sha256(raw).hexdigest(),
            "PinnedBuild",
            None,
            record.sourceRevision,
        )


def mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def sequence(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    document = dict(pairs)
    if len(document) != len(pairs):
        raise ValueError("duplicate JSON field")
    return document


def document(raw: bytes) -> object:
    return json.loads(raw, object_pairs_hook=unique_object)


def revision(value: object) -> TypeGuard[str]:
    return isinstance(value, str) and SHA.fullmatch(value) is not None and value != "0" * 40


def positive(value: object) -> TypeGuard[int]:
    return type(value) is int and value > 0


def bounded_text(value: object) -> TypeGuard[str]:
    return isinstance(value, str) and 0 < len(value) <= 160


PRODUCT_TARGETS = ("linux-x86_64", "macos-aarch64")
MACOS_PRODUCT_SCHEMA = 5


@dataclass(frozen=True, slots=True)
class ProductAsset:
    name: str
    sha256: str
    bytes: int


@dataclass(frozen=True, slots=True)
class ProductProducer:
    runId: int
    runAttempt: int
    workflow: str


@dataclass(frozen=True, slots=True)
class LocalProductProducer:
    kind: Literal["LOCAL"] = "LOCAL"


@dataclass(frozen=True, slots=True)
class ProductInputs:
    javaReleaseSha256: str
    dependencyLocksSha256: str
    compositionRecipeSha256: str
    donorSha256: str


@dataclass(frozen=True, slots=True)
class NativeCompositionEvidence:
    baseSha256: str
    nativeRuntimeSha256: str
    nativeBundleSha256: str
    runtimeSha256: str


@dataclass(frozen=True, slots=True)
class NativeQualification:
    receipt: ProductAsset
    producer: ProductProducer | LocalProductProducer
    bundleSha256: str
    runtimeSha256: str
    controlSha256: str
    kind: str = "NATIVE"
    outcome: str = "COMPLETE"
    composition: NativeCompositionEvidence | None = None


@dataclass(frozen=True, slots=True)
class ProductTarget:
    target: str
    inputs: ProductInputs
    bundle: ProductAsset
    runtime: ProductAsset
    qualification: NativeQualification
    runtimeBase: ProductAsset | None = None

    def assets(self) -> tuple[ProductAsset, ...]:
        return (self.bundle, self.runtime, self.qualification.receipt) + (
            (self.runtimeBase,) if self.runtimeBase else ()
        )


@dataclass(frozen=True, slots=True)
class ProductRecord:
    schemaVersion: int
    repository: str
    sourceRevision: str
    sourceTree: str
    version: str
    producer: ProductProducer
    targets: tuple[ProductTarget, ...]

    def assets(self) -> tuple[ProductAsset, ...]:
        return tuple(asset for target in self.targets for asset in target.assets())


@dataclass(frozen=True, slots=True)
class LocalProductRecord:
    schemaVersion: Literal[4]
    repository: str
    sourceRevision: str
    sourceTree: str
    version: str
    producer: LocalProductProducer
    targets: tuple[ProductTarget, ...]

    def assets(self) -> tuple[ProductAsset, ...]:
        return tuple(asset for target in self.targets for asset in target.assets())


def product_asset(value: object, expected_name: str) -> ProductAsset | Failure:
    if not mapping(value) or set(value) != {"name", "sha256", "bytes"}:
        return Failure.ARTIFACT_INVALID
    name, sha, size = value["name"], value["sha256"], value["bytes"]
    if (
        name != expected_name
        or not isinstance(sha, str)
        or not DIGEST.fullmatch(sha)
        or not positive(size)
        or size >= 2 * 1024**3
    ):
        return Failure.ARTIFACT_INVALID
    return ProductAsset(expected_name, sha, size)


def product_producer(value: object, workflow: str) -> ProductProducer | Failure:
    if not mapping(value) or set(value) != {"runId", "runAttempt", "workflow"}:
        return Failure.ARTIFACT_INVALID
    run, attempt = value["runId"], value["runAttempt"]
    if not positive(run) or not positive(attempt) or value["workflow"] != workflow:
        return Failure.ARTIFACT_INVALID
    return ProductProducer(run, attempt, workflow)


def product_target(value: object, version: str) -> ProductTarget | Failure:
    if not mapping(value) or set(value) - {"runtimeBase"} != {
        "target",
        "inputs",
        "bundle",
        "runtime",
        "qualification",
    }:
        return Failure.ARTIFACT_INVALID
    target, inputs, evidence = value["target"], value["inputs"], value["qualification"]
    if not isinstance(target, str) or target not in PRODUCT_TARGETS:
        return Failure.ARTIFACT_INVALID
    if not mapping(inputs) or set(inputs) != {
        "javaReleaseSha256",
        "dependencyLocksSha256",
        "compositionRecipeSha256",
        "donorSha256",
    }:
        return Failure.ARTIFACT_INVALID
    digests: list[str] = []
    for key in (
        "javaReleaseSha256",
        "dependencyLocksSha256",
        "compositionRecipeSha256",
        "donorSha256",
    ):
        digest = inputs[key]
        if not isinstance(digest, str) or not DIGEST.fullmatch(digest):
            return Failure.ARTIFACT_INVALID
        digests.append(digest)
    bundle = product_asset(value["bundle"], f"kast-composed-setup-v{version}-{target}.tar.gz")
    runtime = product_asset(value["runtime"], f"kast-runtime-v{version}-{target}.zip")
    if (
        isinstance(bundle, Failure)
        or isinstance(runtime, Failure)
        or not mapping(evidence)
        or set(evidence) - {"composition"}
        != {
            "receipt",
            "producer",
            "bundleSha256",
            "runtimeSha256",
            "controlSha256",
            "kind",
            "outcome",
        }
    ):
        return Failure.ARTIFACT_INVALID
    base: ProductAsset | None = None
    composition: NativeCompositionEvidence | None = None
    base_value, composition_value = value.get("runtimeBase"), evidence.get("composition")
    if base_value is not None or composition_value is not None:
        if (
            not mapping(base_value)
            or not isinstance(base_value.get("sha256"), str)
            or not mapping(composition_value)
            or set(composition_value)
            != {"baseSha256", "nativeRuntimeSha256", "nativeBundleSha256", "runtimeSha256"}
        ):
            return Failure.ARTIFACT_INVALID
        parsed_base = product_asset(
            base_value, f"kast-runtime-base-{target}-{base_value['sha256']}.zip"
        )
        if isinstance(parsed_base, Failure):
            return parsed_base
        witnesses: list[str] = []
        for key in ("baseSha256", "nativeRuntimeSha256", "nativeBundleSha256", "runtimeSha256"):
            witness = composition_value[key]
            if not isinstance(witness, str) or not DIGEST.fullmatch(witness):
                return Failure.ARTIFACT_INVALID
            witnesses.append(witness)
        if witnesses[0] != parsed_base.sha256 or witnesses[3] != runtime.sha256:
            return Failure.ARTIFACT_INVALID
        base, composition = parsed_base, NativeCompositionEvidence(*witnesses)
    receipt = product_asset(evidence["receipt"], f"qualification-{target}.json")
    provenance = evidence["producer"]
    producer = (
        LocalProductProducer()
        if mapping(provenance) and set(provenance) == {"kind"} and provenance["kind"] == "LOCAL"
        else product_producer(provenance, ".github/workflows/composed-linux.yml")
    )
    control = evidence["controlSha256"]
    if (
        isinstance(receipt, Failure)
        or isinstance(producer, Failure)
        or not isinstance(control, str)
        or not DIGEST.fullmatch(control)
        or evidence["bundleSha256"] != bundle.sha256
        or evidence["runtimeSha256"] != runtime.sha256
        or evidence["kind"] != "NATIVE"
        or evidence["outcome"] != "COMPLETE"
    ):
        return Failure.ARTIFACT_INVALID
    return ProductTarget(
        target,
        ProductInputs(*digests),
        bundle,
        runtime,
        NativeQualification(
            receipt, producer, bundle.sha256, runtime.sha256, control, composition=composition
        ),
        base,
    )


def parse_installed_product_record(value: object) -> ProductRecord | LocalProductRecord | Failure:
    """Schema evidence only; producer and retained byte admission belong to the publisher."""
    if not mapping(value) or set(value) != {
        "schemaVersion",
        "repository",
        "sourceRevision",
        "sourceTree",
        "version",
        "producer",
        "targets",
    }:
        return Failure.ARTIFACT_INVALID
    build, tree, version, targets = (
        value[key] for key in ("sourceRevision", "sourceTree", "version", "targets")
    )
    provenance = value["producer"]
    producer = (
        LocalProductProducer()
        if value["schemaVersion"] == 4
        and mapping(provenance)
        and set(provenance) == {"kind"}
        and provenance["kind"] == "LOCAL"
        else product_producer(provenance, ".github/workflows/ci.yml")
    )
    if (
        type(value["schemaVersion"]) is not int
        or value["schemaVersion"] not in (2, 3, 4, MACOS_PRODUCT_SCHEMA)
        or value["repository"] != REPOSITORY
        or not revision(build)
        or not revision(tree)
        or not isinstance(version, str)
        or len(version) > 64
        or not re.fullmatch(r"0\.0\.[1-9][0-9]*", version)
        or isinstance(producer, Failure)
        or not sequence(targets)
        or len(targets) != (1 if value["schemaVersion"] in (4, MACOS_PRODUCT_SCHEMA) else 2)
    ):
        return Failure.ARTIFACT_INVALID
    facts: list[ProductTarget] = []
    for target in targets:
        admitted = product_target(target, version)
        if isinstance(admitted, Failure):
            return admitted
        facts.append(admitted)
    if isinstance(producer, LocalProductProducer):
        if any(not isinstance(item.qualification.producer, LocalProductProducer) for item in facts):
            return Failure.ARTIFACT_INVALID
        return LocalProductRecord(4, REPOSITORY, build, tree, version, producer, tuple(facts))
    if value["schemaVersion"] == 4 or any(
        not isinstance(item.qualification.producer, ProductProducer) for item in facts
    ):
        return Failure.ARTIFACT_INVALID
    required = (
        ("macos-aarch64",) if value["schemaVersion"] == MACOS_PRODUCT_SCHEMA else PRODUCT_TARGETS
    )
    if tuple(item.target for item in facts) != required:
        return Failure.ARTIFACT_INVALID
    if any(
        (item.runtimeBase is not None) != (value["schemaVersion"] in (3, MACOS_PRODUCT_SCHEMA))
        for item in facts
    ):
        return Failure.ARTIFACT_INVALID
    return ProductRecord(
        value["schemaVersion"], REPOSITORY, build, tree, version, producer, tuple(facts)
    )


def parse_product_record(value: object) -> ProductRecord | Failure:
    record = parse_installed_product_record(value)
    return Failure.ARTIFACT_INVALID if isinstance(record, LocalProductRecord) else record


def decode_product_record(raw: bytes) -> ProductRecord | Failure:
    if len(raw) > MAXIMUM_RECORD_BYTES:
        return Failure.ARTIFACT_INVALID
    try:
        return parse_product_record(document(raw))
    except (ValueError, UnicodeError, RecursionError):
        return Failure.ARTIFACT_INVALID


def decode_installed_product_record(raw: bytes) -> ProductRecord | LocalProductRecord | Failure:
    if len(raw) > MAXIMUM_RECORD_BYTES:
        return Failure.ARTIFACT_INVALID
    try:
        return parse_installed_product_record(document(raw))
    except (ValueError, UnicodeError, RecursionError):
        return Failure.ARTIFACT_INVALID


def native_receipt_matches(target: ProductTarget, receipt: object) -> bool:
    from dataclasses import asdict

    if not mapping(receipt) or receipt.get("qualification") != "COMPLETE":
        return False
    composition = target.qualification.composition
    expected = composition.nativeRuntimeSha256 if composition else target.runtime.sha256
    # Published component-native receipts remain admissible without relabelling
    # their proof. New assembly receipts do not claim installed acceptance.
    version = receipt.get("schemaVersion", 1)
    if type(version) is not int:
        return False
    if version == 1:
        if (
            receipt.get("qualificationStage") != "COMPONENT_NATIVE"
            or not isinstance(harness := receipt.get("harnessSha256"), str)
            or not DIGEST.fullmatch(harness)
            or not mapping(receipt.get("probe"))
            or not mapping(containers := receipt.get("containers"))
        ):
            return False
        runtime, control = containers.get("runtimeSha256"), containers.get("controlSha256")
    elif version == 2:
        if receipt.get("qualificationStage") != "COMPONENT_ASSEMBLY" or set(receipt) != {
            "schemaVersion",
            "qualificationStage",
            "qualification",
            "sourceRevision",
            "bundleSha256",
            "runtimeSha256",
            "controlSha256",
            "composition",
        }:
            return False
        runtime, control = receipt.get("runtimeSha256"), receipt.get("controlSha256")
    else:
        return False
    return (
        runtime == expected
        and control == target.qualification.controlSha256
        and receipt.get("composition") == (asdict(composition) if composition else None)
    )


@dataclass(frozen=True, slots=True)
class ReleaseMapping:
    schemaVersion: int
    publicVersion: str
    buildRecordSha256: str


def parse_release_mapping(value: object) -> ReleaseMapping | Failure:
    if not mapping(value) or set(value) != {"schemaVersion", "publicVersion", "buildRecordSha256"}:
        return Failure.ARTIFACT_INVALID
    version, digest = value["publicVersion"], value["buildRecordSha256"]
    if (
        type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 1
        or not isinstance(version, str)
        or len(version) > 64
        or not VERSION.fullmatch(version)
        or not isinstance(digest, str)
        or not DIGEST.fullmatch(digest)
    ):
        return Failure.ARTIFACT_INVALID
    return ReleaseMapping(1, version, digest)


@dataclass(frozen=True, slots=True)
class ProductSelection:
    releaseTag: str
    record: ProductRecord | LocalProductRecord
    recordSha256: str
    policy: str
    publicVersion: str | None
    mainRevision: str | None
    type: Literal["Product"] = "Product"

    @property
    def version(self) -> str:
        return self.record.version


def parse_product_selection(value: object) -> ProductSelection | Failure:
    if (
        not mapping(value)
        or set(value)
        != {
            "type",
            "releaseTag",
            "record",
            "recordSha256",
            "policy",
            "publicVersion",
            "mainRevision",
        }
        or value["type"] != "Product"
    ):
        return Failure.SELECTOR_INVALID
    record = parse_installed_product_record(value["record"])
    tag, digest, policy, public, main = (
        value[key]
        for key in ("releaseTag", "recordSha256", "policy", "publicVersion", "mainRevision")
    )
    if (
        isinstance(record, Failure)
        or not isinstance(tag, str)
        or not isinstance(digest, str)
        or not DIGEST.fullmatch(digest)
        or not isinstance(policy, str)
        or policy not in ("TrackStable", "TrackMain", "PinnedBuild")
    ):
        return Failure.SELECTOR_INVALID
    if isinstance(record, LocalProductRecord) and (policy != "PinnedBuild" or public is not None):
        return Failure.SELECTOR_INVALID
    if public is None:
        if (
            tag != f"developer-v{record.version}"
            or not revision(main)
            or main != record.sourceRevision
            or policy == "TrackStable"
        ):
            return Failure.SELECTOR_INVALID
    elif (
        not isinstance(public, str)
        or len(public) > 64
        or not VERSION.fullmatch(public)
        or tag != f"v{public}"
        or main is not None
        or policy == "TrackMain"
    ):
        return Failure.SELECTOR_INVALID
    return ProductSelection(tag, record, digest, policy, public, main)


def parse_identity(value: object) -> BuildIdentity | Failure:
    if not mapping(value) or set(value) != {
        "releaseTag",
        "version",
        "buildRevision",
        "sourceTree",
        "mainRevision",
    }:
        return Failure.SELECTOR_INVALID
    tag, version, build, tree, main = (
        value[key]
        for key in ("releaseTag", "version", "buildRevision", "sourceTree", "mainRevision")
    )
    if (
        not isinstance(tag, str)
        or not isinstance(version, str)
        or not VERSION.fullmatch(version)
        or len(version) > 64
        or not revision(build)
        or not revision(tree)
    ):
        return Failure.SELECTOR_INVALID
    if (main is None and tag == f"v{version}") or (
        revision(main) and tag == f"developer-v{version}"
    ):
        return BuildIdentity(tag, version, build, tree, main)
    return Failure.SELECTOR_INVALID


def parse_entry(value: object) -> MainEntry | Failure:
    if not mapping(value) or set(value) != {
        "mainRevision",
        "buildRevision",
        "sourceTree",
        "releaseTag",
        "version",
        "platformTargets",
        "candidate",
        "promotion",
    }:
        return Failure.CATALOG_UNAVAILABLE
    main, build, tree, tag, version, platforms = (
        value[key]
        for key in (
            "mainRevision",
            "buildRevision",
            "sourceTree",
            "releaseTag",
            "version",
            "platformTargets",
        )
    )
    if (
        not revision(main)
        or not revision(build)
        or not revision(tree)
        or not isinstance(tag, str)
        or not isinstance(version, str)
        or not re.fullmatch(r"0\.0\.[1-9][0-9]*", version)
        or tag != f"developer-v{version}"
        or not sequence(platforms)
    ):
        return Failure.CATALOG_UNAVAILABLE
    targets: list[str] = []
    for target in platforms:
        if (
            not isinstance(target, str)
            or not re.fullmatch(r"[a-z0-9]+-[a-z0-9]+", target)
            or target in targets
        ):
            return Failure.CATALOG_UNAVAILABLE
        targets.append(target)
    if not targets:
        return Failure.CATALOG_UNAVAILABLE
    references: list[RecordReference] = []
    for key, expected in (("candidate", "candidate.json"), ("promotion", "promotion.json")):
        record = value[key]
        if (
            not mapping(record)
            or set(record) != {"name", "sha256"}
            or record["name"] != expected
            or not isinstance(record["sha256"], str)
            or not DIGEST.fullmatch(record["sha256"])
        ):
            return Failure.CATALOG_UNAVAILABLE
        references.append(RecordReference(expected, record["sha256"]))
    return MainEntry(main, build, tree, tag, version, tuple(targets), references[0], references[1])


def decode_selection(raw: bytes) -> Selection | ProductSelection | Rejected:
    if len(raw) > MAXIMUM_RECORD_BYTES:
        return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, "captured selection")
    try:
        value = document(raw)
    except (ValueError, UnicodeError, RecursionError):
        return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, "captured selection")
    if not mapping(value):
        return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, "captured selection")
    if value.get("type") == "Product":
        selected = parse_product_selection(value)
        return (
            Rejected(selected, Stage.SELECTION, "captured selection")
            if isinstance(selected, Failure)
            else selected
        )
    if set(value) == {"type", "version", "releaseTag", "policy"} and value["type"] == "Stable":
        version, tag, policy = value["version"], value["releaseTag"], value["policy"]
        if (
            isinstance(version, str)
            and len(version) <= 64
            and VERSION.fullmatch(version)
            and tag == f"v{version}"
            and policy in ("TrackStable", "PinnedBuild")
        ):
            return StableSelection(version, f"v{version}", StablePolicy(policy))
    if set(value) == {"type", "identity", "policy", "catalogEntry"} and value["type"] == "Main":
        identity, entry, policy = (
            parse_identity(value["identity"]),
            parse_entry(value["catalogEntry"]),
            value["policy"],
        )
        if (
            not isinstance(identity, Failure)
            and not isinstance(entry, Failure)
            and identity == entry.identity()
            and mapping(policy)
        ):
            if policy == {"type": "TrackMain"}:
                return MainSelection(identity, TrackMain(), entry)
            if (
                set(policy) == {"type", "identity"}
                and policy["type"] == "PinnedBuild"
                and parse_identity(policy["identity"]) == identity
            ):
                return MainSelection(identity, PinnedBuild(identity), entry)
    return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, "captured selection")


def resolve_selection(
    channel: str | None, version: str | None, commit: str | None, source: ReleaseSource
) -> Selection | ProductSelection | Rejected:
    requested = commit or version or channel or "stable"
    if sum(value is not None for value in (channel, version, commit)) > 1:
        return Rejected(Failure.SELECTORS_CONFLICT, Stage.SELECTION, requested)
    if version is not None:
        return (
            StableSelection(version, f"v{version}", StablePolicy.PIN)
            if len(version) <= 64 and VERSION.fullmatch(version)
            else Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, requested)
        )
    if commit is not None and (
        len(commit) not in (12, 40) or not re.fullmatch(r"[0-9a-f]+", commit)
    ):
        return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, requested)
    if channel not in (None, "stable", "preview"):
        return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, requested)
    if commit is None and channel in (None, "stable"):
        raw = source.read(STABLE_URL, MAXIMUM_RECORD_BYTES)
        if isinstance(raw, TRANSFER_REJECTIONS):
            return Rejected(
                Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested, transfer=transfer(raw)
            )
        try:
            stable = document(raw)
        except (ValueError, UnicodeError, RecursionError):
            return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
        if mapping(stable) and stable.get("draft") is False and stable.get("prerelease") is False:
            tag = stable.get("tag_name")
            if (
                isinstance(tag, str)
                and tag.startswith("v")
                and len(tag) <= 65
                and VERSION.fullmatch(tag[1:])
            ):
                return StableSelection(tag[1:], tag, StablePolicy.TRACK)
        return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
    raw = source.read(CATALOG_URL, MAXIMUM_RECORD_BYTES)
    if isinstance(raw, TRANSFER_REJECTIONS):
        return Rejected(
            Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested, transfer=transfer(raw)
        )
    try:
        parsed = document(raw)
    except (ValueError, UnicodeError, RecursionError):
        return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
    if (
        not mapping(parsed)
        or set(parsed) != {"schemaVersion", "builds"}
        or type(parsed["schemaVersion"]) is not int
        or parsed["schemaVersion"] not in (1, 2)
        or not sequence(parsed["builds"])
        or len(parsed["builds"]) > 5
    ):
        return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
    if parsed["schemaVersion"] == 2:
        products: list[ProductSelection] = []
        for item in parsed["builds"]:
            if mapping(item) and set(item) == {
                "mainRevision",
                "releaseTag",
                "record",
                "recordSha256",
            }:
                record = parse_product_record(item["record"])
                main, tag, digest = item["mainRevision"], item["releaseTag"], item["recordSha256"]
                if (
                    isinstance(record, Failure)
                    or not revision(main)
                    or main != record.sourceRevision
                    or not isinstance(tag, str)
                    or tag != f"developer-v{record.version}"
                    or not isinstance(digest, str)
                    or not DIGEST.fullmatch(digest)
                ):
                    return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
                products.append(
                    ProductSelection(
                        tag,
                        record,
                        digest,
                        "TrackMain" if commit is None else "PinnedBuild",
                        None,
                        main,
                    )
                )
            else:
                return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
        revisions = [item.mainRevision for item in products]
        if len(set(revisions)) != len(revisions):
            return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
        matches = (
            products[:1]
            if commit is None
            else [
                item
                for item in products
                if item.mainRevision is not None and item.mainRevision.startswith(commit)
            ]
        )
        if len(matches) > 1:
            return Rejected(Failure.AMBIGUOUS_COMMIT, Stage.SELECTION, requested)
        return (
            matches[0]
            if matches
            else Rejected(Failure.BINARY_UNAVAILABLE, Stage.SELECTION, requested)
        )
    entries: list[MainEntry] = []
    for item in parsed["builds"]:
        admitted = parse_entry(item)
        if isinstance(admitted, Failure) or any(
            prior.mainRevision == admitted.mainRevision for prior in entries
        ):
            return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
        entries.append(admitted)
    selectable = tuple(entry.mainRevision for entry in entries)
    matches = (
        entries[:1]
        if commit is None
        else [entry for entry in entries if entry.mainRevision.startswith(commit)]
    )
    if len(matches) != 1:
        return Rejected(
            Failure.AMBIGUOUS_COMMIT if len(matches) > 1 else Failure.BINARY_UNAVAILABLE,
            Stage.SELECTION,
            requested,
            selectable=selectable,
        )
    selected = matches[0]
    if PLATFORM not in selected.platformTargets:
        return Rejected(
            Failure.PLATFORM_UNAVAILABLE, Stage.SELECTION, requested, selectable=selectable
        )
    identity = selected.identity()
    return MainSelection(
        identity, TrackMain() if commit is None else PinnedBuild(identity), selected
    )


@dataclass(frozen=True, slots=True)
class HostedContract:
    runtimeProtocolIdentity: str
    operationRegistryDigest: str
    wireSchemaDigest: str
    capabilities: tuple[str, ...]
    type: Literal["HOSTED_CONTRACT"] = "HOSTED_CONTRACT"


def parse_contract(value: object) -> HostedContract | Failure:
    if (
        not mapping(value)
        or set(value)
        != {
            "type",
            "runtimeProtocolIdentity",
            "operationRegistryDigest",
            "wireSchemaDigest",
            "capabilities",
        }
        or value["type"] != "HOSTED_CONTRACT"
    ):
        return Failure.ARTIFACT_INVALID
    protocol, registry, wire, capabilities = (
        value[key]
        for key in (
            "runtimeProtocolIdentity",
            "operationRegistryDigest",
            "wireSchemaDigest",
            "capabilities",
        )
    )
    if (
        not isinstance(protocol, str)
        or len(protocol) > 128
        or protocol != "kast.ide-hosted.runtime.v3"
        or not isinstance(registry, str)
        or not isinstance(wire, str)
        or not all(re.fullmatch(r"sha256:[0-9a-f]{64}", digest) for digest in (registry, wire))
        or not sequence(capabilities)
    ):
        return Failure.ARTIFACT_INVALID
    names = (
        "workspace.lifecycle",
        "query.run",
        "source.read",
        "diagnostic.check",
        "change.plan",
        "change.apply",
        "change.recover",
    )
    if (
        len(capabilities) != len(names)
        or not all(isinstance(item, str) for item in capabilities)
        or set(capabilities) != set(names)
    ):
        return Failure.ARTIFACT_INVALID
    return HostedContract(protocol, registry, wire, names)


@dataclass(frozen=True, slots=True)
class ComponentRecord:
    version: str
    source_revision: str
    artifact_name: str
    sha256: str
    bytes: int
    contract: HostedContract


def parse_component(
    raw: bytes, version: str, artifact_name: str, idea_line: str | None
) -> ComponentRecord | Failure:
    try:
        value = document(raw)
    except (ValueError, UnicodeError, RecursionError):
        return Failure.ARTIFACT_INVALID
    host = idea_line is not None
    fields = (
        {
            "type",
            "hostedPluginVersion",
            "artifact",
            "providedHostedContract",
            "supportedIntellijReleaseLine",
            "sourceRevision",
        }
        if host
        else {"type", "controlVersion", "artifact", "requiredHostedContract", "sourceRevision"}
    )
    if (
        not mapping(value)
        or set(value) != fields
        or value["type"] != ("HOST_RELEASE" if host else "CONTROL_RELEASE")
        or value["hostedPluginVersion" if host else "controlVersion"] != version
        or (host and value["supportedIntellijReleaseLine"] != idea_line)
        or not revision(value["sourceRevision"])
    ):
        return Failure.ARTIFACT_INVALID
    artifact = value["artifact"]
    if (
        not mapping(artifact)
        or set(artifact) != {"fileName", "sha256", "bytes"}
        or artifact["fileName"] != artifact_name
    ):
        return Failure.ARTIFACT_INVALID
    sha, size = artifact["sha256"], artifact["bytes"]
    contract = parse_contract(value["providedHostedContract" if host else "requiredHostedContract"])
    if (
        not isinstance(sha, str)
        or not re.fullmatch(r"sha256:[0-9a-f]{64}", sha)
        or type(size) is not int
        or not 1 <= size <= MAXIMUM_PAYLOAD_BYTES
        or isinstance(contract, Failure)
    ):
        return Failure.ARTIFACT_INVALID
    return ComponentRecord(
        version, value["sourceRevision"], artifact_name, sha.removeprefix("sha256:"), size, contract
    )


@dataclass(frozen=True, slots=True)
class AdmittedComposition:
    identity: BuildIdentity
    policy: UpdatePolicy
    control_root: Path
    control_archive: Path
    host_archive: Path
    control_record: Path
    host_record: Path
    control_digest: str
    host_digest: str
    contract: HostedContract
    integration_digest: str


def asset_url(selection: Selection, name: str) -> str:
    return f"{RELEASE_URL}/{selection.releaseTag}/{name}"


def file_digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked_record(
    selection: Selection, name: str, directory: Path, source: ReleaseSource
) -> bytes | Failure:
    checksum = source.read(asset_url(selection, name + ".sha256"), 512)
    raw = source.read(asset_url(selection, name), MAXIMUM_RECORD_BYTES)
    if isinstance(checksum, TRANSFER_REJECTIONS) or isinstance(raw, TRANSFER_REJECTIONS):
        if name.startswith("kast-control-release-") and raw is TransferFailure.NOT_FOUND:
            return Failure.INSTALLER_CONTRACT_UNSUPPORTED
        return Failure.ARTIFACT_INVALID
    expected = hashlib.sha256(raw).hexdigest()
    if checksum != f"{expected}  {name}\n".encode():
        return Failure.ARTIFACT_INVALID
    with (directory / name).open("xb") as output:
        _ = output.write(raw)
    with (directory / (name + ".sha256")).open("xb") as output:
        _ = output.write(checksum)
    return raw


def download_asset(
    selection: Selection, record: ComponentRecord, directory: Path, source: ReleaseSource
) -> DownloadedFile | Failure:
    checksum = source.read(asset_url(selection, record.artifact_name + ".sha256"), 512)
    if (
        isinstance(checksum, TRANSFER_REJECTIONS)
        or checksum != f"{record.sha256}  {record.artifact_name}\n".encode()
    ):
        return Failure.ARTIFACT_INVALID
    with (directory / (record.artifact_name + ".sha256")).open("xb") as output:
        _ = output.write(checksum)
    destination = directory / record.artifact_name
    result = source.download(asset_url(selection, record.artifact_name), destination, record.bytes)
    if result is TransferFailure.NOT_FOUND:
        return Failure.PLATFORM_UNAVAILABLE
    if isinstance(result, TRANSFER_REJECTIONS):
        return Failure.ARTIFACT_INVALID
    if (
        result.path != destination
        or destination.is_symlink()
        or not destination.is_file()
        or result.sha256 != record.sha256
        or result.bytes != record.bytes
        or destination.stat().st_size != record.bytes
        or file_digest(destination) != record.sha256
    ):
        return Failure.ARTIFACT_INVALID
    return result


def extract_control(archive: Path, destination: Path) -> Failure | Admission:
    try:
        with tarfile.open(archive, "r:gz") as source:
            members: list[tarfile.TarInfo] = []
            names: set[str] = set()
            total = 0
            for member in source:
                name = member.name.rstrip("/") if member.isdir() else member.name
                parts = name.split("/")
                total += member.size
                if (
                    len(members) >= MAXIMUM_ENTRIES
                    or total > MAXIMUM_PAYLOAD_BYTES
                    or member.size < 0
                    or len(name) > 4096
                    or len(parts) > 32
                    or "\\" in name
                    or any(part in ("", ".", "..") for part in parts)
                    or parts[0] not in ("bin", "lib", "share")
                    or not (member.isdir() or member.isreg())
                    or name in names
                ):
                    return Failure.ARTIFACT_INVALID
                names.add(name)
                members.append(member)
            if not members:
                return Failure.ARTIFACT_INVALID
            destination.mkdir(mode=0o700)
            for member in members:
                path = destination.joinpath(*PurePosixPath(member.name).parts)
                if member.isdir():
                    path.mkdir(parents=True, exist_ok=True)
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    source_file = source.extractfile(member)
                    if source_file is None:
                        return Failure.ARTIFACT_INVALID
                    with source_file, path.open("xb") as output:
                        shutil.copyfileobj(source_file, output)
                    path.chmod(member.mode & 0o777)
        return Admission.ADMITTED
    except (OSError, tarfile.TarError):
        return Failure.ARTIFACT_INVALID


def inspect_host(
    archive: Path, identity: BuildIdentity, contract: HostedContract
) -> Failure | Admission:
    try:
        with zipfile.ZipFile(archive) as source:
            entries = source.infolist()
            paths = [entry.filename.rstrip("/") for entry in entries]
            if (
                not entries
                or len(entries) > MAXIMUM_ENTRIES
                or len(set(paths)) != len(paths)
                or sum(entry.file_size for entry in entries) > MAXIMUM_PAYLOAD_BYTES
                or any(
                    (path != "kast-ide-hosted" and not path.startswith("kast-ide-hosted/"))
                    or "\\" in path
                    or any(part in ("", ".", "..") for part in path.split("/"))
                    or stat.S_ISLNK(entry.external_attr >> 16)
                    for path, entry in zip(paths, entries, strict=True)
                )
            ):
                return Failure.ARTIFACT_INVALID
            hosts = [
                entry
                for entry in entries
                if entry.filename.startswith("kast-ide-hosted/lib/kast-ide-hosted")
                and entry.filename.endswith(".jar")
            ]
            if len(hosts) != 1:
                return Failure.ARTIFACT_INVALID
            with zipfile.ZipFile(io.BytesIO(source.read(hosts[0]))) as plugin:
                resource_names = (
                    "META-INF/plugin.xml",
                    "hosted-contract.json",
                    "kast-hosted-query.properties",
                )
                if any(
                    plugin.getinfo(name).file_size > MAXIMUM_RECORD_BYTES for name in resource_names
                ):
                    return Failure.ARTIFACT_INVALID
                descriptor = XML.fromstring(plugin.read("META-INF/plugin.xml"))
                if (
                    descriptor.findtext("id") != "io.github.amichne.kast.ide-hosted"
                    or descriptor.findtext("version") != identity.version
                ):
                    return Failure.ARTIFACT_INVALID
                if parse_contract(document(plugin.read("hosted-contract.json"))) != contract:
                    return Failure.ARTIFACT_INVALID
                properties: dict[str, str] = {}
                for line in (
                    plugin.read("kast-hosted-query.properties").decode("utf-8").splitlines()
                ):
                    if not line or line.startswith("#"):
                        continue
                    key, separator, value = line.partition("=")
                    if not separator or key in properties:
                        return Failure.ARTIFACT_INVALID
                    properties[key] = value
                if (
                    properties.get("version"),
                    properties.get("sourceRevision"),
                    properties.get("sourceTree"),
                ) != (identity.version, identity.buildRevision, identity.sourceTree):
                    return Failure.ARTIFACT_INVALID
        return Admission.ADMITTED
    except (OSError, ValueError, KeyError, UnicodeError, XML.ParseError, zipfile.BadZipFile):
        return Failure.ARTIFACT_INVALID


def integration_digest(control: Path) -> str:
    directory = control / "share/kast/agent-tools"
    files = sorted(path for path in directory.rglob("*") if path.is_file())
    digest = hashlib.sha256()
    for path in files:
        digest.update(path.relative_to(directory).as_posix().encode())
        digest.update(b"\0")
        digest.update(bytes.fromhex(file_digest(path)))
    return digest.hexdigest()


def native_dependency_library(stream: IO[bytes], header: bytes, target: str) -> bool:
    if len(header) != 32:
        return False
    if target == "macos-aarch64":
        return header[:8] == b"\xcf\xfa\xed\xfe\x0c\x00\x00\x01" and header[12:16] in (
            b"\x06\x00\x00\x00",
            b"\x08\x00\x00\x00",
        )
    if target == "linux-x86_64":
        return _elf_library(stream, header)
    return False


@dataclass(frozen=True, slots=True)
class _ElfSegment:
    kind: int
    offset: int
    address: int
    size: int
    memory_size: int


def _elf_library(stream: IO[bytes], header: bytes) -> bool:
    if header[:6] != b"\x7fELF\x02\x01" or header[16:24] != b"\x03\x00\x3e\x00\x01\x00\x00\x00":
        return False
    rest = stream.read(32)
    if len(rest) != 32:
        return False
    table_offset, header_size, entry_size, count = struct.unpack("<Q12xHHH6x", rest)
    length = stream.seek(0, os.SEEK_END)
    if (
        header_size != 64
        or entry_size != 56
        or not 1 <= count <= 256
        or not 64 <= table_offset <= length - count * entry_size
    ):
        return False
    _ = stream.seek(table_offset)
    table = stream.read(count * entry_size)
    if len(table) != count * entry_size:
        return False
    segments = tuple(_ElfSegment(*entry) for entry in struct.iter_unpack("<I4xQQ8xQQ8x", table))
    # ET_DYN includes PIE. Reject PT_INTERP and inspect the mapped dynamic table.
    for item in segments:
        if item.kind == 3 or item.offset + item.size > length:
            return False
        if item.kind in (1, 2) and (
            item.memory_size < item.size or item.address + item.memory_size >= 2**63
        ):
            return False
    dynamic = tuple(segment for segment in segments if segment.kind == 2)
    if len(dynamic) != 1:
        return False
    (segment,) = dynamic
    if not 16 <= segment.size <= 65_536 or segment.size % 16:
        return False
    loads = tuple(
        load
        for load in segments
        if load.kind == 1
        and load.address <= segment.address
        and segment.address + segment.memory_size <= load.address + load.memory_size
    )
    if len(loads) != 1:
        return False
    (load,) = loads
    delta = segment.address - load.address
    if segment.offset != load.offset + delta or delta + segment.size > load.size:
        return False
    _ = stream.seek(segment.offset)
    entries = stream.read(segment.size)
    if len(entries) != segment.size:
        return False
    flags_seen = False
    terminated = False
    for tag, value in struct.iter_unpack("<QQ", entries):
        if terminated and (tag != 0 or value != 0):
            return False
        if tag == 0:
            terminated = True
        elif tag == 0x6FFFFFFB:
            if flags_seen or value & 0x08000000:
                return False
            flags_seen = True
    return terminated


def inspect_control(
    control: Path, selection: Selection, record: ComponentRecord, target: str = PLATFORM
) -> BuildIdentity | Failure:
    try:
        marker = control / "share/kast/managed-install-v1"
        if not marker.is_file() or marker.read_bytes() != b"1\n":
            return Failure.INSTALLER_CONTRACT_UNSUPPORTED
        metadata = document((control / "share/kast/ide-host.json").read_bytes())
        if (
            not mapping(metadata)
            or metadata.get("schemaVersion") != 2
            or metadata.get("execution") != "existing_ide"
            or metadata.get("productVersion") != selection.version
            or parse_contract(metadata.get("requiredHostedContract")) != record.contract
        ):
            return Failure.ARTIFACT_INVALID
        build = document((control / "share/kast/build-identity.json").read_bytes())
        if (
            not mapping(build)
            or set(build) != {"version", "sourceRevision", "sourceTree"}
            or build["version"] != selection.version
            or build["sourceRevision"] != record.source_revision
            or not revision(build["sourceTree"])
        ):
            return Failure.ARTIFACT_INVALID
        identity = BuildIdentity(
            selection.releaseTag,
            selection.version,
            record.source_revision,
            build["sourceTree"],
            selection.identity.mainRevision if isinstance(selection, MainSelection) else None,
        )
        if isinstance(selection, MainSelection) and selection.identity != identity:
            return Failure.ARTIFACT_INVALID
        executable = control / "share/kast/libexec/kast-management"
        native_header = (
            b"\xcf\xfa\xed\xfe\x0c\x00\x00\x01"
            if target == "macos-aarch64"
            else b"\x7fELF\x02\x01\x01\x00"
        )
        with executable.open("rb") as stream:
            if stream.read(8) != native_header or not os.access(executable, os.X_OK):
                return Failure.ARTIFACT_INVALID
            if target == "linux-x86_64":
                _ = stream.seek(18)
                if stream.read(2) != b"\x3e\x00":
                    return Failure.ARTIFACT_INVALID
        query = control / "bin/kast-query"
        with query.open("rb") as stream:
            query_header = stream.read(8)
        if not os.access(query, os.X_OK):
            return Failure.ARTIFACT_INVALID
        jvm_layout = control / "share/kast/jvm-control-v1"
        native_layout = control / "share/kast/native-control-v1"
        if jvm_layout.exists():
            # The Control executable runs one JVM entry from lib; no bytecode lives elsewhere.
            if jvm_layout.read_bytes() != b"1\n":
                return Failure.INSTALLER_CONTRACT_UNSUPPORTED
            libraries = control / "lib"
            if (
                query_header != b"#!/bin/s"
                or not libraries.is_dir()
                or not any(libraries.glob("*.jar"))
            ):
                return Failure.ARTIFACT_INVALID
            if any(
                path.suffix == ".class" or (path.suffix == ".jar" and path.parent != libraries)
                for path in control.rglob("*")
            ):
                return Failure.ARTIFACT_INVALID
            if native_executables(control, target) != [executable]:
                return Failure.ARTIFACT_INVALID
        elif native_layout.exists():
            if native_layout.read_bytes() != b"1\n":
                return Failure.INSTALLER_CONTRACT_UNSUPPORTED
            if query_header != b"#!/bin/s" or (control / "lib").exists():
                return Failure.ARTIFACT_INVALID
            if any(path.suffix in (".jar", ".class") for path in control.rglob("*")):
                return Failure.ARTIFACT_INVALID
            if native_executables(control, target) != [executable]:
                return Failure.ARTIFACT_INVALID
        elif query_header != native_header:
            return Failure.ARTIFACT_INVALID
        if not (control / "share/kast/agent-tools/plugins/kast/skills/kast/SKILL.md").is_file():
            return Failure.INSTALLER_CONTRACT_UNSUPPORTED
        return identity
    except (OSError, ValueError, UnicodeError, RecursionError):
        return Failure.ARTIFACT_INVALID


NATIVE_HEADERS = (
    b"\xcf\xfa\xed\xfe",
    b"\xfe\xed\xfa\xcf",
    b"\x7fELF",
    b"\xce\xfa\xed\xfe",
    b"\xfe\xed\xfa\xce",
    b"\xca\xfe\xba\xbe",
    b"\xbe\xba\xfe\xca",
    b"\xca\xfe\xba\xbf",
    b"\xbf\xba\xfe\xca",
)


def native_executables(control: Path, target: str) -> list[Path]:
    """Every native file outside admitted dependency libraries; the Control executable must be the only one."""
    found: list[Path] = []
    for path in control.rglob("*"):
        if path.is_file():
            with path.open("rb") as stream:
                header = stream.read(32)
                if header[:4] in NATIVE_HEADERS and not (
                    path.is_relative_to(control / "share/kast/mcp-dependencies/site-packages")
                    and native_dependency_library(stream, header, target)
                ):
                    found.append(path)
    return found


def admit_main_records(
    selection: MainSelection, source: ReleaseSource, downloaded: tuple[DownloadedFile, ...]
) -> Failure | Admission:
    values: list[object] = []
    for reference in (selection.catalogEntry.candidate, selection.catalogEntry.promotion):
        raw = source.read(asset_url(selection, reference.name), MAXIMUM_RECORD_BYTES)
        if (
            isinstance(raw, TRANSFER_REJECTIONS)
            or hashlib.sha256(raw).hexdigest() != reference.sha256
        ):
            return Failure.ARTIFACT_INVALID
        try:
            values.append(document(raw))
        except (ValueError, UnicodeError, RecursionError):
            return Failure.ARTIFACT_INVALID
    candidate, promotion = values
    if not mapping(candidate) or not mapping(promotion):
        return Failure.ARTIFACT_INVALID
    if set(candidate) != {
        "schemaVersion",
        "repository",
        "sourceRevision",
        "sourceTree",
        "version",
        "runId",
        "runNumber",
        "runAttempt",
        "event",
        "platform",
        "toolchain",
        "assets",
    } or set(promotion) != {
        "schemaVersion",
        "repository",
        "buildRevision",
        "mergedRevision",
        "sourceTree",
        "version",
        "buildRunId",
        "buildRunAttempt",
        "mainRunId",
        "mainRunAttempt",
        "candidateSha256",
    }:
        return Failure.ARTIFACT_INVALID
    identity = selection.identity
    if (
        type(candidate.get("schemaVersion")) is not int
        or candidate.get("schemaVersion") != 1
        or candidate.get("repository") != REPOSITORY
        or candidate.get("sourceRevision") != identity.buildRevision
        or candidate.get("sourceTree") != identity.sourceTree
        or candidate.get("version") != identity.version
        or candidate.get("platform") != PLATFORM
    ):
        return Failure.ARTIFACT_INVALID
    if (
        type(promotion.get("schemaVersion")) is not int
        or promotion.get("schemaVersion") != 1
        or promotion.get("repository") != REPOSITORY
        or promotion.get("buildRevision") != identity.buildRevision
        or promotion.get("mergedRevision") != identity.mainRevision
        or promotion.get("sourceTree") != identity.sourceTree
        or promotion.get("version") != identity.version
        or promotion.get("candidateSha256") != selection.catalogEntry.candidate.sha256
        or promotion.get("buildRunId") != candidate.get("runId")
        or promotion.get("buildRunAttempt") != candidate.get("runAttempt")
    ):
        return Failure.ARTIFACT_INVALID
    if (
        any(not positive(candidate.get(key)) for key in ("runId", "runNumber", "runAttempt"))
        or any(
            not positive(promotion.get(key))
            for key in ("buildRunId", "buildRunAttempt", "mainRunId", "mainRunAttempt")
        )
        or candidate.get("event")
        not in ("pull_request", "merge_group", "push", "workflow_dispatch")
    ):
        return Failure.ARTIFACT_INVALID
    run_number = candidate.get("runNumber")
    if (
        not positive(run_number)
        or run_number > 2_147_483_647
        or identity.version != f"0.0.{run_number}"
    ):
        return Failure.ARTIFACT_INVALID
    toolchain = candidate.get("toolchain")
    if (
        not mapping(toolchain)
        or set(toolchain) != {"javaVersion", "vendor", "releaseSha256"}
        or any(not bounded_text(toolchain[key]) for key in ("javaVersion", "vendor"))
        or not isinstance(toolchain["releaseSha256"], str)
        or not DIGEST.fullmatch(toolchain["releaseSha256"])
    ):
        return Failure.ARTIFACT_INVALID
    assets = candidate.get("assets")
    if not sequence(assets) or not 1 <= len(assets) <= 32:
        return Failure.ARTIFACT_INVALID
    inventory: dict[str, tuple[str, int]] = {}
    for asset in assets:
        if not mapping(asset) or set(asset) != {"name", "sha256", "bytes"}:
            return Failure.ARTIFACT_INVALID
        name, digest, size = asset["name"], asset["sha256"], asset["bytes"]
        if (
            not isinstance(name, str)
            or not re.fullmatch(r"[A-Za-z0-9_.-]{1,160}", name)
            or name in (".", "..")
            or not isinstance(digest, str)
            or not DIGEST.fullmatch(digest)
            or type(size) is not int
            or not 0 < size <= MAXIMUM_PAYLOAD_BYTES
            or name in inventory
        ):
            return Failure.ARTIFACT_INVALID
        inventory[name] = (digest, size)
    version = selection.version
    hosts = [
        name
        for name in inventory
        if re.fullmatch(rf"kast-ide-hosted-v{re.escape(version)}-idea-[0-9]{{3}}\.zip", name)
    ]
    primary = f"kast-ide-hosted-v{version}-idea-262.zip"
    variant = f"kast-ide-hosted-v{version}-idea-263.zip"
    if set(hosts) not in ({primary}, {primary, variant}):
        return Failure.ARTIFACT_INVALID
    bases = {
        f"kast-control-v{version}-{PLATFORM}.tar.gz",
        primary,
        f"kast-plugin-v{version}.zip",
        f"kast-marketplace-v{version}.zip",
        f"kast-control-release-v{version}.json",
        f"kast-host-release-v{version}.json",
        f"kast-hosted-catalog-v{version}.json",
        f"kast-module-knowledge-v{version}.json",
        f"kast-sbom-v{version}.cdx.json",
    }
    if variant in hosts:
        bases |= {variant, f"kast-host-release-v{version}-idea-263.json"}
    # Releases published before #1321 also carry the retired desktop installer and its checksum.
    # Remove this tolerance once the acceptance baseline is a release without it (#1321).
    retired = {"host-installation.py", "host-installation.py.sha256"}
    names = set(inventory)
    if names - retired != bases | {name + ".sha256" for name in bases} or names & retired not in (set(), retired):
        return Failure.ARTIFACT_INVALID
    return (
        Admission.ADMITTED
        if all(inventory.get(item.path.name) == (item.sha256, item.bytes) for item in downloaded)
        else Failure.ARTIFACT_INVALID
    )


def download_composition(
    selection: Selection | ProductSelection, idea_line: str, directory: Path, source: ReleaseSource
) -> AdmittedProduct | Rejected:
    """Never activate a legacy component pair as a complete private-runtime product."""
    requested = selection.version
    if (
        isinstance(selection, ProductSelection)
        and isinstance(selection.record, LocalProductRecord)
        and not isinstance(source, LocalReleaseSource)
    ):
        # Local product bytes exist only in their local directory; never acquire them remotely.
        return Rejected(Failure.LOCAL_ARTIFACT_REQUIRED, Stage.SELECTION, requested)
    if isinstance(selection, MainSelection):
        return Rejected(Failure.BINARY_UNAVAILABLE, Stage.RECORDS, requested)
    if isinstance(selection, StableSelection):
        selected = resolve_product_mapping(selection, source)
        if isinstance(selected, Rejected):
            return selected
        selection = selected
    raw = source.read(
        f"{RELEASE_URL}/{selection.releaseTag}/build-record.json", MAXIMUM_RECORD_BYTES
    )
    if isinstance(raw, TRANSFER_REJECTIONS):
        return Rejected(
            Failure.BINARY_UNAVAILABLE, Stage.RECORDS, requested, transfer=transfer(raw)
        )
    if (
        hashlib.sha256(raw).hexdigest() != selection.recordSha256
        or (
            decode_installed_product_record(raw)
            if isinstance(source, LocalReleaseSource)
            else decode_product_record(raw)
        )
        != selection.record
    ):
        return Rejected(Failure.ARTIFACT_INVALID, Stage.RECORDS, requested)
    # Lazy import preserves the existing self-contained legacy recovery bootstrap.
    from managed_product import stage_product

    return stage_product(selection, raw, directory, source)


def resolve_product_mapping(
    selection: StableSelection, source: ReleaseSource
) -> ProductSelection | Rejected:
    base = f"{RELEASE_URL}/{selection.releaseTag}"
    raw = source.read(f"{base}/release-mapping.json", MAXIMUM_RECORD_BYTES)
    if isinstance(raw, TRANSFER_REJECTIONS):
        return Rejected(
            Failure.BINARY_UNAVAILABLE, Stage.RECORDS, selection.version, transfer=transfer(raw)
        )
    try:
        mapping = parse_release_mapping(document(raw))
    except (ValueError, UnicodeError, RecursionError):
        mapping = Failure.ARTIFACT_INVALID
    if isinstance(mapping, Failure) or mapping.publicVersion != selection.version:
        return Rejected(Failure.ARTIFACT_INVALID, Stage.RECORDS, selection.version)
    raw = source.read(f"{base}/build-record.json", MAXIMUM_RECORD_BYTES)
    if isinstance(raw, TRANSFER_REJECTIONS):
        return Rejected(
            Failure.BINARY_UNAVAILABLE, Stage.RECORDS, selection.version, transfer=transfer(raw)
        )
    record = decode_product_record(raw)
    if isinstance(record, Failure) or hashlib.sha256(raw).hexdigest() != mapping.buildRecordSha256:
        return Rejected(Failure.ARTIFACT_INVALID, Stage.RECORDS, selection.version)
    return ProductSelection(
        selection.releaseTag,
        record,
        mapping.buildRecordSha256,
        selection.policy.value,
        selection.version,
        None,
    )


def stage_component_archives(
    selection: Selection,
    idea_line: str,
    directory: Path,
    source: ReleaseSource,
    *,
    target: str = PLATFORM,
) -> AdmittedComposition | Rejected:
    """Legacy component inspection only. This is not complete product admission."""
    requested = (
        selection.identity.mainRevision
        if isinstance(selection, MainSelection)
        else selection.version
    )
    assert requested is not None
    if (
        target not in PRODUCT_TARGETS
        or idea_line not in ("262", "263")
        or (
            isinstance(selection, MainSelection)
            and target not in selection.catalogEntry.platformTargets
        )
    ):
        return Rejected(Failure.PLATFORM_UNAVAILABLE, Stage.SELECTION, requested)
    try:
        if (
            not directory.is_absolute()
            or directory.resolve() != directory
            or not directory.is_dir()
        ):
            return Rejected(Failure.ARTIFACT_INVALID, Stage.STAGING, requested)
        downloads = directory / "downloads"
        downloads.mkdir(mode=0o700)
        control_name = f"kast-control-v{selection.version}-{target}.tar.gz"
        host_name = f"kast-ide-hosted-v{selection.version}-idea-{idea_line}.zip"
        records: list[ComponentRecord] = []
        record_paths: list[Path] = []
        for component, archive, line in (
            ("control", control_name, None),
            ("host", host_name, idea_line),
        ):
            name = f"kast-{component}-release-v{selection.version}.json"
            if component == "host" and line == "263":
                name = f"kast-host-release-v{selection.version}-idea-263.json"
            raw = checked_record(selection, name, downloads, source)
            if isinstance(raw, Failure):
                return Rejected(raw, Stage.RECORDS, requested)
            parsed = parse_component(raw, selection.version, archive, line)
            if isinstance(parsed, Failure):
                return Rejected(parsed, Stage.CONTRACT, requested)
            records.append(parsed)
            record_paths.append(downloads / name)
        control_record, host_record = records
        if (
            control_record.source_revision != host_record.source_revision
            or control_record.contract != host_record.contract
            or (
                isinstance(selection, MainSelection)
                and selection.identity.buildRevision != control_record.source_revision
            )
        ):
            return Rejected(Failure.ARTIFACT_INVALID, Stage.CONTRACT, requested)
        files: list[DownloadedFile] = []
        for record in records:
            downloaded = download_asset(selection, record, downloads, source)
            if isinstance(downloaded, Failure):
                return Rejected(downloaded, Stage.DOWNLOAD, requested)
            files.append(downloaded)
        control, host = files
        if isinstance(selection, MainSelection):
            manifest_paths = (
                tuple(record_paths)
                + tuple(downloads / (record.artifact_name + ".sha256") for record in records)
                + tuple(downloads / (path.name + ".sha256") for path in record_paths)
            )
            all_downloads = tuple(files) + tuple(
                DownloadedFile(path, file_digest(path), path.stat().st_size)
                for path in manifest_paths
            )
            rejected = admit_main_records(selection, source, all_downloads)
            if isinstance(rejected, Failure):
                return Rejected(rejected, Stage.RECORDS, requested)
        control_root = directory / "control"
        rejected = extract_control(control.path, control_root)
        if isinstance(rejected, Failure):
            return Rejected(rejected, Stage.ARCHIVE, requested)
        identity = inspect_control(control_root, selection, control_record, target)
        if isinstance(identity, Failure):
            return Rejected(identity, Stage.CONTRACT, requested)
        rejected = inspect_host(host.path, identity, control_record.contract)
        if isinstance(rejected, Failure):
            return Rejected(rejected, Stage.CONTRACT, requested)
        policy = (
            selection.policy
            if isinstance(selection, MainSelection)
            else TrackStable()
            if selection.policy is StablePolicy.TRACK
            else PinnedBuild(identity)
        )
        return AdmittedComposition(
            identity,
            policy,
            control_root,
            control.path,
            host.path,
            record_paths[0],
            record_paths[1],
            control.sha256,
            host.sha256,
            control_record.contract,
            integration_digest(control_root),
        )
    except (OSError, ValueError, UnicodeError, RecursionError):
        return Rejected(Failure.ARTIFACT_INVALID, Stage.STAGING, requested)
