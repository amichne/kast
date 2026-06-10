#!/usr/bin/env bash
# Run the project-defined high-signal Gradle hook → structured JSON.
# Usage: run_gradle_hook.sh <project_root> [extra_gradle_args...]
set -Eeuo pipefail

json_error() {
  python3 - "$@" <<'PY'
import json
import sys

payload = {"ok": False, "error": sys.argv[1]}
for arg in sys.argv[2:]:
    key, _, value = arg.partition("=")
    if value == "true":
        payload[key] = True
    elif value == "false":
        payload[key] = False
    else:
        payload[key] = value
json.dump(payload, sys.stdout, indent=2)
PY
}

if [ $# -lt 1 ]; then
  echo '{"ok":false,"error":"Usage: run_gradle_hook.sh <project_root> [extra_gradle_args...]"}'
  exit 1
fi

command -v python3 >/dev/null 2>&1 || {
  echo '{"ok":false,"error":"python3 is required"}'
  exit 1
}

if [ ! -d "$1" ]; then
  json_error "Not a directory: $1"
  exit 1
fi

PROJECT_ROOT="$(cd -- "$1" && pwd -P)"
shift

STATE_FILE="$PROJECT_ROOT/.agent-workflow/state.json"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="$(cd -- "$SCRIPT_DIR/../state" && pwd -P)"

if [ ! -f "$STATE_FILE" ]; then
  init_output="$(python3 "$STATE_DIR/init_state.py" "$PROJECT_ROOT")" || {
    printf '%s\n' "$init_output"
    exit 1
  }
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
    json_error \
      "project.gradleHook is not set in $STATE_FILE." \
      "stage=gradle_hook.configure" \
      "needs_configuration=true" \
      "state_file=$STATE_FILE" \
      "next_step=Call gradle_set_hook with a narrow task, or call gradle_run_task with an explicit task."
    exit 1
  fi
  json_error "Failed to read project.gradleHook from $STATE_FILE." "stage=gradle_hook.read_state" "state_file=$STATE_FILE"
  exit 1
fi

bash "$SCRIPT_DIR/run_task.sh" "$PROJECT_ROOT" "$TASK_NAME" "$@"
