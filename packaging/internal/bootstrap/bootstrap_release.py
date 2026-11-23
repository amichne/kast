"""Pinned Go bootstrap executables. Their pre-release exists before a pin names it."""

from __future__ import annotations

from dataclasses import dataclass

TAG = "bootstrap-557978d7432f"


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
        "60451e1905f4991083939d1d841fae8740676eb2d5a45a3d095272a3c11d92ae",
    ),
    BootstrapPin(
        "linux-x86_64",
        "6d6a2ba78d3fa13df6d797f545171c81a11e08507cdcaad185a6a6f25ca7a5b8",
    ),
)
