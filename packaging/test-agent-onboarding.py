"""Exercise the actual passive hook and its host envelopes outside the plugin root."""

from __future__ import annotations

import ast
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "agent-tools"


class AgentOnboardingTest(unittest.TestCase):
    def test_hosts_receive_identical_bounded_context_with_absolute_paths(self):
        with tempfile.TemporaryDirectory(prefix="kast context $ literal ") as temporary:
            workspace = Path(temporary).resolve()
            texts = []
            for host in ("codex", "copilot", "pi"):
                arguments = [
                    sys.executable,
                    str(PLUGIN / "hooks/session_start.py"),
                    host,
                ]
                if host == "pi":
                    arguments.append(str(workspace))
                result = subprocess.run(
                    arguments,
                    input=json.dumps({"cwd": str(workspace)}),
                    cwd=workspace,
                    capture_output=True,
                    text=True,
                    timeout=10,
                    check=False,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stderr)
                if host == "codex":
                    output = json.loads(result.stdout)
                    self.assertEqual(["hookSpecificOutput"], list(output))
                    context = output["hookSpecificOutput"]
                    self.assertEqual("SessionStart", context["hookEventName"])
                    text = context["additionalContext"]
                elif host == "copilot":
                    output = json.loads(result.stdout)
                    self.assertEqual(["additionalContext"], list(output))
                    text = output["additionalContext"]
                else:
                    text = result.stdout.rstrip("\n") + "\n"
                texts.append(text)
                self.assertLessEqual(len(text.encode()), 8192)
                self.assertIn("The `interface` kind searches the CLASS family", text)
                for clause in (
                    "Apply the task's source-set and path exclusions before each read batch",
                    "Remove duplicate paths and reuse unchanged source reads",
                    "Keep intermediate results inside it",
                    "Keep requested matches and failures",
                    "Read only the needed section or named example for unfamiliar shapes",
                    "Incomplete results and scope qualifications do not prove absence. Preserve them",
                    "Use `find` with a simple `name`",
                    "Use `source` with its default `ANCHOR`",
                    "Read the bundled Kast skill",
                ):
                    self.assertIn(clause, text, host)
                brief = (PLUGIN / "references/onboarding.md").read_text().rstrip()
                self.assertTrue(text.startswith(brief + "\nWorkspace: "), host)
                self.assertIn(json.dumps(str(PLUGIN / "references/query-examples.json")), text)
                self.assertIn(json.dumps(str(workspace)), text)
            self.assertEqual(texts[0], texts[1])
            self.assertEqual(texts[1], texts[2])
            self.assertEqual([], list(workspace.iterdir()))

    def test_missing_resource_rejects_without_partial_context(self):
        with tempfile.TemporaryDirectory() as temporary:
            plugin = Path(temporary) / "plugin"
            shutil.copytree(PLUGIN, plugin)
            (plugin / "references/query-schema.json").unlink()
            result = subprocess.run(
                [sys.executable, str(plugin / "hooks/session_start.py"), "codex"],
                input=json.dumps({"cwd": temporary}),
                cwd=temporary,
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )
            self.assertEqual(1, result.returncode)
            self.assertEqual("", result.stdout)
            self.assertEqual(
                {"stage": "KAST_SESSION_CONTEXT", "outcome": "RESOURCE_REJECTED"},
                json.loads(result.stderr),
            )

    def test_malformed_or_relative_workspace_rejects_without_context(self):
        for event in (
            "not json",
            "[]",
            "{}",
            '{"cwd":"relative"}',
            '{"cwd":true}',
            " " * 65537,
        ):
            with self.subTest(event=event[:40]):
                result = subprocess.run(
                    [sys.executable, str(PLUGIN / "hooks/session_start.py"), "copilot"],
                    input=event,
                    capture_output=True,
                    text=True,
                    check=False,
                    timeout=10,
                )
                self.assertEqual(1, result.returncode)
                self.assertEqual("", result.stdout)
                self.assertEqual("INVALID_EVENT", json.loads(result.stderr)["outcome"])

    def test_oversized_or_linked_resources_reject_without_context(self):
        with tempfile.TemporaryDirectory() as temporary:
            plugin = Path(temporary) / "plugin"
            shutil.copytree(PLUGIN, plugin)
            brief = plugin / "references/onboarding.md"
            brief.write_text("a" * 8192)
            command = [
                sys.executable,
                str(plugin / "hooks/session_start.py"),
                "pi",
                temporary,
            ]
            oversized = subprocess.run(
                command, capture_output=True, text=True, check=False, timeout=10
            )
            self.assertEqual(1, oversized.returncode)
            self.assertEqual("", oversized.stdout)
            self.assertEqual("CONTEXT_TOO_LARGE", json.loads(oversized.stderr)["outcome"])
            brief.unlink()
            brief.symlink_to(PLUGIN / "references/onboarding.md")
            linked = subprocess.run(
                command, capture_output=True, text=True, check=False, timeout=10
            )
            self.assertEqual(1, linked.returncode)
            self.assertEqual("", linked.stdout)
            self.assertEqual("RESOURCE_REJECTED", json.loads(linked.stderr)["outcome"])

    def test_manifest_commands_work_with_minimal_host_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary).resolve()
            home = workspace / "home"
            binary = home / ".local/bin/python3"
            binary.parent.mkdir(parents=True)
            binary.symlink_to(sys.executable)
            environment = {
                "HOME": str(home),
                "PATH": "/usr/bin:/bin",
                "PLUGIN_ROOT": str(PLUGIN),
                "COPILOT_PLUGIN_ROOT": str(PLUGIN),
            }
            for host, filename, event in (
                ("codex", "hooks.json", "SessionStart"),
                ("copilot", "copilot.json", "sessionStart"),
            ):
                hooks = json.loads((PLUGIN / "hooks" / filename).read_text())["hooks"][event]
                command = hooks[0]["hooks"][0]["command"] if host == "codex" else hooks[0]["bash"]
                self.assertNotIn("uv ", command)
                result = subprocess.run(
                    ["/bin/bash", "-c", command],
                    cwd=workspace,
                    env=environment,
                    input=json.dumps({"cwd": str(workspace)}),
                    capture_output=True,
                    text=True,
                    check=False,
                    timeout=10,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("additionalContext", result.stdout)

    def test_context_script_uses_python39_syntax_without_runtime_dependencies(self):
        source = (PLUGIN / "hooks/session_start.py").read_text()
        ast.parse(source, feature_version=(3, 9))
        self.assertNotIn("pydantic", source)

    def test_codex_has_only_passive_context_hook(self):
        hooks = json.loads((PLUGIN / "hooks/hooks.json").read_text())["hooks"]["SessionStart"]
        self.assertEqual(1, len(hooks))
        self.assertEqual(1, len(hooks[0]["hooks"]))
        self.assertNotIn("prepare_workspace", hooks[0]["hooks"][0]["command"])


if __name__ == "__main__":
    unittest.main()
