#!/usr/bin/env python3
"""The finite release gate: exact source, exact installed assets, one receipt."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tomllib


class GateRejected(Exception):
    """An absent, failed, or mismatched release authority."""


def digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise GateRejected(f"proof must be a regular file: {path.name}")
    with path.open("rb") as stream:
        return "sha256:" + hashlib.file_digest(stream, "sha256").hexdigest()


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def identity(value: object) -> str:
    return "sha256:" + hashlib.sha256(canonical(value)).hexdigest()


def write(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(canonical(value) + b"\n")
    temporary.replace(path)


def read(path: Path) -> dict:
    digest(path)
    value = json.loads(path.read_bytes())
    if not isinstance(value, dict):
        raise GateRejected(f"proof must be an object: {path.name}")
    return value


def admit_source(root: Path, sha: str) -> None:
    result = subprocess.run([str(root / ".github/scripts/release/admit-source.sh"), "--repository-root", str(root), "--expected-source-revision", sha], cwd=root, text=True, capture_output=True)
    if result.returncode or result.stdout.strip() != sha:
        raise GateRejected("release source must be the exact clean checkout")
    if subprocess.run(["git", "symbolic-ref", "-q", "HEAD"], cwd=root, capture_output=True).returncode == 0:
        raise GateRejected("release gate requires a detached checkout")


def source_command(version: str, sha: str) -> list[str]:
    return ["./gradlew", "--no-daemon", "-Dorg.gradle.jvmargs=-Xmx5g", f"-Pversion={version}", f"-PkastSourceRevision={sha}", "clean", "releaseSourceGate", "assembleSidecarRelease", "generateKastModuleKnowledge"]


def run(command: list[str], root: Path, environment: dict[str, str]) -> None:
    result = subprocess.run(command, cwd=root, env=environment, check=False)
    if result.returncode:
        raise GateRejected(f"required predecessor failed with exit {result.returncode}: {command[0]}")


def prepare_idea(root: Path, environment: dict[str, str]) -> Path:
    configured = environment.get("KAST_ACCEPTANCE_IDEA_HOME")
    if configured:
        idea = Path(configured).resolve(strict=True)
    else:
        installed = Path.home() / "Applications/IntelliJ IDEA.app/Contents"
        if installed.is_dir():
            idea = installed.resolve(strict=True)
        else:
            run(["./gradlew", "--no-daemon", ":indexer:extractIdeaDistribution"], root, environment)
            version = tomllib.loads((root / "gradle/libs.versions.toml").read_text())["versions"]["idea-indexer"]
            gradle_home = Path(environment.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
            platform_home = (gradle_home / "kast/indexer-idea-distributions" / version).resolve(strict=True)
            idea = root / ".gradle/release-idea-home"
            idea.mkdir(parents=True, exist_ok=True)
            for child in platform_home.iterdir():
                target = idea / child.name
                if not target.exists() and not target.is_symlink():
                    target.symlink_to(child)
            resources = idea / "Resources"
            resources.mkdir(exist_ok=True)
            if not (resources / "build.txt").exists():
                (resources / "build.txt").symlink_to(platform_home / "build.txt")
            jbr = idea / "jbr/Contents"
            jbr.mkdir(parents=True, exist_ok=True)
            java = Path(environment["JAVA_HOME"]).resolve(strict=True)
            if not (jbr / "Home").exists():
                (jbr / "Home").symlink_to(java)
    if not (idea / "plugins/Kotlin").is_dir() or not (idea / "jbr/Contents/Home/bin/java").is_file():
        raise GateRejected("acceptance requires a complete supported IDEA and Java runtime")
    environment["KAST_ACCEPTANCE_IDEA_HOME"] = str(idea)
    return idea


def environment_identity(idea: Path) -> dict:
    build = idea / "Resources/build.txt"
    if not build.exists():
        build = idea / "build.txt"
    java = (idea / "jbr/Contents/Home/release").resolve(strict=True)
    return {"system": platform.system(), "architecture": platform.machine(), "osRelease": platform.release(), "ideaBuild": build.read_text().strip(), "javaReleaseDigest": digest(java)}


def asset_names(version: str) -> tuple[str, ...]:
    return (f"kast-control-v{version}-macos-aarch64.tar.gz", f"kast-semantic-runtime-{version}-macos-aarch64.zip", f"kast-cli-schema-v{version}.json", f"kast-module-knowledge-v{version}.json")


def asset_identities(directory: Path, version: str) -> dict[str, str]:
    return {name: digest(directory / name) for asset in asset_names(version) for name in (asset, asset + ".sha256")}


def validate_receipt(receipt: dict, directory: Path, version: str, sha: str) -> None:
    if set(receipt) != {"schemaVersion", "status", "sourceRevision", "productVersion", "commandDigest", "dependencies", "environment", "assets"}:
        raise GateRejected("release receipt has an unsupported closed shape")
    if receipt["schemaVersion"] != 1 or receipt["status"] != "passed" or receipt["sourceRevision"] != sha or receipt["productVersion"] != version:
        raise GateRejected("release receipt does not prove this exact source and version")
    if receipt["commandDigest"] != identity(source_command(version, sha)):
        raise GateRejected("release receipt does not prove the canonical gate command")
    dependencies = receipt["dependencies"]
    if not isinstance(dependencies, dict) or set(dependencies) != {"source", "assets", "installed"}:
        raise GateRejected("release receipt omits a required predecessor")
    for name, dependency in dependencies.items():
        if not isinstance(dependency, dict) or set(dependency) != {"digest", "receipt"} or identity(dependency["receipt"]) != dependency["digest"]:
            raise GateRejected(f"dependency receipt digest changed: {name}")
        proof = dependency["receipt"]
        if proof.get("sourceRevision") != sha or proof.get("status") != "passed":
            raise GateRejected(f"dependency does not prove this source: {name}")
    if dependencies["source"]["receipt"].get("command") != source_command(version, sha):
        raise GateRejected("source predecessor did not run the required Gradle graph")
    installed = dependencies["installed"]["receipt"]
    required = {"cli-without-codex", "semantic-continuity", "verified-mutation", "uninstall-reinstall"}
    if set(installed.get("journeys", [])) != required:
        raise GateRejected("installed predecessor omits a required journey")
    if receipt["environment"].get("system") != "Darwin" or receipt["environment"].get("architecture") not in {"arm64", "aarch64"}:
        raise GateRejected("receipt does not prove the supported host")
    if dependencies["source"]["receipt"].get("environment") != receipt["environment"] or installed.get("environment") != receipt["environment"]:
        raise GateRejected("source and installed gates observed different environments")
    assets = asset_identities(directory, version)
    if receipt["assets"] != assets or installed.get("assets") != assets:
        raise GateRejected("release assets differ from installed proof")
    archive_proof = dependencies["assets"]["receipt"].get("observations", {})
    if archive_proof.get("outcome") != "COMPLETE" or archive_proof.get("release") != f"v{version}":
        raise GateRejected("archive predecessor has no complete verification")
    observed_assets = {item["name"]: "sha256:" + item["sha256"] for item in archive_proof.get("assets", [])}
    if observed_assets != {name: assets[name] for name in asset_names(version)}:
        raise GateRejected("archive predecessor did not verify these assets")


def execute(mode: str, root: Path, directory: Path, version: str, sha: str) -> None:
    admit_source(root, sha)
    reports = root / "build/reports/release-gate"
    receipt_path = directory / f"kast-release-receipt-v{version}.json"
    if mode == "source":
        receipt_path.unlink(missing_ok=True)
        environment = os.environ.copy()
        idea = prepare_idea(root, environment)
        command = source_command(version, sha)
        run(command, root, environment)
        admit_source(root, sha)
        write(reports / "source.json", {"schemaVersion": 1, "status": "passed", "sourceRevision": sha, "command": command, "commandDigest": identity(command), "environment": environment_identity(idea)})
    elif mode == "finish":
        source = read(reports / "source.json")
        assets = read(root / "build/reports/sidecar/release-assets.json")
        if assets.get("outcome") != "COMPLETE" or assets.get("release") != f"v{version}":
            raise GateRejected("release archive verification has no passing proof")
        # Keep the archive verifier's complete observations as one dependency.
        asset_receipt = {"status": "passed", "sourceRevision": sha, "observations": assets}
        installed = read(reports / "installed.json")
        dependencies = {key: {"digest": identity(value), "receipt": value} for key, value in {"source": source, "assets": asset_receipt, "installed": installed}.items()}
        receipt = {"schemaVersion": 1, "status": "passed", "sourceRevision": sha, "productVersion": version, "commandDigest": identity(source_command(version, sha)), "dependencies": dependencies, "environment": source["environment"], "assets": asset_identities(directory, version)}
        validate_receipt(receipt, directory, version, sha)
        write(receipt_path, receipt)
        receipt_path.with_suffix(".json.sha256").write_text(f"{digest(receipt_path).removeprefix('sha256:')}  {receipt_path.name}\n")
    else:
        validate_receipt(read(receipt_path), directory, version, sha)
    print(f"release-gate: {mode} passed for {sha}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("source", "finish", "verify"))
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--assets-directory", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version) or not re.fullmatch(r"[0-9a-f]{40}", args.source_revision):
        parser.error("version and source revision must be exact release identities")
    try:
        execute(args.mode, args.source_root.resolve(), args.assets_directory.resolve(), args.version, args.source_revision)
    except (GateRejected, OSError, ValueError, KeyError) as error:
        raise SystemExit(f"release-gate: rejected: {error}") from None


if __name__ == "__main__":
    main()
