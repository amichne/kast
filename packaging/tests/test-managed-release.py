"""Release admission uses one captured composition and only caller-owned temporary files."""

from __future__ import annotations

import hashlib
import io
import json
import socket
import ssl
import subprocess
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
    jvm: dict[str, bytes] | None = None,
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
    if jvm is not None:
        files["share/kast/jvm-control-v1"] = b"1\n"
        files["bin/kast-query"] = (
            b'#!/bin/sh\nexec "../share/kast/libexec/kast-management" --internal-entrypoint=query "$@"\n'
        )
        files.update(jvm)
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
        jvm: dict[str, bytes] | None = None,
    ) -> Source:
        control, host = archive_bytes(
            version,
            self.contract,
            marker=marker,
            native=native,
            source_tree=source_tree,
            unified=unified,
            jvm=jvm,
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

    def main(
        self, source: Source, version: str = "0.0.123", extra: tuple[str, ...] = ()
    ) -> release.MainEntry:
        for name in (
            f"kast-plugin-v{version}.zip",
            f"kast-marketplace-v{version}.zip",
            f"kast-hosted-catalog-v{version}.json",
            f"kast-module-knowledge-v{version}.json",
            f"kast-sbom-v{version}.cdx.json",
        ):
            source.assets[name] = name.encode()
            source.assets[name + ".sha256"] = f"{digest(name.encode())}  {name}\n".encode()
        for name in extra:
            source.assets[name] = name.encode()
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
        selected = release.resolve_selection("preview", None, None, source)
        assert isinstance(selected, release.MainSelection)
        admitted = release.stage_component_archives(selected, "263", self.stage, source)
        assert isinstance(admitted, release.AdmittedComposition), admitted
        self.assertEqual("kast-ide-hosted-v0.0.123-idea-263.zip", admitted.host_archive.name)
        self.assertEqual("kast-host-release-v0.0.123-idea-263.json", admitted.host_record.name)
        self.assertFalse((self.stage / "downloads/kast-ide-hosted-v0.0.123-idea-262.zip").exists())

    def admit_main(self, extra: tuple[str, ...]) -> object:
        source = self.source("0.0.123")
        _ = self.main(source, extra=extra)
        selected = release.resolve_selection("preview", None, None, source)
        assert isinstance(selected, release.MainSelection)
        return release.stage_component_archives(selected, "262", self.stage, source)

    def test_main_release_without_the_retired_desktop_installer_is_admitted(self) -> None:
        self.assertIsInstance(self.admit_main(()), release.AdmittedComposition)

    def test_published_release_with_the_retired_desktop_installer_is_admitted(self) -> None:
        retired = ("host-installation.py", "host-installation.py.sha256")
        self.assertIsInstance(self.admit_main(retired), release.AdmittedComposition)

    def test_retired_installer_checksum_without_its_asset_rejects(self) -> None:
        self.assertIsInstance(
            self.admit_main(("host-installation.py.sha256",)), release.Rejected
        )

    def test_orphan_263_variant_rejects_even_for_262_selection(self) -> None:
        source = self.source("0.0.123", with_263=True)
        del source.assets["kast-host-release-v0.0.123-idea-263.json"]
        del source.assets["kast-host-release-v0.0.123-idea-263.json.sha256"]
        _ = self.main(source)
        selected = release.resolve_selection("preview", None, None, source)
        assert isinstance(selected, release.MainSelection)
        self.assertIsInstance(
            release.stage_component_archives(selected, "262", self.stage, source), release.Rejected
        )

    def test_changed_catalog_during_download_does_not_change_selection_or_build_provenance(
        self,
    ) -> None:
        source = self.source("0.0.123")
        _ = self.main(source)
        selected = release.resolve_selection("preview", None, None, source)
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

    def test_jvm_control_admits_libraries_only_beneath_lib(self) -> None:
        library = b"PK\x03\x04jar"
        selected = release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK)
        admitted = release.stage_component_archives(
            selected, "262", self.stage, self.source("1.2.3", jvm={"lib/kast-cli.jar": library})
        )
        self.assertIsInstance(admitted, release.AdmittedComposition)
        for index, layout in enumerate((
            {"lib/kast-cli.jar": library, "share/kast/stray.jar": library},
            {"lib/kast-cli.jar": library, "lib/Stray.class": b"\xca\xfe\xba\xbe"},
            {"share/kast/kast-cli.jar": library},
        )):
            stage = self.stage.parent / f"jvm-case-{index}"
            stage.mkdir()
            rejected = release.stage_component_archives(
                selected, "262", stage, self.source("1.2.3", jvm=layout)
            )
            self.assertIsInstance(rejected, release.Rejected, layout)
            assert isinstance(rejected, release.Rejected)
            self.assertEqual((release.Failure.ARTIFACT_INVALID, release.Stage.CONTRACT), (rejected.reason, rejected.stage), layout)

    def test_any_second_native_file_rejects_the_control_payload(self) -> None:
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
        executable = bytes.fromhex("cffaedfe0c0000010000000002000000") + bytes(16)
        elf = bytes.fromhex("7f454c4602010100000000000000000003003e0001000000") + bytes(8)
        for target, header in (
            ("macos-aarch64", dylib),
            ("macos-aarch64", executable),
            ("linux-x86_64", elf),
        ):
            with self.subTest(target=target, header=header.hex()):
                management = admitted.control_root / "share/kast/libexec/kast-management"
                _ = management.write_bytes(executable if target == "macos-aarch64" else elf)
                self.assertEqual(
                    admitted.identity,
                    release.inspect_control(admitted.control_root, selected, record, target),
                )
                path = admitted.control_root / "share/kast/site-packages/module.so"
                path.parent.mkdir(parents=True, exist_ok=True)
                _ = path.write_bytes(header)
                self.assertEqual(
                    release.Failure.ARTIFACT_INVALID,
                    release.inspect_control(admitted.control_root, selected, record, target),
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
        result = release.resolve_selection("preview", None, None, source)
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.PLATFORM_UNAVAILABLE, result.reason)
        self.assertEqual((one.mainRevision, two.mainRevision), result.selectable)

    def test_main_is_not_a_channel_selector(self) -> None:
        source = self.source("0.0.123")
        result = release.resolve_selection("main", None, None, source)
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.SELECTOR_INVALID, result.reason)
        self.assertEqual([], source.calls)

    def test_conflicting_selectors_and_captured_identity_mismatch_reject_without_downloads(
        self,
    ) -> None:
        source = self.source("0.0.123")
        result = release.resolve_selection("preview", "0.0.123", None, source)
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


