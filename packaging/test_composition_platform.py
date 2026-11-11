"""Unsupported native architectures reject without a donor fallback."""

import pytest

from composed_bundle import BundleRejected, read_manifest
from composition_platform import UnsupportedHost, host_platform
from test_composition_bundle import manifest_bytes


@pytest.mark.parametrize("machine", ["aarch64", "arm64"])
def test_linux_arm64_rejects(machine: str) -> None:
    assert host_platform("Linux", machine) == UnsupportedHost("Linux", machine)


def test_linux_arm64_bundle_rejects() -> None:
    assert isinstance(
        read_manifest(manifest_bytes().replace(b"linux-x86_64", b"linux-aarch64")),
        BundleRejected,
    )


def test_unknown_architecture_does_not_fall_back_to_another_cpu() -> None:
    assert host_platform("Linux", "riscv64") == UnsupportedHost("Linux", "riscv64")
    assert isinstance(
        read_manifest(manifest_bytes().replace(b"linux-x86_64", b"linux-riscv64")),
        BundleRejected,
    )
