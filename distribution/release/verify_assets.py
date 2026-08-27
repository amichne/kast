#!/usr/bin/env python3
"""Verify the exact control-plus-plugin Kast default release."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import tempfile
import xml.etree.ElementTree as xml
import zipfile


MAXIMUM_COMBINED_BYTES = 80 * 1024 * 1024


class ReleaseRejected(Exception):
    pass


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--release")
    parser.add_argument("--repository", default="amichne/kast")
    parser.add_argument("--report", type=Path)
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


def verify_control(control: Path) -> None:
    with tarfile.open(control, "r:gz") as archive:
        members = archive.getmembers()
        if any(not safe_name(member.name) for member in members):
            reject("control archive contains an unsafe path")
        if any(member.issym() or member.islnk() for member in members):
            reject("control archive contains a link")
        names = [member.name for member in members]
        if names.count("bin/kast") != 1 or archive.getmember("bin/kast").mode & 0o111 == 0:
            reject("control archive must contain one executable bin/kast")
        forbidden = ("semantic-runtime", "kast-indexer", "idea-home", "plugins/Kotlin")
        if any(any(token in name for token in forbidden) for name in names):
            reject("control archive contains isolated semantic-runtime authority")


def plugin_version(plugin: Path) -> str:
    forbidden_entries = (
        "com/intellij/",
        "org/jetbrains/kotlin/idea/",
        "io/github/amichne/kast/indexer/IndexerBootstrap",
    )
    observed_versions: list[str] = []
    with zipfile.ZipFile(plugin) as archive:
        names = archive.namelist()
        if any(not safe_name(name) for name in names):
            reject("plugin archive contains an unsafe path")
        roots = {PurePosixPath(name).parts[0] for name in names if PurePosixPath(name).parts}
        if roots != {"kast-indexer"}:
            reject("plugin archive must contain exactly the kast-indexer root")
        jars = [name for name in names if name.startswith("kast-indexer/lib/") and name.endswith(".jar")]
        if not jars:
            reject("plugin archive contains no plugin libraries")
        for jar_name in jars:
            with zipfile.ZipFile(io.BytesIO(archive.read(jar_name))) as jar:
                entries = jar.namelist()
                if any(entry.startswith(forbidden_entries) for entry in entries):
                    reject("plugin archive contains platform or isolated-bootstrap payload")
                if "META-INF/plugin.xml" in entries:
                    document = xml.fromstring(jar.read("META-INF/plugin.xml"))
                    identity = document.findtext("id")
                    if identity not in (None, "io.github.amichne.kast.indexer"):
                        reject("plugin archive exposes the wrong plugin identity")
                    match = re.fullmatch(r"kast-indexer/lib/kast-ide-plugin-(.+)\.jar", jar_name)
                    if match is None:
                        reject("plugin implementation jar does not bind its release version")
                    observed_versions.append(match.group(1))
    if len(observed_versions) != 1:
        reject("plugin archive must expose exactly one plugin version")
    return observed_versions[0]


def verify(directory: Path, release: str, report: Path | None) -> dict[str, object]:
    match = re.fullmatch(r"v(.+)", release)
    if match is None or not match.group(1):
        reject("release must be v<version>")
    version = match.group(1)
    control_name = f"kast-control-v{version}-macos-aarch64.tar.gz"
    plugin_name = f"kast-ide-plugin-{version}.zip"
    expected = {control_name, control_name + ".sha256", plugin_name, plugin_name + ".sha256"}
    observed = {path.name for path in directory.iterdir() if path.is_file()}
    if observed != expected:
        reject(f"asset set mismatch: expected {sorted(expected)}, observed {sorted(observed)}")
    control = directory / control_name
    plugin = directory / plugin_name
    combined = control.stat().st_size + plugin.stat().st_size
    if combined > MAXIMUM_COMBINED_BYTES:
        reject(f"combined payload exceeds 80 MiB: {combined}")
    verify_checksum(control)
    verify_checksum(plugin)
    verify_control(control)
    if plugin_version(plugin) != version:
        reject("plugin archive version does not match control release")
    document: dict[str, object] = {
        "schemaVersion": 1, "taskId": "KVP-035", "outcome": "COMPLETE", "release": release,
        "assets": [
            {"kind": "CONTROL", "name": control.name, "bytes": control.stat().st_size,
             "sha256": sha256(control)},
            {"kind": "IDE_PLUGIN", "name": plugin.name, "bytes": plugin.stat().st_size,
             "sha256": sha256(plugin)},
        ],
        "combinedBytes": combined, "maximumCombinedBytes": MAXIMUM_COMBINED_BYTES,
    }
    if report is not None:
        report.parent.mkdir(parents=True, exist_ok=True)
        temporary = report.with_suffix(report.suffix + ".tmp")
        temporary.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(report)
    return document


def write_checksum(path: Path) -> None:
    path.with_name(path.name + ".sha256").write_text(
        f"{sha256(path)}  {path.name}\n", encoding="utf-8",
    )


def synthetic_release(directory: Path, version: str) -> None:
    control = directory / f"kast-control-v{version}-macos-aarch64.tar.gz"
    launcher = b"#!/bin/sh\n"
    with tarfile.open(control, "w:gz") as archive:
        member = tarfile.TarInfo("bin/kast"); member.mode = 0o755; member.size = len(launcher)
        archive.addfile(member, io.BytesIO(launcher))
    plugin = directory / f"kast-ide-plugin-{version}.zip"
    plugin_xml = f"<idea-plugin><version>{version}</version></idea-plugin>".encode()
    jar_bytes = io.BytesIO()
    with zipfile.ZipFile(jar_bytes, "w") as jar:
        jar.writestr("META-INF/plugin.xml", plugin_xml)
    with zipfile.ZipFile(plugin, "w") as archive:
        archive.writestr(f"kast-indexer/lib/kast-ide-plugin-{version}.jar", jar_bytes.getvalue())
    write_checksum(control); write_checksum(plugin)


def expect_rejected(source: Path, mutate) -> None:
    with tempfile.TemporaryDirectory() as raw:
        fixture = Path(raw); shutil.copytree(source, fixture, dirs_exist_ok=True); mutate(fixture)
        try:
            verify(fixture, "v1.2.3", None)
        except ReleaseRejected:
            return
        reject("negative fixture was admitted")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as raw:
        valid = Path(raw); synthetic_release(valid, "1.2.3"); verify(valid, "v1.2.3", None)
        expect_rejected(valid, lambda root: (root / "kast-semantic-runtime-1.2.3.zip").write_bytes(b"x"))
        expect_rejected(valid, lambda root: (root / "kast-ide-plugin-1.2.3.zip").rename(
            root / "kast-ide-plugin-9.9.9.zip"))
        def inject_platform(root: Path) -> None:
            plugin = root / "kast-ide-plugin-1.2.3.zip"; jar_bytes = io.BytesIO()
            with zipfile.ZipFile(jar_bytes, "w") as jar:
                jar.writestr("META-INF/plugin.xml", "<idea-plugin><version>1.2.3</version></idea-plugin>")
                jar.writestr("com/intellij/openapi/project/Project.class", b"x")
            with zipfile.ZipFile(plugin, "w") as archive:
                archive.writestr("kast-indexer/lib/kast-ide-plugin-1.2.3.jar", jar_bytes.getvalue())
            write_checksum(plugin)
        expect_rejected(valid, inject_platform)
        def inject_runtime_manifest(root: Path) -> None:
            control = root / "kast-control-v1.2.3-macos-aarch64.tar.gz"
            with tarfile.open(control, "w:gz") as archive:
                for name, content, mode in (("bin/kast", b"#!/bin/sh\n", 0o755),
                                             ("share/kast/semantic-runtime.json", b"{}", 0o644)):
                    member = tarfile.TarInfo(name); member.size = len(content); member.mode = mode
                    archive.addfile(member, io.BytesIO(content))
            write_checksum(control)
        expect_rejected(valid, inject_runtime_manifest)
        def exceed_size(root: Path) -> None:
            plugin = root / "kast-ide-plugin-1.2.3.zip"
            with plugin.open("ab") as output:
                output.truncate(MAXIMUM_COMBINED_BYTES + 1)
            write_checksum(plugin)
        expect_rejected(valid, exceed_size)
    print("KVP-035 rejected all 5 hosted-release misuses")


def main() -> None:
    args = arguments()
    if args.self_test:
        self_test(); return
    if args.directory is None or args.release is None:
        reject("--directory and --release are required")
    print(json.dumps(verify(args.directory.resolve(), args.release, args.report), separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except ReleaseRejected as failure:
        raise SystemExit(f"release-assets: {failure}")
