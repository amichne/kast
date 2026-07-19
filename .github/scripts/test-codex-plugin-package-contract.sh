#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
verifier="${repo_root}/.github/scripts/verify-codex-plugin-package.py"
[[ -x "$verifier" ]] || die "Codex plugin verifier is missing or not executable: $verifier"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-codex-plugin-package.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

archive="${scratch_dir}/kast-codex-plugin-v9.8.7.zip"
write_fixture() {
  local mutation="${1:-valid}"
  python3 - "$archive" "$mutation" <<'PY'
import json
import stat
import sys
import zipfile
from pathlib import Path

archive_path = Path(sys.argv[1])
mutation = sys.argv[2]
manifest_version = "9.8.8" if mutation == "manifest-version" else "9.8.7"
exposure_version = "9.8.8" if mutation == "exposure-version" else "9.8.7"

marketplace = {
    "name": "kast",
    "plugins": [{
        "name": "kast",
        "source": {"source": "local", "path": "./plugins/kast"},
        "policy": {"installation": "AVAILABLE", "authentication": "ON_INSTALL"},
        "category": "Productivity",
    }],
}
manifest = {
    "name": "kast",
    "version": manifest_version,
    "description": "Kast Codex plugin fixture",
    "author": {"name": "Austin Michne"},
    "homepage": "https://kast.michne.com/",
    "repository": "https://github.com/amichne/kast",
    "license": "MIT",
    "skills": "./skills/",
    "interface": {
        "privacyPolicyURL": "https://kast.michne.com/privacy/",
        "termsOfServiceURL": "https://kast.michne.com/terms/",
    },
}
hook_events = {
    "SessionStart": "session-start",
    "PreToolUse": "pre-tool-use",
    "PostToolUse": "post-tool-use",
    "Stop": "stop",
}
hooks = {
    "hooks": {
        codex_event: [{
            "hooks": [{
                "type": "command",
                "command": f'"$PLUGIN_ROOT/scripts/kast-codex-hook" {rust_event}',
            }]
        }]
        for codex_event, rust_event in hook_events.items()
    }
}
files = {
    "marketplace.json": json.dumps(marketplace),
    ".agents/plugins/marketplace.json": json.dumps(
        {**marketplace, "name": "other"} if mutation == "discovery-marketplace" else marketplace
    ),
    "plugins/kast/.codex-plugin/plugin.json": json.dumps(manifest),
    "plugins/kast/hooks/hooks.json": json.dumps(
        {"hooks": {}} if mutation == "invalid-hooks" else hooks
    ),
    "plugins/kast/scripts/kast-codex-hook": (
        "#!/bin/sh\n"
        "task_launcher=${KAST_AGENT_TASK_LAUNCHER:-$HOME/.local/bin/kast-agent-task}\n"
        "kast_binary=$(dirname \"$task_launcher\")/kast\n"
        "exec \"$kast_binary\" developer codex hook \"$1\"\n"
    ),
    "plugins/kast/skills/kast-codex/SKILL.md": (
        "# Kast Codex\n" if mutation == "invalid-skill" else
        "---\nname: kast-codex\ndescription: \"Fixture skill.\"\n---\n\n# Kast Codex\n"
    ),
    "plugins/kast/skills/kast-codex/agents/openai.yaml": (
        "interface:\n  display_name: \"Kast\"\n"
        "  short_description: \"Kast fixture\"\n"
        "  default_prompt: \"Use $kast-codex.\"\n\n"
        "policy:\n  allow_implicit_invocation: true\n"
        if mutation != "invalid-openai" else
        "interface:\n  display_name: \"Kast\"\n"
        "  short_description: \"Kast fixture\"\n"
        "  default_prompt: \"Use $kast-codex.\n\n"
        "policy:\n  allow_implicit_invocation: true\n"
    ),
    "plugins/kast/assets/codex-exposure.toon": f"version: {exposure_version}\n",
    "plugins/kast/assets/hook-recovery-messages.toon": "messages[0]:\n",
    "plugins/kast/assets/kast.svg": "<svg/>\n",
}
if mutation == "missing-hooks":
    del files["plugins/kast/hooks/hooks.json"]
if mutation == "mcp":
    files["plugins/kast/.mcp.json"] = "{}\n"
if mutation == "traversal":
    files["../outside"] = "unsafe\n"
if mutation == "json-guidance":
    files["plugins/kast/skills/kast-codex/SKILL.md"] += "kast --output json agent verify\n"

with zipfile.ZipFile(archive_path, "w") as archive:
    for name, contents in files.items():
        info = zipfile.ZipInfo(name)
        mode = 0o644
        if name == "plugins/kast/scripts/kast-codex-hook" and mutation != "launcher-mode":
            mode = 0o755
        info.external_attr = (stat.S_IFREG | mode) << 16
        archive.writestr(info, contents)
PY
}

write_fixture
"$verifier" --archive "$archive" --version 9.8.7

assert_rejected() {
  local mutation="$1"
  local expected="$2"
  write_fixture "$mutation"
  if "$verifier" --archive "$archive" --version 9.8.7 \
    >"${scratch_dir}/${mutation}.out" 2>"${scratch_dir}/${mutation}.err"
  then
    die "${mutation} fixture unexpectedly verified"
  fi
  grep -Fq -- "$expected" "${scratch_dir}/${mutation}.err" \
    || die "${mutation} failure did not mention ${expected}"
}

assert_rejected missing-hooks "missing required Codex plugin files"
assert_rejected invalid-hooks "hooks.json must define exactly"
assert_rejected invalid-skill "SKILL.md must start with YAML frontmatter"
assert_rejected json-guidance "must teach TOON-first commands"
assert_rejected invalid-openai "does not match the closed metadata schema"
assert_rejected mcp "forbidden Codex plugin payload"
assert_rejected discovery-marketplace "discovery marketplace must match"
assert_rejected manifest-version "plugin manifest version mismatch"
assert_rejected exposure-version "Codex exposure version mismatch"
assert_rejected launcher-mode "regular executable file"
assert_rejected traversal "unsafe archive path"

printf '%s\n' "Codex plugin package contract test passed"
