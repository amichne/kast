#!/usr/bin/env python3
"""Exercise publication admission without contacting GitHub."""

from pathlib import Path
import copy
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("release_gate", Path(__file__).with_name("release_gate.py"))
assert SPEC and SPEC.loader
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class ReceiptIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.sha = "a" * 40
        self.version = "1.0.0"
        for name in gate.asset_names(self.version):
            (self.directory / name).write_bytes(name.encode())
            (self.directory / (name + ".sha256")).write_text("checksum fixture")
        gate.write(self.directory / f"kast-compatibility-v{self.version}.json", {
            "sourceRevision": self.sha, "productVersion": self.version,
            "inputs": {"schemaDigest": gate.digest(self.directory / f"kast-cli-schema-v{self.version}.json")},
        })
        assets = gate.asset_identities(self.directory, self.version)
        environment = {"system": "Darwin", "architecture": "arm64", "ideaBuild": "262.1", "javaReleaseDigest": "sha256:" + "a" * 64}
        source = {"status": "passed", "sourceRevision": self.sha, "command": gate.source_command(self.version, self.sha), "environment": environment}
        source["archives"] = {name: assets[name] for name in gate.product_asset_names(self.version)[:2]}
        matrix = {"schemaVersion": 1, "status": "passed",
            "matrixSha256": gate.digest(ROOT / "benchmarks/gradle-import-acceptance.json").removeprefix("sha256:"),
            "commandSha256": gate.digest(ROOT / "integration-tests/gradle_import_acceptance.py").removeprefix("sha256:"),
            "javaRuntimeReleaseSha256": {str(feature): "a" * 64 for feature in (17, 21, 25)}, "cases": [
            {"gradle": gradle, "java": java, "expectedRejection": rejected,
             "explicitInputAdmitted": not rejected, "ambientSecretAbsent": not rejected,
             "explicitExecutableAdmitted": not rejected, "gradleUserHomeIsolated": not rejected}
            for gradle, java, rejected in [("7.6.4", 17, False), ("8.14.3", 21, False), ("9.4.1", 25, False), ("7.6.4", 25, True)]
        ]}
        installed = {"status": "passed", "sourceRevision": self.sha, "environment": environment, "assets": assets, "journeys": ["cli-without-codex", "semantic-continuity", "verified-mutation", "uninstall-reinstall", "cold-broker", "gradle-import"], "broker": {"status": "passed", "readOnlyCatalog": True, "cliEquivalent": True, "selectorReused": True}, "gradleImport": matrix}
        installed["broker"].update(schemaVersion=2, cliVersion=f"kast {self.version} (IntelliJ sidecar)",
                                   coldInvocationMillis=1, sourceFirstLine=1, sourceLastLine=1)
        installed["resourceSamples"] = [
            {"schemaVersion": 1, "stage": stage, "status": "not-running" if stage == "after-stop" else "observed",
             "apparentStateBytes": 1, "stateEntryCount": 1, "symlinkCount": 0, "selectedStateRootCount": 2,
             **({"cause": "pid-marker-absent"} if stage == "after-stop" else {"rssBytes": 1, "processCount": 1})}
            for stage in ("after-start", "after-read", "after-restart", "after-stop")]
        installed["semanticCorruption"] = {"schemaVersion": 1, "status": "passed",
            "workspaceDigestBefore": "sha256:" + "a" * 64, "workspaceDigestAfter": "sha256:" + "a" * 64,
            "continuations": [{"family": family, "case": case, "status": "rejected", "exitCode": 2,
                "boundary": "usage", "reason": "arguments-rejected", "documentDigest": "sha256:" + "b" * 64,
                "originalContinuationDigest": "sha256:" + "c" * 64, "validResumeDigest": "sha256:" + "d" * 64}
                for family in ("relation", "traversal") for case in ("malformed", "digest-tampered")],
            "stateReceipt": {"kind": "cache-identity-v3", "status": "rejected-and-restored", "exitCode": 4,
                "boundary": "runtime", "reason": "status-cache-invalid-identity",
                **{key: "sha256:" + "e" * 64 for key in ("documentDigest", "originalReceiptDigest", "restoredReceiptDigest", "recoveredStatusDigest", "recoveredReadDigest")}}}
        preserved = {"activeInstallationDigest": "sha256:" + "c" * 64, "workspaceDigest": "sha256:" + "d" * 64}
        installed["journeys"].extend(["upgrade", "corruption"])
        installed["upgrade"] = {"status": "passed", "candidateVersion": self.version,
            "candidateAssets": {name: assets[name] for asset in gate.product_asset_names(self.version)[:2] for name in (asset, asset + ".sha256")},
            "priorRelease": {"immutable": True, "tag": "v0.32.2", "version": "0.32.2", "passiveStatus": {"status": "stopped"}},
            **preserved, "corruptionCases": [{"case": case, "status": "rejected", "exitCode": 1, **preserved}
                                             for case in ("checksum-mismatch", "unsafe-archive-path")]}
        archive = {"status": "passed", "sourceRevision": self.sha, "observations": {"outcome": "COMPLETE", "release": "v1.0.0", "assets": [{"name": name, "sha256": assets[name].removeprefix("sha256:")} for name in gate.product_asset_names(self.version)]}}
        sbom = {"status": "passed", "sourceRevision": self.sha, "archives": {name: assets[name] for name in gate.product_asset_names(self.version)[:2]}, "sbomDigest": assets[f"kast-sbom-v{self.version}.cdx.json"], "componentCount": 1}
        compatibility = {"status": "passed", "sourceRevision": self.sha, "productVersion": self.version,
                         "candidateDigest": assets[f"kast-compatibility-v{self.version}.json"],
                         "baseline": {"state": "absent"},
                         "comparison": {"status": "first-stable", "candidateVersion": self.version, "changes": []}}
        self.receipt = {"schemaVersion": 1, "status": "passed", "sourceRevision": self.sha, "productVersion": self.version, "commandDigest": gate.identity(gate.source_command(self.version, self.sha)), "environment": environment, "assets": assets, "dependencies": {key: {"receipt": value, "digest": gate.identity(value)} for key, value in {"source": source, "assets": archive, "installed": installed, "sbom": sbom, "compatibility": compatibility}.items()}}

    def validate(self, receipt):
        gate.validate_receipt(receipt, self.directory, self.version, self.sha)

    def test_complete_receipt_binds_all_predecessors_and_installed_bytes(self):
        self.validate(self.receipt)

    def test_asset_change_after_acceptance_rejects_publication(self):
        (self.directory / gate.asset_names(self.version)[0]).write_bytes(b"changed")
        with self.assertRaisesRegex(gate.GateRejected, "assets differ"):
            self.validate(self.receipt)

    def test_matrix_cannot_replace_rejection_or_omit_isolation_proof(self):
        for change in ("no-rejection", "ambientSecretAbsent", "gradleUserHomeIsolated", "commandSha256", "matrixSha256", "javaRuntimeReleaseSha256"):
            receipt = copy.deepcopy(self.receipt)
            dependency = receipt["dependencies"]["installed"]
            cases = dependency["receipt"]["gradleImport"]["cases"]
            if change == "no-rejection":
                cases[-1] = cases[0]
            elif change in {"ambientSecretAbsent", "gradleUserHomeIsolated"}:
                cases[0][change] = False
            else:
                dependency["receipt"]["gradleImport"].pop(change)
            dependency["digest"] = gate.identity(dependency["receipt"])
            with self.subTest(change=change), self.assertRaisesRegex(gate.GateRejected, "Gradle matrix"):
                self.validate(receipt)

    def test_installed_archives_cannot_replace_successful_source_build_outputs(self):
        receipt = copy.deepcopy(self.receipt)
        dependency = receipt["dependencies"]["source"]
        dependency["receipt"]["archives"] = {name: "sha256:" + "e" * 64 for name in dependency["receipt"]["archives"]}
        dependency["digest"] = gate.identity(dependency["receipt"])
        with self.assertRaisesRegex(gate.GateRejected, "source build outputs"):
            self.validate(receipt)

    def test_resource_evidence_requires_live_samples_and_observed_teardown(self):
        for change in ("missing-stage", "invented-zero", "unproven-state"):
            receipt = copy.deepcopy(self.receipt)
            dependency = receipt["dependencies"]["installed"]
            samples = dependency["receipt"]["resourceSamples"]
            if change == "missing-stage":
                samples.pop()
            elif change == "invented-zero":
                samples[-1]["rssBytes"] = 0
            else:
                samples[0]["status"] = "rejected"
            dependency["digest"] = gate.identity(dependency["receipt"])
            with self.subTest(change=change), self.assertRaisesRegex(gate.GateRejected, "resource proof"):
                self.validate(receipt)

    def test_semantic_corruption_requires_real_continuations_and_exact_restoration(self):
        for change in ("missing-case", "missing-valid-resume", "different-restoration", "changed-repository"):
            receipt = copy.deepcopy(self.receipt)
            dependency = receipt["dependencies"]["installed"]
            proof = dependency["receipt"]["semanticCorruption"]
            if change == "missing-case":
                proof["continuations"].pop()
            elif change == "missing-valid-resume":
                proof["continuations"][0].pop("validResumeDigest")
            elif change == "different-restoration":
                proof["stateReceipt"]["restoredReceiptDigest"] = "sha256:" + "f" * 64
            else:
                proof["workspaceDigestAfter"] = "sha256:" + "f" * 64
            dependency["digest"] = gate.identity(dependency["receipt"])
            with self.subTest(change=change), self.assertRaisesRegex(gate.GateRejected, "semantic corruption proof"):
                self.validate(receipt)

    def test_inventory_of_different_archives_cannot_authorize_publication(self):
        receipt = copy.deepcopy(self.receipt)
        dependency = receipt["dependencies"]["sbom"]
        dependency["receipt"]["archives"] = {}
        dependency["digest"] = gate.identity(dependency["receipt"])
        with self.assertRaisesRegex(gate.GateRejected, "SBOM predecessor"):
            self.validate(receipt)

    def test_corruption_rejection_must_preserve_the_active_installation(self):
        receipt = copy.deepcopy(self.receipt)
        dependency = receipt["dependencies"]["installed"]
        dependency["receipt"]["upgrade"]["corruptionCases"][0]["activeInstallationDigest"] = "sha256:" + "e" * 64
        dependency["digest"] = gate.identity(dependency["receipt"])
        with self.assertRaisesRegex(gate.GateRejected, "preserve the active product"):
            self.validate(receipt)

    def test_cold_broker_proof_cannot_exceed_the_declared_startup_bound(self):
        receipt = copy.deepcopy(self.receipt)
        dependency = receipt["dependencies"]["installed"]
        dependency["receipt"]["broker"]["coldInvocationMillis"] = 240_001
        dependency["digest"] = gate.identity(dependency["receipt"])
        with self.assertRaisesRegex(gate.GateRejected, "declared bound"):
            self.validate(receipt)

    def test_missing_foreign_failed_or_tampered_proof_rejects(self):
        cases = []
        missing = copy.deepcopy(self.receipt)
        del missing["dependencies"]["installed"]
        cases.append(missing)
        foreign = copy.deepcopy(self.receipt)
        foreign["sourceRevision"] = "b" * 40
        cases.append(foreign)
        failed = copy.deepcopy(self.receipt)
        failed["dependencies"]["installed"]["receipt"]["status"] = "failed"
        failed["dependencies"]["installed"]["digest"] = gate.identity(failed["dependencies"]["installed"]["receipt"])
        cases.append(failed)
        tampered = copy.deepcopy(self.receipt)
        tampered["dependencies"]["installed"]["receipt"]["journeys"] = []
        cases.append(tampered)
        wrong_command = copy.deepcopy(self.receipt)
        wrong_command["commandDigest"] = gate.identity(["./gradlew", "help"])
        cases.append(wrong_command)
        for candidate in cases:
            with self.subTest(candidate=candidate), self.assertRaises(gate.GateRejected):
                self.validate(candidate)

    def test_failed_source_gate_removes_old_receipt_and_writes_no_success(self):
        receipt = self.directory / "kast-release-receipt-v1.0.0.json"
        receipt.write_text("stale success")
        reports = self.directory / "build/reports/release-gate"
        for name in ("source", "installed", "sbom", "compatibility"):
            gate.write(reports / f"{name}.json", self.receipt["dependencies"][name]["receipt"])
        gate.write(self.directory / "build/reports/sidecar/release-assets.json",
                   self.receipt["dependencies"]["assets"]["receipt"]["observations"])
        with mock.patch.object(gate, "admit_source"), mock.patch.object(gate, "prepare_idea"), mock.patch.object(gate, "run", side_effect=gate.GateRejected("deliberate semantic test failure")):
            with self.assertRaisesRegex(gate.GateRejected, "deliberate semantic"):
                gate.execute("source", self.directory, self.directory, self.version, self.sha)
        self.assertFalse(receipt.exists())
        self.assertFalse((self.directory / "build/reports/release-gate/source.json").exists())
        with mock.patch.object(gate, "admit_source"), self.assertRaises(gate.GateRejected):
            gate.execute("finish", self.directory, self.directory, self.version, self.sha)


