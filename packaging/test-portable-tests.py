"""Exact platform skips for the existing offline packaging runner."""

from __future__ import annotations

import importlib.util
import platform
import unittest
from pathlib import Path
from typing import Protocol, runtime_checkable
from unittest.mock import patch


@runtime_checkable
class SkipPolicy(Protocol):
    def expected_skips(self) -> dict[str, int]: ...


def load_policy() -> SkipPolicy:
    path = Path(__file__).with_name("run-portable-tests.py")
    spec = importlib.util.spec_from_file_location("portable_runner", path)
    if spec is None or spec.loader is None:
        raise AssertionError("the portable runner has no import loader")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if not isinstance(module, SkipPolicy):
        raise AssertionError("the portable runner has no skip policy")
    return module


class PortableSkipInventoryTest(unittest.TestCase):
    def admitted_skips(self, system: str, machine: str) -> dict[str, int]:
        with (
            patch.object(platform, "system", return_value=system),
            patch.object(platform, "machine", return_value=machine),
        ):
            return load_policy().expected_skips()

    def test_darwin_arm64_permits_no_skips(self) -> None:
        self.assertEqual({}, self.admitted_skips("Darwin", "arm64"))

    def test_darwin_x86_64_permits_only_release_installer_skips(self) -> None:
        self.assertEqual({"test-install-checkout.py": 15}, self.admitted_skips("Darwin", "x86_64"))

    def test_linux_arm64_permits_both_exact_platform_counts(self) -> None:
        self.assertEqual(
            {"test-install-checkout.py": 15, "test-host-installation.py": 2},
            self.admitted_skips("Linux", "arm64"),
        )

    def test_linux_x86_64_permits_both_exact_platform_counts(self) -> None:
        self.assertEqual(
            {"test-install-checkout.py": 15, "test-host-installation.py": 2},
            self.admitted_skips("Linux", "x86_64"),
        )


if __name__ == "__main__":
    _ = unittest.main()
