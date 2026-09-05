#!/usr/bin/env python3
"""Install the exact candidate archives and exercise the public semantic CLI."""

from __future__ import annotations

import argparse
from enum import Enum
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

import enterprise_acceptance as enterprise
import release_upgrade_acceptance as upgrade_acceptance
import release_resource_observations as resources
import release_semantic_corruption as semantic_corruption

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("release_gate", ROOT / "distribution/release/release_gate.py")
assert SPEC and SPEC.loader
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class ObservationFailure(str, Enum):
    COMMAND_REJECTED = "command-rejected"
    COMMAND_TIMEOUT = "command-timeout"
    COMMAND_UNAVAILABLE = "command-unavailable"
    OBSERVATION_LIMIT = "observation-limit"
    RESOURCE_UNPROVEN = "resource-unproven"
    SOURCE_CHANGED = "repository-source-changed"
    SOURCE_UNPROVEN = "repository-source-unproven"


class WorkspacePreservationStage(str, Enum):
    FIRST_UNINSTALL = "first-uninstall"
    REINSTALL = "reinstall"
    FINAL_UNINSTALL = "final-uninstall"


class ObservedAcceptance(enterprise.Acceptance):
    def __init__(self, executable, host, bounds, report, source_revision):
        super().__init__(executable, host, bounds)
        self.observations = []
        self.resource_samples = []
        self.workspace_preservation = []
        self.report = report
        self.source_revision = source_revision
        self.host = host
        self.failure = None
        self.starts = 0
        self.read_sampled = False
        self.environment.pop("CODEX_HOME", None)
        self.environment.pop("CODEX_EXECUTABLE", None)
        self.environment["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
        self.environment["KAST_ENABLE_LAUNCHD"] = "0"
        if shutil.which("codex", path=self.environment["PATH"]) is not None:
            raise gate.GateRejected("CLI-only acceptance environment contains Codex")
        self.persist()

    def persist(self):
        gate.write(self.report, {"schemaVersion": 1, "status": "rejected" if self.failure else "running",
                                "sourceRevision": self.source_revision,
                                "failure": self.failure.value if self.failure else None,
                                "observations": self.observations, "resourceSamples": self.resource_samples,
                                "workspacePreservation": self.workspace_preservation})

    def workspace_identity(self):
        try:
            return "sha256:" + enterprise.workspace_source_identity(self.host.workspace)
        except (SystemExit, OSError, subprocess.SubprocessError):
            self.failure = ObservationFailure.SOURCE_UNPROVEN
            self.persist()
            raise gate.GateRejected("repository source identity could not be observed") from None

    def prove_workspace_preservation(self, stage: WorkspacePreservationStage, expected: str):
        if len(self.workspace_preservation) >= len(WorkspacePreservationStage):
            self.failure = ObservationFailure.OBSERVATION_LIMIT
            self.persist()
            raise gate.GateRejected("repository source observation limit exceeded")
        proof = {"schemaVersion": 1, "stage": stage.value, "expectedDigest": expected}
        try:
            observed = self.workspace_identity()
        except gate.GateRejected:
            self.workspace_preservation.append({**proof, "status": "rejected", "cause": ObservationFailure.SOURCE_UNPROVEN.value})
            self.persist()
            raise
        proof["observedDigest"] = observed
        if expected != observed:
            self.failure = ObservationFailure.SOURCE_CHANGED
            self.workspace_preservation.append({**proof, "status": "rejected", "cause": self.failure.value})
            self.persist()
            raise gate.GateRejected("installation transition changed repository source identity")
        self.workspace_preservation.append({**proof, "status": "passed"})
        self.persist()

    def sample(self, stage):
        sample = resources.observe(stage, owner_root=self.host.root.resolve(),
            cache_root=self.host.runtime / "intellij-caches",
            state_roots=(self.host.root / "installation", self.host.runtime))
        self.resource_samples.append(sample)
        self.persist()
        expected = "not-running" if stage is resources.ResourceStage.AFTER_STOP else "observed"
        if sample["status"] != expected:
            self.failure = ObservationFailure.RESOURCE_UNPROVEN
            self.persist()
            raise gate.GateRejected("installed resource observation did not prove the expected owned runtime state")

    def command(self, *argv, **options):
        if len(self.observations) >= 256:
            self.failure = ObservationFailure.OBSERVATION_LIMIT
            self.persist()
            raise gate.GateRejected("installed command observation limit exceeded")
        started = time.monotonic()
        # Record grammar words only; no option values, paths, source, or error text.
        command = " ".join(argv[:2]) if argv[0] in {"symbol", "source", "relation", "traversal", "diagnostic", "index", "topology", "change"} else argv[0]
        try:
            document = super().command(*argv, **options)
        except (SystemExit, subprocess.TimeoutExpired, OSError) as error:
            self.failure = (ObservationFailure.COMMAND_TIMEOUT if isinstance(error, subprocess.TimeoutExpired)
                            else ObservationFailure.COMMAND_UNAVAILABLE if isinstance(error, OSError)
                            else ObservationFailure.COMMAND_REJECTED)
            self.observations.append({"command": command, "status": "rejected", "cause": self.failure.value,
                                      "elapsedMilliseconds": round((time.monotonic() - started) * 1000)})
            self.persist()
            raise
        self.observations.append({"command": command, "status": "observed", "elapsedMilliseconds": round((time.monotonic() - started) * 1000), "evidenceDigest": gate.identity(document)})
        self.persist()
        if argv[0] == "start" and document.get("runtime") == "running":
            self.sample(resources.ResourceStage.AFTER_START if self.starts == 0 else resources.ResourceStage.AFTER_RESTART)
            self.starts += 1
            self.read_sampled = False
        elif argv[0] == "stop" and document.get("runtime") == "stopped":
            self.sample(resources.ResourceStage.AFTER_STOP)
        elif argv[0] in {"symbol", "source", "relation", "traversal", "diagnostic"} and not self.read_sampled:
            self.sample(resources.ResourceStage.AFTER_READ)
            self.read_sampled = True
        return document


def install(host, assets, version, idea, environment):
    install_environment = {**environment, "KAST_INSTALL_ROOT": str(host.root / "installation"), "KAST_BIN_DIR": str(host.root / "bin"), "XDG_CONFIG_HOME": str(host.home / ".config"), "XDG_DATA_HOME": str(host.home / ".local/share"), "KAST_INSTALL_IDEA_HOME": str(idea)}
    gate.run(["/bin/bash", str(ROOT / "install.sh"), "install", "--version", version, "--assets-directory", str(assets)], host.workspace, install_environment)
    return install_environment


def assert_broker_absent(host):
    for path in (host.readiness_file, host.broker_socket, host.home / ".codex"):
        if path.exists() or path.is_symlink():
            raise gate.GateRejected("CLI-only semantic journey created optional broker state")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets-directory", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision", required=True)
    args = parser.parse_args()
    gate.admit_source(ROOT, args.source_revision)
    assets = args.assets_directory.resolve(strict=True)
    identities = gate.asset_identities(assets, args.version)
    environment = os.environ.copy()
    idea = gate.prepare_idea(ROOT, environment)
    os.environ["KAST_ACCEPTANCE_IDEA_HOME"] = str(idea)
    bounds = gate.read(ROOT / "benchmarks/enterprise-acceptance.json")
    runtime = assets / f"kast-semantic-runtime-{args.version}-macos-aarch64.zip"
    ambient = enterprise.AmbientBrokerSnapshot.capture()
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="kr.", dir="/tmp") as raw:
        host = enterprise.IsolatedAcceptanceHost.create(Path(raw), runtime)
        shutil.copytree(ROOT / "fixtures/enterprise-workspace", host.workspace, dirs_exist_ok=True, ignore=shutil.ignore_patterns(".gradle", ".idea", "build"))
        enterprise.prepare_workspace_fixture(host.workspace)
        acceptance = ObservedAcceptance(host.root / "bin/kast", host, bounds,
            ROOT / "build/reports/release-gate/installed-observations.json", args.source_revision)
        installed_environment, upgrade = upgrade_acceptance.install_candidate_with_upgrade_proof(
            host, assets, args.version, idea, acceptance.environment, install)
        gate.validate_upgrade(upgrade, identities, args.version)
        # The launcher must use its retained installed archive, not a test override.
        acceptance.environment.pop("KAST_RUNTIME_ARCHIVE", None)
        retained = host.root / f"installation/versions/{args.version}/share/kast/runtime/{runtime.name}"
        if gate.digest(retained) != identities[runtime.name]:
            raise gate.GateRejected("installer did not retain the exact candidate sidecar")
        try:
            acceptance.prove_installed_surface(bounds)
            acceptance.prove_workspace_write_scope()
            corruption = semantic_corruption.prove_semantic_corruption(acceptance, host)
            gate.validate_semantic_corruption(corruption)
        finally:
            enterprise.stop_indexer(acceptance)
        assert_broker_absent(host)
        workspace_before = acceptance.workspace_identity()
        gate.run(["/bin/bash", str(ROOT / "install.sh"), "uninstall", "--installation-only"], host.workspace, installed_environment)
        acceptance.prove_workspace_preservation(WorkspacePreservationStage.FIRST_UNINSTALL, workspace_before)
        for path in (host.root / "bin/kast", host.root / "installation", host.runtime / "store", host.runtime / "intellij-caches", host.runtime / "endpoints"):
            if path.exists() or path.is_symlink():
                raise gate.GateRejected("uninstall retained selected product state")
        install(host, assets, args.version, idea, acceptance.environment)
        acceptance.prove_workspace_preservation(WorkspacePreservationStage.REINSTALL, workspace_before)
        version = subprocess.check_output([str(acceptance.executable), "--version"], cwd=host.workspace, env=acceptance.environment, text=True).strip()
        if version != f"kast {args.version} (IntelliJ sidecar)":
            raise gate.GateRejected("reinstall selected a different product")
        applications = host.home / "Applications/IntelliJ IDEA.app"
        applications.mkdir(parents=True)
        (applications / "Contents").symlink_to(idea)
        request = host.root / "cold-broker-request.json"
        broker_report = ROOT / "build/reports/release-gate/cold-broker.json"
        gate.write(request, {"kast": str(acceptance.executable), "workspace": str(host.workspace), "query": "enterpriseRootOperation"})
        broker_environment = {**acceptance.environment, "GRADLE_USER_HOME": str(Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))).resolve()), "JAVA_HOME": str((idea / "jbr/Contents/Home").resolve())}
        gate.run(["./gradlew", "--no-daemon", "--max-workers=2", f"-Pversion={args.version}", f"-PkastSourceRevision={args.source_revision}", f"-PkastBrokerAcceptanceRequest={request}", f"-PkastBrokerAcceptanceEvidence={broker_report}", ":cli:installedColdBrokerAcceptance"], ROOT, broker_environment)
        broker = gate.read(broker_report)
        if broker.get("status") != "passed" or not all(broker.get(key) is True for key in ("readOnlyCatalog", "cliEquivalent", "selectorReused")):
            raise gate.GateRejected("cold installed broker did not preserve canonical evidence")
        matrix_root = ROOT / "build/reports/release-gate/gradle-import"
        matrix_command = [sys.executable, str(ROOT / "integration-tests/gradle_import_acceptance.py"),
                          "--kast", str(acceptance.executable), "--fixture", str(ROOT / "fixtures/topology-identity-workspace"),
                          "--idea-home", str(idea), "--state-root", str(matrix_root)]
        for feature in (17, 21, 25):
            java_home = os.environ.get(f"KAST_RELEASE_JDK_{feature}")
            if not java_home:
                raise gate.GateRejected(f"release matrix requires explicit KAST_RELEASE_JDK_{feature}")
            matrix_command.extend(["--jdk", f"{feature}:{java_home}"])
        gate.run(matrix_command, ROOT, os.environ.copy())
        matrix = gate.read(matrix_root / "gradle-import-receipt.json")
        gate.validate_gradle_matrix(matrix)
        gate.run(["/bin/bash", str(ROOT / "install.sh"), "uninstall", "--installation-only"], host.workspace, installed_environment)
        acceptance.prove_workspace_preservation(WorkspacePreservationStage.FINAL_UNINSTALL, workspace_before)
        ambient.assert_unchanged()
        if gate.asset_identities(assets, args.version) != identities:
            raise gate.GateRejected("candidate assets changed during installed acceptance")
        gate.write(ROOT / "build/reports/release-gate/installed.json", {"schemaVersion": 1, "status": "passed", "sourceRevision": args.source_revision, "assets": identities, "environment": gate.environment_identity(idea), "journeys": ["cli-without-codex", "semantic-continuity", "verified-mutation", "uninstall-reinstall", "cold-broker", "gradle-import", "upgrade", "corruption"], "broker": broker, "gradleImport": matrix, "upgrade": upgrade, "semanticCorruption": corruption, "observations": acceptance.observations, "resourceSamples": acceptance.resource_samples, "workspacePreservation": acceptance.workspace_preservation, "elapsedMilliseconds": round((time.monotonic() - started) * 1000)})


if __name__ == "__main__":
    main()
