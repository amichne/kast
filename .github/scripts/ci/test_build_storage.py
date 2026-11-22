import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import workflow_contract

SCRIPT = Path(__file__).with_name("build_storage.py")


class BuildStorageTest(unittest.TestCase):
    def test_preflight_accepts_current_space_and_rejects_impossible_space(self):
        with tempfile.TemporaryDirectory() as directory:
            for minimum, expected in ((1, "READY"), (1 << 63, "LOW_SPACE")):
                with self.subTest(minimum=minimum):
                    result = subprocess.run(
                        [sys.executable, str(SCRIPT), "check", "--minimum-bytes",
                         str(minimum), directory],
                        capture_output=True, text=True, check=False,
                    )
                    self.assertEqual(0 if expected == "READY" else 1, result.returncode, result.stderr)
                    self.assertEqual(expected, json.loads(result.stdout)["status"])

    def test_inventory_reports_allocated_output_and_ignores_symlink_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            repo = root / "repo"
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            outside = root / "outside"
            outside.mkdir()
            (outside / "preserved").write_bytes(b"external")
            (repo / ".gradle").symlink_to(outside, target_is_directory=True)
            build = repo / "build"
            build.mkdir()
            (build / "artifact").write_bytes(b"generated" * 4096)
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "inventory", "--minimum-bytes", "1",
                 "--repo", str(repo)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            outputs = json.loads(result.stdout)["outputs"]
            self.assertEqual([(str(repo), str(build))],
                             [(row["owner"], row["path"]) for row in outputs])
            self.assertGreater(outputs[0]["allocated_bytes"], 0)
            self.assertEqual(b"external", (outside / "preserved").read_bytes())
            self.assertTrue((build / "artifact").is_file())

    def test_composed_workflow_prepares_signed_support_before_gradle_assembly(self):
        # The workflow orders the steps; the tasks own the commands. test_composition_ci.py
        # proves that the linux-support plan runs `composed_release.py linux-support` and the
        # release plan runs `assembleRelease`.
        workflow = SCRIPT.parents[2] / "workflows/composed-linux.yml"
        text = workflow.read_text()
        support = 'run: mise run composed:linux-support --target "$TARGET"'
        self.assertEqual(1, text.count("mise run composed:linux-support"))
        self.assertLess(text.index(support), text.index("run: mise run composed:release "))
        tasks = workflow_contract.tasks()
        self.assertEqual(
            "python3 packaging/internal/composition/composed_ci.py linux-support",
            tasks["composed:linux-support"]["run"],
        )
        self.assertIn("composed_ci.py release ", tasks["composed:release"]["run"])
