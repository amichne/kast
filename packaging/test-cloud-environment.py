"""Offline cloud boundaries: refuse changed sources and participant-context receipts."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CLOUD = Path(__file__).resolve().parent / "cloud"
spec = importlib.util.spec_from_file_location("cloud_bootstrap", CLOUD / "bootstrap.py")
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class CloudEnvironmentTest(unittest.TestCase):
    def test_container_builder_uses_local_socket_and_preserves_registry_configuration(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            docker = root / "docker"
            receipt = root / "calls.jsonl"
            docker.write_text("#!" + sys.executable + "\n" +
                "import json, os, sys\n" +
                "with open(os.environ['CLOUD_DOCKER_RECEIPT'], 'a') as output:\n" +
                " output.write(json.dumps({'argv':sys.argv[1:], 'config':os.environ.get('DOCKER_CONFIG'), 'selectors':[key for key in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH') if key in os.environ]})+'\\n')\n")
            docker.chmod(0o755)
            config = root / "existing-registry-config"
            environment = {**os.environ, "PATH": str(root) + os.pathsep + os.defpath,
                "DOCKER_CONFIG": str(config), "DOCKER_HOST": "tcp://unrelated.invalid:2376",
                "DOCKER_CONTEXT": "unrelated", "DOCKER_TLS": "1", "DOCKER_TLS_VERIFY": "1",
                "DOCKER_CERT_PATH": "/unrelated", "CLOUD_DOCKER_RECEIPT": str(receipt)}
            for key in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
                environment.pop(key, None)
            subprocess.run([sys.executable, str(CLOUD / "container.py")], env=environment, check=True)
            calls = [json.loads(line) for line in receipt.read_text().splitlines()]
            self.assertEqual(2, len(calls))
            self.assertEqual(["--host=unix:///var/run/docker.sock", "info"], calls[0]["argv"])
            self.assertEqual(["--host=unix:///var/run/docker.sock", "build"], calls[1]["argv"][:2])
            self.assertTrue(all(call["config"] == str(config) and not call["selectors"] for call in calls))
            self.assertFalse(config.exists(), "The helper must not create or replace registry configuration")

    def test_source_pin_rejects_tracked_changes_and_untracked_execution_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            def git(*arguments):
                return subprocess.check_output(["git", "-C", str(repository), *arguments], text=True).strip()
            git("init", "--quiet")
            source = repository / "extension.ts"
            source.write_text("export default function () {}\n")
            git("add", "extension.ts")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture")
            revision = git("rev-parse", "HEAD")
            bootstrap.verify_source(repository, revision)
            source.write_text("modified\n")
            with self.assertRaisesRegex(SystemExit, "modified source"):
                bootstrap.verify_source(repository, revision)
            git("checkout", "--", "extension.ts")
            (repository / "extra.ts").write_text("untracked executable input\n")
            with self.assertRaisesRegex(SystemExit, "modified source"):
                bootstrap.verify_source(repository, revision)
            (repository / "extra.ts").unlink()
            with self.assertRaisesRegex(SystemExit, "unpinned source"):
                bootstrap.verify_source(repository, "0" * 40)

    def test_fixture_receipt_inside_participant_tree_is_rejected_before_export(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "participant"
            result = subprocess.run([sys.executable, str(CLOUD / "prepare-fixture.py"),
                "--repository", "/does-not-exist", "--revision", "HEAD", "--fixture", "semantic-fixture",
                "--destination", str(destination), "--receipt", str(destination / "receipt.json")],
                capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("receipt outside participant", result.stderr)
            self.assertFalse(destination.exists())

    def test_plan_inside_participant_tree_is_rejected_before_node_or_authentication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workspace = root / "participant"
            workspace.mkdir()
            plan = workspace / "plan.json"
            plan.write_text(json.dumps({"workspaceRoot": str(workspace), "outputRoot": str(root / "output")}))
            result = subprocess.run([sys.executable, str(CLOUD / "evaluate.py"), "--plan", str(plan)],
                capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("plan outside participant", result.stderr)


if __name__ == "__main__":
    unittest.main()
