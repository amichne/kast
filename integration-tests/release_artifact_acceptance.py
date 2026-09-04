#!/usr/bin/env python3
"""Install the exact candidate archives and exercise the public semantic CLI."""

from __future__ import annotations

import argparse
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

import enterprise_acceptance as enterprise

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("release_gate", ROOT / "distribution/release/release_gate.py")
assert SPEC and SPEC.loader
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class ObservedAcceptance(enterprise.Acceptance):
    def __init__(self, executable, host, bounds):
        super().__init__(executable, host, bounds)
        self.observations = []
        self.environment.pop("CODEX_HOME", None)
        self.environment.pop("CODEX_EXECUTABLE", None)
        self.environment["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
        self.environment["KAST_ENABLE_LAUNCHD"] = "0"
        if shutil.which("codex", path=self.environment["PATH"]) is not None:
            raise gate.GateRejected("CLI-only acceptance environment contains Codex")

    def command(self, *argv, **options):
        started = time.monotonic()
        document = super().command(*argv, **options)
        self.observations.append({"command": " ".join(argv[:2]), "status": document.get("status"), "elapsedMilliseconds": round((time.monotonic() - started) * 1000), "evidenceDigest": gate.identity(document)})
        return document


def install(host, assets, version, idea, environment):
    install_environment = {**environment, "KAST_INSTALL_ROOT": str(host.root / "installation"), "KAST_BIN_DIR": str(host.root / "bin"), "XDG_CONFIG_HOME": str(host.home / ".config"), "XDG_DATA_HOME": str(host.home / ".local/share"), "KAST_INSTALL_IDEA_HOME": str(idea)}
    gate.run(["/bin/bash", str(ROOT / "install.sh"), "install", "--version", version, "--assets-directory", str(assets)], host.workspace, install_environment)
    return install_environment


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
        acceptance = ObservedAcceptance(host.root / "bin/kast", host, bounds)
        installed_environment = install(host, assets, args.version, idea, acceptance.environment)
        # The launcher must use its retained installed archive, not a test override.
        acceptance.environment.pop("KAST_RUNTIME_ARCHIVE", None)
        retained = host.root / f"installation/versions/{args.version}/share/kast/runtime/{runtime.name}"
        if gate.digest(retained) != identities[runtime.name]:
            raise gate.GateRejected("installer did not retain the exact candidate sidecar")
        try:
            acceptance.prove_installed_surface(bounds)
            acceptance.prove_workspace_write_scope()
        finally:
            enterprise.stop_indexer(acceptance)
        if host.readiness_file.exists() or host.broker_socket.exists():
            raise gate.GateRejected("CLI-only semantic journey started the optional broker")
        workspace_before = enterprise.git(host.workspace, "diff")
        gate.run(["/bin/bash", str(ROOT / "install.sh"), "uninstall", "--installation-only"], host.workspace, installed_environment)
        for path in (host.root / "bin/kast", host.root / "installation", host.runtime / "store", host.runtime / "intellij-caches", host.runtime / "endpoints"):
            if path.exists() or path.is_symlink():
                raise gate.GateRejected("uninstall retained selected product state")
        if enterprise.git(host.workspace, "diff") != workspace_before:
            raise gate.GateRejected("uninstall changed repository contents")
        install(host, assets, args.version, idea, acceptance.environment)
        version = subprocess.check_output([str(acceptance.executable), "--version"], cwd=host.workspace, env=acceptance.environment, text=True).strip()
        if version != f"kast {args.version} (IntelliJ sidecar)":
            raise gate.GateRejected("reinstall selected a different product")
        applications = host.home / "Applications/IntelliJ IDEA.app"
        applications.mkdir(parents=True)
        (applications / "Contents").symlink_to(idea)
        request = host.root / "cold-broker-request.json"
        broker_report = host.root / "cold-broker-evidence.json"
        gate.write(request, {"kast": str(acceptance.executable), "workspace": str(host.workspace), "query": "enterpriseRootOperation"})
        broker_environment = {**acceptance.environment, "GRADLE_USER_HOME": str(Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))).resolve()), "JAVA_HOME": str((idea / "jbr/Contents/Home").resolve())}
        gate.run(["./gradlew", "--no-daemon", "--max-workers=2", f"-Pversion={args.version}", f"-PkastSourceRevision={args.source_revision}", f"-PkastBrokerAcceptanceRequest={request}", f"-PkastBrokerAcceptanceEvidence={broker_report}", ":cli:installedColdBrokerAcceptance"], ROOT, broker_environment)
        broker = gate.read(broker_report)
        if broker.get("status") != "passed" or not all(broker.get(key) is True for key in ("readOnlyCatalog", "cliEquivalent", "selectorReused")):
            raise gate.GateRejected("cold installed broker did not preserve canonical evidence")
        gate.run(["/bin/bash", str(ROOT / "install.sh"), "uninstall", "--installation-only"], host.workspace, installed_environment)
        ambient.assert_unchanged()
        if gate.asset_identities(assets, args.version) != identities:
            raise gate.GateRejected("candidate assets changed during installed acceptance")
        gate.write(ROOT / "build/reports/release-gate/installed.json", {"schemaVersion": 1, "status": "passed", "sourceRevision": args.source_revision, "assets": identities, "environment": gate.environment_identity(idea), "journeys": ["cli-without-codex", "semantic-continuity", "verified-mutation", "uninstall-reinstall", "cold-broker"], "broker": broker, "observations": acceptance.observations, "elapsedMilliseconds": round((time.monotonic() - started) * 1000)})


if __name__ == "__main__":
    main()
