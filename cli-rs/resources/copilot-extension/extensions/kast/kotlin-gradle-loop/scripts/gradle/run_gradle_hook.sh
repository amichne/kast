#!/usr/bin/env bash
# Run the project-defined high-signal Gradle hook → structured JSON.
# Usage: run_gradle_hook.sh <project_root> [extra_gradle_args...]
set -euo pipefail

if [ $# -lt 1 ]; then
  echo '{"ok":false,"error":"Usage: run_gradle_hook.sh <project_root> [extra_gradle_args...]"}'
  exit 1
fi

if [ ! -d "$1" ]; then
  echo "{\"ok\":false,\"error\":\"Not a directory: $1\"}"
  exit 1
fi

PROJECT_ROOT="$(cd "$1" && pwd)"
shift

STATE_FILE="$PROJECT_ROOT/.agent-workflow/state.json"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -f "$STATE_FILE" ]; then
  echo "{\"ok\":false,\"error\":\"State file not found at $STATE_FILE. Run init_state.py first.\"}"
  exit 1
fi

TASK_NAME=""
if TASK_NAME="$(python3 - "$STATE_FILE" <<'PY'
import json
import sys

with open(sys.argv[1]) as handle:
    state = json.load(handle)

task = (((state.get("project") or {}).get("gradleHook")) or "").strip()
if not task:
    sys.exit(2)

print(task)
PY
)"; then
  :
else
  status=$?
  if [ "$status" -eq 2 ]; then
    echo "{\"ok\":false,\"error\":\"project.gradleHook is not set in $STATE_FILE. Update discovery state before running the build-health hook.\"}"
    exit 1
  fi
  echo "{\"ok\":false,\"error\":\"Failed to read project.gradleHook from $STATE_FILE.\"}"
  exit 1
fi

bash "$SCRIPT_DIR/run_task.sh" "$PROJECT_ROOT" "$TASK_NAME" "$@"
