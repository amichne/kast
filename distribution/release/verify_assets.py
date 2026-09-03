#!/usr/bin/env python3
"""Verify the exact control, sidecar, CLI schema, and module-knowledge release."""

from __future__ import annotations

import argparse
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


def require_object(value: object, context: str) -> dict[str, object]:
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
        reject(f"{context} must be an object")
    return value


def require_list(value: object, context: str) -> list[object]:
    if not isinstance(value, list):
        reject(f"{context} must be an array")
    return value


def exact_string_list(value: object, context: str) -> list[str]:
    items = require_list(value, context)
    if not all(isinstance(item, str) for item in items):
        reject(f"{context} must contain only strings")
    return items


def module_directory(project_path: str) -> str:
    if re.fullmatch(r":[a-z0-9][a-z0-9-]*(?::[a-z0-9][a-z0-9-]*)*", project_path) is None:
        reject(f"invalid architecture project path: {project_path}")
    return project_path.removeprefix(":").replace(":", "/")


def guide_scope(guide_path: str) -> tuple[str, tuple[str, ...]]:
    if "\\" in guide_path:
        reject(f"invalid agent guide path: {guide_path}")
    parsed = PurePosixPath(guide_path)
    if (
        parsed.is_absolute()
        or str(parsed) != guide_path
        or ".." in parsed.parts
        or parsed.name != "AGENTS.md"
    ):
        reject(f"invalid agent guide path: {guide_path}")
    segments = parsed.parts[:-1]
    return ("/".join(segments) if segments else ".", segments)


def starts_with(value: tuple[str, ...], prefix: tuple[str, ...]) -> bool:
    return len(value) >= len(prefix) and value[: len(prefix)] == prefix


def verify_module_knowledge(
    knowledge: Path,
    version: str,
    source_revision: str,
) -> None:
    try:
        document = require_object(json.loads(knowledge.read_bytes()), "module knowledge")
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        reject(f"module knowledge is not JSON: {failure}")
    if document.get("schemaVersion") != 1:
        reject("module knowledge schema version is not supported")
    if document.get("productVersion") != version:
        reject("module knowledge product version does not match release")
    if document.get("sourceRevision") != source_revision:
        reject("module knowledge source revision does not match release commit")

    verification = require_object(
        document.get("architectureVerification"),
        "architecture verification",
    )
    if (
        verification.get("schemaVersion") != 1
        or verification.get("taskPath") != ":verifyKastArchitecture"
        or verification.get("status") != "ACCEPTED"
        or verification.get("findings") != []
        or re.fullmatch(
            r"sha256:[0-9a-f]{64}",
            str(verification.get("reportSha256")),
        )
        is None
    ):
        reject("module knowledge does not preserve accepted architecture verification")

    policy = require_object(document.get("architecturePolicy"), "architecture policy")
    if policy.get("schemaVersion") != 2:
        reject("module knowledge architecture policy schema is not supported")
    modules = require_list(policy.get("modules"), "architecture policy modules")
    if not modules:
        reject("module knowledge architecture policy has no modules")
    project_paths: list[str] = []
    for raw_module in modules:
        module = require_object(raw_module, "architecture policy module")
        project_path = module.get("projectPath")
        if not isinstance(project_path, str):
            reject("architecture policy module has no project path")
        module_directory(project_path)
        project_paths.append(project_path)
    if len(set(project_paths)) != len(project_paths):
        reject("architecture policy module paths are not unique")
    known_projects = set(project_paths)
    allowed_by_project: dict[str, set[str]] = {}
    for raw_module in modules:
        module = require_object(raw_module, "architecture policy module")
        dependencies = exact_string_list(
            module.get("allowedProjectDependencies"),
            f"{module.get('projectPath')} allowed project dependencies",
        )
        if len(set(dependencies)) != len(dependencies) or not set(dependencies) <= known_projects:
            reject("architecture policy dependencies are duplicated or unresolved")
        allowed_by_project[str(module.get("projectPath"))] = set(dependencies)

    def admitted_dependencies(field: str) -> list[tuple[str, str]]:
        admitted: list[tuple[str, str]] = []
        for raw_dependency in require_list(document.get(field), field):
            dependency = require_object(raw_dependency, field.removesuffix("ies"))
            consumer = dependency.get("consumerProjectPath")
            target = dependency.get("dependencyProjectPath")
            if not isinstance(consumer, str) or not isinstance(target, str):
                reject(f"{field} endpoints must be strings")
            if consumer not in known_projects or target not in known_projects:
                reject(f"{field} contains an unknown module")
            if target not in allowed_by_project[consumer]:
                reject(f"{field} contains an unapproved dependency")
            admitted.append((consumer, target))
        if admitted != sorted(set(admitted)):
            reject(f"{field} must be unique and deterministically ordered")
        return admitted

    observed_dependencies = admitted_dependencies("observedProjectDependencies")
    observed_exports = admitted_dependencies("observedExportedProjectDependencies")
    if not set(observed_exports) <= set(observed_dependencies):
        reject("observed exported dependencies are not present in the admitted graph")

    guides = require_list(document.get("agentGuides"), "agent guides")
    if not guides:
        reject("module knowledge has no agent guides")
    guide_scopes: dict[str, tuple[str, ...]] = {}
    for raw_guide in guides:
        guide = require_object(raw_guide, "agent guide")
        guide_path = guide.get("path")
        content = guide.get("content")
        if not isinstance(guide_path, str) or not isinstance(content, str):
            reject("agent guide path and content must be strings")
        scope_directory, scope_segments = guide_scope(guide_path)
        if guide.get("scopeDirectory") != scope_directory:
            reject(f"agent guide scope does not match path: {guide_path}")
        expected_digest = "sha256:" + hashlib.sha256(content.encode()).hexdigest()
        if guide.get("sha256") != expected_digest:
            reject(f"agent guide digest does not match content: {guide_path}")
        if guide_path in guide_scopes:
            reject(f"duplicate agent guide path: {guide_path}")
        guide_scopes[guide_path] = scope_segments
    if "AGENTS.md" not in guide_scopes:
        reject("module knowledge has no root AGENTS.md authority")

    raw_bindings = require_list(document.get("moduleGuideBindings"), "module bindings")
    bindings: dict[str, dict[str, object]] = {}
    for raw_binding in raw_bindings:
        binding = require_object(raw_binding, "module binding")
        project_path = binding.get("projectPath")
        if not isinstance(project_path, str) or project_path in bindings:
            reject("module binding project paths must be unique strings")
        bindings[project_path] = binding
    if set(bindings) != known_projects:
        reject("module bindings do not exactly cover architecture policy modules")
    for project_path in project_paths:
        binding = bindings[project_path]
        expected_directory = module_directory(project_path)
        if binding.get("moduleDirectory") != expected_directory:
            reject(f"module binding directory does not match {project_path}")
        module_segments = tuple(expected_directory.split("/"))
        expected_governing = [
            guide_path
            for guide_path, scope in sorted(
                guide_scopes.items(),
                key=lambda item: (len(item[1]), item[0]),
            )
            if starts_with(module_segments, scope)
        ]
        expected_descendants = sorted(
            guide_path
            for guide_path, scope in guide_scopes.items()
            if len(scope) > len(module_segments) and starts_with(scope, module_segments)
        )
        if exact_string_list(
            binding.get("governingAgentGuidePaths"),
            f"{project_path} governing agent guide paths",
        ) != expected_governing:
            reject(f"governing agent guide paths do not match {project_path}")
        if exact_string_list(
            binding.get("descendantAgentGuidePaths"),
            f"{project_path} descendant agent guide paths",
        ) != expected_descendants:
            reject(f"descendant agent guide paths do not match {project_path}")


