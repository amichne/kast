"""Generate the self-contained public bootstrap from its existing Python owners."""

import argparse
import base64
import bz2
import hashlib
import io
import sys
import tarfile
import tokenize
from collections.abc import Iterable
from pathlib import Path

sys.path.extend(
    [
        str(Path(__file__).resolve().parents[1] / family)
        for family in ("composition", "installation", "product", "runtime")
    ]
    + [str(Path(__file__).resolve().parents[2])]
)

from private_interpreter import DEPENDENCY_SHA256, DEPENDENCY_URL, PINS

HERE = Path(__file__).resolve().parent
INTERNAL = HERE.parent
PACKAGING = INTERNAL.parent
REPOSITORY = PACKAGING.parent

SOURCE_PATHS = {
    "bootstrap_entry.py": "bootstrap/bootstrap_entry.py",
    "codex-mcp-registration.py": "installation/codex-mcp-registration.py",
    "composed_host.py": "composition/composed_host.py",
    "composition_descriptors.py": "composition/composition_descriptors.py",
    "composition_graph.py": "composition/composition_graph.py",
    "composition_index.py": "composition/composition_index.py",
    "composition_platform.py": "composition/composition_platform.py",
    "composition_recipe.py": "composition/composition_recipe.py",
    "host_directory_exchange.py": "installation/host_directory_exchange.py",
    "immutable_store.py": "installation/immutable_store.py",
    "install-candidate.sh": "bootstrap/install-candidate.sh",
    "installation-lifecycle.py": "installation/installation-lifecycle.py",
    "installation-recovery.py": "installation/installation-recovery.py",
    "managed_installation.py": "installation/managed_installation.py",
    "managed_installation.py.lock": "installation/managed_installation.py.lock",
    "managed_product.py": "product/managed_product.py",
    "managed_product_installation.py": "product/managed_product_installation.py",
    "managed_product_removal.py": "product/managed_product_removal.py",
    "managed_release.py": "installation/managed_release.py",
    "managed_update_contract.py": "installation/managed_update_contract.py",
    "managed_update_journal.py": "installation/managed_update_journal.py",
    "private_interpreter.py": "bootstrap/private_interpreter.py",
    "product_state_removal.py": "product/product_state_removal.py",
    "product_removal_recovery.py": "product/product_removal_recovery.py",
    "runtime_archive.py": "runtime/runtime_archive.py",
    "runtime_base.py": "runtime/runtime_base.py",
}

SOURCES = (
    "bootstrap_entry.py",
    "private_interpreter.py",
    "managed_installation.py",
    "managed_release.py",
    "managed_product.py",
    "managed_product_installation.py",
    "managed_product_removal.py",
    "immutable_store.py",
    "product_removal_recovery.py",
    "product_state_removal.py",
    "managed_update_contract.py",
    "managed_update_journal.py",
    "installation-recovery.py",
    "installation-lifecycle.py",
    "host_directory_exchange.py",
    "composed_bundle.py",
    "composition_platform.py",
    "managed_headless.py",
    "runtime_archive.py",
    "runtime_base.py",
)
# Embedded modules are also retained for offline lifecycle dispatch. These are
# projections of one membership contract, not independent consumer inventories.
RETAINED = (
    "install-candidate.sh",
    "managed_installation.py.lock",
    "codex-mcp-registration.py",
)
FIXED = (
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
    files = tuple((name, source_file(name)) for name in names)
    if any(path.is_symlink() or not path.is_file() for _, path in files):
        raise ValueError("missing installer support member")
    return files


def source_file(name: str) -> Path:
    if name == "install.sh":
        return REPOSITORY / name
    # These two embedded assembly modules retain repository-relative paths.
    # Moving them needs a separately authorized bootstrap-byte change.
    if name in FIXED or name in {"composed_bundle.py", "managed_headless.py", "uv.lock"}:
        return PACKAGING / name
    relative = SOURCE_PATHS.get(name)
    if relative is None:
        raise ValueError("unknown installer source member")
    return INTERNAL / relative


def stage_recipe(destination: Path) -> None:
    """Project the existing runtime recipe inventory without changing recorded names."""
    from runtime_base import RECIPE_INPUTS

    members = validate_members(RECIPE_INPUTS)
    files = tuple((name, source_file(name)) for name in members)
    if any(path.is_symlink() or not path.is_file() for _, path in files):
        raise ValueError("missing runtime recipe source")
    destination.mkdir()
    for name, source in files:
        _ = (destination / name).write_bytes(source.read_bytes())


def check_support(destination: Path) -> bool:
    files = support_files()
    return (
        destination.is_dir()
        and not destination.is_symlink()
        and {path.name for path in destination.iterdir()} == {name for name, _ in files}
        and all(
            (destination / name).is_file()
            and not (destination / name).is_symlink()
            and (destination / name).read_bytes() == source.read_bytes()
            for name, source in files
        )
    )


def stage_support(destination: Path) -> None:
    files = support_files()
    permitted = {name for name, _ in files}
    if destination.is_symlink() or (
        destination.exists()
        and any(
            path.name not in permitted or path.is_symlink() or not path.is_file()
            for path in destination.iterdir()
        )
    ):
        raise ValueError("foreign installer support member")
    destination.mkdir(parents=True, exist_ok=True)
    for name, source in files:
        _ = (destination / name).write_bytes(source.read_bytes())
    if not check_support(destination):
        raise ValueError("installer support projection changed")


def engine_member(name: str) -> bytes:
    """Omit comments from the transient engine copy; code and line numbers stay unchanged."""
    raw = source_file(name).read_bytes()
    if not name.endswith(".py"):
        return raw
    text = raw.decode()
    lines = text.splitlines(keepends=True)
    for token in tokenize.generate_tokens(io.StringIO(text).readline):
        if token.type == tokenize.COMMENT:
            row, column = token.start
            lines[row - 1] = lines[row - 1][:column].rstrip() + "\n"
    return "".join(lines).encode()


def render() -> bytes:
    payload = io.BytesIO()
    with tarfile.open(fileobj=payload, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name in sorted(validate_members(SOURCES)):
            raw = engine_member(name)
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
    args = parser.parse_args()
    if args.support_directory is not None:
        if args.check:
            return 0 if check_support(args.support_directory) else 1
        stage_support(args.support_directory)
        return 0
    destination = REPOSITORY / "install.sh"
    expected = render()
    if args.check:
        return 0 if destination.read_bytes() == expected else 1
    _ = destination.write_bytes(expected)
    destination.chmod(0o755)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
