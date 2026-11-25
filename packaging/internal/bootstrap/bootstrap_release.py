"""Pinned Go bootstrap executables. Their pre-release exists before a pin names it."""

from __future__ import annotations

from dataclasses import dataclass

TAG = "bootstrap-32d92ee987a7"


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
        "eab3272c4d8097950fb81540060e80c0515b6c501237b7fab9352b769f70fd1a",
    ),
    BootstrapPin(
        "linux-x86_64",
        "0dfd36a7953393ecb440a1ecaa1c3e7580c567a5f386b105da1aa5192fd3be9c",
    ),
)
