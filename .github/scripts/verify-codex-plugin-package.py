#!/usr/bin/env python3
import json
import pathlib
import stat
import sys
import zipfile

EXPECTED = {
    "marketplace.json",
    ".agents/plugins/marketplace.json",
    "plugins/kast/.codex-plugin/plugin.json",
    "plugins/kast/skills/kast-codex/SKILL.md",
    "plugins/kast/skills/kast-codex/agents/openai.yaml",
    "plugins/kast/assets/codex-exposure.toon",
    "plugins/kast/assets/kast.svg",
}

def fail(message: str) -> None:
    raise SystemExit(message)

if len(sys.argv) != 3:
    fail("usage: verify-codex-plugin-package.py <archive> <expected-version>")

archive_path = pathlib.Path(sys.argv[1])
expected_version = sys.argv[2]
with zipfile.ZipFile(archive_path) as archive:
    names = {name for name in archive.namelist() if not name.endswith("/")}
    if names != EXPECTED:
        fail(f"unexpected archive files: {sorted(names ^ EXPECTED)}")
    for name in names:
        mode = archive.getinfo(name).external_attr >> 16
        if not stat.S_ISREG(mode):
            fail(f"{name} must be a regular file")
    manifest = json.loads(archive.read("plugins/kast/.codex-plugin/plugin.json"))
    if manifest.get("version") != expected_version:
        fail("plugin version mismatch")
    if any(key in manifest for key in ("hooks", "agents", "apps", "mcpServers")):
        fail("plugin manifest exposes a forbidden integration")
    skill = archive.read("plugins/kast/skills/kast-codex/SKILL.md").decode()
    if "synchronously" not in skill:
        fail("skill does not describe terminal synchronous mutations")

print(f"Verified skill-only Kast Codex plugin package {archive_path} at version {expected_version}")
