"""Pinned Go bootstrap executables. Their pre-release exists before a pin names it."""

from __future__ import annotations

from dataclasses import dataclass

TAG = "bootstrap-4b702a948ddc"


@dataclass(frozen=True)
class BootstrapPin:
    target: str
    sha256: str

    @property
    def url(self) -> str:
        return (
            f"https://github.com/amichne/kast/releases/download/{TAG}/kast-bootstrap-{self.target}"
        )


BOOTSTRAP_PINS = (
    BootstrapPin(
        "macos-aarch64",
        "171ae1299bfee89151b3f390bcd578aa4efab64f28e895aae09ec47f77cb8c46",
    ),
    BootstrapPin(
        "linux-x86_64",
        "0217c3cf18027e93ad130d3255c9d1ae444ae18534a35b0a2414f08ca5838b76",
    ),
)
