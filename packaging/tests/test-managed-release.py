"""Release admission uses one captured composition and only caller-owned temporary files."""

from __future__ import annotations

import hashlib
import io
import json
import os
import socket
import ssl
import struct
import tempfile
import unittest
import urllib.error
import zipfile
from dataclasses import asdict, dataclass, replace
from http.client import HTTPMessage
from pathlib import Path
from typing import IO, override
from unittest.mock import patch
from urllib.parse import urlsplit

import managed_release as release

BUILD = "a" * 40
MAIN = "b" * 40
TREE = "c" * 40


@dataclass(frozen=True)
class Artifact:
    fileName: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class ControlRecord:
    controlVersion: str
    artifact: Artifact
    requiredHostedContract: release.HostedContract
    sourceRevision: str = BUILD
    type: str = "CONTROL_RELEASE"


@dataclass(frozen=True)
class HostRecord:
    hostedPluginVersion: str
    artifact: Artifact
    providedHostedContract: release.HostedContract
    supportedIntellijReleaseLine: str = "262"
    sourceRevision: str = BUILD
    type: str = "HOST_RELEASE"


@dataclass(frozen=True)
class ControlMetadata:
    productVersion: str
    requiredHostedContract: release.HostedContract
    schemaVersion: int = 2
    execution: str = "existing_ide"
    ideaBuild: str = "262.1"
    kotlinPluginBuild: str = "262.1-IJ"


@dataclass(frozen=True)
class BuildResource:
    version: str
    sourceRevision: str = BUILD
    sourceTree: str = TREE


@dataclass(frozen=True)
class StableRelease:
    tag_name: str
    draft: bool = False
    prerelease: bool = False


@dataclass(frozen=True)
class Catalog:
    builds: tuple[release.MainEntry, ...]
    schemaVersion: int = 1


@dataclass(frozen=True)
class CandidateAsset:
    name: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class Toolchain:
    javaVersion: str = "25.0.2"
    vendor: str = "Oracle Corporation"
    releaseSha256: str = "d" * 64


@dataclass(frozen=True)
class Candidate:
    version: str
    assets: tuple[CandidateAsset, ...]
    schemaVersion: int = 1
    repository: str = release.REPOSITORY
    sourceRevision: str = BUILD
    sourceTree: str = TREE
    runId: int = 123
    runNumber: int = 123
    runAttempt: int = 1
    event: str = "pull_request"
    platform: str = release.PLATFORM
    toolchain: Toolchain = Toolchain()


@dataclass(frozen=True)
class Promotion:
    version: str
    candidateSha256: str
    schemaVersion: int = 1
    repository: str = release.REPOSITORY
    buildRevision: str = BUILD
    mergedRevision: str = MAIN
    sourceTree: str = TREE
    buildRunId: int = 123
    buildRunAttempt: int = 1
    mainRunId: int = 456
    mainRunAttempt: int = 1


type JsonFixture = (
    ControlMetadata
    | BuildResource
    | release.HostedContract
    | ControlRecord
    | HostRecord
    | StableRelease
    | Catalog
    | Candidate
    | Promotion
    | release.MainSelection
)


def encoded(value: JsonFixture) -> bytes:
    return json.dumps(asdict(value)).encode()


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def tar_bytes(files: dict[str, bytes], link: str | None = None) -> bytes:
    import tarfile

    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w:gz") as archive:
        for name, content in files.items():
            member = tarfile.TarInfo(name)
            member.size = len(content)
            member.mode = (
                0o755 if name in ("bin/kast-query", "share/kast/libexec/kast-management") else 0o644
            )
            archive.addfile(member, io.BytesIO(content))
        if link is not None:
            member = tarfile.TarInfo("bin/link")
            member.type = tarfile.SYMTYPE
            member.linkname = link
            archive.addfile(member)
    return stream.getvalue()


