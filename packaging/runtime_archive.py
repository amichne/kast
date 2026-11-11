"""Installer-free payload consumed by native `kast container import-runtime`."""

from __future__ import annotations

import json
import os
import re
import stat
import zipfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Literal

from composed_bundle import (
    BundleFailure,
    BundleRejected,
    PackedBundle,
    native_support_files,
    sha256,
)
from composition_platform import HostPlatform
from managed_headless import Rejected, object_fields, selected_private_profile


@dataclass(frozen=True, slots=True)
class RuntimeFile:
    path: str
    bytes: int
    sha256: str
    executable: bool
    type: Literal["FILE"] = field(default="FILE", init=False)


@dataclass(frozen=True, slots=True)
class RuntimeLink:
    path: str
    target: str
    type: Literal["SYMLINK"] = field(default="SYMLINK", init=False)


@dataclass(frozen=True, slots=True)
class RuntimeManifest:
    version: str
    platform: HostPlatform
    platformBuild: str
    distributionSha256: str
    bootstrapSha256: str
    files: tuple[RuntimeFile | RuntimeLink, ...]
    schemaVersion: Literal[1] = field(default=1, init=False)
    kind: Literal["KAST_RUNTIME_V1"] = field(default="KAST_RUNTIME_V1", init=False)


def pack_runtime(
    root: Path,
    output: Path,
    version: str,
    target: HostPlatform,
    *,
    linux_support: Path | None = None,
) -> PackedBundle | BundleRejected | Rejected:
    """Keep vendor paths and native modes; ship no installer, Python, or mutable profile."""
    if output.exists() or output.is_symlink():
        return BundleRejected(BundleFailure.EXISTING_OUTPUT, str(output))
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "version")
    home = root / target.layout.home
    raw: object = json.loads((home / "kast-headless.json").read_bytes())
    if not object_fields(raw):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "assembly")
    build, distribution, bootstrap = (
        raw.get(key) for key in ("platformBuild", "distributionSha256", "bootstrapSha256")
    )
    if (
        not isinstance(build, str)
        or not isinstance(distribution, str)
        or distribution != target.layout.archive_sha256
        or not isinstance(bootstrap, str)
        or not re.fullmatch("[a-f0-9]{64}", bootstrap)
    ):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "assembly identity")
    profile = selected_private_profile(root / "state")
    if isinstance(profile, Rejected):
        return profile
    plugins = profile.directory / "plugins"
    prefix = "host" if target.is_linux else "host/Kast.app/Contents"
    sources = [
        (prefix + "/" + path.relative_to(home).as_posix(), path)
        for path in sorted(home.rglob("*"))
        if (path.is_file() or path.is_symlink()) and path != home / "kast-headless.json"
    ]
    sources.extend(
        ("components/plugins/" + path.relative_to(plugins).as_posix(), path)
        for path in sorted((plugins / "kast-ide-hosted").rglob("*"))
        if path.is_file() or path.is_symlink()
    )
    support = native_support_files(target, linux_support)
    if isinstance(support, BundleRejected):
        return support
    sources.extend((prefix + "/" + name, path) for name, path in support)
    records: list[RuntimeFile | RuntimeLink] = []
    for name, path in sources:
        if path.is_symlink():
            if not name.startswith("host/") or not path.resolve().is_relative_to(home):
                return BundleRejected(BundleFailure.UNSAFE_PATH, name)
            records.append(RuntimeLink(name, os.readlink(path)))
        else:
            records.append(
                RuntimeFile(
                    name, path.stat().st_size, sha256(path), bool(path.stat().st_mode & 0o111)
                )
            )
    bootstrap_path = (
        prefix + "/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar"
    )
    if not any(
        isinstance(item, RuntimeFile) and item.path == bootstrap_path and item.sha256 == bootstrap
        for item in records
    ):
        return BundleRejected(BundleFailure.DIGEST_MISMATCH, "bootstrap")
    if not any(item.path.startswith("components/plugins/kast-ide-hosted/lib/") for item in records):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "Host plugin")
    manifest = RuntimeManifest(version, target, build, distribution, bootstrap, tuple(records))
    encoded = json.dumps(asdict(manifest), indent=2).encode() + b"\n"
    if (
        len(encoded) > 16_777_216
        or len(sources) > 100_000
        or sum(item.bytes for item in records if isinstance(item, RuntimeFile)) > 8_589_934_592
    ):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "limits")
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        archive.writestr("manifest.json", encoded)
        for name, path in sources:
            metadata = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            metadata.compress_type = zipfile.ZIP_DEFLATED
            metadata.external_attr = (stat.S_IFREG | 0o444) << 16
            if path.is_symlink():
                archive.writestr(metadata, os.fsencode(os.readlink(path)))
            else:
                with (
                    path.open("rb") as source,
                    archive.open(metadata, "w", force_zip64=True) as destination,
                ):
                    import shutil

                    shutil.copyfileobj(source, destination)
    digest = sha256(output)
    _ = output.with_name(output.name + ".sha256").write_text(f"{digest}  {output.name}\n")
    return PackedBundle(str(output), digest, output.stat().st_size)
