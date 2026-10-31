"""Admit one captured release composition before effects on an installed Kast."""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
import shutil
import stat
import tarfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as XML
import zipfile
from dataclasses import dataclass
from enum import StrEnum
from http.client import HTTPMessage
from pathlib import Path, PurePosixPath
from typing import IO, Literal, Protocol, TypeGuard, override

from managed_update_journal import BuildIdentity, PinnedBuild, TrackMain, TrackStable

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


class ReleaseSource(Protocol):
    def read(self, url: str, limit: int) -> bytes | TransferFailure: ...
    def download(
        self, url: str, destination: Path, limit: int
    ) -> DownloadedFile | TransferFailure: ...


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
    def read(self, url: str, limit: int) -> bytes | TransferFailure:
        try:
            request = urllib.request.Request(
                url, headers={"Cache-Control": "no-cache", "User-Agent": "kast-install"}
            )
            with urllib.request.build_opener(HttpsRedirects()).open(request, timeout=30) as stream:
                data: bytes = stream.read(limit + 1)
            return data if len(data) <= limit else TransferFailure.LIMIT_EXCEEDED
        except urllib.error.HTTPError as failure:
            return TransferFailure.NOT_FOUND if failure.code == 404 else TransferFailure.UNAVAILABLE
        except (OSError, urllib.error.URLError):
            return TransferFailure.UNAVAILABLE

    def download(self, url: str, destination: Path, limit: int) -> DownloadedFile | TransferFailure:
        digest, size = hashlib.sha256(), 0
        try:
            request = urllib.request.Request(
                url, headers={"Cache-Control": "no-cache", "User-Agent": "kast-install"}
            )
            with (
                urllib.request.build_opener(HttpsRedirects()).open(request, timeout=30) as stream,
                destination.open("xb") as output,
            ):
                while block := stream.read(min(65_536, limit - size + 1)):
                    size += len(block)
                    if size > limit:
                        return TransferFailure.LIMIT_EXCEEDED
                    digest.update(block)
                    _ = output.write(block)
            return DownloadedFile(destination, digest.hexdigest(), size)
        except urllib.error.HTTPError as failure:
            return TransferFailure.NOT_FOUND if failure.code == 404 else TransferFailure.UNAVAILABLE
        except (OSError, urllib.error.URLError):
            return TransferFailure.UNAVAILABLE


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


def decode_selection(raw: bytes) -> Selection | Rejected:
    if len(raw) > MAXIMUM_RECORD_BYTES:
        return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, "captured selection")
    try:
        value = document(raw)
    except (ValueError, UnicodeError, RecursionError):
        return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, "captured selection")
    if not mapping(value):
        return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, "captured selection")
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
) -> Selection | Rejected:
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
    if channel not in (None, "stable", "main"):
        return Rejected(Failure.SELECTOR_INVALID, Stage.SELECTION, requested)
    if commit is None and channel in (None, "stable"):
        raw = source.read(STABLE_URL, MAXIMUM_RECORD_BYTES)
        if isinstance(raw, TransferFailure):
            return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
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
    if isinstance(raw, TransferFailure):
        return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
    try:
        parsed = document(raw)
    except (ValueError, UnicodeError, RecursionError):
        return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
    if (
        not mapping(parsed)
        or set(parsed) != {"schemaVersion", "builds"}
        or type(parsed["schemaVersion"]) is not int
        or parsed["schemaVersion"] != 1
        or not sequence(parsed["builds"])
        or len(parsed["builds"]) > 5
    ):
        return Rejected(Failure.CATALOG_UNAVAILABLE, Stage.SELECTION, requested)
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
    if isinstance(checksum, TransferFailure) or isinstance(raw, TransferFailure):
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
        isinstance(checksum, TransferFailure)
        or checksum != f"{record.sha256}  {record.artifact_name}\n".encode()
    ):
        return Failure.ARTIFACT_INVALID
    with (directory / (record.artifact_name + ".sha256")).open("xb") as output:
        _ = output.write(checksum)
    destination = directory / record.artifact_name
    result = source.download(asset_url(selection, record.artifact_name), destination, record.bytes)
    if result is TransferFailure.NOT_FOUND:
        return Failure.PLATFORM_UNAVAILABLE
    if isinstance(result, TransferFailure):
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


def inspect_control(
    control: Path, selection: Selection, record: ComponentRecord
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
        for relative in ("share/kast/libexec/kast-management", "bin/kast-query"):
            executable = control / relative
            with executable.open("rb") as stream:
                header = stream.read(8)
            if header != b"\xcf\xfa\xed\xfe\x0c\x00\x00\x01" or not os.access(executable, os.X_OK):
                return Failure.ARTIFACT_INVALID
        if not (control / "share/kast/agent-tools/plugins/kast/skills/kast/SKILL.md").is_file():
            return Failure.INSTALLER_CONTRACT_UNSUPPORTED
        return identity
    except (OSError, ValueError, UnicodeError, RecursionError):
        return Failure.ARTIFACT_INVALID


def admit_main_records(
    selection: MainSelection, source: ReleaseSource, downloaded: tuple[DownloadedFile, ...]
) -> Failure | Admission:
    values: list[object] = []
    for reference in (selection.catalogEntry.candidate, selection.catalogEntry.promotion):
        raw = source.read(asset_url(selection, reference.name), MAXIMUM_RECORD_BYTES)
        if isinstance(raw, TransferFailure) or hashlib.sha256(raw).hexdigest() != reference.sha256:
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
    if len(hosts) != 1:
        return Failure.ARTIFACT_INVALID
    bases = {
        f"kast-control-v{version}-{PLATFORM}.tar.gz",
        hosts[0],
        f"kast-plugin-v{version}.zip",
        f"kast-marketplace-v{version}.zip",
        f"kast-control-release-v{version}.json",
        f"kast-host-release-v{version}.json",
        "host-installation.py",
        f"kast-hosted-catalog-v{version}.json",
        f"kast-module-knowledge-v{version}.json",
        f"kast-sbom-v{version}.cdx.json",
    }
    if set(inventory) != bases | {name + ".sha256" for name in bases}:
        return Failure.ARTIFACT_INVALID
    return (
        Admission.ADMITTED
        if all(inventory.get(item.path.name) == (item.sha256, item.bytes) for item in downloaded)
        else Failure.ARTIFACT_INVALID
    )


def download_composition(
    selection: Selection, idea_line: str, directory: Path, source: ReleaseSource
) -> AdmittedComposition | Rejected:
    requested = (
        selection.identity.mainRevision
        if isinstance(selection, MainSelection)
        else selection.version
    )
    assert requested is not None
    if not re.fullmatch(r"[0-9]{3}", idea_line) or (
        isinstance(selection, MainSelection)
        and PLATFORM not in selection.catalogEntry.platformTargets
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
        control_name = f"kast-control-v{selection.version}-{PLATFORM}.tar.gz"
        host_name = f"kast-ide-hosted-v{selection.version}-idea-{idea_line}.zip"
        records: list[ComponentRecord] = []
        record_paths: list[Path] = []
        for component, archive, line in (
            ("control", control_name, None),
            ("host", host_name, idea_line),
        ):
            name = f"kast-{component}-release-v{selection.version}.json"
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
        identity = inspect_control(control_root, selection, control_record)
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
