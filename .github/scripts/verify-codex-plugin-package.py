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
    "plugins/kast/hooks/hooks.json",
    "plugins/kast/scripts/kast-codex-hook",
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
    launcher = "plugins/kast/scripts/kast-codex-hook"
    if archive.getinfo(launcher).external_attr >> 16 & 0o111 == 0:
        fail(f"{launcher} must be executable")
    manifest = json.loads(archive.read("plugins/kast/.codex-plugin/plugin.json"))
    if manifest.get("version") != expected_version:
        fail("plugin version mismatch")
    if any(key in manifest for key in ("hooks", "agents", "apps", "mcpServers")):
        fail("plugin manifest exposes a forbidden integration")
    hooks = json.loads(archive.read("plugins/kast/hooks/hooks.json"))
    if set(hooks) != {"hooks"} or set(hooks["hooks"]) != {"SessionStart", "PostToolUse"}:
        fail("hooks.json must define exactly SessionStart and PostToolUse")
    if hooks["hooks"]["SessionStart"][0].get("matcher") != "startup":
        fail("SessionStart must match startup")
    if hooks["hooks"]["PostToolUse"][0].get("matcher") != "apply_patch|Edit|Write":
        fail("PostToolUse must match apply_patch, Edit, and Write")
    skill = archive.read("plugins/kast/skills/kast-codex/SKILL.md").decode()
    if "synchronously" not in skill:
        fail("skill does not describe terminal synchronous mutations")

print(f"Verified advisory-hook Kast Codex plugin package {archive_path} at version {expected_version}")
