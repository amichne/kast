#!/usr/bin/env python3
"""Verify the exact two-payload Kast release asset set."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
from typing import Any
import zipfile


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--release", required=True)
    parser.add_argument("--repository", default="amichne/kast")
    return parser.parse_args()


def fail(message: str) -> None:
    raise SystemExit(f"release-assets: {message}")


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
    fields = checksum.read_text().split()
    if fields != [sha256(payload), payload.name]:
        fail(f"checksum does not exactly identify {payload.name}")


def manifest_from_control(control: Path) -> tuple[dict[str, Any], list[tarfile.TarInfo]]:
    with tarfile.open(control, "r:gz") as archive:
        members = archive.getmembers()
        if any(not safe_name(member.name) for member in members):
            fail("control archive contains an unsafe path")
        if any(member.issym() or member.islnk() for member in members):
            fail("control archive contains a link")
        names = [member.name for member in members]
        if names.count("bin/kast") != 1:
            fail("control archive must contain exactly one bin/kast")
        launcher = archive.getmember("bin/kast")
        if launcher.mode & 0o111 == 0:
            fail("control launcher is not executable")
        forbidden = ("kast-indexer", "idea-home", "plugins/Kotlin", "plugins/gradle")
        if any(any(part in name for part in forbidden) for name in names):
            fail("control archive contains semantic runtime payload")
        manifest_member = archive.getmember("share/kast/semantic-runtime.json")
        manifest_source = archive.extractfile(manifest_member)
        if manifest_source is None:
            fail("control archive runtime manifest is unreadable")
        manifest = json.loads(manifest_source.read())
        forbidden_jar_entries = (
            "com/intellij/",
            "io/github/amichne/kast/indexer/",
            "org/jetbrains/kotlin/idea/",
        )
        for member in members:
            if not member.isfile() or not member.name.endswith(".jar"):
                continue
            source = archive.extractfile(member)
            if source is None:
                fail(f"control jar is unreadable: {member.name}")
            with zipfile.ZipFile(io.BytesIO(source.read())) as jar:
                if any(
                    entry.startswith(forbidden_jar_entries)
                    for entry in jar.namelist()
                ):
                    fail(f"control jar contains semantic runtime payload: {member.name}")
    return manifest, members


def verify_runtime(runtime: Path) -> None:
    with zipfile.ZipFile(runtime) as archive:
        names = archive.namelist()
        if any(not safe_name(name) for name in names):
            fail("semantic runtime contains an unsafe path")
        required = (
            "kast-indexer",
            "idea-home/product-info.json",
        )
        if any(name not in names for name in required):
            fail("semantic runtime is missing its executable or IntelliJ identity")
        if not any(name.startswith("idea-home/plugins/kast-indexer/") for name in names):
            fail("semantic runtime is missing the private Kast plugin")
        if not any(name.startswith("runtime-libs/") for name in names):
            fail("semantic runtime is missing runtime libraries")


def main() -> None:
    args = arguments()
    match = re.fullmatch(r"v(\d+\.\d+\.\d+)", args.release)
    if match is None:
        fail("release must be v<major>.<minor>.<patch>")
    version = match.group(1)
    directory = args.directory.resolve()
    control_name = f"kast-control-v{version}-macos-aarch64.tar.gz"
    runtime_name = f"kast-semantic-runtime-{version}-macos-aarch64.zip"
    expected = {
        control_name,
        f"{control_name}.sha256",
        runtime_name,
        f"{runtime_name}.sha256",
    }
    observed = {path.name for path in directory.iterdir() if path.is_file()}
    if observed != expected:
        fail(f"asset set mismatch: expected {sorted(expected)}, observed {sorted(observed)}")
    control = directory / control_name
    runtime = directory / runtime_name
    if control.stat().st_size >= 64 * 1024 * 1024:
        fail(f"control archive exceeds 64 MiB: {control.stat().st_size}")
    verify_checksum(control)
    verify_checksum(runtime)
    manifest, _ = manifest_from_control(control)
    archive = manifest.get("archive")
    if not isinstance(archive, dict):
        fail("runtime manifest archive must be an object")
    expected_url = (
        f"https://github.com/{args.repository}/releases/download/{args.release}/{runtime_name}"
    )
    if manifest.get("productVersion") != version:
        fail("control manifest product version does not match release")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(manifest.get("runtimeId"))):
        fail("control manifest runtime identity is not content-addressed")
    if archive.get("fileName") != runtime_name or archive.get("url") != expected_url:
        fail("control manifest does not identify the published semantic runtime")
    if archive.get("sha256") != f"sha256:{sha256(runtime)}":
        fail("control manifest semantic runtime digest does not match")
    if archive.get("bytes") != runtime.stat().st_size:
        fail("control manifest semantic runtime size does not match")
    verify_runtime(runtime)
    print(
        json.dumps(
            {
                "control": control.name,
                "controlBytes": control.stat().st_size,
                "runtime": runtime.name,
                "runtimeBytes": runtime.stat().st_size,
                "runtimeId": manifest["runtimeId"],
            },
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    main()
