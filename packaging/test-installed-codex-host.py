#!/usr/bin/env python3
"""Exercise the staged Kast App Server façade against an installed Codex CLI."""

from __future__ import annotations

import json
from dataclasses import asdict
from installed_codex_lifecycle import AcceptanceFailure, qualify_installed_lifecycle
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
from acceptance_environment import AcceptanceEnvironment, admitted_tools
from acceptance_idea import digest
from hosted_acceptance_fixture import admit_hosted_idea
from released_acceptance_product import admit_release_assets, install_release, product_executable, ReleaseRejected
import zipfile


def canonical_json(document: object) -> bytes:
    return json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode()


def sha256_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def codex_protocol_digest(root: Path) -> str:
    digest = hashlib.sha256()
    paths = sorted(path for path in root.rglob("*.json") if path.is_file())
    if not paths:
        raise AcceptanceFailure("installed Codex generated no protocol schemas")
    for path in paths:
        relative = path.relative_to(root).as_posix().encode()
        digest.update(relative)
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return "sha256:" + digest.hexdigest()


def installed_catalog_evidence(kast: Path) -> dict:
    catalog = kast.parent.parent / "share/kast/provider-catalog.json"
    if (catalog.resolve() != catalog or not catalog.is_file() or catalog.is_symlink()
            or not 0 < catalog.stat().st_size <= 4 * 1024 * 1024):
        raise AcceptanceFailure("installed Kast catalog was unavailable")
    try:
        contract = json.loads(catalog.read_text())
        bootstrap = contract["serverProjection"]["hostedBootstrap"]
        policy = bootstrap["policy"]
        tools = bootstrap["tools"]
        # Read the installed generation projection, whose constraints intentionally
        # differ from the full runtime validation schema.
        projections = []
        for jar in (kast.parent.parent / "lib").glob("*.jar"):
            with zipfile.ZipFile(jar) as archive:
                resource = "io/github/amichne/kast/appserver/query/tools.app-server.json"
                if resource in archive.namelist():
                    projections.append(json.loads(archive.read(resource)))
        if len(projections) != 1:
            raise AcceptanceFailure("installed generation projection was missing or ambiguous")
        generation = {tool["name"]: tool["inputSchema"] for tool in projections[0]["tools"]}
        namespace = {
            "type": "namespace",
            "name": "kast",
            "description": "Compiler-grounded Kotlin source intelligence from Kast.",
            "tools": [
                {
                    "type": "function",
                    "name": tool["name"],
                    "description": tool["description"],
                    "inputSchema": generation.get(tool["name"], tool["inputSchema"]),
                    "deferLoading": tool["deferLoading"],
                }
                for tool in tools
            ],
        }
    except (KeyError, TypeError, json.JSONDecodeError) as failure:
        raise AcceptanceFailure("installed Kast catalog projection was invalid") from failure
    projection = {"developerInstructions": policy, "namespace": namespace}
    return {
        "kastContractSha256": sha256_bytes(canonical_json(contract)),
        "catalogProjectionSha256": sha256_bytes(canonical_json(projection)),
        "catalogToolNames": [tool["name"] for tool in tools],
    }


def executable(candidate: str, name: str) -> Path:
    # Preserve the installed launcher directory: a Node shim may need its sibling node.
    path = Path(candidate)
    if not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK):
        raise AcceptanceFailure(f"installed {name} executable is unavailable")
    return path


def prepare_installed_product(isolation, source: Path, project: Path, control: Path, plugin: Path,
                              idea_home: Path) -> Path:
    if any(not path.is_absolute() or not path.is_file() or path.is_symlink()
           or path.resolve() != path for path in (control, plugin)):
        raise AcceptanceFailure('assembled artifact identity rejected')
    metadata = json.loads((source / 'share/kast/ide-host.json').read_text())
    version = metadata['productVersion']
    original_hashes = {path: digest(path) for path in (control, plugin)}
    catalog_hash = sha256_file(source / 'share/kast/provider-catalog.json')
    assets = isolation.root / 'input-assets'
    assets.mkdir(mode=0o700)
    for original in (control, plugin):
        copied = assets / original.name
        shutil.copyfile(original, copied)
        if digest(copied) != original_hashes[original]:
            raise AcceptanceFailure('assembled artifact changed during staging')
        copied.with_name(copied.name + '.sha256').write_text(f'{digest(copied)}  {copied.name}\n')
    idea = admit_hosted_idea(idea_home, project / 'gradle/libs.versions.toml')
    admitted = admit_release_assets(assets, version, idea, project / 'install.sh', 'a' * 40)
    installed = install_release(isolation, admitted, idea)
    product = Path(installed.product)
    if (sha256_file(product / 'share/kast/provider-catalog.json') != catalog_hash
            or sha256_file(source / 'share/kast/provider-catalog.json') != catalog_hash
            or any(digest(path) != expected for path, expected in original_hashes.items())):
        raise AcceptanceFailure('installed catalog differs from assembled product')
    return product


