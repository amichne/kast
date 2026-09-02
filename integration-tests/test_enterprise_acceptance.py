#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("enterprise_acceptance.py")
SPEC = importlib.util.spec_from_file_location("enterprise_acceptance", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
enterprise_acceptance = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = enterprise_acceptance
SPEC.loader.exec_module(enterprise_acceptance)


class IsolatedAcceptanceHostTest(unittest.TestCase):
    def test_child_environment_replaces_ambient_state_with_isolated_authorities(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            outer = Path(raw)
            source_archive = outer / "semantic-runtime.zip"
            source_archive.write_bytes(b"runtime")
            host_root = outer / "host"
            host_root.mkdir()

            host = enterprise_acceptance.IsolatedAcceptanceHost.create(
                host_root,
                source_archive,
            )
            environment = host.child_environment(
                {
                    "HOME": "/ambient/home",
                    "CODEX_HOME": "/ambient/codex",
                    "JAVA_OPTS": "-Duser.home=/ambient/home",
                    "KAST_RUNTIME_DIRECTORY": "/ambient/runtime",
                    "PATH": "/usr/bin:/bin",
                    "JAVA_HOME": "/jdk",
                    "LANG": "en_US.UTF-8",
                    "SECRET_SENTINEL": "must-not-escape",
                }
            )

            self.assertEqual(str(host.home), environment["HOME"])
            self.assertEqual(str(host.codex_home), environment["CODEX_HOME"])
            self.assertEqual(f"-Duser.home={host.home}", environment["JAVA_OPTS"])
            self.assertEqual(str(host.runtime / "endpoints"), environment["KAST_RUNTIME_DIRECTORY"])
            self.assertEqual(str(host.runtime / "store"), environment["KAST_RUNTIME_STORE"])
            self.assertEqual(str(host.archive), environment["KAST_RUNTIME_ARCHIVE"])
            self.assertEqual(str(host.runtime / "intellij-caches"), environment["KAST_CACHE_ROOT"])
            self.assertEqual(str(host.temporary), environment["TMPDIR"])
            self.assertNotIn("SECRET_SENTINEL", environment)
            self.assertEqual(b"runtime", host.archive.read_bytes())
            for path in (
                host.home,
                host.codex_home,
                host.runtime,
                host.archive,
                host.app_server_control,
                host.temporary,
                host.workspace,
            ):
                self.assertTrue(path.resolve().is_relative_to(host.root))
            host.assert_confined()

    def test_cleanup_runs_after_passing_and_deliberately_failing_scenarios(self) -> None:
        for scenario_fails in (False, True):
            with self.subTest(scenario_fails=scenario_fails):
                events: list[str] = []

                class FakeAcceptance:
                    def prove_installed_surface(self, _bounds: object) -> None:
                        events.append("scenario")
                        if scenario_fails:
                            raise RuntimeError("deliberate acceptance failure")

                    def prove_workspace_write_scope(self) -> None:
                        events.append("workspace-scope")

                    def command(self, *argv: str) -> dict[str, str]:
                        self_outer.assertEqual(("stop",), argv)
                        events.append("stop-indexer")
                        return {
                            "command": "stop",
                            "status": "complete",
                            "runtime": "stopped",
                        }

                class FakeHost:
                    def retire_broker(self, _timeout_seconds: int) -> None:
                        events.append("retire-broker")

                    def assert_confined(self) -> None:
                        events.append("assert-confined")

                class FakeAmbient:
                    def assert_unchanged(self) -> None:
                        events.append("assert-ambient")

                self_outer = self
                if scenario_fails:
                    with self.assertRaisesRegex(RuntimeError, "deliberate"):
                        enterprise_acceptance.run_acceptance_scenario(
                            FakeAcceptance(), FakeHost(), {}, FakeAmbient(), 10
                        )
                    self.assertEqual(
                        [
                            "scenario",
                            "stop-indexer",
                            "retire-broker",
                            "assert-confined",
                            "assert-ambient",
                        ],
                        events,
                    )
                else:
                    enterprise_acceptance.run_acceptance_scenario(
                        FakeAcceptance(), FakeHost(), {}, FakeAmbient(), 10
                    )
                    self.assertEqual(
                        [
                            "scenario",
                            "workspace-scope",
                            "stop-indexer",
                            "retire-broker",
                            "assert-confined",
                            "assert-ambient",
                        ],
                        events,
                    )


if __name__ == "__main__":
    unittest.main()
