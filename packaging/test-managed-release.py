"""Release admission uses one captured composition and only caller-owned temporary files."""

from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from dataclasses import asdict, dataclass, replace
from dataclasses import field as dataclass_field
from pathlib import Path
from typing import override
from urllib.parse import urlsplit

import managed_release as release

BUILD = "a" * 40
MAIN = "b" * 40
TREE = "c" * 40
CATALOG_V2_URL = "https://raw.githubusercontent.com/amichne/kast/developer-latest/builds-v2.json"


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
class SdkTargetFixture:
    intellijReleaseLine: str
    ideBuild: str
    kotlinPluginBuild: str


@dataclass(frozen=True)
class TargetProfileFixture:
    targets: tuple[SdkTargetFixture, SdkTargetFixture]
    defaultTarget: str = "262"
    schemaVersion: int = 1


PROFILE = TargetProfileFixture(
    (SdkTargetFixture("262", "262.1", "262.1-IJ"), SdkTargetFixture("263", "263.2", "263.2-IJ"))
)


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
class CandidateV2(Candidate):
    hostTargets: tuple[release.HostTarget, release.HostTarget] = dataclass_field(kw_only=True)
    schemaVersion: int = 2


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
    | TargetProfileFixture
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
    idea_line: str = "262",
    ide_build: str | None = None,
    kotlin_build: str | None = None,
    target_profile: TargetProfileFixture | None = PROFILE,
) -> tuple[bytes, bytes]:
    sdk = ide_build if ide_build is not None else idea_line + ".1"
    kotlin = kotlin_build if kotlin_build is not None else sdk + "-IJ"
    files = {
        "share/kast/ide-host.json": encoded(ControlMetadata(version, contract)),
        "share/kast/build-identity.json": encoded(BuildResource(version, sourceTree=source_tree)),
        "share/kast/agent-tools/plugins/kast/skills/kast/SKILL.md": b"# Kast\nUse kast describe.\n",
        "share/kast/libexec/kast-management": b"\xcf\xfa\xed\xfe\x0c\x00\x00\x01fixture",
    }
    if target_profile is not None:
        files["share/kast/intellij-targets.json"] = encoded(target_profile)
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
            f"<idea-plugin><id>io.github.amichne.kast.ide-hosted</id><version>{version}</version>"
            f'<idea-version since-build="{idea_line}" until-build="{idea_line}.*"/></idea-plugin>',
        )
        plugin.writestr("hosted-contract.json", encoded(contract))
        plugin.writestr(
            "kast-hosted-query.properties",
            f"version={version}\nsourceRevision={BUILD}\nsourceTree={source_tree}\n"
            f"ideBuild={sdk}\nkotlinBuild={kotlin}\n",
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
            if url in (release.CATALOG_URL, CATALOG_V2_URL, release.STABLE_URL)
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
        target_profile: TargetProfileFixture | None = PROFILE,
    ) -> Source:
        control, host = archive_bytes(
            version,
            self.contract,
            marker=marker,
            native=native,
            source_tree=source_tree,
            unified=unified,
            target_profile=target_profile,
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

    def dual_main(
        self, source: Source, host_sdk: str = "263.2", host_kotlin: str = "263.2-IJ"
    ) -> release.MainEntryV2:
        version = "0.0.123"
        entry = self.main(source, version)
        _, host = archive_bytes(
            version,
            self.contract,
            idea_line="263",
            ide_build=host_sdk,
            kotlin_build=host_kotlin,
        )
        archive_name = f"kast-ide-hosted-v{version}-idea-263.zip"
        record_name = f"kast-host-release-v{version}-idea-263.json"
        record = encoded(
            HostRecord(
                version,
                Artifact(archive_name, "sha256:" + digest(host), len(host)),
                self.contract,
                supportedIntellijReleaseLine="263",
            )
        )
        for name, raw in ((archive_name, host), (record_name, record)):
            source.assets[name] = raw
            source.assets[name + ".sha256"] = f"{digest(raw)}  {name}\n".encode()
        targets = (
            release.HostTarget(
                "262",
                "262.1",
                "262.1-IJ",
                release.RecordReference(
                    f"kast-host-release-v{version}.json",
                    digest(source.assets[f"kast-host-release-v{version}.json"]),
                ),
            ),
            release.HostTarget(
                "263",
                "263.2",
                "263.2-IJ",
                release.RecordReference(
                    record_name,
                    digest(record),
                ),
            ),
        )
        records = {"candidate.json", "promotion.json", "candidate-v2.json", "promotion-v2.json"}
        candidate = encoded(
            CandidateV2(
                version,
                tuple(
                    CandidateAsset(name, digest(raw), len(raw))
                    for name, raw in sorted(source.assets.items())
                    if not name.startswith("https://") and name not in records
                ),
                hostTargets=targets,
            )
        )
        source.assets["candidate-v2.json"] = candidate
        source.assets["promotion-v2.json"] = encoded(
            Promotion(
                version,
                digest(candidate),
                schemaVersion=2,
            )
        )
        dual = release.MainEntryV2(
            entry.mainRevision,
            entry.buildRevision,
            entry.sourceTree,
            entry.releaseTag,
            entry.version,
            entry.platformTargets,
            release.RecordReference("candidate-v2.json", digest(candidate)),
            release.RecordReference(
                "promotion-v2.json", digest(source.assets["promotion-v2.json"])
            ),
            targets,
        )
        source.assets[CATALOG_V2_URL] = encoded(Catalog((dual,), schemaVersion=2))
        return dual

    def test_dual_main_admits_only_the_selected_namespaced_host(self) -> None:
        source = self.source("0.0.123")
        entry = self.dual_main(source)
        selection = release.MainSelection(entry.identity(), release.TrackMain(), entry)
        result = release.download_composition(selection, "263", self.stage, source)
        assert isinstance(result, release.AdmittedComposition), result
        self.assertEqual("kast-host-release-v0.0.123-idea-263.json", result.host_record.name)
        self.assertEqual("kast-ide-hosted-v0.0.123-idea-263.zip", result.host_archive.name)
        self.assertFalse(any(url.endswith("idea-262.zip") for url in source.calls))
        self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())

    def test_dual_control_profile_must_bind_both_published_compile_targets(self) -> None:
        changed = replace(
            PROFILE, targets=(PROFILE.targets[0], SdkTargetFixture("263", "263.999", "263.999-IJ"))
        )
        for profile in (None, changed):
            with (
                self.subTest(profile=profile),
                tempfile.TemporaryDirectory(dir=self.root) as directory,
            ):
                source = self.source("0.0.123", target_profile=profile)
                entry = self.dual_main(source)
                result = release.download_composition(
                    release.MainSelection(entry.identity(), release.TrackMain(), entry),
                    "263",
                    Path(directory),
                    source,
                )
                self.assertIsInstance(result, release.Rejected)
                if isinstance(result, release.Rejected):
                    self.assertEqual(release.Stage.CONTRACT, result.stage)
                self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())

    def test_dual_main_preserves_legacy262_reader_view_and_common_payloads(self) -> None:
        source = self.source("0.0.123")
        _ = self.dual_main(source)
        legacy = release.resolve_selection(
            "main",
            None,
            None,
            Source(
                {
                    release.CATALOG_URL: source.assets[release.CATALOG_URL],
                }
            ),
        )
        assert isinstance(legacy, release.MainSelection)
        self.assertNotIsInstance(legacy.catalogEntry, release.MainEntryV2)
        result = release.download_composition(legacy, "262", self.stage, source)
        assert isinstance(result, release.AdmittedComposition), result
        self.assertEqual("kast-host-release-v0.0.123.json", result.host_record.name)

    def test_v2_archive_sdk_or_kotlin_substitution_rejects_after_ledger_admission(self) -> None:
        for sdk, kotlin in (("263.99", "263.2-IJ"), ("263.2", "263.99-IJ")):
            with self.subTest(sdk=sdk, kotlin=kotlin):
                source = self.source("0.0.123")
                entry = self.dual_main(source, sdk, kotlin)
                directory = self.root / ("case-" + sdk + kotlin)
                directory.mkdir()
                selected = release.MainSelection(entry.identity(), release.TrackMain(), entry)
                result = release.download_composition(selected, "263", directory, source)
                assert isinstance(result, release.Rejected), result
                self.assertEqual(release.Stage.CONTRACT, result.stage)
                self.assertEqual(release.Failure.ARTIFACT_INVALID, result.reason)
                self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())

    def test_v2_shared_provenance_substitution_in_paired_v1_is_rejected(self) -> None:
        source = self.source("0.0.123")
        entry = self.dual_main(source)
        # A separately well-formed V1 pair is still not the admitted V2 projection.
        candidate = json.loads(source.assets["candidate.json"])
        candidate["toolchain"]["vendor"] = "substituted vendor"
        source.assets["candidate.json"] = json.dumps(candidate).encode()
        source.assets["promotion.json"] = encoded(
            Promotion(
                "0.0.123",
                digest(source.assets["candidate.json"]),
            )
        )
        selected = release.MainSelection(entry.identity(), release.TrackMain(), entry)
        result = release.download_composition(selected, "263", self.stage, source)
        assert isinstance(result, release.Rejected), result
        self.assertEqual(release.Stage.RECORDS, result.stage)
        self.assertEqual(release.Failure.ARTIFACT_INVALID, result.reason)

    def test_stable_admission_keeps_files_processes_and_registrations_outside_temporary_storage(
        self,
    ) -> None:
        source = self.source("1.2.3")
        selected = release.resolve_selection(None, None, None, source)
        assert isinstance(selected, release.StableSelection)
        admitted = release.download_composition(selected, "262", self.stage, source)
        assert isinstance(admitted, release.AdmittedComposition)
        self.assertEqual(release.BuildIdentity("v1.2.3", "1.2.3", BUILD, TREE), admitted.identity)
        self.assertEqual(release.TrackStable(), admitted.policy)
        self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())
        self.assertEqual(("keep",), tuple(path.name for path in self.protected.iterdir()))

    def test_changed_catalog_during_download_does_not_change_selection_or_build_provenance(
        self,
    ) -> None:
        source = self.source("0.0.123")
        _ = self.main(source)
        selected = release.resolve_selection("main", None, None, source)
        assert isinstance(selected, release.MainSelection)
        source.change_catalog_on_download = True
        admitted = release.download_composition(selected, "262", self.stage, source)
        assert isinstance(admitted, release.AdmittedComposition), admitted
        self.assertEqual(MAIN, admitted.identity.mainRevision)
        self.assertEqual(BUILD, admitted.identity.buildRevision)
        self.assertEqual(1, source.catalog_reads)
        self.assertTrue(
            all(
                "developer-v0.0.123" in url
                for url in source.calls
                if url not in (release.CATALOG_URL, CATALOG_V2_URL, release.STABLE_URL)
            )
        )

    def test_explicit_version_creates_pin_after_source_identity_admission(self) -> None:
        source = self.source("1.2.3")
        selected = release.resolve_selection(None, "1.2.3", None, source)
        assert isinstance(selected, release.StableSelection)
        admitted = release.download_composition(selected, "262", self.stage, source)
        assert isinstance(admitted, release.AdmittedComposition)
        self.assertEqual(release.PinnedBuild(admitted.identity), admitted.policy)
        self.assertNotIn(release.STABLE_URL, source.calls)

    def test_single_native_control_admits_its_query_launcher_without_jvm_libraries(self) -> None:
        source = self.source("1.2.3", unified=True)
        selected = release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK)
        admitted = release.download_composition(selected, "262", self.stage, source)
        self.assertIsInstance(admitted, release.AdmittedComposition)
        self.assertEqual(b"prior composition", (self.protected / "keep").read_bytes())

    def test_missing_capability_or_native_query_rejects_before_installation_changes(self) -> None:
        for option in ("marker", "native"):
            with (
                self.subTest(option=option),
                tempfile.TemporaryDirectory(dir=self.root) as temporary,
            ):
                source = self.source("1.2.3", marker=option != "marker", native=option != "native")
                selected = release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.TRACK)
                result = release.download_composition(selected, "262", Path(temporary), source)
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
        result = release.download_composition(
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
        result = release.download_composition(
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
        result = release.download_composition(selected, "262", self.stage, source)
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
                result = release.download_composition(selected, "262", Path(temporary), source)
                assert isinstance(result, release.Rejected)
                self.assertEqual(release.Failure.ARTIFACT_INVALID, result.reason)
                self.assertFalse((Path(temporary) / "control").exists())

    def test_checksum_failure_never_downloads_executables(self) -> None:
        source = self.source("1.2.3")
        source.assets["kast-control-release-v1.2.3.json.sha256"] = b"different\n"
        result = release.download_composition(
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
        result = release.download_composition(
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


class VersionedCatalogTest(unittest.TestCase):
    def test_authoritative_profile_preserves_each_compiled_sdk_without_runtime_patch_equality(
        self,
    ) -> None:
        raw = encoded(
            TargetProfileFixture(
                (
                    SdkTargetFixture("262", "262.123", "262.123-IJ"),
                    SdkTargetFixture("263", "263.456", "263.456-IJ"),
                )
            )
        )
        result = release.parse_supported_ide_targets(raw)
        assert isinstance(result, release.SupportedIdeTargets), result
        self.assertEqual(("262", "263"), tuple(t.intellijReleaseLine for t in result.targets))
        self.assertEqual("263.456", result.targets[1].ideBuild)
        self.assertEqual("262", result.defaultTarget)

    def test_profile_rejects_unknown_mixed_duplicate_targets_and_extra_fields(self) -> None:
        raw = encoded(
            TargetProfileFixture(
                (
                    SdkTargetFixture("262", "262.123", "262.123-IJ"),
                    SdkTargetFixture("263", "263.456", "263.456-IJ"),
                )
            )
        )
        for key, value in (
            ("intellijReleaseLine", "264"),
            ("intellijReleaseLine", "262"),
            ("kotlinPluginBuild", "262.456-IJ"),
            ("unknown", True),
        ):
            with self.subTest(key=key, value=value):
                invalid = json.loads(raw)
                invalid["targets"][1][key] = value
                self.assertEqual(
                    release.Failure.ARTIFACT_INVALID,
                    release.parse_supported_ide_targets(json.dumps(invalid).encode()),
                )

    def legacy_entry(self) -> release.MainEntry:
        return release.MainEntry(
            MAIN,
            BUILD,
            TREE,
            "developer-v0.0.123",
            "0.0.123",
            (release.PLATFORM,),
            release.RecordReference("candidate.json", "d" * 64),
            release.RecordReference("promotion.json", "e" * 64),
        )

    def dual_entry(self) -> release.MainEntryV2:
        return release.MainEntryV2(
            MAIN,
            BUILD,
            TREE,
            "developer-v0.0.123",
            "0.0.123",
            (release.PLATFORM,),
            release.RecordReference("candidate-v2.json", "d" * 64),
            release.RecordReference("promotion-v2.json", "e" * 64),
            (
                release.HostTarget(
                    "262",
                    "262.11879.100",
                    "262.11879.100-IJ",
                    release.RecordReference("kast-host-release-v0.0.123.json", "f" * 64),
                ),
                release.HostTarget(
                    "263",
                    "263.7307.200",
                    "263.7307.200-IJ",
                    release.RecordReference("kast-host-release-v0.0.123-idea-263.json", "a" * 64),
                ),
            ),
        )

    def test_main_v2_selects_exact_target_and_preserves_captured_selection(self) -> None:
        entry = self.dual_entry()
        source = Source({CATALOG_V2_URL: encoded(Catalog((entry,), schemaVersion=2))})
        selected = release.resolve_selection("main", None, None, source)
        self.assertEqual(
            release.MainSelection(entry.identity(), release.TrackMain(), entry), selected
        )
        assert isinstance(selected, release.MainSelection)
        self.assertEqual(selected, release.decode_selection(encoded(selected)))
        self.assertEqual(entry.hostTargets[1], release.select_host_target(selected, "263"))
        self.assertEqual([CATALOG_V2_URL], source.calls)

    def test_only_v2_not_found_falls_back_to_unchanged_legacy_catalog(self) -> None:
        entry = self.legacy_entry()
        source = Source({release.CATALOG_URL: encoded(Catalog((entry,)))})
        selected = release.resolve_selection("main", None, None, source)
        self.assertEqual(
            release.MainSelection(entry.identity(), release.TrackMain(), entry), selected
        )
        self.assertEqual([CATALOG_V2_URL, release.CATALOG_URL], source.calls)
        assert isinstance(selected, release.MainSelection)
        self.assertIsInstance(release.select_host_target(selected, "262"), release.Legacy262Target)
        rejected = release.select_host_target(selected, "263")
        assert isinstance(rejected, release.Rejected)
        self.assertEqual(release.Failure.HOST_TARGET_UNAVAILABLE, rejected.reason)
        self.assertEqual(MAIN, rejected.requested)

    def test_present_malformed_v2_never_uses_valid_v1(self) -> None:
        legacy = encoded(Catalog((self.legacy_entry(),)))
        for malformed in (b"not json", b'{"schemaVersion":2,"builds":[],"extra":true}'):
            with self.subTest(raw=malformed):
                source = Source({CATALOG_V2_URL: malformed, release.CATALOG_URL: legacy})
                selected = release.resolve_selection("main", None, None, source)
                assert isinstance(selected, release.Rejected)
                self.assertEqual(release.Failure.CATALOG_UNAVAILABLE, selected.reason)
                self.assertEqual([CATALOG_V2_URL], source.calls)

    def test_v2_missing_requested_history_does_not_search_v1(self) -> None:
        source = Source(
            {
                CATALOG_V2_URL: encoded(Catalog((), schemaVersion=2)),
                release.CATALOG_URL: encoded(Catalog((self.legacy_entry(),))),
            }
        )
        selected = release.resolve_selection(None, None, MAIN, source)
        assert isinstance(selected, release.Rejected)
        self.assertEqual(release.Failure.BINARY_UNAVAILABLE, selected.reason)
        self.assertEqual([CATALOG_V2_URL], source.calls)

    def test_target_discrimination_rejects_duplicate_unknown_and_substituted_bindings(self) -> None:
        entry = self.dual_entry()
        selected = release.MainSelection(entry.identity(), release.TrackMain(), entry)
        changes = (
            ("intellijReleaseLine", "262"),
            ("intellijReleaseLine", "264"),
            ("ideBuild", "262.7307.200"),
            ("kotlinPluginBuild", "262.7307.200-IJ"),
            ("record", {"name": "kast-host-release-v0.0.123.json", "sha256": "a" * 64}),
            ("unknown", "field"),
        )
        for key, value in changes:
            with self.subTest(key=key, value=value):
                # Deliberately malformed boundary documents, never valid fixture DTOs.
                document = json.loads(encoded(selected))
                document["catalogEntry"]["hostTargets"][1][key] = value
                result = release.decode_selection(json.dumps(document).encode())
                assert isinstance(result, release.Rejected)
                self.assertEqual(release.Failure.SELECTOR_INVALID, result.reason)

    def test_stable_263_is_an_explicit_target_rejection_without_effects(self) -> None:
        selected = release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.PIN)
        result = release.select_host_target(selected, "263")
        assert isinstance(result, release.Rejected)
        self.assertEqual(release.Failure.HOST_TARGET_UNAVAILABLE, result.reason)
        self.assertEqual("1.2.3", result.requested)


if __name__ == "__main__":
    _ = unittest.main()