class PublicationAdmissionTest(unittest.TestCase):
    def test_missing_semantic_receipt_cannot_create_even_a_draft_release(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            checkout = root / "checkout"
            scripts = checkout / ".github/scripts/release"
            scripts.mkdir(parents=True)
            for name in ("publish-release.sh", "admit-source.sh"):
                shutil.copy2(ROOT / ".github/scripts/release" / name, scripts / name)
            gate = ROOT / "distribution/release/release_gate.py"
            if gate.is_file():
                (checkout / "distribution/release").mkdir(parents=True)
                shutil.copy2(gate, checkout / "distribution/release/release_gate.py")
            (checkout / ".gitignore").write_text("build/\n")
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=checkout, stderr=subprocess.DEVNULL, text=True).strip()
            git("init", "-b", "main")
            git("config", "user.name", "Gate Test")
            git("config", "user.email", "gate@kast.invalid")
            git("add", ".")
            git("-c", "commit.gpgsign=false", "commit", "-m", "fixture")
            sha = git("rev-parse", "HEAD")
            git("clone", "--bare", str(checkout), str(root / "origin.git"))
            git("remote", "add", "origin", str(root / "origin.git"))
            git("checkout", "--detach", sha)
            assets = checkout / "build/release/v1.0.0"
            assets.mkdir(parents=True)
            for name in ("kast-control-v1.0.0-macos-aarch64.tar.gz", "kast-semantic-runtime-1.0.0-macos-aarch64.zip", "kast-cli-schema-v1.0.0.json", "kast-module-knowledge-v1.0.0.json"):
                (assets / name).write_bytes(b"unproven")
                (assets / (name + ".sha256")).write_bytes(b"unproven")
            commands = root / "commands"
            commands.mkdir()
            gh = commands / "gh"
            gh.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$GATE_GH_LOG"\ncase "$1 $2" in\n "release create") exit 0 ;;\n *) exit 1 ;;\nesac\n')
            gh.chmod(0o755)
            log = root / "gh.log"
            result = subprocess.run(["bash", str(scripts / "publish-release.sh"), "--release", "v1.0.0", "--commit", sha, "--assets-directory", str(assets)], cwd=checkout, env={**os.environ, "PATH": f"{commands}:{os.environ['PATH']}", "GH_TOKEN": "fixture", "GATE_GH_LOG": str(log)}, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            calls = log.read_text() if log.exists() else ""
            self.assertNotIn("release create", calls, "publication created a release without installed semantic proof")


class FreshHostIdeaTest(unittest.TestCase):
    def test_extracted_platform_facade_preserves_launcher_required_metadata(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "gradle").mkdir()
            (root / "gradle/libs.versions.toml").write_text('[versions]\nidea-indexer="262.1"\n')
            platform = root / "cache/kast/indexer-idea-distributions/262.1"
            for name in ("plugins/Kotlin", "lib", "modules"):
                (platform / name).mkdir(parents=True)
            for name in ("build.txt", "product-info.json", "lib/nio-fs.jar", "modules/module-descriptors.dat"):
                (platform / name).write_text("fixture")
            java = root / "jdk"
            (java / "bin").mkdir(parents=True)
            (java / "bin/java").write_text("fixture")
            environment = {"GRADLE_USER_HOME": str(root / "cache"), "JAVA_HOME": str(java)}
            with mock.patch.object(Path, "home", return_value=root / "empty-home"), mock.patch.object(gate, "run"):
                facade = gate.prepare_idea(root, environment)
            self.assertEqual((platform / "product-info.json").resolve(), (facade / "Resources/product-info.json").resolve())
            self.assertEqual(java.resolve(), (facade / "jbr/Contents/Home").resolve())
            self.assertEqual(str(facade), environment["KAST_ACCEPTANCE_IDEA_HOME"])


if __name__ == "__main__":
    unittest.main()
