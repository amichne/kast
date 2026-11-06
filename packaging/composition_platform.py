"""Two pinned donor layouts cannot mix archive identities."""

from dataclasses import dataclass
from enum import StrEnum
from typing import assert_never


@dataclass(frozen=True, slots=True)
class PlatformLayout:
    archive_sha256: str
    product: str
    home: str
    launch_os: str
    launch_arch: str
    support: tuple[str, ...]


class HostPlatform(StrEnum):
    MACOS_ARM64 = "macos-aarch64"
    LINUX_X86_64 = "linux-x86_64"

    @property
    def layout(self) -> PlatformLayout:
        match self:
            case HostPlatform.MACOS_ARM64:
                return PlatformLayout(
                    "f4a45f451008c86ce4e9342d5931fd89c8553f9d3ab6e8012da7533e9fe5c249",
                    "Resources/product-info.json",
                    "runtime/Kast Analysis Host.app/Contents",
                    "macOS",
                    "aarch64",
                    ("Info.plist", "Resources/build.txt"),
                )
            case HostPlatform.LINUX_X86_64:
                return PlatformLayout(
                    "f1cc5329a7adf3ab3bd8886744103f7d3bcf1ca12e699762ecd9bffe57335f8b",
                    "product-info.json",
                    "runtime/kast-analysis-host",
                    "Linux",
                    "amd64",
                    ("build.txt",),
                )
            case _:
                assert_never(self)


@dataclass(frozen=True, slots=True)
class UnsupportedHost:
    system: str
    machine: str


def host_platform(system: str, machine: str) -> HostPlatform | UnsupportedHost:
    match system, machine:
        case "Darwin", "arm64":
            return HostPlatform.MACOS_ARM64
        case "Linux", "x86_64":
            return HostPlatform.LINUX_X86_64
        case _:
            return UnsupportedHost(system, machine)
