#!/usr/bin/env python3
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"error: {message}")


def formula_version(content: str, label: str) -> str:
    matches = re.findall(r'ARTIFACT_VERSION = "([^"]+)"', content)
    require(len(matches) == 1, f"{label} must contain exactly one artifact version constant")
    return matches[0]


root = Path(os.environ.get("KAST_TAP_ROOT", Path(__file__).resolve().parents[1]))
kast_formula = root / "Formula" / "kast.rb"
readme = root / "README.md"
release_state = root / "release-state.json"
release_state_helper = root / "scripts" / "release-state.py"
updater = root / "scripts" / "update-formulas.py"

require(kast_formula.is_file(), "Formula/kast.rb is missing")
require(not (root / "Casks" / "kast-plugin.rb").exists(), "the retired plugin cask must be absent")
require(readme.is_file(), "README.md is missing")
require(release_state.is_file(), "release-state.json is missing")
require(release_state_helper.is_file(), "scripts/release-state.py is missing")
require(updater.is_file(), "scripts/update-formulas.py is missing")

kast = kast_formula.read_text(encoding="utf-8")
docs = readme.read_text(encoding="utf-8")
state = json.loads(release_state.read_text(encoding="utf-8"))
version = formula_version(kast, "Formula/kast.rb")
require(state.get("current_release") == f"v{version}", "release state must match the formula")
require("brew install amichne/kast/kast" in docs, "README must document CLI installation")
require("kast repair --for machine --apply" in docs, "README must document CLI receipt repair")
require("Install Plugin from Disk" in docs, "README must document JetBrains-owned plugin installation")
require("custom plugin repository" in docs, "README must document explicit IDE repository enrollment")
require("kast developer machine plugin" not in docs + kast, "retired plugin command must be absent")
require("PLUGIN_CASK" not in kast, "formula must not name a plugin cask")
require("HOMEBREW_KAST_CLI_RELEASE_ROOT" in kast, "formula must support a CLI artifact mirror")
require("on_linux" not in kast, "formula must remain macOS-only")
require(
    'bin.install "kast", "kast-agent-task"' in kast,
    "formula must install the Rust CLI and its policy-free task launcher",
)
require("def post_install" not in kast, "formula must not mutate user profiles")
require("sudo" not in kast, "formula must not recommend sudo")

with tempfile.TemporaryDirectory() as tmp:
    tap_root = Path(tmp)
    shutil.copytree(root / "Formula", tap_root / "Formula")
    shutil.copy2(readme, tap_root / "README.md")
    shutil.copy2(release_state, tap_root / "release-state.json")
    env = {
        **os.environ,
        "KAST_TAP_ROOT": str(tap_root),
        "VERSION": "v9.8.7",
        "SHA256_MACOS_X64": "1" * 64,
        "SHA256_MACOS_ARM64": "2" * 64,
    }
    subprocess.run([str(updater)], env=env, check=True)
    updated_kast = (tap_root / "Formula" / "kast.rb").read_text(encoding="utf-8")
    updated_docs = (tap_root / "README.md").read_text(encoding="utf-8")
    updated_state = json.loads((tap_root / "release-state.json").read_text(encoding="utf-8"))
    require(formula_version(updated_kast, "updated formula") == "9.8.7", "updater must set CLI version")
    require(updated_state.get("current_release") == "v9.8.7", "updater must set release state")
    require("/v9.8.7/kast-v9.8.7-macos-arm64.zip" in updated_docs, "updater must set CLI mirror example")
    require('sha256 "' + ("1" * 64) + '"' in updated_kast, "updater must set x64 sha")
    require('sha256 "' + ("2" * 64) + '"' in updated_kast, "updater must set arm64 sha")

print("Homebrew CLI-only package contract test passed")
