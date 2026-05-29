#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/hook-state.sh"

REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"
STATE_FILE="$(hook_state_file "${REPO_ROOT}")"
HOOK_INPUT="$(cat || true)"
export HOOK_INPUT

python3 - "${REPO_ROOT}" "${STATE_FILE}" <<'PY'
import json
import os
import sys
from pathlib import Path

repo_root = Path(sys.argv[1]).resolve()
state_file = Path(sys.argv[2])
raw = os.environ.get("HOOK_INPUT", "").strip()
if not raw:
    raise SystemExit(0)

try:
    payload = json.loads(raw)
except json.JSONDecodeError:
    raise SystemExit(0)

tool_name = payload.get("toolName")
tool_result = (payload.get("toolResult") or {}).get("resultType")
if tool_name not in {"edit", "create"} or tool_result != "success":
    raise SystemExit(0)

cwd = Path(payload.get("cwd") or repo_root).resolve()
tool_args_raw = payload.get("toolArgs") or "{}"
try:
    tool_args = json.loads(tool_args_raw)
except json.JSONDecodeError:
    raise SystemExit(0)

values = []
for key in ("path", "filePath", "file_path", "target_file"):
    value = tool_args.get(key)
    if isinstance(value, str) and value:
        values.append(value)

for key in ("paths", "filePaths", "file_paths"):
    value = tool_args.get(key)
    if isinstance(value, list):
        values.extend(entry for entry in value if isinstance(entry, str) and entry)

normalized = []
for value in values:
    path = Path(value)
    if not path.is_absolute():
        path = (cwd / path).resolve()
    else:
        path = path.resolve()
    try:
        relative = path.relative_to(repo_root)
    except ValueError:
        continue
    normalized.append(str(relative))

if not normalized:
    raise SystemExit(0)

existing = set()
if state_file.exists():
    existing = {line.strip() for line in state_file.read_text(encoding="utf-8").splitlines() if line.strip()}

existing.update(normalized)
state_file.write_text("".join(f"{path}\n" for path in sorted(existing)), encoding="utf-8")
PY