def verify(
    directory: Path,
    release: str,
    repository: str,
    source_revision: str,
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
    verify_module_knowledge(knowledge, version, source_revision)
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


def synthetic_release(directory: Path, version: str) -> None:
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
                    "reportSha256": "sha256:" + "b" * 64,
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


def expect_rejected(source: Path, mutate) -> None:
    with tempfile.TemporaryDirectory() as raw:
        fixture = Path(raw)
        shutil.copytree(source, fixture, dirs_exist_ok=True)
        mutate(fixture)
        try:
            verify(fixture, "v1.2.3", "amichne/kast", "a" * 40, None)
        except ReleaseRejected:
            return
        reject("negative fixture was admitted")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as raw:
        valid = Path(raw)
        synthetic_release(valid, "1.2.3")
        verify(valid, "v1.2.3", "amichne/kast", "a" * 40, None)
        expect_rejected(
            valid,
            lambda root: (root / "kast-ide-plugin-1.2.3.zip").write_bytes(b"public"),
        )

        def embed_idea(root: Path) -> None:
            sidecar = root / "kast-semantic-runtime-1.2.3-macos-aarch64.zip"
            with zipfile.ZipFile(sidecar, "a") as archive:
                archive.writestr("idea-home/product-info.json", b"{}")
            write_checksum(sidecar)

        expect_rejected(valid, embed_idea)

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

        expect_rejected(valid, mismatch_manifest)

        def mismatch_schema(root: Path) -> None:
            schema = root / "kast-cli-schema-v1.2.3.json"
            schema.write_text('{"schemaVersion":1}\n', encoding="utf-8")
            write_checksum(schema)

        expect_rejected(valid, mismatch_schema)

        def unsafe_sidecar(root: Path) -> None:
            sidecar = root / "kast-semantic-runtime-1.2.3-macos-aarch64.zip"
            with zipfile.ZipFile(sidecar, "a") as archive:
                archive.writestr("../escape", b"x")
            write_checksum(sidecar)

        expect_rejected(valid, unsafe_sidecar)

        def exceed_size(root: Path) -> None:
            sidecar = root / "kast-semantic-runtime-1.2.3-macos-aarch64.zip"
            with sidecar.open("ab") as output:
                output.truncate(MAXIMUM_COMBINED_BYTES + 1)
            write_checksum(sidecar)

        expect_rejected(valid, exceed_size)

        def corrupt_knowledge(root: Path) -> None:
            knowledge = root / "kast-module-knowledge-v1.2.3.json"
            document = json.loads(knowledge.read_text(encoding="utf-8"))
            document["agentGuides"][0]["content"] = "# Corrupted guidance\n"
            knowledge.write_text(
                json.dumps(document, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            write_checksum(knowledge)

        expect_rejected(valid, corrupt_knowledge)
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
    if args.directory is None or args.release is None or args.source_revision is None:
        reject("--directory, --release, and --source-revision are required")
    print(
        json.dumps(
            verify(
                args.directory.resolve(),
                args.release,
                args.repository,
                args.source_revision,
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
