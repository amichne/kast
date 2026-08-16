#!/usr/bin/env python3
"""Deterministic contract tests for macOS smoke process ownership."""

from __future__ import annotations

import importlib.util
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("smoke_macos_fixture_processes.py")
SPEC = importlib.util.spec_from_file_location("smoke_macos_fixture_processes", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load fixture process guard: {MODULE_PATH}")
GUARD = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = GUARD
SPEC.loader.exec_module(GUARD)


class FixtureProcessIdentityTest(unittest.TestCase):
    def test_reused_preexisting_pid_is_eligible_only_with_a_new_birth(self) -> None:
        document = {
            "ownerUid": os.getuid(),
            "preexistingIdentities": {"41": "100:000001"},
        }
        reused = GUARD.Process(41, 1, os.getuid(), "S", "101:000002", "fixture")
        original = GUARD.Process(41, 1, os.getuid(), "S", "100:000001", "unrelated")

        self.assertTrue(GUARD.eligible(reused, document))
        self.assertFalse(GUARD.eligible(original, document))

    def test_process_snapshot_rejects_mixed_birth_and_metadata(self) -> None:
        identity = GUARD.KernelIdentity
        samples = {
            42: [
                identity(42, 7, os.getuid(), "100:000001"),
                identity(42, 8, os.getuid(), "101:000002"),
            ],
            43: [
                identity(43, 7, os.getuid(), "102:000003"),
                identity(43, 7, os.getuid(), "102:000003"),
            ],
        }

        def next_identity(pid: int):
            return samples[pid].pop(0)

        metadata = (
            f"42 7 {os.getuid()} S stale-command\n"
            f"43 7 {os.getuid()} S coherent-command\n"
        )
        with (
            patch.object(GUARD, "list_process_ids", return_value=[42, 43]),
            patch.object(GUARD, "kernel_identity", side_effect=next_identity),
            patch.object(GUARD.subprocess, "check_output", return_value=metadata),
        ):
            captured = GUARD.processes()

        self.assertNotIn(42, captured)
        self.assertEqual("coherent-command", captured[43].command)


if __name__ == "__main__":
    unittest.main()
