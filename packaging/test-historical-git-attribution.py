"""Independent Git attribution oracle. All repository effects use case-owned roots."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).parent / "topology" / "git_attribution.py"


class GitAttributionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.owner = tempfile.TemporaryDirectory(prefix="kast-historical-git-")
        self.addCleanup(self.owner.cleanup)
        self.root = Path(self.owner.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.text = b"package api\nfun selected(value: Int) = value\n"
        (self.root / "src").mkdir()
        (self.root / "src/Api.kt").write_bytes(self.text)
        self.git("add", "src/Api.kt")
        self.git("commit", "--quiet", "-m", "fixture A")
        self.commit = self.git("rev-parse", "HEAD").strip()
        self.tree = self.git("rev-parse", "HEAD^{tree}").strip()
        self.blob = self.git("rev-parse", "HEAD:src/Api.kt").strip()

    def git(self, *args: str) -> str:
        return subprocess.run(
            ["git", "-C", str(self.root), *args],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env={key: value for key, value in os.environ.items() if not key.startswith("GIT_")},
        ).stdout

    def request(self, content: bytes | None = None) -> dict[str, object]:
        return {
            "repository": "https://example.invalid/team/fixture.git",
            "commit": self.commit,
            "files": [{"path": "src/Api.kt", "content": hashlib.sha256(self.text if content is None else content).hexdigest()}],
        }

    def run_adapter(self, request: dict[str, object], checkout: Path | None = None) -> dict[str, object]:
        request_path = self.root / "request.json"
        request_path.write_text(json.dumps(request), encoding="utf-8")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--checkout", str(self.root if checkout is None else checkout), "--request", str(request_path)],
            check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def test_exact_revision_and_native_input_hash_receive_git_blob_identity(self) -> None:
        before = self.git("status", "--porcelain")
        result = self.run_adapter(self.request())
        self.assertEqual("attributed", result["status"])
        self.assertEqual({"repository": "https://example.invalid/team/fixture.git", "commit": self.commit, "tree": self.tree}, result["revision"])
        self.assertEqual([{ "path": "src/Api.kt", "content": hashlib.sha256(self.text).hexdigest(), "blob": self.blob}], result["files"])
        self.assertEqual(1, result["work"]["filesCompared"])
        self.assertEqual(len(self.text) * 2, result["work"]["bytesExamined"])
        self.assertEqual(6, result["work"]["gitCalls"])
        self.assertEqual(before, self.git("status", "--porcelain", "--untracked-files=no"))
        self.assertEqual(self.commit, self.git("rev-parse", "HEAD").strip())

    def test_dirty_source_rejects_even_when_request_repeats_committed_hash(self) -> None:
        (self.root / "src/Api.kt").write_bytes(self.text + b"// changed\n")
        result = self.run_adapter(self.request())
        self.assertEqual("rejected", result["status"])
        self.assertEqual("CHECKOUT_CONTENT_MISMATCH", result["cause"])
        self.assertEqual(self.text + b"// changed\n", (self.root / "src/Api.kt").read_bytes())
        self.assertEqual(self.commit, self.git("rev-parse", "HEAD").strip())

    def test_matching_current_content_cannot_be_attributed_to_old_commit(self) -> None:
        changed = self.text + b"fun added() = 2\n"
        (self.root / "src/Api.kt").write_bytes(changed)
        self.git("add", "src/Api.kt")
        self.git("commit", "--quiet", "-m", "fixture B")
        result = self.run_adapter(self.request(changed))
        self.assertEqual("rejected", result["status"])
        self.assertEqual("REVISION_CONTENT_MISMATCH", result["cause"])

    def test_untracked_admitted_source_cannot_enter_selected_revision(self) -> None:
        (self.root / "src/New.kt").write_bytes(self.text)
        request = self.request()
        request["files"][0]["path"] = "src/New.kt"
        result = self.run_adapter(request)
        self.assertEqual("rejected", result["status"])
        self.assertEqual("TRACKED_SOURCE_UNAVAILABLE", result["cause"])

    def test_symlink_is_rejected_without_following_its_target(self) -> None:
        outside = self.root / "external.kt"
        outside.write_bytes(self.text)
        target = self.root / "src/Api.kt"
        target.unlink()
        target.symlink_to(outside)
        result = self.run_adapter(self.request())
        self.assertEqual("rejected", result["status"])
        self.assertEqual("UNSUPPORTED_CHECKOUT_PATH", result["cause"])

    def test_unknown_request_fields_and_parent_paths_fail_before_git(self) -> None:
        invalid = self.request()
        invalid["extra"] = True
        result = self.run_adapter(invalid)
        self.assertEqual("INVALID_REQUEST", result["cause"])
        self.assertEqual(0, result["work"]["gitCalls"])
        invalid = self.request()
        invalid["files"][0]["path"] = "../Api.kt"
        result = self.run_adapter(invalid)
        self.assertEqual("INVALID_REQUEST", result["cause"])
        self.assertEqual(0, result["work"]["gitCalls"])

    def test_duplicate_source_obligation_is_rejected(self) -> None:
        invalid = self.request()
        invalid["files"] += invalid["files"]
        result = self.run_adapter(invalid)
        self.assertEqual("INVALID_REQUEST", result["cause"])
        self.assertEqual(0, result["work"]["gitCalls"])

    def test_nested_directory_cannot_borrow_parent_repository_identity(self) -> None:
        nested = self.root / "nested"
        (nested / "src").mkdir(parents=True)
        (nested / "src/Api.kt").write_bytes(self.text)
        result = self.run_adapter(self.request(), checkout=nested)
        self.assertEqual("rejected", result["status"])
        self.assertEqual("CHECKOUT_ROOT_MISMATCH", result["cause"])

    def test_source_capacity_rejects_before_git_or_file_reads(self) -> None:
        request = self.request()
        content = hashlib.sha256(self.text).hexdigest()
        request["files"] = [{"path": f"src/File{index}.kt", "content": content} for index in range(10_001)]
        result = self.run_adapter(request)
        self.assertEqual("rejected", result["status"])
        self.assertEqual("CAPACITY_EXCEEDED", result["cause"])
        self.assertEqual({"gitCalls": 0, "filesCompared": 0, "bytesExamined": 0}, result["work"])

    def test_empty_admitted_file_inventory_remains_explicit(self) -> None:
        request = self.request()
        request["files"] = []
        result = self.run_adapter(request)
        self.assertEqual("attributed", result["status"])
        self.assertEqual([], result["files"])
        self.assertEqual(0, result["work"]["filesCompared"])
        self.assertEqual(0, result["work"]["bytesExamined"])


if __name__ == "__main__":
    unittest.main()