def archive_bytes(
    version: str,
    contract: release.HostedContract,
    marker: bool = True,
    native: bool = True,
    unified: bool = False,
    source_tree: str = TREE,
    link: str | None = None,
) -> tuple[bytes, bytes]:
    files = {
        "share/kast/ide-host.json": encoded(ControlMetadata(version, contract)),
        "share/kast/build-identity.json": encoded(BuildResource(version, sourceTree=source_tree)),
        "share/kast/agent-tools/plugins/kast/skills/kast/SKILL.md": b"# Kast\nUse kast describe.\n",
        "share/kast/libexec/kast-management": b"\xcf\xfa\xed\xfe\x0c\x00\x00\x01fixture",
    }
    if marker:
        files["share/kast/managed-install-v1"] = b"1\n"
    if native:
        files["bin/kast-query"] = b"\xcf\xfa\xed\xfe\x0c\x00\x00\x01fixture"
    if unified:
        files["share/kast/native-control-v1"] = b"1\n"
        if native:
            files["bin/kast-query"] = (
                b'#!/bin/sh\nexec "../share/kast/libexec/kast-management" '
                b'--internal-entrypoint=query "$@"\n'
            )
    control = tar_bytes(files, link)
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, "w") as plugin:
        plugin.writestr(
            "META-INF/plugin.xml",
            f"<idea-plugin><id>io.github.amichne.kast.ide-hosted</id><version>{version}</version></idea-plugin>",
        )
        plugin.writestr("hosted-contract.json", encoded(contract))
        plugin.writestr(
            "kast-hosted-query.properties",
            f"version={version}\nsourceRevision={BUILD}\nsourceTree={source_tree}\n",
        )
    host = io.BytesIO()
    with zipfile.ZipFile(host, "w") as archive:
        archive.writestr("kast-ide-hosted/", b"")
        archive.writestr("kast-ide-hosted/lib/", b"")
        archive.writestr("kast-ide-hosted/lib/kast-ide-hosted.jar", jar.getvalue())
    return control, host.getvalue()


class Source:
    def __init__(self, assets: dict[str, bytes]):
        self.assets = assets
        self.calls: list[str] = []
        self.catalog_reads = 0
        self.change_catalog_on_download = False

    def read(self, url: str, limit: int) -> bytes | release.TransferFailure:
        self.calls.append(url)
        if url == release.CATALOG_URL:
            self.catalog_reads += 1
        key = (
            url
            if url in (release.CATALOG_URL, release.STABLE_URL)
            else Path(urlsplit(url).path).name
        )
        value = self.assets.get(key)
        if value is None:
            return release.TransferFailure.NOT_FOUND
        return value if len(value) <= limit else release.TransferFailure.LIMIT_EXCEEDED

    def download(
        self, url: str, destination: Path, limit: int
    ) -> release.DownloadedFile | release.TransferFailure:
        raw = self.read(url, limit)
        if isinstance(raw, release.TransferFailure):
            return raw
        with destination.open("xb") as output:
            _ = output.write(raw)
        if self.change_catalog_on_download:
            self.assets[release.CATALOG_URL] = encoded(Catalog(()))
        return release.DownloadedFile(destination, digest(raw), len(raw))


