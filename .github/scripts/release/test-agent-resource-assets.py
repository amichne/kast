#!/usr/bin/env python3
import io
import json
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
PACKAGER = ROOT / ".github/scripts/release/agent-resource-assets.py"
TAG = "v1.2.3"
SHA = "a" * 40


class AgentResourceAssetsTest(unittest.TestCase):
    def test_assets_are_deterministic_versioned_and_verified(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "resources"
            self.write_source(source)
            first = root / "first"
            second = root / "second"
            for output in (first, second):
                subprocess.run(
                    [
                        "python3",
                        str(PACKAGER),
                        "build",
                        "--source",
                        str(source),
                        "--output",
                        str(output),
                        "--tag",
                        TAG,
                        "--git-sha",
                        SHA,
                    ],
                    check=True,
                )

            expected = {
                *(f"kast-{provider}-{TAG}.tar" for provider in ("codex", "claude", "copilot")),
                *(f"kast-{provider}-{TAG}.tar.sha256sum" for provider in ("codex", "claude", "copilot")),
                "kast-agent-resources-provenance.json",
            }
            self.assertEqual(expected, {path.name for path in first.iterdir()})
            for name in expected:
                self.assertEqual((first / name).read_bytes(), (second / name).read_bytes())

            with tarfile.open(first / f"kast-codex-{TAG}.tar") as archive:
                members = archive.getmembers()
                self.assertEqual(
                    {
                        ".agents/plugins/marketplace.json",
                        "plugins/kast/.codex-plugin/plugin.json",
                        "plugins/kast/hooks/hooks.json",
                        "plugins/kast/skills/kast/SKILL.md",
                    },
                    {member.name for member in members},
                )
                for member in members:
                    self.assertTrue(member.isreg())
                    self.assertEqual((0, 0, 0, 0o644, "", ""), (
                        member.mtime,
                        member.uid,
                        member.gid,
                        member.mode,
                        member.uname,
                        member.gname,
                    ))
                plugin = json.load(
                    io.TextIOWrapper(
                        archive.extractfile("plugins/kast/.codex-plugin/plugin.json"),
                        encoding="utf-8",
                    )
                )
                self.assertEqual("1.2.3", plugin["version"])

            verify = [
                "python3",
                str(PACKAGER),
                "verify",
                "--release-dir",
                str(first),
                "--tag",
                TAG,
                "--git-sha",
                SHA,
            ]
            subprocess.run(verify, check=True)
            with (first / f"kast-codex-{TAG}.tar").open("ab") as asset:
                asset.write(b"tampered")
            self.assertNotEqual(0, subprocess.run(verify, check=False).returncode)

    @staticmethod
    def write_source(source: Path) -> None:
        source.mkdir(parents=True)
        (source / "SKILL.md").write_text("Use `kast`.\n", encoding="utf-8")
        for provider in ("codex", "claude", "copilot"):
            directory = source / provider
            directory.mkdir()
            (directory / "marketplace.json").write_text(
                json.dumps({"name": "kast", "plugins": [{"name": "kast"}]}) + "\n",
                encoding="utf-8",
            )
            (directory / "plugin.json").write_text(
                json.dumps({"name": "kast", "version": "${KAST_VERSION}"}) + "\n",
                encoding="utf-8",
            )
            (directory / "hooks.json").write_text(
                json.dumps({"command": "${HOME}/.local/bin/kast"}) + "\n",
                encoding="utf-8",
            )


if __name__ == "__main__":
    unittest.main()
