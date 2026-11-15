"""Package authenticated composition inputs for the canonical product installer."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import tarfile
from dataclasses import asdict, dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Literal, TypeGuard

from composition_platform import HostPlatform
from managed_headless import (
    AssemblyEntryKind,
    AssemblyFile,
    file_record,
    object_fields,
)

LINUX_SUPPORT = "lib/kast-linux-support"


class BundleFailure(StrEnum):
    INVALID_MANIFEST = "INVALID_MANIFEST"
    UNSAFE_PATH = "UNSAFE_PATH"
    DIGEST_MISMATCH = "DIGEST_MISMATCH"
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    EXISTING_OUTPUT = "EXISTING_OUTPUT"
    EFFECT_FAILED = "EFFECT_FAILED"


@dataclass(frozen=True, slots=True)
class BundleRejected:
    reason: BundleFailure
    subject: str


@dataclass(frozen=True, slots=True)
class BundleManifest:
    version: str
    platformBuild: str
    sourceDistributionSha256: str
    bootstrapArchiveSha256: str
    compositionManifestSha256: str
    files: tuple[AssemblyFile, ...]
    schemaVersion: Literal[2] = field(default=2, init=False)
    kind: Literal["KAST_COMPOSED_SETUP_V2"] = field(default="KAST_COMPOSED_SETUP_V2", init=False)
    platform: HostPlatform = HostPlatform.LINUX_X86_64


@dataclass(frozen=True, slots=True)
class PackedBundle:
    archive: str
    sha256: str
    bytes: int


def objects(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def safe_path(value: str) -> bool:
    return (
        bool(value)
        and not any(c in value for c in ("\\", "\x00", ":"))
        and all(part not in {"", ".", ".."} for part in value.split("/"))
    )


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def file_records(value: object) -> tuple[AssemblyFile, ...] | BundleRejected:
    if not objects(value) or not value or len(value) > 100_000:
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "files")
    records: list[AssemblyFile] = []
    names: set[str] = set()
    for item in value:
        if not object_fields(item):
            return BundleRejected(BundleFailure.INVALID_MANIFEST, "file")
        path, kind, size, digest = (item.get(key) for key in ("path", "kind", "bytes", "sha256"))
        if (
            not isinstance(path, str)
            or not safe_path(path)
            or path in names
            or not isinstance(kind, str)
            or kind not in {"FILE", "SYMLINK"}
            or not isinstance(size, int)
            or isinstance(size, bool)
            or size < 0
            or not isinstance(digest, str)
            or not re.fullmatch("[a-f0-9]{64}", digest)
        ):
            return BundleRejected(BundleFailure.INVALID_MANIFEST, "file fields")
        names.add(path)
        records.append(AssemblyFile(path, AssemblyEntryKind(kind), size, digest))
    return tuple(records)


def read_manifest(data: bytes) -> BundleManifest | BundleRejected:
    if len(data) > 16_777_216:
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "size")
    try:
        raw: object = json.loads(data)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "JSON")
    if (
        not object_fields(raw)
        or raw.get("kind") != "KAST_COMPOSED_SETUP_V2"
        or type(raw.get("schemaVersion")) is not int
        or raw.get("schemaVersion") != 2
        or not isinstance(raw.get("platform"), str)
        or raw.get("platform") not in {item.value for item in HostPlatform}
    ):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "identity")
    selected_platform = HostPlatform(str(raw.get("platform")))
    version, build = raw.get("version"), raw.get("platformBuild")
    if (
        not isinstance(version, str)
        or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
        or not isinstance(build, str)
        or not re.fullmatch(r"262\.[0-9]+(?:\.[0-9]+)*", build)
    ):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "version")
    digests: list[str] = []
    for key in ("sourceDistributionSha256", "bootstrapArchiveSha256", "compositionManifestSha256"):
        value = raw.get(key)
        if not isinstance(value, str) or not re.fullmatch("[a-f0-9]{64}", value):
            return BundleRejected(BundleFailure.INVALID_MANIFEST, key)
        digests.append(value)
    records = file_records(raw.get("files"))
    if isinstance(records, BundleRejected):
        return records
    required = {
        "bootstrap.zip",
        "composition-manifest.json",
        "install.sh",
        "runtime/" + selected_platform.layout.product,
        "runtime/lib/kast-product.jar",
        "runtime/plugins/plugin-classpath.txt",
    }
    if not required <= {item.path for item in records}:
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "missing required files")
    return BundleManifest(
        version, build, digests[0], digests[1], digests[2], records, selected_platform
    )


@dataclass(frozen=True, slots=True)
class _VerifiedFiles:
    root: Path
    records: tuple[AssemblyFile, ...]


def examine_files(root: Path, records: tuple[AssemblyFile, ...]) -> _VerifiedFiles | BundleRejected:
    for record in records:
        path = root / record.path
        if (
            path.parent.resolve() != path.parent
            or not path.resolve().is_relative_to(root)
            or not (path.is_file() or path.is_symlink())
        ):
            return BundleRejected(BundleFailure.UNSAFE_PATH, record.path)
        if file_record(path, root) != record:
            return BundleRejected(BundleFailure.DIGEST_MISMATCH, record.path)
    return _VerifiedFiles(root, records)


@dataclass(frozen=True, slots=True)
class _ExtractedBundle:
    source: Path
    archive: Path
    sha256: str


def extract_bundle(
    archive: Path,
    destination: Path,
    expected: str,
) -> _ExtractedBundle | BundleRejected:
    if not re.fullmatch("[a-f0-9]{64}", expected) or sha256(archive) != expected:
        return BundleRejected(BundleFailure.DIGEST_MISMATCH, "bundle archive")
    with tarfile.open(archive, "r:*") as incoming:
        names: set[str] = set()
        total = 0
        for member in incoming:
            name = member.name.rstrip("/")
            if (
                not safe_path(name)
                or not (name.startswith("setup-kast/") or name == "setup-kast" and member.isdir())
                or name in names
                or not (member.isfile() or member.isdir() or member.issym())
                or member.size < 0
            ):
                return BundleRejected(BundleFailure.UNSAFE_PATH, name)
            names.add(name)
            total += member.size
            if total > 8_589_934_592 or len(names) > 100_000:
                return BundleRejected(BundleFailure.INVALID_MANIFEST, "archive limits")
            incoming.extract(member, destination, filter="data")
    return _ExtractedBundle(destination / "setup-kast", archive, expected)


def pack_bundle(
    root: Path,
    assets: Path,
    bootstrap: Path,
    composition_sha256: str,
    output: Path,
    version: str,
    *,
    linux_support: Path | None = None,
) -> PackedBundle | BundleRejected:
    if output.exists():
        return BundleRejected(BundleFailure.EXISTING_OUTPUT, str(output))
    receipt = root / "composition-manifest.json"
    if (
        not re.fullmatch("[a-f0-9]{64}", composition_sha256)
        or sha256(receipt) != composition_sha256
    ):
        return BundleRejected(BundleFailure.DIGEST_MISMATCH, "composition manifest")
    raw: object = json.loads(receipt.read_bytes())
    if not object_fields(raw):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "composition")
    records: list[AssemblyFile] = []
    for key in ("vendorFiles", "generatedFiles"):
        parsed = file_records(raw.get(key))
        if isinstance(parsed, BundleRejected):
            return parsed
        records.extend(parsed)
    distribution = raw.get("distributionSha256")
    targets = tuple(item for item in HostPlatform if item.layout.archive_sha256 == distribution)
    if len(targets) != 1:
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "donor identity")
    target = targets[0]
    home = root / target.layout.home
    rejected = examine_files(home, tuple(records))
    if isinstance(rejected, BundleRejected):
        return rejected
    distribution, bootstrap_sha = raw.get("distributionSha256"), raw.get("bootstrapArchiveSha256")
    if not isinstance(distribution, str) or not isinstance(bootstrap_sha, str):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "composition digests")
    if sha256(bootstrap) != bootstrap_sha:
        return BundleRejected(BundleFailure.DIGEST_MISMATCH, "bootstrap archive")
    product: object = json.loads((home / target.layout.product).read_bytes())
    if not object_fields(product) or not isinstance(product.get("buildNumber"), str):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "product")
    build = product.get("buildNumber")
    if not isinstance(build, str):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "build")
    repository = Path(__file__).resolve().parent.parent
    files: list[tuple[str, Path]] = [("runtime/" + item.path, home / item.path) for item in records]
    files.extend(
        (
            ("composition-manifest.json", receipt),
            ("bootstrap.zip", bootstrap),
            ("install.sh", repository / "install.sh"),
        )
    )
    files.extend(
        ("assets/" + path.name, path) for path in sorted(assets.iterdir()) if path.is_file()
    )
    if any(path.is_symlink() for name, path in files if not name.startswith("runtime/")):
        return BundleRejected(BundleFailure.UNSAFE_PATH, "bundle support")
    support = native_support_files(target, linux_support)
    if isinstance(support, BundleRejected):
        return support
    files.extend(("runtime/" + name, path) for name, path in support)
    manifest = BundleManifest(
        version,
        build,
        distribution,
        bootstrap_sha,
        composition_sha256,
        tuple(
            AssemblyFile(name, entry.kind, entry.bytes, entry.sha256)
            for name, path in files
            for entry in (file_record(path, path.parent),)
        ),
        target,
    )
    encoded = json.dumps(asdict(manifest), indent=2).encode() + b"\n"
    parsed_manifest = read_manifest(encoded)
    if isinstance(parsed_manifest, BundleRejected):
        return parsed_manifest

    def normalized(info: tarfile.TarInfo) -> tarfile.TarInfo:
        info.uid = info.gid = 0
        info.uname = info.gname = ""
        info.mtime = 0
        return info

    with (
        output.open("xb") as stream,
        gzip.GzipFile(
            filename="",
            fileobj=stream,
            mode="wb",
            compresslevel=1,
            mtime=0,
        ) as compressed,
        tarfile.open(fileobj=compressed, mode="w") as archive,
    ):
        for name, data in (("manifest.json", encoded),):
            metadata = tarfile.TarInfo("setup-kast/" + name)
            metadata.size = len(data)
            metadata.mode = 0o444
            archive.addfile(metadata, io.BytesIO(data))
        for name, path in sorted(files):
            archive.add(path, "setup-kast/" + name, recursive=False, filter=normalized)
    return PackedBundle(str(output), sha256(output), output.stat().st_size)


def native_support_files(
    target: HostPlatform, linux_support: Path | None
) -> tuple[tuple[str, Path], ...] | BundleRejected:
    if not target.is_linux:
        return (
            ()
            if linux_support is None
            else BundleRejected(BundleFailure.INVALID_MANIFEST, "Linux support on macOS")
        )
    if linux_support is None or linux_support.resolve() != linux_support:
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "Linux support")
    paths = sorted(linux_support.rglob("*"))
    if (
        any(path.is_symlink() for path in paths)
        or not all((linux_support / name).is_dir() for name in ("lib", "fonts", "licenses"))
        or not (linux_support / "fonts.conf").is_file()
    ):
        return BundleRejected(BundleFailure.INVALID_MANIFEST, "Linux support")
    return tuple(
        (LINUX_SUPPORT + "/" + path.relative_to(linux_support).as_posix(), path)
        for path in paths
        if path.is_file()
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    pack = sub.add_parser("pack")
    for name in ("root", "assets", "bootstrap", "output"):
        _ = pack.add_argument("--" + name, type=Path, required=True)
    _ = pack.add_argument("--composition-sha256", required=True)
    _ = pack.add_argument("--version", required=True)
    _ = pack.add_argument("--linux-support", type=Path)
    args = parser.parse_args()
    try:
        result = pack_bundle(
            args.root,
            args.assets,
            args.bootstrap,
            args.composition_sha256,
            args.output,
            args.version,
            linux_support=args.linux_support,
        )
    except (OSError, ValueError, tarfile.TarError) as error:
        result = BundleRejected(BundleFailure.EFFECT_FAILED, type(error).__name__)
    print(json.dumps(asdict(result), default=os.fspath, indent=2))
    return 0 if isinstance(result, PackedBundle) else 1


if __name__ == "__main__":
    raise SystemExit(main())