class ReleaseAdmissionTest(unittest.TestCase):
    @override
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.stage = self.root / "stage"
        self.stage.mkdir()
        self.protected = self.root / "installation"
        self.protected.mkdir()
        _ = (self.protected / "keep").write_bytes(b"prior composition")
        self.contract = release.HostedContract(
            "kast.ide-hosted.runtime.v3",
            "sha256:" + "d" * 64,
            "sha256:" + "e" * 64,
            (
                "workspace.lifecycle",
                "query.run",
                "source.read",
                "diagnostic.check",
                "change.plan",
                "change.apply",
                "change.recover",
            ),
        )

    def source(
        self,
        version: str,
        marker: bool = True,
        native: bool = True,
        source_tree: str = TREE,
        unified: bool = False,
        with_263: bool = False,
    ) -> Source:
        control, host = archive_bytes(
            version,
            self.contract,
            marker=marker,
            native=native,
            source_tree=source_tree,
            unified=unified,
        )
        control_name = f"kast-control-v{version}-{release.PLATFORM}.tar.gz"
        host_name = f"kast-ide-hosted-v{version}-idea-262.zip"
        assets = {control_name: control, host_name: host}
        assets[f"kast-control-release-v{version}.json"] = encoded(
            ControlRecord(
                version,
                Artifact(control_name, "sha256:" + digest(control), len(control)),
                self.contract,
            )
        )
        assets[f"kast-host-release-v{version}.json"] = encoded(
            HostRecord(
                version, Artifact(host_name, "sha256:" + digest(host), len(host)), self.contract
            )
        )
        if with_263:
            name = f"kast-ide-hosted-v{version}-idea-263.zip"
            assets[name] = host
            assets[f"kast-host-release-v{version}-idea-263.json"] = encoded(
                HostRecord(
                    version,
                    Artifact(name, "sha256:" + digest(host), len(host)),
                    self.contract,
                    "263",
                )
            )
        assets[release.STABLE_URL] = encoded(StableRelease("v" + version))
        return Source(
            assets
            | {
                name + ".sha256": f"{digest(raw)}  {name}\n".encode()
                for name, raw in assets.items()
                if name != release.STABLE_URL
            }
        )

    def main(self, source: Source, version: str = "0.0.123") -> release.MainEntry:
        for name in (
            f"kast-plugin-v{version}.zip",
            f"kast-marketplace-v{version}.zip",
            "host-installation.py",
            f"kast-hosted-catalog-v{version}.json",
            f"kast-module-knowledge-v{version}.json",
            f"kast-sbom-v{version}.cdx.json",
        ):
            source.assets[name] = name.encode()
            source.assets[name + ".sha256"] = f"{digest(name.encode())}  {name}\n".encode()
        candidate = Candidate(
            version,
            tuple(
                CandidateAsset(name, digest(raw), len(raw))
                for name, raw in sorted(source.assets.items())
                if name != release.STABLE_URL
            ),
        )
        source.assets["candidate.json"] = encoded(candidate)
        source.assets["promotion.json"] = encoded(
            Promotion(version, digest(source.assets["candidate.json"]))
        )
        entry = release.MainEntry(
            MAIN,
            BUILD,
            TREE,
            f"developer-v{version}",
            version,
            (release.PLATFORM,),
            release.RecordReference("candidate.json", digest(source.assets["candidate.json"])),
            release.RecordReference("promotion.json", digest(source.assets["promotion.json"])),
        )
        source.assets[release.CATALOG_URL] = encoded(Catalog((entry,)))
        return entry

    def test_stable_admission_keeps_files_processes_and_registrations_outside_temporary_storage(
        self,
    ) -> None:
        source = self.source("1.2.3")
        selected = release.resolve_selection(None, None, None, source)
        assert isinstance(selected, release.StableSelection)
        admitted = release.stage_component_archives(selected, "262", self.stage, source)
        assert isinstance(admitted, release.AdmittedComposition)
        self.assertEqual(release.BuildIdentity("v1.2.3", "1.2.3", BUILD, TREE), admitted.identity)
        self.assertEqual(release.TrackStable(), admitted.policy)
        self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())
        self.assertEqual(("keep",), tuple(path.name for path in self.protected.iterdir()))

    def test_main_263_selects_its_authenticated_variant(self) -> None:
        source = self.source("0.0.123", with_263=True)
        _ = self.main(source)
        selected = release.resolve_selection("main", None, None, source)
        assert isinstance(selected, release.MainSelection)
        admitted = release.stage_component_archives(selected, "263", self.stage, source)
        assert isinstance(admitted, release.AdmittedComposition), admitted
        self.assertEqual("kast-ide-hosted-v0.0.123-idea-263.zip", admitted.host_archive.name)
        self.assertEqual("kast-host-release-v0.0.123-idea-263.json", admitted.host_record.name)
        self.assertFalse((self.stage / "downloads/kast-ide-hosted-v0.0.123-idea-262.zip").exists())

    def test_orphan_263_variant_rejects_even_for_262_selection(self) -> None:
        source = self.source("0.0.123", with_263=True)
        del source.assets["kast-host-release-v0.0.123-idea-263.json"]
        del source.assets["kast-host-release-v0.0.123-idea-263.json.sha256"]
        _ = self.main(source)
        selected = release.resolve_selection("main", None, None, source)
        assert isinstance(selected, release.MainSelection)
        self.assertIsInstance(
            release.stage_component_archives(selected, "262", self.stage, source), release.Rejected
        )

    def test_changed_catalog_during_download_does_not_change_selection_or_build_provenance(
        self,
    ) -> None:
        source = self.source("0.0.123")
        _ = self.main(source)
        selected = release.resolve_selection("main", None, None, source)
        assert isinstance(selected, release.MainSelection)
        source.change_catalog_on_download = True
        admitted = release.stage_component_archives(selected, "262", self.stage, source)
        assert isinstance(admitted, release.AdmittedComposition), admitted
        self.assertEqual(MAIN, admitted.identity.mainRevision)
        self.assertEqual(BUILD, admitted.identity.buildRevision)
        self.assertEqual(1, source.catalog_reads)
        self.assertTrue(
            all(
                "developer-v0.0.123" in url
                for url in source.calls
                if url not in (release.CATALOG_URL, release.STABLE_URL)
            )
        )

    def test_explicit_version_creates_pin_after_source_identity_admission(self) -> None:
        source = self.source("1.2.3")
        selected = release.resolve_selection(None, "1.2.3", None, source)
        assert isinstance(selected, release.StableSelection)
        admitted = release.stage_component_archives(selected, "262", self.stage, source)
        assert isinstance(admitted, release.AdmittedComposition)
        self.assertEqual(release.PinnedBuild(admitted.identity), admitted.policy)
        self.assertNotIn(release.STABLE_URL, source.calls)

    def test_single_native_control_admits_its_query_launcher_without_jvm_libraries(self) -> None:
        source = self.source("1.2.3", unified=True)
        selected = release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK)
        admitted = release.stage_component_archives(selected, "262", self.stage, source)
        self.assertIsInstance(admitted, release.AdmittedComposition)
        self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())

    def test_native_dependencies_require_library_headers_target_and_dependency_scope(self) -> None:
        source = self.source("1.2.3", unified=True)
        selected = release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK)
        admitted = release.stage_component_archives(selected, "262", self.stage, source)
        assert isinstance(admitted, release.AdmittedComposition)
        record = release.parse_component(
            source.assets["kast-control-release-v1.2.3.json"],
            "1.2.3",
            f"kast-control-v1.2.3-{release.PLATFORM}.tar.gz",
            None,
        )
        assert not isinstance(record, release.Failure)
        dylib = bytes.fromhex("cffaedfe0c0000010000000006000000") + bytes(16)
        bundle = bytes.fromhex("cffaedfe0c0000010000000008000000") + bytes(16)
        executable = bytes.fromhex("cffaedfe0c0000010000000002000000") + bytes(16)
        elf = bytes.fromhex("7f454c4602010100000000000000000003003e0001000000") + bytes(8)

        # CFFI's pinned Linux library has e_entry=0x9000 in the CI inventory.
        def elf_dependency(*, interpreter: bool = False, pie: bool = False) -> bytes:
            count = 3 if interpreter else 2
            offset = 64 + count * 56
            dynamic = struct.pack("<QQQQ", 0x6FFFFFFB, 0x08000000 if pie else 0, 0, 0)
            size = offset + len(dynamic)
            return (
                elf[:24]
                + struct.pack("<QQQIHHHHHH", 0x9000, 64, 0, 0, 64, 56, count, 0, 0, 0)
                + struct.pack("<IIQQQQQQ", 1, 4, 0, 0, 0, size, size, 4096)
                + struct.pack("<IIQQQQQQ", 2, 4, offset, offset, 0, 32, 32, 8)
                + (struct.pack("<IIQQQQQQ", 3, 4, 0, 0, 0, 1, 1, 1) if interpreter else b"")
                + dynamic
            )

        library = elf_dependency()
        dependency = "share/kast/mcp-dependencies/site-packages/native/module.so"
        for target, name, header, accepted in (
            ("macos-aarch64", dependency, dylib, True),
            ("macos-aarch64", dependency, bundle, True),
            ("macos-aarch64", dependency, executable, False),
            ("macos-aarch64", "share/kast/foreign.so", dylib, False),
            ("macos-aarch64", dependency, elf, False),
            ("macos-aarch64", dependency, dylib[:16], False),
            ("linux-x86_64", dependency, library, True),
            ("linux-x86_64", dependency, elf_dependency(interpreter=True), False),
            ("linux-x86_64", dependency, elf_dependency(pie=True), False),
            ("linux-x86_64", dependency, library[:64], False),
            ("linux-x86_64", dependency, library[:18] + b"\xb7\x00" + library[20:], False),
        ):
            with self.subTest(target=target, name=name, header=header.hex()):
                management = admitted.control_root / "share/kast/libexec/kast-management"
                _ = management.write_bytes(executable if target == "macos-aarch64" else elf)
                path = admitted.control_root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                _ = path.write_bytes(header)
                result = release.inspect_control(admitted.control_root, selected, record, target)
                self.assertEqual(
                    admitted.identity if accepted else release.Failure.ARTIFACT_INVALID, result
                )
                path.unlink()
        self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())

    def test_missing_capability_or_native_query_rejects_before_installation_changes(self) -> None:
        for option in ("marker", "native"):
            with (
                self.subTest(option=option),
                tempfile.TemporaryDirectory(dir=self.root) as temporary,
            ):
                source = self.source("1.2.3", marker=option != "marker", native=option != "native")
                selected = release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK)
                result = release.stage_component_archives(selected, "262", Path(temporary), source)
                assert isinstance(result, release.Rejected)
                self.assertEqual(
                    release.Failure.INSTALLER_CONTRACT_UNSUPPORTED
                    if option == "marker"
                    else release.Failure.ARTIFACT_INVALID,
                    result.reason,
                )
                self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())

    def test_missing_old_control_record_rejects_with_installer_contract_reason(self) -> None:
        source = self.source("1.2.3")
        _ = source.assets.pop("kast-control-release-v1.2.3.json")
        result = release.stage_component_archives(
            release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK),
            "262",
            self.stage,
            source,
        )
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.INSTALLER_CONTRACT_UNSUPPORTED, result.reason)

    def test_missing_platform_archive_preserves_the_prior_installation(self) -> None:
        source = self.source("1.2.3")
        _ = source.assets.pop("kast-control-v1.2.3-macos-aarch64.tar.gz")
        result = release.stage_component_archives(
            release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK),
            "262",
            self.stage,
            source,
        )
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.PLATFORM_UNAVAILABLE, result.reason)
        self.assertFalse((self.stage / "control").exists())
        self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())

    def test_source_tree_substitution_rejects_even_with_fresh_archive_checksums(self) -> None:
        source = self.source("0.0.123", source_tree="f" * 40)
        entry = self.main(source)
        selected = release.MainSelection(entry.identity(), release.TrackMain(), entry)
        result = release.stage_component_archives(selected, "262", self.stage, source)
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.ARTIFACT_INVALID, result.reason)
        self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())

    def test_malformed_candidate_cannot_pass_through_rebound_record_digests(self) -> None:
        for field, value in (
            ("runId", True),
            ("runNumber", 122),
            ("runNumber", 2_147_483_648),
            ("schemaVersion", True),
            ("unknown", "field"),
            ("toolchain", "invalid"),
        ):
            with self.subTest(field=field), tempfile.TemporaryDirectory(dir=self.root) as temporary:
                source = self.source("0.0.123")
                entry = self.main(source)
                candidate: object = json.loads(source.assets["candidate.json"])
                assert release.mapping(candidate)
                candidate[field] = value
                source.assets["candidate.json"] = json.dumps(candidate).encode()
                source.assets["promotion.json"] = encoded(
                    Promotion("0.0.123", digest(source.assets["candidate.json"]))
                )
                entry = replace(
                    entry,
                    candidate=release.RecordReference(
                        "candidate.json", digest(source.assets["candidate.json"])
                    ),
                    promotion=release.RecordReference(
                        "promotion.json", digest(source.assets["promotion.json"])
                    ),
                )
                selected = release.MainSelection(entry.identity(), release.TrackMain(), entry)
                result = release.stage_component_archives(selected, "262", Path(temporary), source)
                assert isinstance(result, release.Rejected)
                self.assertEqual(release.Failure.ARTIFACT_INVALID, result.reason)
                self.assertFalse((Path(temporary) / "control").exists())

    def test_checksum_failure_never_downloads_executables(self) -> None:
        source = self.source("1.2.3")
        source.assets["kast-control-release-v1.2.3.json.sha256"] = b"different\n"
        result = release.stage_component_archives(
            release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK),
            "262",
            self.stage,
            source,
        )
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.ARTIFACT_INVALID, result.reason)
        self.assertFalse(any(url.endswith("tar.gz") for url in source.calls))

    def test_unsupported_protocol_rejects_before_archive_download(self) -> None:
        self.contract = replace(
            self.contract, runtimeProtocolIdentity="kast.ide-hosted.runtime.v999"
        )
        source = self.source("1.2.3")
        result = release.stage_component_archives(
            release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK),
            "262",
            self.stage,
            source,
        )
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.ARTIFACT_INVALID, result.reason)
        self.assertFalse(any(url.endswith("tar.gz") for url in source.calls))

    def test_links_and_parent_traversal_never_escape_temporary_extraction(self) -> None:
        for archive in (
            tar_bytes({"../outside": b"bad"}),
            tar_bytes({"bin/file": b"good"}, "/tmp/unowned"),
        ):
            with (
                self.subTest(archive=archive[:8]),
                tempfile.TemporaryDirectory(dir=self.root) as temporary,
            ):
                path = Path(temporary) / "control.tar.gz"
                _ = path.write_bytes(archive)
                self.assertEqual(
                    release.Failure.ARTIFACT_INVALID,
                    release.extract_control(path, Path(temporary) / "control"),
                )
        self.assertFalse((self.root / "outside").exists())

    def test_main_prefix_ambiguity_and_newest_platform_failure_never_select_an_older_build(
        self,
    ) -> None:
        source = self.source("0.0.123")
        first = self.main(source)
        prefix = "abcdef123456"
        one, two = (
            replace(first, mainRevision=prefix + "a" * 28),
            replace(first, mainRevision=prefix + "b" * 28),
        )
        source.assets[release.CATALOG_URL] = encoded(Catalog((one, two)))
        result = release.resolve_selection(None, None, prefix, source)
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.AMBIGUOUS_COMMIT, result.reason)
        source.assets[release.CATALOG_URL] = encoded(
            Catalog((replace(one, platformTargets=("unsupported-target",)), two))
        )
        result = release.resolve_selection("main", None, None, source)
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.PLATFORM_UNAVAILABLE, result.reason)
        self.assertEqual((one.mainRevision, two.mainRevision), result.selectable)

    def test_conflicting_selectors_and_captured_identity_mismatch_reject_without_downloads(
        self,
    ) -> None:
        source = self.source("0.0.123")
        result = release.resolve_selection("main", "0.0.123", None, source)
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.SELECTORS_CONFLICT, result.reason)
        self.assertEqual([], source.calls)
        entry = self.main(source)
        selected = release.MainSelection(entry.identity(), release.TrackMain(), entry)
        self.assertEqual(selected, release.decode_selection(encoded(selected)))
        mismatched = replace(selected, identity=replace(selected.identity, sourceTree="f" * 40))
        result = release.decode_selection(encoded(mismatched))
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.SELECTOR_INVALID, result.reason)


