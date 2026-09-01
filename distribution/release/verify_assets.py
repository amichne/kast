#!/usr/bin/env python3
"""Verify the exact control-plus-private-sidecar Kast release."""

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


def verify(
    directory: Path,
    release: str,
    repository: str,
    report: Path | None,
) -> dict[str, object]:
    match = re.fullmatch(r"v(\d+\.\d+\.\d+)", release)
    if match is None:
        reject("release must be v<major>.<minor>.<patch>")
    version = match.group(1)
    control_name = f"kast-control-v{version}-macos-aarch64.tar.gz"
    sidecar_name = f"kast-semantic-runtime-{version}-macos-aarch64.zip"
    expected = {
        control_name,
        control_name + ".sha256",
        sidecar_name,
        sidecar_name + ".sha256",
    }
    observed = {path.name for path in directory.iterdir() if path.is_file()}
    if observed != expected:
        reject(f"asset set mismatch: expected {sorted(expected)}, observed {sorted(observed)}")
    control = directory / control_name
    sidecar = directory / sidecar_name
    combined = control.stat().st_size + sidecar.stat().st_size
    if combined > MAXIMUM_COMBINED_BYTES:
        reject(f"combined payload exceeds 80 MiB: {combined}")
    verify_checksum(control)
    verify_checksum(sidecar)
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
            ("bin/kast", b"#!/bin/sh\n", 0o755),
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


def expect_rejected(source: Path, mutate) -> None:
    with tempfile.TemporaryDirectory() as raw:
        fixture = Path(raw)
        shutil.copytree(source, fixture, dirs_exist_ok=True)
        mutate(fixture)
        try:
            verify(fixture, "v1.2.3", "amichne/kast", None)
        except ReleaseRejected:
            return
        reject("negative fixture was admitted")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as raw:
        valid = Path(raw)
        synthetic_release(valid, "1.2.3")
        verify(valid, "v1.2.3", "amichne/kast", None)
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
    print("Rejected all 5 plugin-free sidecar release misuses")


def write_negative_report(path: Path) -> None:
    document = {
        "schemaVersion": 1,
        "taskId": "SIDECAR-RELEASE",
        "outcome": "REJECTED",
        "rejectedFixtureCount": 5,
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
    if args.directory is None or args.release is None:
        reject("--directory and --release are required")
    print(
        json.dumps(
            verify(
                args.directory.resolve(),
                args.release,
                args.repository,
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
