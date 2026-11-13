"""Complete product records retain target-local evidence, not platform promises."""

import contextlib
import hashlib
import io
import json
import os
import shlex
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from collections.abc import Callable
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import cast
from unittest.mock import patch

import bootstrap_entry
import composed_bundle
import generate_bootstrap
import immutable_store
import managed_installation as install
import managed_product as staging
import managed_product_installation as activation
import managed_product_removal as removal
import managed_release as release
import managed_update_contract as contract
import managed_update_journal as journal
import mcp_environment as mcp
import product_removal_recovery
import runtime_base
from composition_platform import HostPlatform
from managed_headless import AssemblyEntryKind, AssemblyFile
from runtime_archive import RuntimeFile, RuntimeLink, RuntimeManifest


def product() -> release.ProductRecord:
    targets = tuple(
        release.ProductTarget(
            target,
            release.ProductInputs("1" * 64, "2" * 64, "3" * 64, "4" * 64),
            release.ProductAsset(f"kast-composed-setup-v0.0.123-{target}.tar.gz", "5" * 64, 17),
            release.ProductAsset(f"kast-runtime-v0.0.123-{target}.zip", "6" * 64, 19),
            release.NativeQualification(
                release.ProductAsset(f"qualification-{target}.json", "7" * 64, 23),
                release.ProductProducer(456, 2, ".github/workflows/composed-linux.yml"),
                "5" * 64,
                "6" * 64,
                "8" * 64,
            ),
        )
        for target in ("linux-x86_64", "macos-aarch64")
    )
    return release.ProductRecord(
        2,
        "amichne/kast",
        "a" * 40,
        "b" * 40,
        "0.0.123",
        release.ProductProducer(123, 1, ".github/workflows/ci.yml"),
        targets,
    )


@dataclass(frozen=True)
class Entry:
    mainRevision: str
    releaseTag: str
    record: release.ProductRecord
    recordSha256: str


@dataclass(frozen=True)
class Catalog:
    builds: tuple[Entry, ...]
    schemaVersion: int = 2


class Source:
    def __init__(self, values: dict[str, bytes]):
        self.values = values
        self.calls: list[str] = []

    def read(self, url: str, limit: int) -> bytes | release.TransferFailure:
        self.calls.append(url)
        value = self.values.get(url)
        if value is None:
            return release.TransferFailure.NOT_FOUND
        assert len(value) <= limit
        return value

    def download(
        self, url: str, destination: Path, limit: int
    ) -> release.DownloadedFile | release.TransferFailure:
        value = self.read(url, limit)
        if isinstance(value, release.TransferFailure):
            return value
        with destination.open("xb") as output:
            _ = output.write(value)
        return release.DownloadedFile(destination, hashlib.sha256(value).hexdigest(), len(value))


class ProductRecordTest(unittest.TestCase):
    def test_complete_record_has_target_local_artifacts_and_native_evidence(self) -> None:
        record = product()
        raw = json.dumps(asdict(record)).encode()
        self.assertEqual(record, release.decode_product_record(raw))
        wire = json.loads(raw)
        self.assertEqual(
            {
                "schemaVersion",
                "repository",
                "sourceRevision",
                "sourceTree",
                "version",
                "producer",
                "targets",
            },
            set(wire),
        )
        self.assertNotIn("platformTargets", wire)
        self.assertEqual("NATIVE", wire["targets"][0]["qualification"]["kind"])
        self.assertEqual("COMPLETE", wire["targets"][0]["qualification"]["outcome"])

    def test_missing_duplicate_unsupported_runtime_mismatch_and_scripted_evidence_reject(
        self,
    ) -> None:
        original = product()
        target = original.targets[0]
        for targets in (
            original.targets[:1],
            (target, target),
            (replace(target, target="linux-aarch64"), original.targets[1]),
            (
                replace(target, runtime=replace(target.runtime, sha256="9" * 64)),
                original.targets[1],
            ),
            (
                replace(target, qualification=replace(target.qualification, kind="SCRIPTED")),
                original.targets[1],
            ),
            (
                replace(target, qualification=replace(target.qualification, outcome="NOT_RUN")),
                original.targets[1],
            ),
        ):
            with self.subTest(targets=targets):
                self.assertEqual(
                    release.Failure.ARTIFACT_INVALID,
                    release.decode_product_record(
                        json.dumps(asdict(replace(original, targets=targets))).encode()
                    ),
                )

    def test_unknown_fields_duplicates_boolean_attempt_and_foreign_workflow_reject(self) -> None:
        raw = json.dumps(asdict(product())).encode()
        for invalid in (
            raw.replace(b'"runAttempt": 1', b'"runAttempt": true'),
            raw.replace(b'"schemaVersion": 2', b'"schemaVersion": 2, "schemaVersion": 2'),
            raw.replace(b'"producer":', b'"unknown": 1, "producer":', 1),
            raw.replace(b"composed-linux.yml", b"untrusted.yml"),
        ):
            with self.subTest(raw=invalid):
                self.assertEqual(
                    release.Failure.ARTIFACT_INVALID, release.decode_product_record(invalid)
                )

    def test_preview_can_lag_main_and_main_is_only_an_alias(self) -> None:
        record = product()
        entry = Entry("a" * 40, "developer-v0.0.123", record, "d" * 64)
        source = Source({release.CATALOG_URL: json.dumps(asdict(Catalog((entry,)))).encode()})
        preview = release.resolve_selection("preview", None, None, source)
        self.assertEqual(preview, release.resolve_selection("main", None, None, source))
        assert isinstance(preview, release.ProductSelection)
        self.assertEqual("a" * 40, preview.mainRevision)
        self.assertEqual([release.CATALOG_URL, release.CATALOG_URL], source.calls)
        pinned = release.resolve_selection(None, None, "a" * 12, source)
        assert isinstance(pinned, release.ProductSelection)
        self.assertEqual("PinnedBuild", pinned.policy)
        self.assertEqual(pinned, release.decode_selection(json.dumps(asdict(pinned)).encode()))
        missing = release.resolve_selection(None, None, "e" * 40, source)
        assert isinstance(missing, release.Rejected)
        self.assertEqual(release.Failure.BINARY_UNAVAILABLE, missing.reason)

    def test_exact_commit_cannot_substitute_same_tree_different_revision(self) -> None:
        entry = Entry("c" * 40, "developer-v0.0.123", product(), "d" * 64)
        source = Source({release.CATALOG_URL: json.dumps(asdict(Catalog((entry,)))).encode()})
        selected = release.resolve_selection(None, None, "c" * 40, source)
        assert isinstance(selected, release.Rejected)
        self.assertEqual(release.Failure.CATALOG_UNAVAILABLE, selected.reason)
        self.assertEqual([release.CATALOG_URL], source.calls)

    def test_public_version_maps_to_embedded_version_without_substitution_or_activation(
        self,
    ) -> None:
        raw = json.dumps(asdict(product())).encode()
        digest = hashlib.sha256(raw).hexdigest()
        base = f"{release.RELEASE_URL}/v1.2.3"
        source = Source(
            {
                f"{base}/release-mapping.json": json.dumps(
                    asdict(release.ReleaseMapping(1, "1.2.3", digest))
                ).encode(),
                f"{base}/build-record.json": raw,
            }
        )
        selection = release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.PIN)
        selected = release.resolve_product_mapping(selection, source)
        assert isinstance(selected, release.ProductSelection)
        self.assertEqual("0.0.123", selected.version)
        self.assertEqual("1.2.3", selected.publicVersion)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = release.download_composition(selected, "262", root, source)
            assert isinstance(result, release.Rejected)
            self.assertEqual(release.Failure.ARTIFACT_INVALID, result.reason)
            self.assertEqual([], list(root.iterdir()))
        self.assertTrue(all(url.startswith(base) for url in source.calls))

    def test_legacy_incomplete_release_is_unavailable_before_staging(self) -> None:
        source = Source({})
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = release.download_composition(
                release.StableSelection("1.2.3", "v1.2.3", release.StablePolicy.PIN),
                "262",
                root,
                source,
            )
            assert isinstance(result, release.Rejected)
            self.assertEqual(release.Failure.BINARY_UNAVAILABLE, result.reason)
            self.assertEqual([], list(root.iterdir()))


@dataclass(frozen=True)
class QualificationFixture:
    sourceRevision: str
    bundleSha256: str
    containers: "ContainerFixture"
    probe: "ProbeFixture"
    qualification: str = "COMPLETE"
    composition: release.NativeCompositionEvidence | None = None
    qualificationStage: str = "COMPONENT_NATIVE"
    harnessSha256: str = "d" * 64


@dataclass(frozen=True)
class ContainerFixture:
    runtimeSha256: str
    controlSha256: str


@dataclass(frozen=True)
class ProbeFixture:
    evidence: str = "disposable test bytes; not native qualification"


@dataclass(frozen=True)
class AbsenceFixture:
    type: str


@dataclass(frozen=True)
class RemovalJournalFixture:
    installationRoot: str
    executable: str
    executableSha256: str
    channel: str
    type: str
    managedRootIdentity: journal.Identity
    controlIdentity: journal.Identity
    recoveryProof: AbsenceFixture
    homeConfiguration: AbsenceFixture
    product: staging.ProductReference


@dataclass(frozen=True)
class WorkspaceRemovalFixture:
    id: str
    identity: journal.Identity
    recordSha256: str


@dataclass(frozen=True)
class WorkspaceRemovalJournalFixture(RemovalJournalFixture):
    workspaceRemovals: tuple[WorkspaceRemovalFixture, ...]


def encoded_fixture(value: object) -> bytes:
    # All fixed fixture shapes are dataclasses; archive member maps contain opaque bytes.
    return install.encoded(value)


