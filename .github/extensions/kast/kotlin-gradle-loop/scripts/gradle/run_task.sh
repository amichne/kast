#!/usr/bin/env bash
# Run a Gradle task → structured JSON. All raw output → log file.
# Usage: run_task.sh <project_root> <task_name> [extra_gradle_args...]
set -Eeuo pipefail

if [ $# -lt 2 ]; then
  echo '{"ok":false,"error":"Usage: run_task.sh <project_root> <task_name> [args...]"}'
  exit 1
fi

command -v python3 >/dev/null 2>&1 || {
  echo '{"ok":false,"error":"python3 is required"}'
  exit 1
}

if [ ! -d "$1" ]; then
  python3 - "$1" <<'PY'
import json
import sys

json.dump({"ok": False, "error": f"Not a directory: {sys.argv[1]}"}, sys.stdout)
PY
  exit 1
fi

PROJECT_ROOT="$(cd -- "$1" && pwd -P)"
TASK_NAME="$2"
shift 2

LOGS_DIR="$PROJECT_ROOT/.agent-workflow/logs"
mkdir -p "$LOGS_DIR"

SAFE_NAME="$(printf '%s' "$TASK_NAME" | tr -c '[:alnum:]_.-' '_' | cut -c1-80)"
[ -n "$SAFE_NAME" ] || SAFE_NAME="gradle_task"
TIMESTAMP=$(date -u +"%Y%m%dT%H%M%S")
LOG_FILE="$LOGS_DIR/${SAFE_NAME}-${TIMESTAMP}.log"

START_MS=$(python3 -c "import time; print(int(time.time()*1000))")

cd -- "$PROJECT_ROOT"
if [ -x "./gradlew" ]; then
  GRADLE_CMD=("./gradlew")
elif [ -f "./gradlew" ]; then
  GRADLE_CMD=("bash" "./gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=("gradle")
else
  python3 - "$PROJECT_ROOT" <<'PY'
import json
import sys

json.dump(
    {
        "ok": False,
        "error": "No executable ./gradlew and no gradle on PATH",
        "project_root": sys.argv[1],
    },
    sys.stdout,
)
PY
  exit 1
fi

EXIT_CODE=0
"${GRADLE_CMD[@]}" "$TASK_NAME" "$@" --console=plain > "$LOG_FILE" 2>&1 || EXIT_CODE=$?

END_MS=$(python3 -c "import time; print(int(time.time()*1000))")
DURATION_MS=$((END_MS - START_MS))

TASKS_EXECUTED=$(grep -c "^> Task " "$LOG_FILE" 2>/dev/null || true)
TASKS_UP_TO_DATE=$(grep -c "UP-TO-DATE$" "$LOG_FILE" 2>/dev/null || true)
TASKS_FROM_CACHE=$(grep -c "FROM-CACHE$" "$LOG_FILE" 2>/dev/null || true)

BUILD_SUCCESSFUL=false
grep -q "BUILD SUCCESSFUL" "$LOG_FILE" 2>/dev/null && BUILD_SUCCESSFUL=true

TEST_TASK_DETECTED=false
echo "$TASK_NAME" | grep -qiE "test|check" && TEST_TASK_DETECTED=true

OK=true
[ "$EXIT_CODE" -ne 0 ] && OK=false

export OK TASK_NAME EXIT_CODE DURATION_MS LOG_FILE TASKS_EXECUTED TASKS_UP_TO_DATE TASKS_FROM_CACHE BUILD_SUCCESSFUL TEST_TASK_DETECTED
python3 <<'PY'
import json
import os
import sys


def bool_env(name):
    return os.environ[name].lower() == "true"


def failure_summary(exit_code, log_file):
    if exit_code == 0:
        return None
    try:
        with open(log_file, errors="replace") as handle:
            lines = [line.rstrip("\n") for line in handle]
    except OSError as exc:
        return f"Gradle exit code {exit_code}. Failed to read log: {exc}"

    start = next((index for index, line in enumerate(lines) if line.startswith("FAILURE:")), None)
    if start is not None:
        end = next(
            (
                index
                for index in range(start + 1, len(lines))
                if lines[index].startswith("BUILD FAILED")
            ),
            min(start + 15, len(lines)),
        )
        snippet = " ".join(line for line in lines[start:end] if line.strip())
    else:
        snippet = " ".join(line for line in lines[-10:] if line.strip())

    snippet = snippet[:500]
    return f"Gradle exit code {exit_code}. Tail: {snippet}" if snippet else f"Gradle exit code {exit_code}"


exit_code = int(os.environ["EXIT_CODE"])
payload = {
    "ok": bool_env("OK"),
    "task": os.environ["TASK_NAME"],
    "exit_code": exit_code,
    "duration_ms": int(os.environ["DURATION_MS"]),
    "log_file": os.environ["LOG_FILE"],
    "tasks_executed": int(os.environ["TASKS_EXECUTED"]),
    "tasks_up_to_date": int(os.environ["TASKS_UP_TO_DATE"]),
    "tasks_from_cache": int(os.environ["TASKS_FROM_CACHE"]),
    "build_successful": bool_env("BUILD_SUCCESSFUL"),
    "test_task_detected": bool_env("TEST_TASK_DETECTED"),
    "failure_summary": failure_summary(exit_code, os.environ["LOG_FILE"]),
}
json.dump(payload, sys.stdout, indent=2)
PY