INSPECTION_ROOT = """-----BEGIN CERTIFICATE-----
MIIBUzCB+qADAgECAgkAl++VsKRqLQcwCgYIKoZIzj0EAwIwJDEiMCAGA1UEAwwZ
S2FzdCBUZXN0IEluc3BlY3Rpb24gUm9vdDAgFw0yNjExMTYyMjIwNTRaGA8yMTI2
MTAyMzIyMjA1NFowJDEiMCAGA1UEAwwZS2FzdCBUZXN0IEluc3BlY3Rpb24gUm9v
dDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABK1CTlTDefQqSmopUhg2v7SdC6j6
ZCmO9LbgpxDvKG5lUg5eybEItpUMq2jOhlailITyJfrO3tYMZaLmTmrXotGjEzAR
MA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgKPy/Wx0DH0YaAYRi
wcMid8PLMsgX1+j0CBlZecUCUiMCIQCFRyPNxLQvCSTECfuXrRt+MI2pxB/3u37j
pfa5qC+VoQ==
-----END CERTIFICATE-----
"""
INSPECTION_SUBJECT = ((("commonName", "Kast Test Inspection Root"),),)


def trusts_inspection_root(context: ssl.SSLContext) -> bool:
    return any(item.get("subject") == INSPECTION_SUBJECT for item in context.get_ca_certs())