class ProductActivationTest(unittest.TestCase):
    def setUp(self) -> None:
        _ = self.enterContext(patch.object(staging.platform, "system", return_value="Darwin"))
        _ = self.enterContext(patch.object(staging.platform, "machine", return_value="arm64"))
        self.temporary = tempfile.TemporaryDirectory(prefix="kast-product-test-")
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name).resolve()
        self.root = self.home / "managed"
        fixture = install.module(
            "kast_component_fixtures", Path(__file__).with_name("test-managed-release.py")
        )
        case = fixture.ReleaseAdmissionTest()
        case.setUp()
        self.addCleanup(case.doCleanups)
        assets = cast(
            dict[str, bytes], case.source("0.0.123", source_tree="b" * 40, unified=True).assets
        )
        assets = {name: raw for name, raw in assets.items() if name != release.STABLE_URL}
        self.values: dict[str, bytes] = {}
        self.record = product()
        target = "macos-aarch64"
        prefix = "host/Kast.app/Contents"
        host_name = "kast-ide-hosted/lib/kast-ide-hosted.jar"
        with zipfile.ZipFile(io.BytesIO(assets["kast-ide-hosted-v0.0.123-idea-262.zip"])) as host:
            host_jar = host.read(host_name)
        bootstrap_name = "/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar"
        runtime_files = {
            prefix + bootstrap_name: b"bootstrap",
            prefix + "/jbr/Contents/Home/bin/java": b"native fixture",
            prefix + "/Resources/product-info.json": b"product",
            prefix + "/lib/kast-product.jar": b"jar",
            prefix + "/plugins/plugin-classpath.txt": b"classpath",
            "components/plugins/" + host_name: host_jar,
        }
        manifest = RuntimeManifest(
            "0.0.123",
            HostPlatform.MACOS_ARM64,
            "262.1",
            "4" * 64,
            hashlib.sha256(b"bootstrap").hexdigest(),
            tuple(
                RuntimeFile(name, len(raw), hashlib.sha256(raw).hexdigest(), name.endswith("/java"))
                for name, raw in runtime_files.items()
            ),
        )
        runtime = io.BytesIO()
        with zipfile.ZipFile(runtime, "w") as archive:
            archive.writestr("manifest.json", encoded_fixture(manifest))
            for name, raw in runtime_files.items():
                archive.writestr(name, raw)
        composed_files = {"assets/" + name: raw for name, raw in assets.items()}
        bootstrap = io.BytesIO()
        with zipfile.ZipFile(bootstrap, "w") as archive:
            archive.writestr("kast-managed-headless/lib/kast-managed-headless.jar", b"bootstrap")
        composed_files.update(
            {
                "bootstrap.zip": bootstrap.getvalue(),
                "composition-manifest.json": b"composition",
                "install.sh": b"must not execute",
                "bin/setup-kast.pyz": b"must not execute",
                "runtime/Resources/product-info.json": b"product",
                "runtime/lib/kast-product.jar": b"jar",
                "runtime/plugins/plugin-classpath.txt": b"classpath",
            }
        )
        metadata = composed_bundle.BundleManifest(
            "0.0.123",
            "262.1",
            "4" * 64,
            hashlib.sha256(composed_files["bootstrap.zip"]).hexdigest(),
            hashlib.sha256(b"composition").hexdigest(),
            tuple(
                AssemblyFile(
                    name, AssemblyEntryKind.FILE, len(raw), hashlib.sha256(raw).hexdigest()
                )
                for name, raw in composed_files.items()
            ),
            HostPlatform.MACOS_ARM64,
        )
        bundle = io.BytesIO()
        with tarfile.open(fileobj=bundle, mode="w:gz") as archive:
            for name, raw in {"manifest.json": encoded_fixture(metadata), **composed_files}.items():
                member = tarfile.TarInfo("setup-kast/" + name)
                member.size, member.mode = len(raw), 0o644
                archive.addfile(member, io.BytesIO(raw))
        bundle_bytes, runtime_bytes = bundle.getvalue(), runtime.getvalue()

        def asset(name: str, raw: bytes) -> release.ProductAsset:
            self.values[name] = raw
            return release.ProductAsset(name, hashlib.sha256(raw).hexdigest(), len(raw))

        bundle_asset = asset(f"kast-composed-setup-v0.0.123-{target}.tar.gz", bundle_bytes)
        runtime_asset = asset(f"kast-runtime-v0.0.123-{target}.zip", runtime_bytes)
        control_sha = hashlib.sha256(b"\xcf\xfa\xed\xfe\x0c\x00\x00\x01fixture").hexdigest()
        receipt = QualificationFixture(
            "a" * 40,
            bundle_asset.sha256,
            ContainerFixture(runtime_asset.sha256, control_sha),
            ProbeFixture(),
        )
        self.qualification = receipt
        facts = replace(
            self.record.targets[1],
            bundle=bundle_asset,
            runtime=runtime_asset,
            qualification=release.NativeQualification(
                asset("qualification-macos-aarch64.json", encoded_fixture(receipt)),
                self.record.targets[1].qualification.producer,
                bundle_asset.sha256,
                runtime_asset.sha256,
                control_sha,
            ),
        )
        self.record = replace(self.record, targets=(self.record.targets[0], facts))
        # Noncanonical whitespace must survive installation exactly.
        self.raw = json.dumps(asdict(self.record), indent=4).encode() + b"\n\n"
        self.selected = release.ProductSelection(
            "v1.2.3",
            self.record,
            hashlib.sha256(self.raw).hexdigest(),
            "PinnedBuild",
            "1.2.3",
            None,
        )
        self.values["build-record.json"] = self.raw
        self.source = Source(
            {f"{release.RELEASE_URL}/v1.2.3/{name}": raw for name, raw in self.values.items()}
        )

    def admitted(self) -> staging.AdmittedProduct:
        directory = Path(tempfile.mkdtemp(dir=self.home))
        with (
            patch.object(staging.platform, "system", return_value="Darwin"),
            patch.object(staging.platform, "machine", return_value="arm64"),
        ):
            result = release.download_composition(self.selected, "262", directory, self.source)
        self.assertIsInstance(result, staging.AdmittedProduct, result)
        assert isinstance(result, staging.AdmittedProduct)
        return result

    def test_split_product_activation_recovers_with_original_record_and_owned_runtime(self) -> None:
        directory = self.home / "producer"
        directory.mkdir()
        facts = self.record.targets[1]
        runtime, bundle = directory / facts.runtime.name, directory / facts.bundle.name
        _ = runtime.write_bytes(self.values[facts.runtime.name])
        _ = bundle.write_bytes(self.values[facts.bundle.name])
        work = directory / "split"
        work.mkdir()
        selected = runtime_base.split(
            runtime, bundle, HostPlatform.MACOS_ARM64, Path(__file__).parent, work
        )
        native = directory / "native"
        native.mkdir()
        native_runtime, native_bundle, _ = runtime_base.project_native(runtime, bundle, native)
        witness = release.NativeCompositionEvidence(
            selected.base.sha256,
            release.file_digest(native_runtime),
            release.file_digest(native_bundle),
            release.file_digest(runtime),
        )
        qualification = replace(
            self.qualification,
            bundleSha256=release.file_digest(bundle),
            containers=replace(
                self.qualification.containers, runtimeSha256=witness.nativeRuntimeSha256
            ),
            composition=witness,
        )
        receipt_path = directory / facts.qualification.receipt.name
        _ = receipt_path.write_bytes(encoded_fixture(qualification))

        def asset(path: Path) -> release.ProductAsset:
            return release.ProductAsset(path.name, release.file_digest(path), path.stat().st_size)

        facts = replace(
            facts,
            runtime=asset(runtime),
            bundle=asset(bundle),
            runtimeBase=selected.base,
            inputs=replace(
                facts.inputs,
                compositionRecipeSha256=release.file_digest(
                    Path(__file__).with_name("composition_recipe.py")
                ),
            ),
            qualification=replace(
                facts.qualification,
                bundleSha256=release.file_digest(bundle),
                runtimeSha256=release.file_digest(runtime),
                receipt=asset(receipt_path),
                composition=witness,
            ),
        )
        # This case exercises local macOS staging; the other target remains explicit
        # schema evidence, never an assertion that a Linux native probe ran.
        other = self.record.targets[0]
        other_base = release.ProductAsset(
            "kast-runtime-base-linux-x86_64-" + "9" * 64 + ".zip", "9" * 64, 10
        )
        other = replace(
            other,
            runtimeBase=other_base,
            qualification=replace(
                other.qualification,
                composition=release.NativeCompositionEvidence(
                    "9" * 64, "8" * 64, "7" * 64, other.runtime.sha256
                ),
            ),
        )
        self.record = replace(self.record, schemaVersion=3, targets=(other, facts))
        self.raw = encoded_fixture(self.record)
        self.selected = replace(
            self.selected, record=self.record, recordSha256=hashlib.sha256(self.raw).hexdigest()
        )
        for item in facts.assets():
            self.source.values[f"{release.RELEASE_URL}/v1.2.3/{item.name}"] = (
                directory / item.name
            ).read_bytes()
        self.source.values[f"{release.RELEASE_URL}/v1.2.3/build-record.json"] = self.raw
        admitted = self.admitted()
        base_parent = self.home / "kast-runtime-bases"
        activation.private_directory(base_parent)
        with (
            patch.object(immutable_store, "clean_publication", return_value=None),
            immutable_store.publication(base_parent, admitted, "BASE") as payload,
        ):
            _ = runtime_base.extract_base(
                admitted.runtime.parent / selected.base.name, selected, payload
            )
            immutable_store.publish(payload, admitted, "BASE")
            java = payload / "host/Kast.app/Contents/jbr/Contents/Home/bin/java"
            java.chmod(0o700)
            _ = java.write_bytes(b"native ")
        # Older publishers staged inside the digest-only store.
        payload = payload.parent.rename(base_parent / (".base-" + payload.parent.name)) / "payload"
        proofs, _, _ = product_removal_recovery.capture(self.root, admitted.reference(self.root))
        self.assertEqual([], proofs)
        self.assertEqual([], list(base_parent.iterdir()))
        with patch.object(install, "replace_file", side_effect=KeyboardInterrupt):
            self.assertIsInstance(
                activation.activate(self.root, self.home, admitted), contract.RecoveryRequired
            )
        self.assertIsInstance(activation.resume(self.root, self.home), activation.ProductInstalled)
        base = self.home / "kast-runtime-bases" / selected.base.sha256
        self.assertTrue(base.is_dir())
        self.assertEqual(
            self.raw, (self.root / "installation/share/kast/build-record.json").read_bytes()
        )
        installed = activation.activate(self.root, self.home, admitted, runner=self.retire)
        self.assertIsInstance(installed, activation.ProductInstalled)
        assert isinstance(installed, activation.ProductInstalled)
        vendor = "host/Kast.app/Contents/jbr/Contents/Home/bin/java"
        composed = Path(admitted.reference(self.root).runtimeRoot)
        self.assertNotEqual((base / vendor).stat().st_ino, (composed / vendor).stat().st_ino)
        self.assertEqual(b"native fixture", (base / vendor).read_bytes())
        with (
            patch.object(immutable_store, "clean_publication", return_value=None),
            immutable_store.publication(composed.parent, admitted, "RUNTIME") as payload,
        ):
            _ = shutil.copytree(admitted.runtime, payload)
            immutable_store.publish(payload, admitted, "RUNTIME")
            (payload / vendor).chmod(0o700)
            _ = (payload / vendor).write_bytes(b"native ")
        payload = (
            payload.parent.rename(composed.parent / (".runtime-" + payload.parent.name)) / "payload"
        )
        proofs, _, _ = product_removal_recovery.capture(self.root, admitted.reference(self.root))
        self.assertEqual({"RUNTIME", "BASE"}, {proof.kind for proof in proofs})
        self.assertFalse(payload.parent.exists())
        (base / vendor).chmod(0o700)
        _ = (base / vendor).write_bytes(b"damaged owned base")
        (composed / vendor).chmod(0o700)
        _ = (composed / vendor).write_bytes(b"damaged owned runtime")
        original = (base / "provenance/provenance.json").read_bytes()
        repaired = activation.activate(self.root, self.home, admitted, runner=self.retire)
        assert isinstance(repaired, activation.ProductInstalled)
        installed = repaired
        self.assertEqual(b"native fixture", (base / vendor).read_bytes())
        self.assertEqual(b"native fixture", (composed / vendor).read_bytes())
        self.assertEqual(original, (base / "provenance/provenance.json").read_bytes())
        self.assertFalse((self.root / "recovery/replacement").exists())
        path, _ = self.removal_journal(installed)
        self.assertEqual(0, self.remove_product(path))
        self.assertFalse(base.exists())
        self.assertFalse(composed.exists())
        entries = json.loads(path.read_bytes())["productRemoval"]["immutableRemovals"]
        self.assertEqual({"RUNTIME", "BASE"}, {item["kind"] for item in entries})

    def test_stages_exact_bundle_and_activates_digest_runtime_without_idea_or_children(
        self,
    ) -> None:
        observations = io.StringIO()
        with contextlib.redirect_stderr(observations):
            admitted = self.admitted()
        self.assertIn(
            {"stage": "STAGING", "outcome": "COMPLETED"},
            [json.loads(line) for line in observations.getvalue().splitlines()],
        )
        with (
            patch.object(install, "run_child", side_effect=AssertionError("no process")),
            patch.object(install, "select_idea", side_effect=AssertionError("no IDEA")),
        ):
            result = activation.activate(self.root, self.home, admitted)
        self.assertIsInstance(result, activation.ProductInstalled, result)
        assert isinstance(result, activation.ProductInstalled)
        expected = self.home / "kast-workspaces/runtimes" / self.record.targets[1].runtime.sha256
        self.assertEqual(str(expected), result.product.runtimeRoot)
        self.assertEqual(0o700, expected.stat().st_mode & 0o777)
        self.assertEqual(0o555, (expected / "host").stat().st_mode & 0o777)
        self.assertEqual(0o444, (expected / "manifest.json").stat().st_mode & 0o777)
        self.assertEqual(
            self.raw, (self.root / "installation/share/kast/build-record.json").read_bytes()
        )
        receipt = json.loads((expected / "runtime.json").read_bytes())
        self.assertEqual(
            {
                "schemaVersion": 1,
                "archiveSha256": self.record.targets[1].runtime.sha256,
                "manifestSha256": hashlib.sha256(
                    (expected / "manifest.json").read_bytes()
                ).hexdigest(),
            },
            receipt,
        )
        self.assertEqual("1.2.3", result.product.publicVersion)
        self.assertEqual("0.0.123", result.product.version)
        self.assertEqual("NOT_STARTED", result.hostState)
        manifest = json.loads((self.root / "installation/installation.json").read_bytes())
        self.assertEqual(str(self.home / ".codex"), manifest.get("codexHome"))
        active = self.root / "installation"
        self.assertEqual(
            b"# Kast runtime configuration. Values are literal; shell syntax is not evaluated.\n",
            (active / "config/environment").read_bytes(),
        )
        self.assertEqual(0o600, stat.S_IMODE((active / "config/environment").stat().st_mode))
        inventory = {item["path"] for item in manifest["payloadFiles"]}
        self.assertNotIn("config/environment", inventory)
        for executable in ("kast", "kast-query", "kast-mcp", "kast-tool-rpc", "kast-codex"):
            relative = f"bin/{executable}-complete"
            self.assertIn(relative, inventory)
            self.assertTrue(os.access(active / relative, os.X_OK))
        # Saved installation values are mutable owner input, not Control payload.
        configuration = active / "config/environment"
        saved = b"KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n"
        _ = configuration.write_bytes(saved)
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted, runner=self.retire),
            activation.ProductInstalled,
        )
        self.assertEqual(saved, configuration.read_bytes())
        management = json.loads((self.root / "management.json").read_bytes())
        self.assertEqual([], management["registrations"])
        self.assertEqual([], management["guidance"])
        self.assertEqual(str(self.root), management["installationRoot"])
        self.assertFalse((self.root / "recovery/replacement").exists())
        self.assertTrue(
            all(url.startswith(f"{release.RELEASE_URL}/v1.2.3/") for url in self.source.calls)
        )

    def test_corrupt_qualified_archive_rejects_before_installation_effects(self) -> None:
        name = self.record.targets[1].runtime.name
        self.source.values[f"{release.RELEASE_URL}/v1.2.3/{name}"] = b"wrong bytes"
        directory = self.home / "reject"
        directory.mkdir()
        observations = io.StringIO()
        with contextlib.redirect_stderr(observations):
            result = release.download_composition(self.selected, "262", directory, self.source)
        self.assertIsInstance(result, release.Rejected)
        self.assertFalse(self.root.exists())
        self.assertEqual(
            {"stage": "DOWNLOAD", "outcome": "REJECTED"},
            json.loads(observations.getvalue().splitlines()[-1]),
        )

    def removal_journal(
        self, installed: activation.ProductInstalled | None = None
    ) -> tuple[Path, RemovalJournalFixture]:
        if installed is None:
            result = activation.activate(self.root, self.home, self.admitted())
            assert isinstance(result, activation.ProductInstalled), result
            installed = result
        assert isinstance(installed, activation.ProductInstalled), installed
        self.removal_support()
        receipt = self.root / "management.json"
        management = activation.read_management(
            receipt.read_bytes(), self.root, Path(installed.executable), self.root / "installation"
        )
        record = RemovalJournalFixture(
            str(self.root),
            management.executable,
            management.executableSha256,
            "STABLE",
            "REMOVAL_REQUESTED",
            journal.Identity.observe(self.root),
            journal.Identity.observe(self.root / "installation"),
            AbsenceFixture("ABSENT_RECOVERY"),
            AbsenceFixture("ABSENT_CONFIGURATION"),
            management.product,
        )
        path = self.root.parent / (
            ".kast-uninstall-" + hashlib.sha256(str(self.root).encode()).hexdigest() + ".json"
        )
        _ = path.write_bytes(encoded_fixture(record))
        path.chmod(0o600)
        return path, record

    def removal_support(self) -> None:
        payload = self.root / "installation"
        prior = install.LIFECYCLE.Installation.admit(str(payload)).manifest
        support = payload / "share/kast"
        for name in (*generate_bootstrap.SOURCES, "install-candidate.sh"):
            _ = (support / name).write_bytes((Path(__file__).parent / name).read_bytes())
        interpreter = support / "private-python/bin/python3.12"
        interpreter.parent.mkdir(parents=True)
        # Process-adapter fixture, not a claim about native/private CPython qualification.
        _ = interpreter.write_text("#!/bin/bash\nexec " + shlex.quote(sys.executable) + ' "$@"\n')
        interpreter.chmod(0o755)
        self.fixture_inventory(prior)

    def fixture_inventory(self, prior: dict[str, object]) -> None:
        payload = self.root / "installation"
        files = tuple(
            activation.PayloadFile(
                path.relative_to(payload).as_posix(),
                "sha256:" + release.file_digest(path),
                stat.S_IMODE(path.stat().st_mode),
            )
            for path in sorted(payload.rglob("*"))
            if path.is_file() and path.relative_to(payload).parts[0] in ("bin", "lib", "share")
        )
        manifest = activation.InstallationManifest(
            str(payload),
            str(payload / "state"),
            str(payload / "config/environment"),
            str(payload / "config/workspaces.json"),
            str(self.home / ".codex"),
            files,
            str(prior["controlSha256"]),
            str(prior["payloadIdentity"]),
            str(prior["semanticVersion"]),
        )
        _ = (payload / "installation.json").write_bytes(encoded_fixture(manifest))

    def assert_retired_journal(self, path: Path, expected: object) -> None:
        actual = json.loads(path.read_bytes())
        proof = actual.pop("productRemoval")
        self.assertEqual(expected, actual)
        self.assertEqual(1, proof["schemaVersion"])
        self.assertEqual("PREPARED", proof["phase"])
        self.assertEqual(
            asdict(journal.Identity.observe(self.root / "recovery/installation/product-removal")),
            proof["executionIdentity"],
        )

    def remove_product(self, path: Path) -> int:
        with patch.object(
            sys,
            "argv",
            [
                "bootstrap_entry.py",
                "--remove-product",
                "--install-root",
                str(self.root),
                "--uninstall-journal",
                str(path),
            ],
        ):
            return bootstrap_entry.main()

    def test_product_removal_preserves_exact_journal_product_and_external_owners(self) -> None:
        path, record = self.removal_journal()
        protected = {
            self.root / "management.json": (self.root / "management.json").read_bytes(),
            Path(record.executable): Path(record.executable).read_bytes(),
        }
        _ = (self.root / "foreign").write_bytes(b"not installation payload")
        with patch.object(install, "run_child", side_effect=AssertionError("outer retires")):
            self.assertEqual(0, self.remove_product(path))
            self.assertEqual(0, self.remove_product(path))
        self.assertFalse((self.root / "installation").exists())
        self.assert_retired_journal(path, asdict(replace(record, type="CONTROL_RETIRED")))
        self.assertFalse(Path(record.product.runtimeRoot).exists())
        self.assertEqual(b"not installation payload", (self.root / "foreign").read_bytes())
        for file, raw in protected.items():
            self.assertEqual(raw, file.read_bytes())

    def test_product_removal_partial_retry_rejects_foreign_payload(self) -> None:
        path, record = self.removal_journal()
        original = install.LIFECYCLE.delete_tree

        def interrupted(directory: Path) -> None:
            original(directory)
            raise OSError("interrupted after one owned payload subtree")

        with patch.object(install.LIFECYCLE, "delete_tree", side_effect=interrupted):
            self.assertEqual(1, self.remove_product(path))
        self.assertFalse((self.root / "installation/bin").exists())
        self.assert_retired_journal(path, asdict(replace(record, type="CONTROL_RETIRED")))
        foreign = self.root / "installation/share/foreign"
        _ = foreign.write_bytes(b"preserve")
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(b"preserve", foreign.read_bytes())
        foreign.unlink()
        self.assertEqual(0, self.remove_product(path))

    def test_immutable_removal_requires_original_provenance_not_a_digest_name(self) -> None:
        path, record = self.removal_journal()
        runtime = Path(record.product.runtimeRoot)
        self.assertEqual(self.raw, (runtime / "provenance/build-record.json").read_bytes())
        raw_journal = path.read_bytes()
        foreign = runtime.parent / ("0" * 64)
        foreign.mkdir()
        _ = (foreign / "manifest.json").write_bytes(b"not ownership")
        rejected = removal.remove(self.root, path)
        self.assertEqual(
            removal.Rejected(removal.Failure.ADMISSION, removal.Stage.RETAIN_EXECUTION), rejected
        )
        self.assertEqual(raw_journal, path.read_bytes())
        self.assertTrue((runtime / "manifest.json").exists())
        self.assertEqual(b"not ownership", (foreign / "manifest.json").read_bytes())
        (foreign / "manifest.json").unlink()
        foreign.rmdir()
        original = runtime / "provenance/archive.zip"
        raw = original.read_bytes()
        original.chmod(0o600)
        _ = original.write_bytes(b"changed retained bytes")
        original.chmod(0o444)
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(raw_journal, path.read_bytes())
        original.chmod(0o600)
        _ = original.write_bytes(raw)
        original.chmod(0o444)
        self.assertEqual(0, self.remove_product(path))

    def test_immutable_partial_removal_keeps_manifest_and_rejects_new_survivors(self) -> None:
        path, record = self.removal_journal()
        runtime = Path(record.product.runtimeRoot)
        original = Path.unlink

        def interrupted(file: Path, missing_ok: bool = False) -> None:
            original(file, missing_ok=missing_ok)
            if file == runtime / "provenance/archive.zip":
                raise OSError("interrupted immutable removal")

        with patch.object(Path, "unlink", interrupted):
            self.assertEqual(1, self.remove_product(path))
        self.assertTrue((runtime / "manifest.json").exists())
        self.assertFalse((runtime / "provenance/archive.zip").exists())
        self.assert_retired_journal(path, asdict(replace(record, type="CONTROL_RETIRED")))
        foreign = runtime / "foreign"
        _ = foreign.write_bytes(b"preserve")
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(b"preserve", foreign.read_bytes())
        foreign.unlink()
        self.assertEqual(0, self.remove_product(path))
        self.assertFalse(runtime.exists())

    def test_retained_execution_resumes_offline_after_original_interpreter_is_erased(self) -> None:
        path, record = self.removal_journal()
        original = install.LIFECYCLE.delete_tree

        def interrupted(directory: Path) -> None:
            original(directory)
            if directory == self.root / "installation/share":
                raise OSError("interrupted after interpreter removal")

        with patch.object(install.LIFECYCLE, "delete_tree", interrupted):
            self.assertEqual(1, self.remove_product(path))
        self.assertFalse((self.root / "installation/share").exists())
        self.assert_retired_journal(path, asdict(replace(record, type="CONTROL_RETIRED")))
        helper = (
            self.root
            / "recovery/installation/product-removal/control/share/kast/install-candidate.sh"
        )
        completed = subprocess.run(
            [
                "/bin/bash",
                str(helper),
                "--remove-product",
                "--install-root",
                str(self.root),
                "--uninstall-journal",
                str(path),
            ],
            env={"PATH": "", "HOME": str(self.home)},
            capture_output=True,
            check=False,
        )
        self.assertEqual(
            0, completed.returncode, completed.stderr.decode() + completed.stdout.decode()
        )
        self.assertEqual(
            {"installationRoot": str(self.root), "status": "CONTROL_REMOVED"},
            json.loads(completed.stdout),
        )
        self.assertFalse((self.root / "installation").exists())

    def test_retained_execution_preparation_is_resumable_and_changed_copy_rejects(self) -> None:
        path, record = self.removal_journal()
        original = product_removal_recovery.copy_bytes

        def interrupted(destination: Path, raw: bytes, mode: int) -> None:
            if destination.name == "bootstrap_entry.py":
                original(destination, raw[:17], mode)
                raise OSError("interrupted copy")
            original(destination, raw, mode)

        with patch.object(product_removal_recovery, "copy_bytes", interrupted):
            self.assertEqual(1, self.remove_product(path))
        self.assertEqual("PREPARING", json.loads(path.read_bytes())["productRemoval"]["phase"])
        self.assertTrue(Path(record.product.runtimeRoot).exists())
        previous_mask = os.umask(0o077)
        try:
            proof = product_removal_recovery.prepare(self.root, path)
        finally:
            _ = os.umask(previous_mask)
        helper = (
            product_removal_recovery.location(self.root) / "control/share/kast/bootstrap_entry.py"
        )
        raw = helper.read_bytes()
        _ = helper.write_bytes(b"foreign")
        self.assertEqual(1, self.remove_product(path))
        self.assertTrue(Path(record.product.runtimeRoot).exists())
        _ = helper.write_bytes(raw)
        self.assertEqual("PREPARED", proof.phase)
        self.assertEqual(0, self.remove_product(path))

    def mcp_fixture(self) -> Path:
        payload = self.root / "installation"
        prior = install.LIFECYCLE.Installation.admit(str(payload)).manifest
        server = payload / mcp.SERVER
        server.parent.mkdir(parents=True)
        _ = server.write_bytes(b"pass\n")
        _ = (payload / mcp.SERVER_LOCK).write_bytes(b"locked fixture\n")
        _ = (payload / mcp.REQUIREMENTS).write_bytes(b"fixture-package==1\n")
        export = mcp.RequirementsLock(
            mcp.digest(server),
            mcp.digest(payload / mcp.SERVER_LOCK),
            mcp.digest(payload / mcp.REQUIREMENTS),
        )
        _ = (payload / mcp.EXPORT_LOCK).write_bytes(mcp.encoded(export))
        self.fixture_inventory(prior)
        calls: list[mcp.Command] = []

        def runner(command: mcp.Command) -> mcp.Exited:
            calls.append(command)
            if len(calls) == 1:
                site = payload / "state/.mcp-preparing/site-packages/example"
                site.mkdir(parents=True)
                _ = (site / "__init__.py").write_bytes(b"pass\n")
            self.assertLessEqual(len(calls), 2)
            return mcp.Exited(0)

        self.assertIsInstance(mcp.prepare(payload, runner), mcp.Prepared)
        self.assertEqual(2, len(calls))
        return payload / "state/mcp"

    def test_optional_mcp_state_is_admitted_and_partial_removal_resumes(self) -> None:
        path, record = self.removal_journal()
        environment = self.mcp_fixture()
        for name in ("one-shot", "tool-sessions"):
            (self.root / "installation/state/run" / name).mkdir(parents=True, mode=0o700)
        raw = (environment / "receipt.json").read_bytes()
        original = Path.unlink

        def interrupted(file: Path, missing_ok: bool = False) -> None:
            original(file, missing_ok=missing_ok)
            if file == environment / "site-packages/example/__init__.py":
                raise OSError("interrupted MCP removal")

        with patch.object(Path, "unlink", interrupted):
            self.assertEqual(1, self.remove_product(path))
        self.assertEqual(raw, (environment / "receipt.json").read_bytes())
        self.assertEqual(
            hashlib.sha256(raw).hexdigest(),
            json.loads(path.read_bytes())["productRemoval"]["mcp"]["receiptSha256"],
        )
        self.assert_retired_journal(path, asdict(replace(record, type="CONTROL_RETIRED")))
        self.assertEqual(0, self.remove_product(path))
        self.assertFalse(environment.exists())

    def test_completed_native_invocations_allow_repair_and_removal_but_survivors_do_not(
        self,
    ) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        records = self.root / "installation/state/run/one-shot"
        records.mkdir(parents=True, mode=0o700)
        survivor = records / "unsettled.json"
        _ = survivor.write_bytes(b"unsettled invocation")
        observations = io.StringIO()
        with contextlib.redirect_stderr(observations):
            self.assertIsInstance(
                activation.activate(self.root, self.home, admitted, runner=self.retire),
                contract.Rejected,
            )
        self.assertIn(
            {
                "outcome": "REJECTED",
                "reason": "UNSETTLED_INVOCATIONS",
                "component": "kast-invocation-state",
            },
            [json.loads(line) for line in observations.getvalue().splitlines()],
        )
        self.assertEqual(b"unsettled invocation", survivor.read_bytes())
        survivor.unlink()
        observations = io.StringIO()
        with contextlib.redirect_stderr(observations):
            installed = activation.activate(self.root, self.home, admitted, runner=self.retire)
        self.assertIsInstance(installed, activation.ProductInstalled)
        assert isinstance(installed, activation.ProductInstalled)
        self.assertIn(
            {"outcome": "ADMITTED", "component": "kast-invocation-state"},
            [json.loads(line) for line in observations.getvalue().splitlines()],
        )
        records.mkdir(parents=True, mode=0o700)
        path, record = self.removal_journal(installed)
        _ = survivor.write_bytes(b"unsettled invocation")
        self.assertEqual(1, self.remove_product(path))
        self.assertTrue(Path(record.product.runtimeRoot).exists())
        self.assertEqual(b"unsettled invocation", survivor.read_bytes())
        survivor.unlink()
        self.assertEqual(0, self.remove_product(path))
        self.assertFalse((self.root / "installation").exists())

    def test_unknown_or_altered_optional_state_is_preserved_before_any_removal(self) -> None:
        path, record = self.removal_journal()
        environment = self.mcp_fixture()
        raw_journal = path.read_bytes()
        configuration = self.root / "installation/config"
        foreign_configuration = configuration / "foreign"
        _ = foreign_configuration.write_bytes(b"preserve configuration neighbor")
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(raw_journal, path.read_bytes())
        self.assertEqual(b"preserve configuration neighbor", foreign_configuration.read_bytes())
        self.assertTrue(Path(record.product.runtimeRoot).exists())
        foreign_configuration.unlink()
        saved = configuration / "environment"
        content = saved.read_bytes()
        saved.unlink()
        saved.symlink_to(self.home / "foreign-environment")
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(raw_journal, path.read_bytes())
        self.assertTrue(saved.is_symlink())
        saved.unlink()
        _ = saved.write_bytes(content)
        extra = environment / "site-packages/foreign"
        _ = extra.write_bytes(b"preserve")
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(raw_journal, path.read_bytes())
        self.assertTrue(Path(record.product.runtimeRoot).exists())
        self.assertEqual(b"preserve", extra.read_bytes())
        extra.unlink()
        unknown = self.root / "installation/state/foreign"
        _ = unknown.write_bytes(b"preserve unknown state")
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(raw_journal, path.read_bytes())
        self.assertEqual(b"preserve unknown state", unknown.read_bytes())
        unknown.unlink()
        partial = self.root / "installation/state/.mcp-preparing"
        partial.mkdir()
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(raw_journal, path.read_bytes())
        self.assertTrue(partial.is_dir())
        self.assertTrue(Path(record.product.runtimeRoot).exists())
        partial.rmdir()
        self.assertEqual(0, self.remove_product(path))

    def test_product_removal_rejects_swapped_identity_product_and_recovery(self) -> None:
        path, record = self.removal_journal()
        raw = path.read_bytes()
        payload = self.root / "installation"
        manifest = (payload / "installation.json").read_bytes()
        for changed in (
            replace(record, product=replace(record.product, buildRecordSha256="0" * 64)),
            replace(record, controlIdentity=replace(record.controlIdentity, inode=0)),
            replace(record, managedRootIdentity=replace(record.managedRootIdentity, inode=0)),
        ):
            with self.subTest(changed=changed):
                _ = path.write_bytes(encoded_fixture(changed))
                self.assertEqual(1, self.remove_product(path))
                self.assertEqual(encoded_fixture(changed), path.read_bytes())
                self.assertEqual(manifest, (payload / "installation.json").read_bytes())
        _ = path.write_bytes(raw)
        pending = self.root / "recovery/replacement"
        pending.mkdir(parents=True)
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(raw, path.read_bytes())
        pending.rmdir()
        self.assertEqual(0, self.remove_product(path))

    def test_product_removal_final_directory_interruption_is_retryable(self) -> None:
        path, record = self.removal_journal()
        payload = self.root / "installation"
        original = Path.rmdir

        def interrupted(directory: Path) -> None:
            if directory == payload:
                raise OSError("directory interruption")
            original(directory)

        with patch.object(Path, "rmdir", interrupted):
            self.assertEqual(1, self.remove_product(path))
        self.assert_retired_journal(path, asdict(replace(record, type="CONTROL_RETIRED")))
        self.assertEqual([], list(payload.iterdir()))
        self.assertEqual(0, self.remove_product(path))
        self.assertFalse(payload.exists())

    def test_product_removal_preserves_bounded_workspace_proofs_without_deleting_profiles(
        self,
    ) -> None:
        path, record = self.removal_journal()
        profile = self.home / "kast-workspaces/containers/foreign"
        profile.mkdir(parents=True)
        marker = profile / "container.json"
        _ = marker.write_bytes(b"not child authority")
        lock = self.home / ".kast-workspaces.lifetime.lock"
        _ = lock.write_bytes(b"held by outer owner")
        entries = tuple(
            WorkspaceRemovalFixture(f"{index:064x}", journal.Identity.observe(profile), "c" * 64)
            for index in range(1025)
        )
        overflow = WorkspaceRemovalJournalFixture(**vars(record), workspaceRemovals=entries)
        _ = path.write_bytes(encoded_fixture(overflow))
        self.assertEqual(1, self.remove_product(path))
        self.assertEqual(encoded_fixture(overflow), path.read_bytes())
        admitted = replace(overflow, workspaceRemovals=entries[:1024])
        _ = path.write_bytes(encoded_fixture(admitted))
        self.assertGreater(path.stat().st_size, 65536)
        self.assertEqual(0, self.remove_product(path))
        self.assertEqual(
            asdict(replace(admitted, type="CONTROL_RETIRED"))["product"],
            json.loads(path.read_bytes())["product"],
        )
        self.assert_retired_journal(
            path, json.loads(encoded_fixture(replace(admitted, type="CONTROL_RETIRED")))
        )
        self.assertEqual(b"not child authority", marker.read_bytes())
        self.assertEqual(b"held by outer owner", lock.read_bytes())

    def retire(
        self, arguments: list[str], environment: dict[str, str], timeout: float
    ) -> subprocess.CompletedProcess[bytes]:
        self.assertEqual(
            [
                str(self.root / "installation/share/kast/libexec/kast-management"),
                "--internal-install",
                "managed-retire",
            ],
            arguments,
        )
        self.assertEqual(str(self.root), environment["KAST_INSTALL_ROOT"])
        self.assertEqual(str(self.home), environment["HOME"])
        self.assertGreater(timeout, 0)
        fence = self.root / "shutdown.json"
        if not fence.exists():
            self.assertEqual(
                "PREPARED",
                json.loads((self.root / "recovery/replacement/receipt.json").read_bytes())[
                    "product"
                ]["phase"],
            )
            _ = fence.write_bytes(
                install.encoded(install.ShutdownRequest(str(self.root / "installation")))
            )
        self.assertEqual(
            {"installationRoot": str(self.root / "installation"), "schemaVersion": 1},
            json.loads(fence.read_bytes()),
        )
        self.assertFalse((self.root / "recovery/replacement/payload").exists())
        return subprocess.CompletedProcess(arguments, 0, b"", b"")

    def test_lease_rejection_creates_no_fence_and_retry_cannot_skip_retirement(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        prior = journal.Identity.observe(self.root / "installation")

        def busy(
            arguments: list[str], environment: dict[str, str], timeout: float
        ) -> subprocess.CompletedProcess[bytes]:
            self.assertFalse((self.root / "shutdown.json").exists())
            self.assertFalse((self.root / "recovery/replacement/payload").exists())
            return subprocess.CompletedProcess(arguments, 1, b"", b"workspace busy")

        with patch.object(install, "run_child", side_effect=busy) as callback:
            self.assertIsInstance(
                activation.activate(self.root, self.home, admitted), contract.RecoveryRequired
            )
            self.assertIsInstance(
                activation.resume(self.root, self.home), contract.RecoveryRequired
            )
            self.assertEqual(2, callback.call_count)
        self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
        self.assertFalse((self.root / "shutdown.json").exists())
        with patch.object(install, "run_child", side_effect=self.retire) as callback:
            self.assertIsInstance(
                activation.resume(self.root, self.home), activation.ProductInstalled
            )
            self.assertEqual(1, callback.call_count)

    def test_retirement_rejection_keeps_old_payload_and_retry_requires_the_callback(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        prior = journal.Identity.observe(self.root / "installation")
        old = (self.root / "management.json").read_bytes()
        calls: list[list[str]] = []

        def rejected(
            arguments: list[str], environment: dict[str, str], timeout: float
        ) -> subprocess.CompletedProcess[bytes]:
            _ = self.retire(arguments, environment, timeout)
            activation.read_runtime(admitted.reference(self.root))
            calls.append(arguments)
            return subprocess.CompletedProcess(arguments, 1, b"", b"retirement unproven")

        with patch.object(install, "run_child", side_effect=rejected):
            result = activation.activate(self.root, self.home, admitted)
            self.assertIsInstance(result, contract.RecoveryRequired)
            self.assertIsInstance(
                activation.resume(self.root, self.home), contract.RecoveryRequired
            )
        self.assertEqual(2, len(calls))
        self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
        self.assertEqual(old, (self.root / "management.json").read_bytes())
        self.assertTrue((self.root / "shutdown.json").is_file())
        with patch.object(install, "run_child", side_effect=self.retire) as callback:
            self.assertIsInstance(
                activation.resume(self.root, self.home), activation.ProductInstalled
            )
            self.assertEqual(1, callback.call_count)
        self.assertFalse((self.root / "shutdown.json").exists())
        self.assertFalse((self.root / "recovery/replacement").exists())

    def test_schema_two_guidance_and_registrations_survive_interrupted_product_activation(
        self,
    ) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        command = self.home / ".local/bin/kast"
        guidance_root = self.home / "guidance"
        guidance_root.mkdir()
        guide = guidance_root / "SKILL.md"
        _ = guide.write_bytes(b"keep authored installed guidance")
        registrations = (
            install.Registration(
                "PI",
                str(self.home / "pi/extensions/kast.ts"),
                "9" * 64,
                str(self.home / "pi"),
                "CliOnly",
            ),
        )
        guidance = (
            install.Guidance(
                "PI",
                str(guidance_root),
                (install.GuidanceFile("SKILL.md", release.file_digest(guide)),),
            ),
        )
        old = install.ManagementReceipt(
            2,
            str(self.root),
            str(command),
            release.file_digest(command),
            "STABLE",
            registrations,
            release.TrackStable(),
            None,
            guidance,
            (install.ProviderRegistration("CODEX", str(self.home / "custom-codex")),),
        )
        original = install.encoded(old)
        _ = (self.root / "management.json").write_bytes(original)
        with patch.object(install, "replace_file", side_effect=KeyboardInterrupt):
            result = activation.activate(self.root, self.home, admitted, runner=self.retire)
        self.assertIsInstance(result, contract.RecoveryRequired)
        self.assertEqual(
            original, (self.root / "recovery/replacement/management-preimage").read_bytes()
        )
        self.assertIsInstance(activation.resume(self.root, self.home), activation.ProductInstalled)
        saved = json.loads((self.root / "management.json").read_bytes())
        self.assertEqual(
            {
                "schemaVersion",
                "installationRoot",
                "executable",
                "executableSha256",
                "product",
                "registrations",
                "guidance",
                "providers",
            },
            set(saved),
        )
        self.assertEqual(3, saved["schemaVersion"])
        self.assertEqual(str(self.root), saved["installationRoot"])
        self.assertEqual(
            [
                {
                    "connection": "PI",
                    "destination": str(self.home / "pi/extensions/kast.ts"),
                    "payloadSha256": "9" * 64,
                    "directory": str(self.home / "pi"),
                    "composition": "CliOnly",
                }
            ],
            saved["registrations"],
        )
        self.assertEqual(json.loads(original)["guidance"], saved["guidance"])
        self.assertEqual(
            [{"harness": "CODEX", "directory": str(self.home / "custom-codex")}],
            saved["providers"],
        )
        self.assertEqual(b"keep authored installed guidance", guide.read_bytes())
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted, runner=self.retire),
            activation.ProductInstalled,
        )
        self.assertEqual(saved, json.loads((self.root / "management.json").read_bytes()))

    def test_invalid_provisioning_rejects_before_retirement_or_journal_publication(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        command = self.home / ".local/bin/kast"
        original = install.encoded(
            activation.ProductManagement(
                str(self.root),
                str(command),
                release.file_digest(command),
                admitted.reference(self.root),
            )
        )
        invalid: tuple[tuple[str, object], ...] = (
            ("providers", [{"harness": "PI", "directory": "/pi"}]),
            ("providers", [{"harness": "CODEX", "directory": "relative"}]),
            ("providers", [{"harness": "CODEX", "directory": "/codex"}] * 2),
            (
                "providers",
                [{"harness": "CODEX", "directory": f"/codex-{index}"} for index in range(33)],
            ),
            (
                "registrations",
                [{"connection": "UNKNOWN", "destination": "/keep", "payloadSha256": "9" * 64}],
            ),
            (
                "registrations",
                [{"connection": "PI", "destination": "/keep", "payloadSha256": "bad"}],
            ),
            (
                "registrations",
                [
                    {
                        "connection": "PI",
                        "destination": "/wrong",
                        "directory": "/pi",
                        "payloadSha256": "9" * 64,
                    }
                ],
            ),
            (
                "registrations",
                [{"connection": "PI", "destination": "/keep", "payloadSha256": "9" * 64}] * 2,
            ),
            ("guidance", [{"harness": "PI", "destination": "/guidance", "files": list[object]()}]),
            (
                "guidance",
                [
                    {
                        "harness": "PI",
                        "destination": "/guidance",
                        "files": [{"path": "../escape", "sha256": "8" * 64}],
                    }
                ],
            ),
            (
                "guidance",
                [
                    {
                        "harness": "PI",
                        "destination": "/guidance",
                        "files": [{"path": "SKILL.md", "sha256": "bad"}],
                    }
                ],
            ),
            ("installationRoot", str(self.root / "installation")),
        )
        prior = journal.Identity.observe(self.root / "installation")
        for key, value in invalid:
            with self.subTest(key=key, value=value):
                malformed = json.loads(original)
                malformed[key] = value
                raw = json.dumps(malformed).encode()
                _ = (self.root / "management.json").write_bytes(raw)
                with patch.object(
                    install, "run_child", side_effect=AssertionError("no retirement")
                ):
                    self.assertIsInstance(
                        activation.activate(self.root, self.home, admitted), contract.Rejected
                    )
                self.assertFalse((self.root / "recovery/replacement").exists())
                self.assertFalse((self.root / "shutdown.json").exists())
                self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
                self.assertEqual(raw, (self.root / "management.json").read_bytes())

    def test_interrupted_retirement_is_repeated_before_any_payload_move(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        prior = journal.Identity.observe(self.root / "installation")

        def interrupted(
            arguments: list[str], environment: dict[str, str], timeout: float
        ) -> subprocess.CompletedProcess[bytes]:
            _ = self.retire(arguments, environment, timeout)
            raise KeyboardInterrupt()

        result = activation.activate(self.root, self.home, admitted, runner=interrupted)
        self.assertIsInstance(result, contract.RecoveryRequired)
        self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
        with patch.object(install, "run_child", side_effect=self.retire) as callback:
            self.assertIsInstance(
                activation.resume(self.root, self.home), activation.ProductInstalled
            )
            self.assertEqual(1, callback.call_count)

    def test_retirement_timeout_preserves_its_typed_failure_and_active_bytes(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        prior = journal.Identity.observe(self.root / "installation")

        def timed_out(
            arguments: list[str], environment: dict[str, str], timeout: float
        ) -> subprocess.CompletedProcess[bytes]:
            _ = self.retire(arguments, environment, timeout)
            raise subprocess.TimeoutExpired(arguments, timeout)

        result = activation.activate(self.root, self.home, admitted, runner=timed_out)
        assert isinstance(result, contract.RecoveryRequired)
        self.assertEqual(contract.Stage.REQUEST_RETIREMENT, result.failure.stage)
        self.assertEqual(contract.Failure.CHILD_TIMEOUT, result.failure.reason)
        self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
        self.assertTrue((self.root / "shutdown.json").is_file())

    def test_missing_runtime_is_republished_before_prior_control_retirement(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        runtime = Path(admitted.reference(self.root).runtimeRoot)
        _ = runtime.rename(self.home / "removed-runtime")
        with patch.object(install, "run_child", side_effect=self.retire) as callback:
            self.assertIsInstance(
                activation.activate(self.root, self.home, admitted), activation.ProductInstalled
            )
            self.assertEqual(1, callback.call_count)
        activation.read_runtime(admitted.reference(self.root))

    def test_first_install_rejects_nonempty_workspace_store_without_prior_control(self) -> None:
        admitted = self.admitted()
        workspace = self.root.parent / "kast-workspaces/containers/existing"
        workspace.mkdir(parents=True)
        _ = (workspace / "keep").write_bytes(b"unretired workspace")
        with patch.object(install, "run_child", side_effect=AssertionError("no prior Control")):
            self.assertIsInstance(
                activation.activate(self.root, self.home, admitted), contract.Rejected
            )
        self.assertEqual(b"unretired workspace", (workspace / "keep").read_bytes())
        self.assertFalse((self.root / "installation").exists())
        self.assertFalse((self.root / "recovery/replacement").exists())

    def test_recovery_never_removes_a_replaced_shutdown_fence(self) -> None:
        admitted = self.admitted()
        with patch.object(install, "replace_file", side_effect=KeyboardInterrupt):
            self.assertIsInstance(
                activation.activate(self.root, self.home, admitted), contract.RecoveryRequired
            )
        fence = self.root / "shutdown.json"
        self.assertTrue(fence.is_file())
        _ = fence.rename(self.root / "original-shutdown.json")
        _ = fence.write_bytes(
            install.encoded(install.ShutdownRequest(str(self.root / "installation")))
        )
        self.assertIsInstance(activation.resume(self.root, self.home), contract.RecoveryRequired)
        self.assertTrue(fence.exists())

    def test_incomplete_native_receipt_rejects_even_with_matching_asset_digest(self) -> None:
        cases = (
            replace(self.qualification, qualification="NOT_RUN"),
            replace(self.qualification, qualificationStage="INSTALLED_PRODUCT_NATIVE"),
            replace(self.qualification, qualificationStage=""),
            replace(self.qualification, harnessSha256="unbound"),
        )
        for index, invalid in enumerate(cases):
            with self.subTest(case=index):
                raw = encoded_fixture(invalid)
                target = self.record.targets[1]
                receipt = replace(
                    target.qualification.receipt,
                    bytes=len(raw),
                    sha256=hashlib.sha256(raw).hexdigest(),
                )
                target = replace(
                    target, qualification=replace(target.qualification, receipt=receipt)
                )
                record = replace(self.record, targets=(self.record.targets[0], target))
                record_raw = encoded_fixture(record)
                selection = replace(
                    self.selected,
                    record=record,
                    recordSha256=hashlib.sha256(record_raw).hexdigest(),
                )
                self.source.values[f"{release.RELEASE_URL}/v1.2.3/{receipt.name}"] = raw
                self.source.values[f"{release.RELEASE_URL}/v1.2.3/build-record.json"] = record_raw
                directory = self.home / f"incomplete-{index}"
                directory.mkdir()
                result = release.download_composition(selection, "262", directory, self.source)
                self.assertIsInstance(result, release.Rejected)
                assert isinstance(result, release.Rejected)
                self.assertEqual(release.Stage.RECORDS, result.stage)
                self.assertFalse(self.root.exists())

    def test_uncertain_prior_rename_resumes_without_losing_prior_bytes(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        move = install.RECOVERY.managed_move
        calls: list[Path] = []

        def interrupted(source: Path, destination: Path, witness: object) -> None:
            move(source, destination, witness)
            calls.append(source)
            if source == self.root / "installation":
                raise KeyboardInterrupt()

        with patch.object(install.RECOVERY, "managed_move", side_effect=interrupted):
            result = activation.activate(self.root, self.home, admitted, runner=self.retire)
        self.assertIsInstance(result, contract.RecoveryRequired)
        self.assertEqual([self.root / "installation"], calls)
        self.assertEqual(
            self.raw,
            (self.root / "recovery/replacement/payload/share/kast/build-record.json").read_bytes(),
        )
        self.assertIsInstance(activation.resume(self.root, self.home), activation.ProductInstalled)

    def test_foreign_launcher_and_mutable_legacy_payload_reject_without_replacement(self) -> None:
        admitted = self.admitted()
        command = self.home / ".local/bin/kast"
        command.parent.mkdir(parents=True)
        _ = command.write_bytes(b"foreign executable")
        result = activation.activate(self.root, self.home, admitted)
        self.assertIsInstance(result, contract.Rejected)
        self.assertEqual(b"foreign executable", command.read_bytes())
        self.assertFalse((self.root / "recovery/replacement").exists())
        command.unlink()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        state = self.root / "installation/state"
        state.mkdir()
        _ = (state / "keep").write_bytes(b"unresolved state")
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), contract.Rejected
        )
        self.assertEqual(b"unresolved state", (state / "keep").read_bytes())
        self.assertFalse((self.root / "recovery/replacement").exists())

    def test_interrupted_commit_resumes_exact_journal_without_download(self) -> None:
        admitted = self.admitted()
        with patch.object(install, "replace_file", side_effect=KeyboardInterrupt):
            result = activation.activate(self.root, self.home, admitted)
        self.assertIsInstance(result, contract.RecoveryRequired)
        pending = self.root / "recovery/replacement/receipt.json"
        self.assertTrue(pending.is_file())
        calls = tuple(self.source.calls)
        result = activation.resume(self.root, self.home)
        self.assertIsInstance(result, activation.ProductInstalled, result)
        self.assertEqual(calls, tuple(self.source.calls))
        self.assertFalse(pending.exists())

    def test_preparation_failure_never_publishes_an_incomplete_journal(self) -> None:
        admitted = self.admitted()
        with patch.object(install, "saved_file", side_effect=OSError("preimage unavailable")):
            result = activation.activate(self.root, self.home, admitted)
        self.assertIsInstance(result, contract.Rejected)
        self.assertFalse((self.root / "recovery/replacement").exists())
        self.assertFalse((self.root / "installation").exists())
        self.assertEqual([], list(self.root.parent.glob(".kast-install-*")))
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )

    def test_failed_update_preparation_does_not_prevent_product_removal(self) -> None:
        admitted = self.admitted()
        installed = activation.activate(self.root, self.home, admitted)
        assert isinstance(installed, activation.ProductInstalled)
        prior = journal.Identity.observe(self.root / "installation")
        with patch.object(install, "saved_file", side_effect=OSError("preimage unavailable")):
            self.assertIsInstance(
                activation.activate(self.root, self.home, admitted), contract.Rejected
            )
        self.assertEqual([], list(self.root.parent.glob(".kast-install-*")))
        self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
        path, _ = self.removal_journal(installed)
        self.assertEqual(0, self.remove_product(path))
        self.assertFalse((self.root / "installation").exists())

    def test_partial_control_extraction_is_cleaned_without_removing_foreign_stages(self) -> None:
        admitted = self.admitted()
        self.root.mkdir()
        foreign = self.root / ".install-foreign"
        foreign.mkdir()
        _ = (foreign / "keep").write_bytes(b"not this transaction")
        extract = release.extract_control

        def partial(archive: Path, stage: Path) -> release.Failure | release.Admission:
            self.assertEqual(release.Admission.ADMITTED, extract(archive, stage))
            return release.Failure.ARTIFACT_INVALID

        with patch.object(release, "extract_control", side_effect=partial):
            self.assertIsInstance(
                activation.activate(self.root, self.home, admitted), contract.Rejected
            )
        self.assertEqual([foreign], list(self.root.glob(".install-*")))
        self.assertEqual([], list(self.root.parent.glob(".kast-install-*")))
        self.assertEqual(b"not this transaction", (foreign / "keep").read_bytes())

    def test_control_stage_name_collision_preserves_the_foreign_directory(self) -> None:
        admitted = self.admitted()
        self.root.mkdir()
        token = "12345678-1234-1234-1234-123456789abc"
        foreign = self.root.parent / (".kast-install-" + token)
        foreign.mkdir()
        _ = (foreign / "keep").write_bytes(b"foreign allocation")
        with patch.object(activation.uuid, "uuid4", return_value=activation.uuid.UUID(token)):
            self.assertIsInstance(
                activation.activate(self.root, self.home, admitted), contract.Rejected
            )
        self.assertEqual(b"foreign allocation", (foreign / "keep").read_bytes())
        self.assertFalse((self.root / "recovery/replacement").exists())

    def test_crashed_control_preparation_cannot_strand_product_removal(self) -> None:
        admitted = self.admitted()
        installed = activation.activate(self.root, self.home, admitted)
        assert isinstance(installed, activation.ProductInstalled), installed
        prior = journal.Identity.observe(self.root / "installation")
        stages: list[Path] = []

        class Crash(BaseException):
            pass

        def interrupted(archive: Path, extracted: Path) -> release.Admission:
            stages.append(extracted.parent)
            extracted.mkdir()
            _ = (extracted / "partial").write_bytes(b"interrupted Control extraction")
            raise Crash()

        with (
            patch.object(release, "extract_control", side_effect=interrupted),
            patch.object(activation, "clean_control_stage", return_value=None),
            self.assertRaises(Crash),
        ):
            _ = activation.activate(self.root, self.home, admitted)
        self.assertEqual(1, len(stages))
        self.assertFalse(stages[0].is_relative_to(self.root))
        self.assertFalse((self.root / "recovery/replacement").exists())
        self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
        path, _ = self.removal_journal(installed)
        self.assertEqual(0, self.remove_product(path))
        self.assertTrue(stages[0].is_dir())

    def test_crashed_publication_stage_stays_outside_managed_stores(self) -> None:
        admitted = self.admitted()
        rename = install.rename_file_no_replace
        stages: list[Path] = []

        class Crash(BaseException):
            pass

        def crash(source: Path, destination: Path) -> None:
            if destination == Path(admitted.reference(self.root).runtimeRoot):
                stages.append(source.parent)
                raise Crash()
            rename(source, destination)

        with (
            patch.object(install, "rename_file_no_replace", side_effect=crash),
            patch.object(immutable_store, "clean_publication", side_effect=Crash),
            self.assertRaises(Crash),
        ):
            activation.publish_runtime(admitted, admitted.reference(self.root))
        self.assertEqual(1, len(stages))
        self.assertTrue(stages[0].is_dir())
        runtime = Path(admitted.reference(self.root).runtimeRoot)
        self.assertFalse(stages[0].is_relative_to(runtime.parent.parent))
        self.assertFalse(stages[0].is_relative_to(self.root))
        proofs, _, _ = product_removal_recovery.capture(self.root, admitted.reference(self.root))
        self.assertEqual([], proofs)
        self.assertTrue(stages[0].exists())
        # Even loss of the receipt cannot poison digest-only capture or require
        # guessing that a now-unreceipted directory is owned.
        (stages[0] / "publication.json").unlink()
        proofs, _, _ = product_removal_recovery.capture(self.root, admitted.reference(self.root))
        self.assertEqual([], proofs)
        self.assertTrue(stages[0].is_dir())

    def test_partial_publication_with_original_archive_is_removable_offline(self) -> None:
        admitted = self.admitted()
        parent = Path(admitted.reference(self.root).runtimeRoot).parent
        activation.private_directory(parent)
        with (
            patch.object(immutable_store, "clean_publication", return_value=None),
            immutable_store.publication(parent, admitted, "RUNTIME") as payload,
        ):
            _ = shutil.copytree(admitted.runtime, payload)
            immutable_store.publish(payload, admitted, "RUNTIME")
            java = payload / "host/Kast.app/Contents/jbr/Contents/Home/bin/java"
            java.chmod(0o700)
            _ = java.write_bytes(b"native ")
        payload = payload.parent.rename(parent / (".runtime-" + payload.parent.name)) / "payload"
        proofs, _, _ = product_removal_recovery.capture(self.root, admitted.reference(self.root))
        self.assertEqual([], proofs)
        self.assertEqual([], list(parent.iterdir()))

    def test_partial_publication_archive_requires_same_selection_then_recovers(self) -> None:
        admitted = self.admitted()
        parent = Path(admitted.reference(self.root).runtimeRoot).parent
        activation.private_directory(parent)
        original = admitted.runtime.parent / admitted.target.runtime.name
        with (
            patch.object(immutable_store, "clean_publication", return_value=None),
            immutable_store.publication(parent, admitted, "RUNTIME") as payload,
        ):
            (payload / "provenance").mkdir(parents=True, mode=0o700)
            _ = (payload / "provenance/archive.zip").write_bytes(original.read_bytes()[:32])
        payload = payload.parent.rename(parent / (".runtime-" + payload.parent.name)) / "payload"
        with self.assertRaises(install.StageRejected) as rejection:
            _ = product_removal_recovery.capture(self.root, admitted.reference(self.root))
        self.assertEqual(
            "PUBLICATION_INTERRUPTED_RETRY_SAME_SELECTION", rejection.exception.failure.adapterCode
        )
        self.assertTrue(payload.parent.exists())
        activation.publish_runtime(admitted, admitted.reference(self.root))
        self.assertFalse(payload.parent.exists())
        activation.read_runtime(admitted.reference(self.root))

    def test_removal_reports_incomplete_publication_archive_without_erasing_payload(self) -> None:
        admitted = self.admitted()
        installed = activation.activate(self.root, self.home, admitted)
        assert isinstance(installed, activation.ProductInstalled)
        path, _ = self.removal_journal(installed)
        parent = Path(admitted.reference(self.root).runtimeRoot).parent
        archive = admitted.runtime.parent / admitted.target.runtime.name
        with (
            patch.object(immutable_store, "clean_publication", return_value=None),
            immutable_store.publication(parent, admitted, "RUNTIME") as payload,
        ):
            (payload / "provenance").mkdir(parents=True, mode=0o700)
            _ = (payload / "provenance/archive.zip").write_bytes(archive.read_bytes()[:32])
        payload = payload.parent.rename(parent / (".runtime-" + payload.parent.name)) / "payload"
        result = removal.remove(self.root, path)
        self.assertEqual(
            {
                "failure": "PUBLICATION_INTERRUPTED_RETRY_SAME_SELECTION",
                "stage": "RETAIN_EXECUTION",
                "status": "REMOVAL_REJECTED",
            },
            json.loads(install.encoded(result)),
        )
        self.assertTrue((self.root / "installation").exists())
        self.assertTrue(Path(installed.product.runtimeRoot).exists())
        self.assertTrue(payload.parent.exists())

    def test_publication_recovery_preserves_unknown_files_and_replaced_stage_identity(self) -> None:
        admitted = self.admitted()
        parent = Path(admitted.reference(self.root).runtimeRoot).parent
        activation.private_directory(parent)
        with (
            patch.object(immutable_store, "clean_publication", return_value=None),
            immutable_store.publication(parent, admitted, "RUNTIME") as payload,
        ):
            payload.mkdir(mode=0o700)
            unknown = payload / "keep"
            _ = unknown.write_bytes(b"foreign")
        payload = payload.parent.rename(parent / (".runtime-" + payload.parent.name)) / "payload"
        unknown = payload / "keep"
        with self.assertRaises(ValueError):
            _ = product_removal_recovery.capture(self.root, admitted.reference(self.root))
        self.assertEqual(b"foreign", unknown.read_bytes())
        unknown.unlink()
        old = payload.parent.rename(self.home / "old-stage")
        payload.parent.mkdir(mode=0o700)
        _ = shutil.copyfile(old / "publication.json", payload.parent / "publication.json")
        with self.assertRaises(ValueError):
            _ = product_removal_recovery.capture(self.root, admitted.reference(self.root))
        self.assertTrue(payload.parent.is_dir())

    def test_legacy_complete_publication_is_admitted_but_unrecorded_foreign_stage_is_not(
        self,
    ) -> None:
        admitted = self.admitted()
        reference = admitted.reference(self.root)
        activation.publish_runtime(admitted, reference)
        runtime = Path(reference.runtimeRoot)
        legacy = runtime.parent / ".runtime-legacy"
        legacy.mkdir(mode=0o700)
        _ = runtime.rename(legacy / "payload")
        proofs, _, _ = product_removal_recovery.capture(self.root, reference)
        self.assertEqual([], proofs)
        self.assertFalse(legacy.exists())
        foreign = runtime.parent / ".runtime-foreign"
        foreign.mkdir(mode=0o700)
        _ = (foreign / "keep").write_bytes(b"not an owned stage")
        with self.assertRaises(OSError):
            _ = product_removal_recovery.capture(self.root, reference)
        self.assertEqual(b"not an owned stage", (foreign / "keep").read_bytes())

    def test_damaged_owned_runtime_repairs_only_after_exact_retirement(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        runtime = Path(admitted.reference(self.root).runtimeRoot)
        runtime_identity = journal.Identity.observe(runtime)
        java = runtime / "host/Kast.app/Contents/jbr/Contents/Home/bin/java"
        java.chmod(0o700)
        _ = java.write_bytes(b"damaged owned executable")

        def retired(
            arguments: list[str], environment: dict[str, str], timeout: float
        ) -> subprocess.CompletedProcess[bytes]:
            self.assertEqual(b"damaged owned executable", java.read_bytes())
            result = self.retire(arguments, environment, timeout)
            pending = install.journal_types_record(
                install.RECOVERY.load(
                    self.root / "recovery/replacement/receipt.json", journal.Replacement
                )
            )
            assert pending.reservation is not None
            with self.assertRaises(install.StageRejected):
                immutable_store.finish_resets(
                    self.root,
                    Path(pending.reservation.staged),
                    admitted.reference(self.root),
                    pending,
                )
            self.assertEqual(b"damaged owned executable", java.read_bytes())
            return result

        result = activation.activate(self.root, self.home, admitted, runner=retired)
        self.assertIsInstance(result, activation.ProductInstalled)
        self.assertEqual(b"native fixture", java.read_bytes())
        self.assertNotEqual(runtime_identity, journal.Identity.observe(runtime))
        activation.read_runtime(admitted.reference(self.root))
        self.assertFalse((self.root / "recovery/replacement").exists())
        self.assertFalse((self.root / "shutdown.json").exists())

    def test_reset_rebuilds_corrupt_retained_archive_from_exact_admitted_archive(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        runtime = Path(admitted.reference(self.root).runtimeRoot)
        archive = runtime / "provenance/archive.zip"
        original_record = (runtime / "provenance/build-record.json").read_bytes()
        archive.chmod(0o600)
        _ = archive.write_bytes(b"damaged retained archive")
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted, runner=self.retire),
            activation.ProductInstalled,
        )
        self.assertEqual(admitted.target.runtime.sha256, release.file_digest(archive))
        self.assertEqual(original_record, (runtime / "provenance/build-record.json").read_bytes())
        activation.read_runtime(admitted.reference(self.root))

    def test_reset_rejects_cross_filesystem_publication_before_retirement_and_deletion(
        self,
    ) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        runtime = Path(admitted.reference(self.root).runtimeRoot)
        java = runtime / "host/Kast.app/Contents/jbr/Contents/Home/bin/java"
        java.chmod(0o700)
        _ = java.write_bytes(b"damaged")
        observe = journal.Identity.observe

        def other_device(path: Path) -> journal.Identity:
            observed = observe(path)
            return (
                replace(observed, device=observed.device + 1)
                if path == runtime.parent
                else observed
            )

        with (
            patch.object(journal.Identity, "observe", side_effect=other_device),
            patch.object(install, "run_child", side_effect=AssertionError("no retirement")),
        ):
            result = activation.activate(self.root, self.home, admitted)
        assert isinstance(result, contract.RecoveryRequired)
        self.assertEqual("IMMUTABLE_RESET_REQUIRES_SAME_FILESYSTEM", result.failure.adapterCode)
        self.assertEqual(b"damaged", java.read_bytes())
        self.assertFalse((self.root / "shutdown.json").exists())
        self.assertIsInstance(
            activation.resume(self.root, self.home, runner=self.retire), activation.ProductInstalled
        )
        self.assertEqual(b"native fixture", java.read_bytes())

    def test_damaged_runtime_failed_retirement_preserves_bytes_and_resumes_repair(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        prior = journal.Identity.observe(self.root / "installation")
        management = (self.root / "management.json").read_bytes()
        runtime = Path(admitted.reference(self.root).runtimeRoot)
        java = runtime / "host/Kast.app/Contents/jbr/Contents/Home/bin/java"
        java.chmod(0o700)
        _ = java.write_bytes(b"damaged owned executable")

        def busy(
            arguments: list[str], environment: dict[str, str], timeout: float
        ) -> subprocess.CompletedProcess[bytes]:
            return subprocess.CompletedProcess(arguments, 1, b"", b"workspace busy")

        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted, runner=busy),
            contract.RecoveryRequired,
        )
        self.assertEqual(b"damaged owned executable", java.read_bytes())
        self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
        self.assertEqual(management, (self.root / "management.json").read_bytes())
        self.assertTrue((self.root / "recovery/replacement/receipt.json").is_file())
        self.assertIsInstance(
            activation.resume(self.root, self.home, runner=self.retire), activation.ProductInstalled
        )
        self.assertEqual(b"native fixture", java.read_bytes())

    def test_reset_resumes_partial_deletion_and_publication_without_incoming_archive(self) -> None:
        for boundary in ("PARTIAL_DELETE", "DELETED", "DELETED_LINKED_PARENT", "PUBLISHED"):
            with (
                self.subTest(boundary=boundary),
                tempfile.TemporaryDirectory(dir=self.home) as temporary,
                patch.object(self, "home", Path(temporary)),
                patch.object(self, "root", Path(temporary) / "managed"),
            ):
                admitted = self.admitted()
                self.assertIsInstance(
                    activation.activate(self.root, self.home, admitted), activation.ProductInstalled
                )
                reference = admitted.reference(self.root)
                runtime = Path(reference.runtimeRoot)
                java = runtime / "host/Kast.app/Contents/jbr/Contents/Home/bin/java"
                java.chmod(0o700)
                _ = java.write_bytes(b"damaged")
                prior = journal.Identity.observe(self.root / "installation")
                markers = (
                    self.home / "repository/source.kt",
                    self.home / ".gradle/caches/keep",
                    runtime.parent.parent / "containers/kept/record.json",
                    runtime.parent.parent / "containers/kept/profile/keep",
                )
                for marker in markers:
                    marker.parent.mkdir(parents=True, exist_ok=True)
                    _ = marker.write_bytes(b"protected")
                rename, unlink = install.rename_file_no_replace, Path.unlink

                def interrupted_unlink(
                    path: Path,
                    missing_ok: bool = False,
                    *,
                    native: Callable[[Path, bool], None] = unlink,
                    boundary: str = boundary,
                    java: Path = java,
                ) -> None:
                    native(path, missing_ok)
                    if boundary == "PARTIAL_DELETE" and path == java:
                        raise KeyboardInterrupt

                def interrupted_publish(
                    source: Path,
                    destination: Path,
                    *,
                    native: Callable[[Path, Path], None] = rename,
                    boundary: str = boundary,
                ) -> None:
                    resetting = source.parent.name == "immutable-reset"
                    if resetting and boundary.startswith("DELETED"):
                        raise KeyboardInterrupt
                    native(source, destination)
                    if resetting and boundary == "PUBLISHED":
                        raise KeyboardInterrupt

                with (
                    patch.object(Path, "unlink", new=interrupted_unlink),
                    patch.object(
                        install, "rename_file_no_replace", side_effect=interrupted_publish
                    ),
                ):
                    result = activation.activate(self.root, self.home, admitted, runner=self.retire)
                self.assertIsInstance(result, contract.RecoveryRequired)
                self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
                receipt = json.loads((self.root / "recovery/replacement/receipt.json").read_bytes())
                self.assertEqual("SETTLED", receipt["product"]["phase"])
                self.assertEqual(not boundary.startswith("DELETED"), runtime.exists())
                shutil.rmtree(admitted.runtime.parent)
                if boundary == "DELETED_LINKED_PARENT":
                    retained = runtime.parent.rename(self.home / "foreign-store")
                    runtime.parent.symlink_to(retained, target_is_directory=True)
                    with patch.object(install, "run_child", side_effect=AssertionError("no child")):
                        self.assertIsInstance(
                            activation.resume(self.root, self.home), contract.RecoveryRequired
                        )
                    self.assertFalse((retained / runtime.name).exists())
                    runtime.parent.unlink()
                    _ = retained.rename(runtime.parent)
                with patch.object(
                    install, "run_child", side_effect=AssertionError("already retired")
                ):
                    self.assertIsInstance(
                        activation.resume(self.root, self.home), activation.ProductInstalled
                    )
                self.assertEqual(b"native fixture", java.read_bytes())
                activation.read_runtime(reference)
                for marker in markers:
                    self.assertEqual(b"protected", marker.read_bytes())
                self.assertFalse((self.root / "recovery/replacement").exists())
                self.assertFalse((self.root / "shutdown.json").exists())

    def test_reset_refuses_foreign_additions_links_and_replaced_roots_after_retirement(
        self,
    ) -> None:
        for change in ("ADDITION", "SYMLINK", "HARDLINK", "ROOT", "STAGED_ADDITION"):
            with (
                self.subTest(change=change),
                tempfile.TemporaryDirectory(dir=self.home) as temporary,
                patch.object(self, "home", Path(temporary)),
                patch.object(self, "root", Path(temporary) / "managed"),
            ):
                admitted = self.admitted()
                self.assertIsInstance(
                    activation.activate(self.root, self.home, admitted), activation.ProductInstalled
                )
                runtime = Path(admitted.reference(self.root).runtimeRoot)
                java = runtime / "host/Kast.app/Contents/jbr/Contents/Home/bin/java"
                java.chmod(0o700)
                _ = java.write_bytes(b"damaged")
                protected = self.home / "foreign"
                _ = protected.write_bytes(b"do not remove")
                prior = journal.Identity.observe(self.root / "installation")

                def changed(
                    arguments: list[str],
                    environment: dict[str, str],
                    timeout: float,
                    *,
                    change: str = change,
                    runtime: Path = runtime,
                    java: Path = java,
                    protected: Path = protected,
                    admitted: staging.AdmittedProduct = admitted,
                ) -> subprocess.CompletedProcess[bytes]:
                    result = self.retire(arguments, environment, timeout)
                    if change == "ADDITION":
                        _ = (runtime / "keep").write_bytes(b"foreign")
                    elif change in ("SYMLINK", "HARDLINK"):
                        java.parent.chmod(0o700)
                        java.unlink()
                        if change == "SYMLINK":
                            java.symlink_to(protected)
                        else:
                            os.link(protected, java)
                    elif change == "ROOT":
                        old = runtime.rename(self.home / "old-runtime")
                        _ = shutil.copytree(old, runtime, symlinks=True)
                    else:
                        ready = (
                            self.root
                            / "recovery/replacement/immutable-reset"
                            / ("RUNTIME-" + admitted.target.runtime.sha256)
                        )
                        _ = (ready / "keep").write_bytes(b"foreign")
                    return result

                result = activation.activate(self.root, self.home, admitted, runner=changed)
                self.assertIsInstance(result, contract.RecoveryRequired)
                self.assertEqual(prior, journal.Identity.observe(self.root / "installation"))
                self.assertEqual(b"do not remove", protected.read_bytes())
                if change not in ("SYMLINK", "HARDLINK"):
                    self.assertEqual(b"damaged", java.read_bytes())
                if change == "SYMLINK":
                    self.assertTrue(java.is_symlink())
                if change == "HARDLINK":
                    self.assertEqual(2, java.stat().st_nlink)
                self.assertTrue((self.root / "recovery/replacement/receipt.json").exists())

    def test_foreign_runtime_addition_is_not_reported_as_owned_byte_damage(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        runtime = Path(admitted.reference(self.root).runtimeRoot)
        unknown = runtime / "keep"
        _ = unknown.write_bytes(b"foreign payload")
        result = activation.activate(self.root, self.home, admitted)
        assert isinstance(result, contract.Rejected)
        self.assertEqual(contract.Failure.OWNERSHIP_UNPROVEN, result.reason)
        self.assertIsNone(result.adapterCode)
        self.assertEqual(b"foreign payload", unknown.read_bytes())
        self.assertFalse((self.root / "recovery/replacement").exists())

    def test_interrupted_finalization_revalidates_before_cleanup(self) -> None:
        admitted = self.admitted()
        with patch.object(install.RECOVERY, "close_managed_journal", side_effect=KeyboardInterrupt):
            result = activation.activate(self.root, self.home, admitted)
        self.assertIsInstance(result, contract.RecoveryRequired)
        self.assertEqual(
            self.raw, (self.root / "installation/share/kast/build-record.json").read_bytes()
        )
        self.assertIsInstance(activation.resume(self.root, self.home), activation.ProductInstalled)

    def test_public_entry_dry_run_and_install_use_only_captured_release(self) -> None:
        selected = self.home / "selection.json"
        _ = selected.write_bytes(encoded_fixture(self.selected))
        root = self.home / ".local/share/kast"
        with (
            patch.dict(os.environ, {"HOME": str(self.home)}),
            patch.object(staging.platform, "system", return_value="Darwin"),
            patch.object(staging.platform, "machine", return_value="arm64"),
            patch.object(install, "select_idea", side_effect=AssertionError("no IDEA")),
            patch.object(install.subprocess, "run", side_effect=AssertionError("no process")),
        ):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = install.main(
                    ["--resolved-selection", str(selected), "--dry-run", "--json"], self.source
                )
            self.assertEqual(0, code)
            self.assertEqual("PRODUCT_PLANNED", json.loads(output.getvalue())["type"])
            self.assertFalse(root.exists())
            installed_output = io.StringIO()
            with contextlib.redirect_stdout(installed_output):
                self.assertEqual(
                    0, install.main(["--resolved-selection", str(selected), "--json"], self.source)
                )
            self.assertEqual([], json.loads(installed_output.getvalue())["notices"])
        self.assertEqual(
            self.raw, (root / "installation/share/kast/build-record.json").read_bytes()
        )
        self.assertTrue(
            all(url.startswith(f"{release.RELEASE_URL}/v1.2.3/") for url in self.source.calls)
        )

    def test_product_guidance_refresh_retains_selection_and_reports_pending_repair(self) -> None:
        installed = activation.activate(self.root, self.home, self.admitted())
        assert isinstance(installed, activation.ProductInstalled)
        marker = self.root / "installation/share/kast/guidance-refresh-v1"
        _ = marker.write_bytes(b"1\n")
        calls: list[list[str]] = []

        def run(
            arguments: list[str], environment: dict[str, str], timeout: float
        ) -> subprocess.CompletedProcess[bytes]:
            calls.append(arguments)
            self.assertEqual(str(self.root), environment["KAST_INSTALL_ROOT"])
            self.assertEqual(str(self.home), environment["HOME"])
            self.assertGreater(timeout, 0)
            return subprocess.CompletedProcess(arguments, exit_code, b"", b"")

        exit_code = 1
        pending = install.refresh_installed_guidance(self.root, self.home, installed, run)
        self.assertEqual(installed.product, pending.product)
        self.assertEqual(installed.executable, pending.executable)
        self.assertEqual((journal.ManagedNotice.HARNESS_REFRESH_REQUIRED,), pending.notices)
        exit_code = 0
        refreshed = install.refresh_installed_guidance(self.root, self.home, pending, run)
        self.assertEqual(installed, refreshed)
        expected = [
            str(self.root / "installation/share/kast/libexec/kast-management"),
            "--internal-install",
            "refresh-guidance",
        ]
        self.assertEqual([expected, expected], calls)

    def test_replacement_interruption_retains_prior_payload_and_foreign_command(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        old = (self.root / "management.json").read_bytes()
        with patch.object(install, "replace_file", side_effect=KeyboardInterrupt):
            # A different release label changes management, never embedded bytes.
            relabeled = replace(
                admitted,
                selection=replace(admitted.selection, releaseTag="v1.2.4", publicVersion="1.2.4"),
            )
            result = activation.activate(self.root, self.home, relabeled, runner=self.retire)
        self.assertIsInstance(result, contract.RecoveryRequired)
        self.assertEqual(old, (self.root / "management.json").read_bytes())
        self.assertEqual(
            self.raw,
            (self.root / "recovery/replacement/payload/share/kast/build-record.json").read_bytes(),
        )
        command = self.home / ".local/bin/kast"
        _ = command.write_bytes(b"foreign change")
        self.assertIsInstance(activation.resume(self.root, self.home), contract.RecoveryRequired)
        self.assertEqual(b"foreign change", command.read_bytes())
        self.assertTrue((self.root / "recovery/replacement/payload").is_dir())

    def test_digest_runtime_reuse_rejects_tampering_without_replacement(self) -> None:
        admitted = self.admitted()
        reference = admitted.reference(self.root)
        activation.publish_runtime(admitted, reference)
        marker = Path(reference.runtimeRoot) / "runtime.json"
        marker.chmod(0o600)
        _ = marker.write_bytes(b"foreign")
        with self.assertRaises(install.StageRejected) as rejected:
            activation.publish_runtime(admitted, reference)
        self.assertEqual(contract.Failure.RECOVERY_UNPROVEN, rejected.exception.failure.reason)
        self.assertEqual(b"foreign", marker.read_bytes())
        self.assertFalse(self.root.exists())

    def test_dangling_cyclic_and_escaping_runtime_links_reject(self) -> None:
        admitted = self.admitted()
        link = admitted.runtime / "host/link"
        for target in ("missing", "link"):
            with self.subTest(target=target):
                link.symlink_to(target)
                manifest = replace(
                    admitted.manifest,
                    files=admitted.manifest.files + (RuntimeLink("host/link", target),),
                )
                with self.assertRaises(ValueError):
                    staging.verify_runtime(admitted.runtime, manifest)
                link.unlink()
        manifest = replace(
            admitted.manifest,
            files=admitted.manifest.files + (RuntimeLink("host/link", "../../outside"),),
        )
        with self.assertRaises(ValueError):
            _ = staging.runtime_manifest(
                encoded_fixture(manifest), "0.0.123", HostPlatform.MACOS_ARM64
            )
        self.assertFalse(self.root.exists())

    def test_recovery_preserves_a_changed_previous_manifest(self) -> None:
        admitted = self.admitted()
        self.assertIsInstance(
            activation.activate(self.root, self.home, admitted), activation.ProductInstalled
        )
        relabeled = replace(
            admitted,
            selection=replace(admitted.selection, releaseTag="v1.2.4", publicVersion="1.2.4"),
        )
        with patch.object(install, "replace_file", side_effect=KeyboardInterrupt):
            self.assertIsInstance(
                activation.activate(self.root, self.home, relabeled, runner=self.retire),
                contract.RecoveryRequired,
            )
        prior = self.root / "recovery/replacement/payload/installation.json"
        changed = prior.read_bytes() + b" "
        _ = prior.write_bytes(changed)
        self.assertIsInstance(activation.resume(self.root, self.home), contract.RecoveryRequired)
        self.assertEqual(changed, prior.read_bytes())
        self.assertTrue((self.root / "recovery/replacement/receipt.json").is_file())


if __name__ == "__main__":
    _ = unittest.main()
