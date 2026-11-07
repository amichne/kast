"""Native Linux ARM64 has a separate donor identity, never an emulation fallback."""

from dataclasses import asdict

import pytest
from composed_bundle import BundleManifest, BundleRejected, read_manifest
from composition_platform import HostPlatform, UnsupportedHost, host_platform
from test_composition_bundle import manifest_bytes


@pytest.mark.parametrize("machine", ["aarch64", "arm64"])
def test_linux_arm64_selects_its_own_pinned_donor(machine: str) -> None:
    target = host_platform("Linux", machine)
    assert not isinstance(target, UnsupportedHost)
    assert target.value == "linux-aarch64"
    assert target.layout.launch_arch == "aarch64"
    assert target.layout.archive_sha256 == (
        "04baba6fb1034aebfbf86e8b55186c818b93dbbbe131012975718ff7f70740f5"
    )
    assert target.layout.archive_sha256 != HostPlatform.LINUX_X86_64.layout.archive_sha256


def test_linux_arm64_bundle_preserves_architecture_at_the_boundary() -> None:
    result = read_manifest(manifest_bytes().replace(b"linux-x86_64", b"linux-aarch64"))
    assert isinstance(result, BundleManifest), result
    assert asdict(result)["platform"] == "linux-aarch64"


def test_unknown_architecture_does_not_fall_back_to_another_cpu() -> None:
    assert host_platform("Linux", "riscv64") == UnsupportedHost("Linux", "riscv64")
    assert isinstance(
        read_manifest(manifest_bytes().replace(b"linux-x86_64", b"linux-riscv64")),
        BundleRejected,
    )
