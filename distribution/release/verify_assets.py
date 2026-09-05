#!/usr/bin/env python3
"""Verify the exact control, sidecar, CLI schema, and module-knowledge release."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile


MAXIMUM_COMBINED_BYTES = 80 * 1024 * 1024


class ReleaseRejected(Exception):
    pass


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--release")
    parser.add_argument("--source-revision")
    parser.add_argument("--source-root", type=Path)
    parser.add_argument("--repository", default="amichne/kast")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--negative-report", type=Path)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def reject(message: str) -> None:
    raise ReleaseRejected(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def safe_name(name: str) -> bool:
    path = PurePosixPath(name)
    return not path.is_absolute() and ".." not in path.parts


def verify_checksum(payload: Path) -> None:
    checksum = payload.with_name(payload.name + ".sha256")
    if checksum.read_text(encoding="utf-8").split() != [sha256(payload), payload.name]:
        reject(f"checksum does not exactly identify {payload.name}")


def control_manifest(control: Path) -> dict[str, object]:
    with tarfile.open(control, "r:gz") as archive:
        members = archive.getmembers()
        if any(not safe_name(member.name) for member in members):
            reject("control archive contains an unsafe path")
        if any(member.issym() or member.islnk() for member in members):
            reject("control archive contains a link")
        names = [member.name for member in members]
        if names.count("bin/kast") != 1 or archive.getmember("bin/kast").mode & 0o111 == 0:
            reject("control archive must contain one executable bin/kast")
        forbidden = ("kast-indexer", "idea-home", "private-plugins", "plugins/Kotlin")
        if any(any(token in name for token in forbidden) for name in names):
            reject("control archive contains sidecar or IDEA payload")
        try:
            source = archive.extractfile("share/kast/semantic-runtime.json")
        except KeyError:
            source = None
        if source is None:
            reject("control archive has no semantic runtime manifest")
        return json.loads(source.read())


def verify_sidecar(sidecar: Path) -> None:
    with zipfile.ZipFile(sidecar) as archive:
        names = archive.namelist()
        if any(not safe_name(name) for name in names):
            reject("sidecar archive contains an unsafe path")
        if "kast-indexer" not in names:
            reject("sidecar archive has no launcher")
        if not any(name.startswith("runtime-libs/") for name in names):
            reject("sidecar archive has no launcher runtime")
        if not any(name.startswith("private-plugins/kast-indexer/lib/") for name in names):
            reject("sidecar archive has no private Kast extension")
        forbidden = ("idea-home", "product-info.json", "plugins/Kotlin", "plugins/gradle")
        if any(any(token in name for token in forbidden) for name in names):
            reject("sidecar archive contains an IDEA distribution")
        if any(name.startswith("kast-indexer/lib/") for name in names):
            reject("sidecar archive exposes a public plugin root")


def schema_emitted_by_control(control: Path) -> bytes:
    with tempfile.TemporaryDirectory() as raw:
        root = Path(raw)
        home = root / "home"
        home.mkdir()
        with tarfile.open(control, "r:gz") as archive:
            archive.extractall(root, filter="data")
        executable = root / "bin/kast"
        environment = os.environ.copy()
        environment["HOME"] = str(home)
        environment["JAVA_OPTS"] = f"-Duser.home={home}"
        try:
            process = subprocess.run(
                [executable, "--schema"],
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=60,
                env=environment,
            )
        except (OSError, subprocess.TimeoutExpired) as failure:
            reject(f"control schema execution failed: {failure}")
        if process.returncode != 0:
            reject(
                "control --schema failed: "
                + process.stderr.decode("utf-8", errors="replace").strip()
            )
        try:
            document = json.loads(process.stdout)
        except (UnicodeDecodeError, json.JSONDecodeError) as failure:
            reject(f"control --schema did not emit JSON: {failure}")
        if not isinstance(document, dict) or document.get("schemaVersion") != 1:
            reject("control --schema did not emit the canonical schema document")
        return process.stdout


def verify_module_knowledge(
    knowledge: Path,
    canonical_knowledge: Path,
    architecture_report: Path,
    version: str,
    source_revision: str,
) -> None:
    try:
        artifact = knowledge.read_bytes()
        canonical = canonical_knowledge.read_bytes()
    except OSError as failure:
        reject(f"module knowledge authority could not be read: {failure}")
    if artifact != canonical:
        reject("module knowledge does not exactly match checked-out source authority")
    try:
        document = json.loads(canonical)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        reject(f"canonical module knowledge is not JSON: {failure}")
    if not isinstance(document, dict):
        reject("canonical module knowledge must be an object")
    if document.get("schemaVersion") != 1:
        reject("canonical module knowledge schema version is not supported")
    if document.get("productVersion") != version:
        reject("canonical module knowledge product version does not match release")
    if document.get("sourceRevision") != source_revision:
        reject("canonical module knowledge source revision does not match release commit")
    verification = document.get("architectureVerification")
    if not isinstance(verification, dict):
        reject("canonical module knowledge has no architecture verification")
    expected_report_digest = f"sha256:{sha256(architecture_report)}"
    if verification.get("reportSha256") != expected_report_digest:
        reject("module knowledge digest does not match the actual architecture report")


@dataclass(frozen=True)
class CanonicalModuleKnowledge:
    knowledge: Path
    architecture_report: Path


def regenerate_module_knowledge(
    source_root_candidate: Path,
    version: str,
    source_revision: str,
) -> CanonicalModuleKnowledge:
    try:
        source_root = source_root_candidate.resolve(strict=True)
    except OSError as failure:
        reject(f"source root is unavailable: {failure}")
    gradlew = source_root / "gradlew"
    source_admission = source_root / ".github/scripts/release/admit-source.sh"
    if (
        not source_root.is_dir()
        or not gradlew.is_file()
        or gradlew.is_symlink()
        or not source_admission.is_file()
        or source_admission.is_symlink()
    ):
        reject("source root has no admitted release tooling")
    identity = subprocess.run(
        [
            str(source_admission),
            "--repository-root",
            str(source_root),
            "--expected-source-revision",
            source_revision,
        ],
        cwd=source_root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if identity.returncode != 0 or identity.stdout.strip() != source_revision:
        reject("checked-out source is not the clean release commit")
    knowledge = source_root / "build/reports/kast-architecture/kast-module-knowledge.json"
    architecture_report = (
        source_root
        / "build/reports/kast-architecture/verifyKastArchitecture.json"
    )
    if knowledge.is_symlink() or architecture_report.is_symlink():
        reject("canonical module-knowledge outputs must not be symbolic links")
    generated = subprocess.run(
        [
            str(gradlew),
            "-Dorg.gradle.jvmargs=-Xmx5g",
            f"-Pversion={version}",
            f"-PkastSourceRevision={source_revision}",
            "--rerun-tasks",
            "generateKastModuleKnowledge",
        ],
        cwd=source_root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if generated.returncode != 0:
        reject(
            "canonical module knowledge regeneration failed: "
            + generated.stderr.strip()
        )
    for output in (knowledge, architecture_report):
        if output.is_symlink() or not output.is_file():
            reject(f"canonical module-knowledge output is unavailable: {output}")
    return CanonicalModuleKnowledge(knowledge, architecture_report)


def verify(
    directory: Path,
    release: str,
    repository: str,
    source_revision: str,
    authority: CanonicalModuleKnowledge,
    report: Path | None,
) -> dict[str, object]:
    match = re.fullmatch(r"v(\d+\.\d+\.\d+)", release)
    if match is None:
        reject("release must be v<major>.<minor>.<patch>")
    if re.fullmatch(r"[0-9a-f]{40}", source_revision) is None:
        reject("source revision must be one full Git identity")
    version = match.group(1)
    control_name = f"kast-control-v{version}-macos-aarch64.tar.gz"
    sidecar_name = f"kast-semantic-runtime-{version}-macos-aarch64.zip"
    schema_name = f"kast-cli-schema-v{version}.json"
    knowledge_name = f"kast-module-knowledge-v{version}.json"
    expected = {
        control_name,
        control_name + ".sha256",
        sidecar_name,
        sidecar_name + ".sha256",
        schema_name,
        schema_name + ".sha256",
        knowledge_name,
        knowledge_name + ".sha256",
    }
    observed = {path.name for path in directory.iterdir() if path.is_file()}
    for additional_name in (f"kast-release-receipt-v{version}.json", f"kast-sbom-v{version}.cdx.json"):
        if additional_name in observed or additional_name + ".sha256" in observed:
            expected.update({additional_name, additional_name + ".sha256"})
            verify_checksum(directory / additional_name)
    if observed != expected:
        reject(f"asset set mismatch: expected {sorted(expected)}, observed {sorted(observed)}")
    control = directory / control_name
    sidecar = directory / sidecar_name
    schema = directory / schema_name
    knowledge = directory / knowledge_name
    combined = (
        control.stat().st_size
        + sidecar.stat().st_size
        + schema.stat().st_size
        + knowledge.stat().st_size
    )
    if combined > MAXIMUM_COMBINED_BYTES:
        reject(f"combined payload exceeds 80 MiB: {combined}")
    verify_checksum(control)
    verify_checksum(sidecar)
    verify_checksum(schema)
    verify_checksum(knowledge)
    manifest = control_manifest(control)
    archive = manifest.get("archive")
    if not isinstance(archive, dict):
        reject("runtime manifest archive must be an object")
    expected_url = (
        f"https://github.com/{repository}/releases/download/{release}/{sidecar_name}"
    )
    if manifest.get("productVersion") != version:
        reject("control manifest product version does not match release")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(manifest.get("runtimeId"))):
        reject("control manifest runtime identity is not content-addressed")
    if archive.get("fileName") != sidecar_name or archive.get("url") != expected_url:
        reject("control manifest does not identify the private sidecar")
    if archive.get("sha256") != f"sha256:{sha256(sidecar)}":
        reject("control manifest sidecar digest does not match")
    if archive.get("bytes") != sidecar.stat().st_size:
        reject("control manifest sidecar size does not match")
    layout = manifest.get("layout")
    if not isinstance(layout, dict):
        reject("runtime manifest layout must be an object")
    required = layout.get("requiredEntries")
    if required != ["kast-indexer", "runtime-libs/", "private-plugins/kast-indexer/"]:
        reject("runtime manifest does not require the plugin-free sidecar layout")
    verify_sidecar(sidecar)
    if schema.read_bytes() != schema_emitted_by_control(control):
        reject("published CLI schema does not exactly match control --schema")
    verify_module_knowledge(
        knowledge,
        authority.knowledge,
        authority.architecture_report,
        version,
        source_revision,
    )
    document: dict[str, object] = {
        "schemaVersion": 1,
        "taskId": "SIDECAR-RELEASE",
        "outcome": "COMPLETE",
        "release": release,
        "assets": [
            {
                "kind": "CONTROL",
                "name": control.name,
                "bytes": control.stat().st_size,
                "sha256": sha256(control),
            },
            {
                "kind": "PRIVATE_SIDECAR",
                "name": sidecar.name,
                "bytes": sidecar.stat().st_size,
                "sha256": sha256(sidecar),
            },
            {
                "kind": "CLI_SCHEMA",
                "name": schema.name,
                "bytes": schema.stat().st_size,
                "sha256": sha256(schema),
            },
            {
                "kind": "MODULE_KNOWLEDGE",
                "name": knowledge.name,
                "bytes": knowledge.stat().st_size,
                "sha256": sha256(knowledge),
            },
        ],
        "combinedBytes": combined,
        "maximumCombinedBytes": MAXIMUM_COMBINED_BYTES,
    }
    if report is not None:
        report.parent.mkdir(parents=True, exist_ok=True)
        temporary = report.with_suffix(report.suffix + ".tmp")
        temporary.write_text(
            json.dumps(document, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        temporary.replace(report)
    return document


def write_checksum(path: Path) -> None:
    path.with_name(path.name + ".sha256").write_text(
        f"{sha256(path)}  {path.name}\n",
        encoding="utf-8",
    )


def synthetic_release(
    directory: Path,
    version: str,
    architecture_report: Path,
) -> None:
    schema = directory / f"kast-cli-schema-v{version}.json"
    schema_bytes = (
        b'{"schemaVersion":1,"operationRegistry":{},"wireSchema":{},'
        b'"cliProjection":{},"serverProjection":{}}\n'
    )
    schema.write_bytes(schema_bytes)
    guide_content = "# Synthetic repository guidance\n"
    module_knowledge = directory / f"kast-module-knowledge-v{version}.json"
    module_knowledge.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "productVersion": version,
                "sourceRevision": "a" * 40,
                "architectureVerification": {
                    "schemaVersion": 1,
                    "taskPath": ":verifyKastArchitecture",
                    "status": "ACCEPTED",
                    "findings": [],
                    "reportSha256": f"sha256:{sha256(architecture_report)}",
                },
                "architecturePolicy": {
                    "schemaVersion": 2,
                    "modules": [
                        {"projectPath": ":kernel", "allowedProjectDependencies": []}
                    ],
                },
                "observedProjectDependencies": [],
                "observedExportedProjectDependencies": [],
                "agentGuides": [
                    {
                        "path": "AGENTS.md",
                        "scopeDirectory": ".",
                        "sha256": "sha256:"
                        + hashlib.sha256(guide_content.encode()).hexdigest(),
                        "content": guide_content,
                    }
                ],
                "moduleGuideBindings": [
                    {
                        "projectPath": ":kernel",
                        "moduleDirectory": "kernel",
                        "governingAgentGuidePaths": ["AGENTS.md"],
                        "descendantAgentGuidePaths": [],
                    }
                ],
            },
            separators=(",", ":"),
        )
        + "\n",
        encoding="utf-8",
    )
    sidecar = directory / f"kast-semantic-runtime-{version}-macos-aarch64.zip"
    with zipfile.ZipFile(sidecar, "w") as archive:
        archive.writestr("kast-indexer", b"#!/bin/sh\n")
        archive.writestr("runtime-libs/launcher.jar", b"launcher")
        archive.writestr("private-plugins/kast-indexer/lib/indexer-plugin.jar", b"plugin")
    sidecar_digest = sha256(sidecar)
    manifest = {
        "schemaVersion": 1,
        "runtimeId": "sha256:" + "a" * 64,
        "productVersion": version,
        "archive": {
            "fileName": sidecar.name,
            "url": f"https://github.com/amichne/kast/releases/download/v{version}/{sidecar.name}",
            "sha256": f"sha256:{sidecar_digest}",
            "bytes": sidecar.stat().st_size,
        },
        "layout": {
            "executable": "kast-indexer",
            "requiredEntries": [
                "kast-indexer",
                "runtime-libs/",
                "private-plugins/kast-indexer/",
            ],
            "executableEntries": ["kast-indexer"],
        },
    }
    control = directory / f"kast-control-v{version}-macos-aarch64.tar.gz"
    with tarfile.open(control, "w:gz") as archive:
        for name, content, mode in (
            (
                "bin/kast",
                b"#!/bin/sh\nprintf '%s\\n' '{\"schemaVersion\":1,"
                b"\"operationRegistry\":{},\"wireSchema\":{},"
                b"\"cliProjection\":{},\"serverProjection\":{}}'\n",
                0o755,
            ),
            (
                "share/kast/semantic-runtime.json",
                json.dumps(manifest, separators=(",", ":")).encode(),
                0o644,
            ),
        ):
            member = tarfile.TarInfo(name)
            member.mode = mode
            member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
    write_checksum(control)
    write_checksum(sidecar)
    write_checksum(schema)
    write_checksum(module_knowledge)


def expect_rejected(
    source: Path,
    authority: CanonicalModuleKnowledge,
    mutate,
) -> None:
    with tempfile.TemporaryDirectory() as raw:
        fixture = Path(raw)
        shutil.copytree(source, fixture, dirs_exist_ok=True)
        mutate(fixture)
        try:
            verify(
                fixture,
                "v1.2.3",
                "amichne/kast",
                "a" * 40,
                authority,
                None,
            )
        except ReleaseRejected:
            return
        reject("negative fixture was admitted")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as raw:
        root = Path(raw)
        valid = root / "assets"
        valid.mkdir()
        architecture_report = root / "verifyKastArchitecture.json"
        architecture_report.write_text(
            '{"schemaVersion":1,"status":"ACCEPTED","findings":[]}\n',
            encoding="utf-8",
        )
        synthetic_release(valid, "1.2.3", architecture_report)
        canonical_knowledge = root / "canonical-module-knowledge.json"
        shutil.copy2(
            valid / "kast-module-knowledge-v1.2.3.json",
            canonical_knowledge,
        )
        authority = CanonicalModuleKnowledge(canonical_knowledge, architecture_report)
        verify(
            valid,
            "v1.2.3",
            "amichne/kast",
            "a" * 40,
            authority,
            None,
        )
        expect_rejected(
            valid,
            authority,
            lambda root: (root / "kast-ide-plugin-1.2.3.zip").write_bytes(b"public"),
        )

        def embed_idea(root: Path) -> None:
            sidecar = root / "kast-semantic-runtime-1.2.3-macos-aarch64.zip"
            with zipfile.ZipFile(sidecar, "a") as archive:
                archive.writestr("idea-home/product-info.json", b"{}")
            write_checksum(sidecar)

        expect_rejected(valid, authority, embed_idea)

        def mismatch_manifest(root: Path) -> None:
            control = root / "kast-control-v1.2.3-macos-aarch64.tar.gz"
            with tarfile.open(control, "w:gz") as archive:
                launcher = b"#!/bin/sh\n"
                member = tarfile.TarInfo("bin/kast")
                member.mode = 0o755
                member.size = len(launcher)
                archive.addfile(member, io.BytesIO(launcher))
                manifest = b'{"archive":{}}'
                member = tarfile.TarInfo("share/kast/semantic-runtime.json")
                member.size = len(manifest)
                archive.addfile(member, io.BytesIO(manifest))
            write_checksum(control)

        expect_rejected(valid, authority, mismatch_manifest)

        def mismatch_schema(root: Path) -> None:
            schema = root / "kast-cli-schema-v1.2.3.json"
            schema.write_text('{"schemaVersion":1}\n', encoding="utf-8")
            write_checksum(schema)

        expect_rejected(valid, authority, mismatch_schema)

        def unsafe_sidecar(root: Path) -> None:
            sidecar = root / "kast-semantic-runtime-1.2.3-macos-aarch64.zip"
            with zipfile.ZipFile(sidecar, "a") as archive:
                archive.writestr("../escape", b"x")
            write_checksum(sidecar)

        expect_rejected(valid, authority, unsafe_sidecar)

        def exceed_size(root: Path) -> None:
            sidecar = root / "kast-semantic-runtime-1.2.3-macos-aarch64.zip"
            with sidecar.open("ab") as output:
                output.truncate(MAXIMUM_COMBINED_BYTES + 1)
            write_checksum(sidecar)

        expect_rejected(valid, authority, exceed_size)

        def corrupt_knowledge(root: Path) -> None:
            knowledge = root / "kast-module-knowledge-v1.2.3.json"
            document = json.loads(knowledge.read_text(encoding="utf-8"))
            document["agentGuides"][0]["content"] = "# Corrupted guidance\n"
            knowledge.write_text(
                json.dumps(document, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            write_checksum(knowledge)

        expect_rejected(valid, authority, corrupt_knowledge)
    print("Rejected all 7 release asset misuses")


def write_negative_report(path: Path) -> None:
    document = {
        "schemaVersion": 1,
        "taskId": "SIDECAR-RELEASE",
        "outcome": "REJECTED",
        "rejectedFixtureCount": 7,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(document, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def main() -> None:
    args = arguments()
    if args.self_test:
        self_test()
        if args.negative_report is not None:
            write_negative_report(args.negative_report)
        return
    if (
        args.directory is None
        or args.release is None
        or args.source_revision is None
        or args.source_root is None
    ):
        reject("--directory, --release, --source-revision, and --source-root are required")
    release_match = re.fullmatch(r"v(\d+\.\d+\.\d+)", args.release)
    if release_match is None:
        reject("release must be v<major>.<minor>.<patch>")
    if re.fullmatch(r"[0-9a-f]{40}", args.source_revision) is None:
        reject("source revision must be one full Git identity")
    authority = regenerate_module_knowledge(
        args.source_root,
        release_match.group(1),
        args.source_revision,
    )
    print(
        json.dumps(
            verify(
                args.directory.resolve(),
                args.release,
                args.repository,
                args.source_revision,
                authority,
                args.report,
            ),
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    try:
        main()
    except ReleaseRejected as failure:
        raise SystemExit(f"release-assets: {failure}")
