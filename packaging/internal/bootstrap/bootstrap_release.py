"""Pinned Go bootstrap executables. Their pre-release exists before a pin names it."""

from __future__ import annotations

from dataclasses import dataclass

TAG = "bootstrap-392890fc8287"


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
        "61b04be35af7ed904f0c72789972b4143c78803d012c906b5781a5fd25653d28",
    ),
    BootstrapPin(
        "linux-x86_64",
        "172c5e00301015602c2bd0b6fbe18fde69c65733dfc0ca931806d0850d26d0e9",
    ),
)
