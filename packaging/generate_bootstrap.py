"""Generate the self-contained public bootstrap from its existing Python owners."""

import argparse
import base64
import bz2
import hashlib
import io
import tarfile
from pathlib import Path

from private_interpreter import DEPENDENCY_SHA256, DEPENDENCY_URL, PINS

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
    "isolated_runtime.py",
    "managed_headless.py",
    "runtime_archive.py",
    "runtime_base.py",
)
HERE = Path(__file__).resolve().parent


def render() -> bytes:
    payload = io.BytesIO()
    with tarfile.open(fileobj=payload, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name in sorted(SOURCES):
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
    args = parser.parse_args()
    destination = HERE.parent / "install.sh"
    expected = render()
    if args.check:
        return 0 if destination.read_bytes() == expected else 1
    _ = destination.write_bytes(expected)
    destination.chmod(0o755)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
