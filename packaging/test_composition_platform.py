"""Native donors retain the exact operating system and CPU identity."""

import pytest

from composed_bundle import BundleRejected, read_manifest
from composition_platform import HostPlatform, UnsupportedHost, host_platform
from test_composition_bundle import manifest_bytes


@pytest.mark.parametrize(
    ("system", "machine", "expected"),
    [
        ("Darwin", "arm64", "macos-aarch64"),
        ("Darwin", "x86_64", "macos-x86_64"),
        ("Linux", "aarch64", "linux-aarch64"),
        ("Linux", "arm64", "linux-aarch64"),
        ("Linux", "x86_64", "linux-x86_64"),
        ("Linux", "amd64", "linux-x86_64"),
    ],
)
def test_native_platforms(system: str, machine: str, expected: str) -> None:
    actual = host_platform(system, machine)
    assert isinstance(actual, HostPlatform)
    assert actual.value == expected


def test_unknown_architecture_does_not_fall_back_to_another_cpu() -> None:
    assert host_platform("Linux", "riscv64") == UnsupportedHost("Linux", "riscv64")
    assert isinstance(
        read_manifest(manifest_bytes().replace(b"linux-x86_64", b"linux-riscv64")),
        BundleRejected,
    )