class FailingTransport(release.HttpReleaseSource):
    """Inject one urllib observation at the opener boundary; production maps it."""

    def __init__(self, failure: OSError | None, body: bytes = b"") -> None:
        super().__init__(
            lambda: (ssl.create_default_context(), release.TrustRoots.KEYCHAIN), system="Linux"
        )
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
        selection = release.resolve_selection("preview", None, None, untrusted)
        assert isinstance(selection, release.Rejected)
        self.assertEqual(
            {
                "reason": "CATALOG_UNAVAILABLE",
                "stage": "SELECTION",
                "requested": "preview",
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


class NativeTransportTest(unittest.TestCase):
    def test_native_reads_and_downloads_keep_limits_digests_and_protected_files(self) -> None:
        calls: list[tuple[str, ...]] = []

        def run(command: tuple[str, ...], output: IO[bytes]) -> subprocess.CompletedProcess[bytes]:
            calls.append(command)
            _ = output.write(b"native bytes")
            return subprocess.CompletedProcess(command, 0, stderr=b"200")

        source = release.HttpReleaseSource(system="Darwin", environment={}, native=run)
        self.assertEqual(b"native bytes", source.read("https://example.test/data", 12))
        self.assertEqual(
            release.TransferFailure.LIMIT_EXCEEDED, source.read("https://example.test/data", 11)
        )
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "asset"
            self.assertEqual(
                release.DownloadedFile(target, hashlib.sha256(b"native bytes").hexdigest(), 12),
                source.download("https://example.test/data", target, 12),
            )
            self.assertEqual(b"native bytes", target.read_bytes())
            self.assertEqual(
                release.TransferRejected(
                    release.TransferFailure.UNAVAILABLE, release.TrustRoots.KEYCHAIN
                ),
                source.download("https://example.test/data", target, 12),
            )
            self.assertEqual(b"native bytes", target.read_bytes())
        self.assertEqual(3, len(calls))
        for command in calls:
            self.assertEqual(("/usr/bin/curl", "--disable"), command[:2])
            for option in ("--proto", "--proto-redir"):
                self.assertEqual("=https", command[command.index(option) + 1])
            self.assertEqual("1800", command[command.index("--max-time") + 1])
            self.assertNotIn("--insecure", command)
            self.assertNotIn("--cacert", command)

    def test_native_failures_are_finite_and_do_not_fall_back_to_exported_anchors(self) -> None:
        failure = release.TransferFailure
        cases = (
            (60, b"000", failure.TLS_CERTIFICATE_UNTRUSTED, None),
            (35, b"000", failure.TLS_FAILED, None),
            (28, b"000", failure.TIMEOUT, None),
            (6, b"000", failure.CONNECTION_FAILED, None),
            (63, b"200", failure.LIMIT_EXCEEDED, None),
            (22, b"403", failure.HTTP_STATUS, 403),
            (56, b"503", failure.HTTP_STATUS, 503),
            (22, b"404", failure.NOT_FOUND, None),
            (56, b"404", failure.NOT_FOUND, None),
            (22, b"malformed", failure.UNAVAILABLE, None),
        )
        for code, status, cause, expected_status in cases:
            with self.subTest(code=code):
                calls: list[None] = []

                def run(
                    command: tuple[str, ...],
                    output: IO[bytes],
                    observations: list[None] = calls,
                    exit_code: int = code,
                    http_status: bytes = status,
                ) -> subprocess.CompletedProcess[bytes]:
                    observations.append(None)
                    return subprocess.CompletedProcess(command, exit_code, stderr=http_status)

                def forbidden_context() -> tuple[ssl.SSLContext, release.TrustRoots]:
                    self.fail("native failures must not fall back to OpenSSL anchors")

                source = release.HttpReleaseSource(
                    forbidden_context, system="Darwin", environment={}, native=run
                )
                self.assertEqual(
                    failure.NOT_FOUND
                    if cause is failure.NOT_FOUND
                    else release.TransferRejected(
                        cause, release.TrustRoots.KEYCHAIN, expected_status
                    ),
                    source.read("https://example.test/data", 12),
                )
                self.assertEqual([None], calls)

    def test_linux_and_explicit_openssl_roots_do_not_invoke_native_transport(self) -> None:
        for system, environment in (
            ("Linux", {}),
            ("Darwin", {"SSL_CERT_FILE": "/private/roots.pem"}),
            ("Darwin", {"SSL_CERT_DIR": "/private/roots"}),
        ):
            with self.subTest(system=system, environment=environment):

                def forbidden_native(
                    command: tuple[str, ...], output: IO[bytes]
                ) -> subprocess.CompletedProcess[bytes]:
                    self.fail("Linux and explicit OpenSSL roots must not invoke native transport")

                source = release.HttpReleaseSource(
                    system=system, environment=environment, native=forbidden_native
                )
                with patch.object(source, "open", return_value=io.BytesIO(b"openssl bytes")):
                    self.assertEqual(b"openssl bytes", source.read("https://example.test/data", 13))


if __name__ == "__main__":
    _ = unittest.main()