def main() -> int:
    if len(sys.argv) != 6:
        raise AcceptanceFailure(
            "expected staged-product, project-root, report-file, control archive, and plugin archive"
        )
    product = Path(sys.argv[1]).resolve()
    project = Path(sys.argv[2]).resolve()
    report = Path(sys.argv[3]).resolve()
    control = Path(sys.argv[4]).resolve()
    plugin = Path(sys.argv[5]).resolve()
    idea_value = os.environ.get('KAST_ACCEPTANCE_IDEA_HOME')
    if not idea_value:
        raise AcceptanceFailure('KAST_ACCEPTANCE_IDEA_HOME is required for installed runtime qualification')
    idea_home = Path(idea_value)
    codex_value = os.environ.get('KAST_ACCEPTANCE_CODEX_EXECUTABLE')
    if not codex_value:
        raise AcceptanceFailure('KAST_ACCEPTANCE_CODEX_EXECUTABLE is required for installed runtime qualification')
    codex = executable(codex_value, "codex")
    tools = admitted_tools()
    tools["codex"] = codex
    with AcceptanceEnvironment(tools) as isolation:
        product = prepare_installed_product(isolation, product, project, control, plugin, idea_home)
        facade = executable(str(product / "bin/kast-codex-complete"), "kast-codex-complete")
        kast = product_executable(product, isolation.root)
        home = Path(isolation.environment["HOME"])
        project = isolation.root / "workspace"
        (project / "settings.gradle.kts").write_text('rootProject.name = "installed-host-fixture"\n')
        environment = dict(isolation.environment)
        environment.update({
            "KAST_REAL_CODEX_EXECUTABLE": str(codex),
            "CODEX_EXECUTABLE": str(codex),
            "KAST_APP_SERVER_PUBLIC_ENDPOINT": "private",
        })
        version = subprocess.run(
            [str(codex), "--version"], check=True, capture_output=True,
            text=True, timeout=10, env=environment, cwd=project,
        ).stdout.strip()
        expected_version = os.environ.get("KAST_CODEX_ACCEPTANCE_VERSION")
        if expected_version is not None and version != f"codex-cli {expected_version}":
            raise AcceptanceFailure("installed Codex version did not match the admitted authority")
        catalog_evidence = installed_catalog_evidence(kast)
        schemas = home / "generated-codex-schema"
        subprocess.run(
            [
                str(codex),
                "app-server",
                "generate-json-schema",
                "--experimental",
                "--out",
                str(schemas),
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
            env=environment,
        )
        protocol_digest = codex_protocol_digest(schemas)
        lifecycle = qualify_installed_lifecycle(isolation, kast, facade, environment, home, project, product)

        document = {
            "schemaVersion": 2,
            "taskId": "HOST-08",
            "outcome": "COMPLETE",
            "facadeRole": "app-server-stdio",
            "desktopStartupArguments": "VALIDATED",
            "codexVersion": version,
            "initialize": "VALIDATED",
            "threadStart": "VALIDATED",
            "parentClosure": "CLEAN",
            "persistentServiceAfterDetach": "VALIDATED",
            "desktopCompatibility": "UNQUALIFIED",
            "desktopDiscovery": "UNQUALIFIED",
            "stdoutProtocol": "JSONL_ONLY",
            "codexProtocolSha256": protocol_digest,
            "canonicalService": asdict(lifecycle),
            "codexExecutableSha256": sha256_file(codex),
            "kastExecutableSha256": sha256_file(kast),
            "kastFacadeSha256": sha256_file(facade),
            **catalog_evidence,
        }
        report.parent.mkdir(parents=True, exist_ok=True)
        temporary_report = report.with_suffix(report.suffix + ".tmp")
        temporary_report.write_text(
            json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n"
        )
        temporary_report.replace(report)
        print("installed-codex-host: canonical discovery and real Codex stdio handshake passed")
        isolation.mark_passed()
        return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AcceptanceFailure, ReleaseRejected, OSError, ValueError, KeyError,
            TypeError, subprocess.SubprocessError) as failure:
        print(f"installed-codex-host: {failure}", file=sys.stderr)
        raise SystemExit(1)
