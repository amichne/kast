#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/hook-state.sh"

REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"
SKILL_STATE_FILE="$(hook_skill_state_file "${REPO_ROOT}")"
SHADOWED_EXTENSION_STATE_FILE="$(hook_shadowed_extension_state_file "${REPO_ROOT}")"
SKILL_CONFIG_FILE="${REPO_ROOT}/.github/hooks/skill-shadowing.json"

HOOK_INPUT="$(cat || true)"
export HOOK_INPUT

python3 - "${REPO_ROOT}" "${SKILL_STATE_FILE}" "${SHADOWED_EXTENSION_STATE_FILE}" "${SKILL_CONFIG_FILE}" <<'PY'
import json
import os
import sys
from pathlib import Path

repo_root = Path(sys.argv[1]).resolve()
state_file = Path(sys.argv[2])
shadow_state_file = Path(sys.argv[3])
config_file = Path(sys.argv[4])

config = json.loads(config_file.read_text(encoding="utf-8"))
configured_skills = config.get("skills", [])

raw = os.environ.get("HOOK_INPUT", "").strip()
if not raw:
    raise SystemExit(0)

try:
    payload = json.loads(raw)
except json.JSONDecodeError:
    raise SystemExit(0)

tool_name = payload.get("toolName")
tool_args_raw = payload.get("toolArgs") or "{}"

try:
    tool_args = json.loads(tool_args_raw)
except json.JSONDecodeError:
    tool_args = {}

loaded = set()
if state_file.exists():
    loaded = {
        line.strip()
        for line in state_file.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }

loaded_extensions = set()
if shadow_state_file.exists():
    loaded_extensions = {
        line.strip()
        for line in shadow_state_file.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }

def normalize_path(value: str) -> str | None:
    path = Path(value)
    if not path.is_absolute():
        path = (repo_root / path).resolve()
    else:
        path = path.resolve()
    return str(path)

required_skill_set = set()
for entry in configured_skills:
    if not entry.get("requireRead", False):
        continue
    shadowing_extension_id = entry.get("shadowingExtensionId")
    if shadowing_extension_id and shadowing_extension_id in loaded_extensions:
        continue
    skill_path = entry.get("skillPath")
    if not isinstance(skill_path, str) or not skill_path:
        continue
    normalized_skill_path = normalize_path(skill_path)
    if not Path(normalized_skill_path).exists():
        continue
    required_skill_set.add(normalized_skill_path)

if tool_name in {"read_file", "mcp_idea_read_file", "mcp_idea2_read_file"}:
    candidates = []
    for key in ("filePath", "pathInProject", "file_path", "path"):
        value = tool_args.get(key)
        if isinstance(value, str) and value:
            candidates.append(value)

    for candidate in candidates:
        normalized = normalize_path(candidate)
        if normalized in required_skill_set:
            loaded.add(normalized)

    if loaded:
        state_file.write_text(
            "".join(f"{entry}\\n" for entry in sorted(loaded)),
            encoding="utf-8",
        )

missing = sorted(required_skill_set - loaded)
if not missing:
    raise SystemExit(0)

# Allow reads to continue so the agent can satisfy the requirement.
if tool_name in {"read_file", "mcp_idea_read_file", "mcp_idea2_read_file"}:
    raise SystemExit(0)

missing_display = []
for missing_path in missing:
    try:
        rel = Path(missing_path).relative_to(repo_root)
        missing_display.append(str(rel))
    except ValueError:
        missing_display.append(missing_path)

reason = (
    "Read required skill files before using other tools: "
    + ", ".join(missing_display)
)

output = {
    "hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": reason,
    },
    "systemMessage": (
        "Before calling other tools, call read_file for each required skill file. "
        f"Missing: {', '.join(missing_display)}"
    ),
}
print(json.dumps(output))
PY