def unexpected_export() -> bytes | release.TrustRoots:
    raise AssertionError("unexpected Keychain export")


class FailingTransport(release.HttpReleaseSource):
    """Inject one urllib observation at the opener boundary; production maps it."""

    def __init__(self, failure: OSError | None, body: bytes = b"") -> None:
        super().__init__(lambda: (ssl.create_default_context(), release.TrustRoots.KEYCHAIN))
        self.failure, self.body = failure, body

    @override
    def open(self, url: str) -> IO[bytes]:
        if self.failure is not None:
            raise self.failure
        return io.BytesIO(self.body)


def http_error(code: int) -> urllib.error.HTTPError:
    return urllib.error.HTTPError(release.STABLE_URL, code, "status", HTTPMessage(), None)


class TransferCauseTest(unittest.TestCase):
    def test_urllib_failures_map_to_their_bounded_transfer_cause(self) -> None:
        verify = ssl.SSLCertVerificationError(1, "certificate verify failed")
        cases: tuple[tuple[OSError, release.TransferRejection], ...] = (
            (
                urllib.error.URLError(verify),
                release.TransferRejected(
                    release.TransferFailure.TLS_CERTIFICATE_UNTRUSTED, release.TrustRoots.KEYCHAIN
                ),
            ),
            (
                verify,
                release.TransferRejected(
                    release.TransferFailure.TLS_CERTIFICATE_UNTRUSTED, release.TrustRoots.KEYCHAIN
                ),
            ),
            (
                urllib.error.URLError(ssl.SSLError(1, "wrong version number")),
                release.TransferRejected(
                    release.TransferFailure.TLS_FAILED, release.TrustRoots.KEYCHAIN
                ),
            ),
            (
                urllib.error.URLError(TimeoutError("timed out")),
                release.TransferRejected(
                    release.TransferFailure.TIMEOUT, release.TrustRoots.KEYCHAIN
                ),
            ),
            (
                urllib.error.URLError(socket.gaierror(8, "nodename nor servname provided")),
                release.TransferRejected(
                    release.TransferFailure.CONNECTION_FAILED, release.TrustRoots.KEYCHAIN
                ),
            ),
            (
                urllib.error.URLError(OSError("Tunnel connection failed: 407")),
                release.TransferRejected(
                    release.TransferFailure.CONNECTION_FAILED, release.TrustRoots.KEYCHAIN
                ),
            ),
            (
                http_error(403),
                release.TransferRejected(
                    release.TransferFailure.HTTP_STATUS, release.TrustRoots.KEYCHAIN, 403
                ),
            ),
            (
                http_error(500),
                release.TransferRejected(
                    release.TransferFailure.HTTP_STATUS, release.TrustRoots.KEYCHAIN, 500
                ),
            ),
            (http_error(404), release.TransferFailure.NOT_FOUND),
            (
                urllib.error.URLError("release redirect does not use HTTPS"),
                release.TransferRejected(
                    release.TransferFailure.UNAVAILABLE, release.TrustRoots.KEYCHAIN
                ),
            ),
        )
        for failure, expected in cases:
            with self.subTest(failure=type(failure).__name__, expected=expected):
                self.assertEqual(expected, FailingTransport(failure).read(release.STABLE_URL, 10))

    def test_local_destination_failure_is_not_reported_as_a_network_cause(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "existing"
            _ = destination.write_bytes(b"prior")
            result = FailingTransport(None, b"body").download(release.STABLE_URL, destination, 10)
            self.assertEqual(
                release.TransferRejected(
                    release.TransferFailure.UNAVAILABLE, release.TrustRoots.KEYCHAIN
                ),
                result,
            )
            self.assertEqual(b"prior", destination.read_bytes())

    def test_tls_rejection_reaches_selection_and_record_rejections(self) -> None:
        untrusted = FailingTransport(
            urllib.error.URLError(ssl.SSLCertVerificationError(1, "certificate verify failed"))
        )
        expected_transfer = {
            "cause": "TLS_CERTIFICATE_UNTRUSTED",
            "trust": "KEYCHAIN",
            "httpStatus": None,
        }
        selection = release.resolve_selection("main", None, None, untrusted)
        assert isinstance(selection, release.Rejected)
        self.assertEqual(
            {
                "reason": "CATALOG_UNAVAILABLE",
                "stage": "SELECTION",
                "requested": "main",
                "platform": "macos-aarch64",
                "selectable": [],
                "transfer": expected_transfer,
            },
            json.loads(json.dumps(asdict(selection))),
        )
        pinned = release.resolve_selection(None, "0.52.0", None, untrusted)
        assert isinstance(pinned, release.StableSelection)
        with tempfile.TemporaryDirectory() as temporary:
            records = release.download_composition(pinned, "262", Path(temporary), untrusted)
        assert isinstance(records, release.Rejected)
        self.assertEqual(
            {
                "reason": "BINARY_UNAVAILABLE",
                "stage": "RECORDS",
                "requested": "0.52.0",
                "platform": "macos-aarch64",
                "selectable": [],
                "transfer": expected_transfer,
            },
            json.loads(json.dumps(asdict(records))),
        )


class TrustContextTest(unittest.TestCase):
    def test_openssl_environment_selects_its_roots_without_keychain_export(self) -> None:
        for variable in ("SSL_CERT_FILE", "SSL_CERT_DIR"):
            with self.subTest(variable=variable):
                context, trust = release.trust_context(
                    "Darwin", {variable: "/private/roots.pem"}, unexpected_export
                )
                self.assertEqual(release.TrustRoots.ENVIRONMENT, trust)
                self.assertFalse(trusts_inspection_root(context))

    def test_linux_keeps_the_platform_context_without_export(self) -> None:
        context, trust = release.trust_context("Linux", {}, unexpected_export)
        self.assertEqual(release.TrustRoots.PLATFORM, trust)
        self.assertFalse(trusts_inspection_root(context))

    def test_darwin_adds_exported_keychain_roots(self) -> None:
        context, trust = release.trust_context("Darwin", {}, lambda: INSPECTION_ROOT.encode())
        self.assertEqual(release.TrustRoots.KEYCHAIN, trust)
        self.assertTrue(trusts_inspection_root(context))
        self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)
        self.assertTrue(context.check_hostname)

    def test_export_failures_keep_the_default_context_and_their_finite_outcome(self) -> None:
        rejected = b"-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
        roots = release.TrustRoots
        cases: tuple[tuple[bytes | release.TrustRoots, release.TrustRoots], ...] = (
            (roots.KEYCHAIN_EXPORT_FAILED, roots.KEYCHAIN_EXPORT_FAILED),
            (roots.KEYCHAIN_EXPORT_TIMEOUT, roots.KEYCHAIN_EXPORT_TIMEOUT),
            (roots.KEYCHAIN_LIMIT_EXCEEDED, roots.KEYCHAIN_LIMIT_EXCEEDED),
            (INSPECTION_ROOT.encode() + rejected, roots.KEYCHAIN_REJECTED),
        )
        for exported, expected in cases:
            with self.subTest(expected=expected):
                context, trust = release.trust_context(
                    "Darwin", {}, lambda observed=exported: observed
                )
                self.assertEqual(expected, trust)
                self.assertFalse(trusts_inspection_root(context))
                self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)

    def test_security_export_admits_only_successful_certificate_output(self) -> None:
        self.assertEqual(
            INSPECTION_ROOT.encode(),
            release.export_keychains(("/bin/sh", "-c", 'printf "%s" "$0"', INSPECTION_ROOT)),
        )
        for command in (
            ("/bin/sh", "-c", 'printf "%s" "$0"; exit 1', INSPECTION_ROOT),
            ("/bin/sh", "-c", "printf 'no certificates'"),
            ("/nonexistent/security",),
        ):
            with self.subTest(command=command[-1][:20]):
                self.assertEqual(
                    release.TrustRoots.KEYCHAIN_EXPORT_FAILED, release.export_keychains(command)
                )

    def test_process_trust_exports_the_keychain_once(self) -> None:
        exports: list[None] = []

        def export() -> bytes:
            exports.append(None)
            return INSPECTION_ROOT.encode()

        environment = {
            key: value
            for key, value in os.environ.items()
            if key not in ("SSL_CERT_FILE", "SSL_CERT_DIR")
        }
        release.process_trust.cache_clear()
        try:
            with (
                patch.dict(os.environ, environment, clear=True),
                patch.object(release.platform, "system", return_value="Darwin"),
                patch.object(release, "export_keychains", export),
            ):
                first, second = release.process_trust(), release.process_trust()
        finally:
            release.process_trust.cache_clear()
        self.assertEqual([None], exports)
        self.assertIs(first, second)
        self.assertEqual(release.TrustRoots.KEYCHAIN, first[1])


if __name__ == "__main__":
    _ = unittest.main()
