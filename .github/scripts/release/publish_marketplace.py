"""Project one stable release's agent marketplace into a checkout of the marketplace repository.

The release mapping names the build record, the record names the Linux setup bundle by digest,
and the bundle carries the marketplace archive beside its digest sidecar. Only that chain admits
the files this writes.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import stat
import sys
import tarfile
import tempfile
import zipfile
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path, PurePosixPath

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "packaging/internal/installation"))
import managed_release as product

TARGET = "linux-x86_64"
COMPOSITION = "compositions/mcp/"
EXECUTABLE_DIRECTORY = "plugins/kast/skills/kast/scripts/"
MAXIMUM_FILES = 128
MAXIMUM_FILE_BYTES = 1_048_576
MAXIMUM_SIDECAR_BYTES = 256
RECORD_FILE = "kast-release.json"
README = """# Kast agent marketplace

Generated from [amichne/kast](https://github.com/amichne/kast) for each stable release.
Do not edit this repository by hand. `{record}` names the release that produced it.

The [agent setup guide](https://kast.michne.com/reference/integrations) gives each agent's commands.
"""


class Failure(StrEnum):
    RELEASE_UNAVAILABLE = "RELEASE_UNAVAILABLE"
    TARGET_MISSING = "TARGET_MISSING"
    BUNDLE_UNAVAILABLE = "BUNDLE_UNAVAILABLE"
    BUNDLE_MISMATCH = "BUNDLE_MISMATCH"
    MARKETPLACE_MISSING = "MARKETPLACE_MISSING"
    MARKETPLACE_MISMATCH = "MARKETPLACE_MISMATCH"
    ENTRY_INVALID = "ENTRY_INVALID"
    COMMAND_NOT_EXECUTABLE = "COMMAND_NOT_EXECUTABLE"
    CHECKOUT_INVALID = "CHECKOUT_INVALID"


@dataclass(frozen=True, slots=True)
class Rejected:
    failure: Failure
    detail: str


@dataclass(frozen=True, slots=True)
class MarketplaceFile:
    path: PurePosixPath
    content: bytes
    executable: bool


@dataclass(frozen=True, slots=True)
class ReleaseIdentity:
    """Written beside the marketplace so a reader can trace it to the exact release record."""

    publicVersion: str
    buildVersion: str
    sourceRevision: str
    buildRecordSha256: str
    marketplaceSha256: str


@dataclass(frozen=True, slots=True)
class Marketplace:
    identity: ReleaseIdentity
    files: tuple[MarketplaceFile, ...]


def marketplace_files(archive: bytes) -> tuple[MarketplaceFile, ...] | Rejected:
    """Select the MCP composition. Every shipped skill command must keep its executable mode."""
    selected: list[MarketplaceFile] = []
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        for entry in bundle.infolist():
            if not entry.filename.startswith(COMPOSITION) or entry.is_dir():
                continue
            relative = PurePosixPath(entry.filename.removeprefix(COMPOSITION))
            mode = entry.external_attr >> 16
            if (
                relative.is_absolute()
                or any(part in ("", ".", "..", ".git") for part in relative.parts)
                or "\\" in entry.filename
                or not stat.S_ISREG(mode)
                or entry.file_size > MAXIMUM_FILE_BYTES
                or len(selected) >= MAXIMUM_FILES
            ):
                return Rejected(Failure.ENTRY_INVALID, entry.filename)
            executable = bool(mode & stat.S_IXUSR)
            if relative.as_posix().startswith(EXECUTABLE_DIRECTORY) and not executable:
                return Rejected(Failure.COMMAND_NOT_EXECUTABLE, relative.as_posix())
            selected.append(MarketplaceFile(relative, bundle.read(entry), executable))
    required = {".claude-plugin/marketplace.json", ".agents/plugins/marketplace.json"}
    if not required <= {item.path.as_posix() for item in selected}:
        return Rejected(Failure.MARKETPLACE_MISSING, COMPOSITION)
    return tuple(sorted(selected, key=lambda item: item.path.as_posix()))


def bundled_marketplace(bundle: Path, version: str) -> bytes | Rejected:
    name = f"setup-kast/assets/kast-marketplace-v{version}.zip"
    try:
        with tarfile.open(bundle, "r:gz") as archive:
            members = {member.name: member for member in archive.getmembers()}
            entry, sidecar = members.get(name), members.get(f"{name}.sha256")
            if entry is None or sidecar is None or not entry.isfile() or not sidecar.isfile():
                return Rejected(Failure.MARKETPLACE_MISSING, name)
            if entry.size > 16 * MAXIMUM_FILE_BYTES or sidecar.size > MAXIMUM_SIDECAR_BYTES:
                return Rejected(Failure.ENTRY_INVALID, name)
            content = archive.extractfile(entry)
            recorded = archive.extractfile(sidecar)
            if content is None or recorded is None:
                return Rejected(Failure.MARKETPLACE_MISSING, name)
            raw, expected = content.read(), recorded.read().decode("ascii").split()
    except (OSError, tarfile.TarError, UnicodeError) as error:
        return Rejected(Failure.BUNDLE_UNAVAILABLE, type(error).__name__)
    if not expected or hashlib.sha256(raw).hexdigest() != expected[0]:
        return Rejected(Failure.MARKETPLACE_MISMATCH, name)
    return raw


def admit(
    public_version: str, source: product.ReleaseSource, workspace: Path
) -> Marketplace | Rejected:
    selection = product.StableSelection(
        public_version, f"v{public_version}", product.StablePolicy.PIN
    )
    resolved = product.resolve_product_mapping(selection, source)
    if isinstance(resolved, product.Rejected) or not isinstance(
        resolved.record, product.ProductRecord
    ):
        return Rejected(Failure.RELEASE_UNAVAILABLE, selection.releaseTag)
    record = resolved.record
    target = next((item for item in record.targets if item.target == TARGET), None)
    if target is None:
        return Rejected(Failure.TARGET_MISSING, TARGET)
    path = workspace / target.bundle.name
    downloaded = source.download(
        f"{product.RELEASE_URL}/{selection.releaseTag}/{target.bundle.name}",
        path,
        target.bundle.bytes,
    )
    if isinstance(downloaded, product.TRANSFER_REJECTIONS):
        return Rejected(Failure.BUNDLE_UNAVAILABLE, target.bundle.name)
    if (downloaded.sha256, downloaded.bytes) != (target.bundle.sha256, target.bundle.bytes):
        return Rejected(Failure.BUNDLE_MISMATCH, target.bundle.name)
    archive = bundled_marketplace(path, record.version)
    if isinstance(archive, Rejected):
        return archive
    files = marketplace_files(archive)
    if isinstance(files, Rejected):
        return files
    identity = ReleaseIdentity(
        public_version,
        record.version,
        record.sourceRevision,
        resolved.recordSha256,
        hashlib.sha256(archive).hexdigest(),
    )
    return Marketplace(identity, files)


def write(marketplace: Marketplace, checkout: Path) -> Rejected | None:
    """Replace every byte except Git metadata, so removed plugin files leave the repository."""
    if checkout.is_symlink() or not (checkout / ".git").exists():
        return Rejected(Failure.CHECKOUT_INVALID, str(checkout))
    for child in checkout.iterdir():
        if child.name == ".git":
            continue
        if child.is_dir() and not child.is_symlink():
            remove_tree(child)
        else:
            child.unlink()
    for item in marketplace.files:
        destination = checkout.joinpath(*item.path.parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        _ = destination.write_bytes(item.content)
        destination.chmod(0o755 if item.executable else 0o644)
    identity = json.dumps(asdict(marketplace.identity), indent=2) + "\n"
    _ = (checkout / RECORD_FILE).write_text(identity)
    _ = (checkout / "README.md").write_text(README.format(record=RECORD_FILE))
    return None


def remove_tree(directory: Path) -> None:
    for child in directory.iterdir():
        if child.is_dir() and not child.is_symlink():
            remove_tree(child)
        else:
            child.unlink()
    directory.rmdir()


def main(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--public-version", required=True)
    _ = parser.add_argument("--checkout", required=True, type=Path)
    args = parser.parse_args(arguments)
    if not product.VERSION.fullmatch(args.public_version):
        print(
            "publish-marketplace: public version must be <major>.<minor>.<patch>", file=sys.stderr
        )
        return 2
    with tempfile.TemporaryDirectory(prefix="kast-marketplace-") as temporary:
        admitted = admit(args.public_version, product.HttpReleaseSource(), Path(temporary))
    if isinstance(admitted, Rejected):
        print(json.dumps(asdict(admitted)), file=sys.stderr)
        return 1
    written = write(admitted, args.checkout)
    if written is not None:
        print(json.dumps(asdict(written)), file=sys.stderr)
        return 1
    print(json.dumps(asdict(admitted.identity)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
