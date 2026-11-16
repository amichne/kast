"""Generate the self-contained public bootstrap from its existing Python owners."""

import argparse
import base64
import bz2
import hashlib
import io
import shutil
import sys
import tarfile
from collections.abc import Iterable
from pathlib import Path

import mcp_dependency_artifact as producer
import mcp_environment as mcp
from private_interpreter import DEPENDENCY_SHA256, DEPENDENCY_URL, PINS

SOURCES = (
    "bootstrap_entry.py",
    "private_interpreter.py",
    "managed_installation.py",
    "managed_release.py",
    "managed_product.py",
    "managed_product_installation.py",
    "managed_product_removal.py",
    "legacy_purge.py",
    "immutable_store.py",
    "product_removal_recovery.py",
    "product_mcp_removal.py",
    "mcp_environment.py",
    "managed_update_contract.py",
    "managed_update_journal.py",
    "reload_hosted.py",
    "host-installation.py",
    "installation-recovery.py",
    "installation-lifecycle.py",
    "host_directory_exchange.py",
    "composed_bundle.py",
    "composition_platform.py",
    "managed_headless.py",
    "runtime_archive.py",
    "runtime_base.py",
)
HERE = Path(__file__).resolve().parent
# Embedded modules are also retained for offline lifecycle dispatch. These are
# projections of one membership contract, not independent consumer inventories.
RETAINED = (
    "install-candidate.sh",
    "managed_installation.py.lock",
    "prune-prior-installations.py",
    "codex-mcp-registration.py",
)
FIXED = (
    "mcp-requirements.txt",
    "mcp-requirements.txt.lock",
    "managed-install-v1",
    "install-completion-v1",
)


def validate_members(names: Iterable[str]) -> tuple[str, ...]:
    members = tuple(names)
    if len(set(members)) != len(members) or any(
        not name or Path(name).name != name or name in {".", ".."} or "\\" in name
        for name in members
    ):
        raise ValueError("duplicate or unsafe installer support member")
    return members


def support_files() -> tuple[tuple[str, Path], ...]:
    names = validate_members((*SOURCES, *RETAINED, *FIXED, "install.sh"))
    files = tuple(
        (name, HERE.parent / name if name == "install.sh" else HERE / name) for name in names
    )
    if any(path.is_symlink() or not path.is_file() for _, path in files):
        raise ValueError("missing installer support member")
    return files


def dependency_artifact_matches(destination: Path, target: mcp.DependencyTarget) -> bool:
    artifact = destination / "mcp-dependencies"
    export = producer.source_export()
    inventory = mcp.inventory(artifact / "site-packages")
    return (
        mcp.owned(artifact, directory=True)
        and {path.name for path in artifact.iterdir()} == {"artifact.json", "site-packages"}
        and not isinstance(export, mcp.Rejected)
        and not isinstance(inventory, mcp.Rejected)
        and bool(inventory.files)
        and all(entry.mode == 0o644 for entry in inventory.files)
        and mcp.record_bytes(artifact / "artifact.json")
        == mcp.encoded(mcp.DependencyArtifact(target, export, inventory))
    )


def check_support(destination: Path, target: mcp.DependencyTarget | None = None) -> bool:
    files = support_files()
    return (
        destination.is_dir()
        and not destination.is_symlink()
        and {path.name for path in destination.iterdir()}
        == {name for name, _ in files} | ({"mcp-dependencies"} if target is not None else set())
        and all(
            (destination / name).is_file()
            and not (destination / name).is_symlink()
            and (destination / name).read_bytes() == source.read_bytes()
            for name, source in files
        )
        and (target is None or dependency_artifact_matches(destination, target))
    )


def stage_support(
    destination: Path, target: mcp.DependencyTarget | None = None, uv: Path | None = None
) -> None:
    files = support_files()
    permitted = {name for name, _ in files}
    if target is not None:
        permitted.add("mcp-dependencies")
    if destination.is_symlink() or (
        destination.exists()
        and any(
            path.name not in permitted
            or path.is_symlink()
            or (not path.is_file() and path.name != "mcp-dependencies")
            for path in destination.iterdir()
        )
    ):
        raise ValueError("foreign installer support member")
    destination.mkdir(parents=True, exist_ok=True)
    for name, source in files:
        _ = (destination / name).write_bytes(source.read_bytes())
    if target is not None:
        if (destination / "mcp-dependencies").exists():
            if not dependency_artifact_matches(destination, target):
                raise ValueError("changed MCP artifact; clean generated support before rebuilding")
        else:
            result = (
                mcp.Rejected(mcp.Failure.PROCESS)
                if uv is None
                else producer.produce(destination.resolve(), target, uv=uv)
            )
            if isinstance(result, mcp.Rejected):
                print(mcp.encoded(result).decode(), end="", file=sys.stderr)
                raise ValueError(f"MCP dependency production rejected: {result.failure.value}")
    if not check_support(destination, target):
        raise ValueError("installer support projection changed")


def render() -> bytes:
    payload = io.BytesIO()
    with tarfile.open(fileobj=payload, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name in sorted(validate_members(SOURCES)):
            raw = (HERE / name).read_bytes()
            member = tarfile.TarInfo(name)
            member.mode, member.size = 0o600, len(raw)
            archive.addfile(member, io.BytesIO(raw))
    # Keep the documented bash -c bootstrap below Linux's per-argument byte limit.
    # The already admitted private interpreter owns decompression, not a PATH tool.
    compressed = bz2.compress(payload.getvalue())
    text = (HERE / "bootstrap.sh.in").read_text()
    mac, linux = PINS
    for name, value in (
        ("MAC_URL", mac.url),
        ("MAC_SHA", mac.sha256),
        ("LINUX_URL", linux.url),
        ("LINUX_SHA", linux.sha256),
        ("DEPENDENCY_URL", DEPENDENCY_URL),
        ("DEPENDENCY_SHA", DEPENDENCY_SHA256),
        ("ENGINE", base64.b85encode(compressed).decode()),
        ("ENGINE_SHA", hashlib.sha256(compressed).hexdigest()),
    ):
        text = text.replace("@" + name + "@", value)
    return text.encode()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--check", action="store_true")
    _ = parser.add_argument("--support-directory", type=Path)
    _ = parser.add_argument("--target", type=mcp.DependencyTarget, choices=mcp.DependencyTarget)
    args = parser.parse_args()
    if args.support_directory is not None:
        if args.check:
            return 0 if check_support(args.support_directory, args.target) else 1
        uv = shutil.which("uv")
        stage_support(args.support_directory, args.target, Path(uv) if uv is not None else None)
        return 0
    destination = HERE.parent / "install.sh"
    expected = render()
    if args.check:
        return 0 if destination.read_bytes() == expected else 1
    _ = destination.write_bytes(expected)
    destination.chmod(0o755)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
