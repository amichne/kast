#!/usr/bin/env bash
# Run a Gradle task → structured JSON. All raw output → log file.
# Usage: run_task.sh <project_root> <task_name> [extra_gradle_args...]
set -uo pipefail

if [ $# -lt 2 ]; then
  echo '{"ok":false,"error":"Usage: run_task.sh <project_root> <task_name> [args...]"}'
  exit 1
fi

PROJECT_ROOT="$(cd "$1" && pwd)"
TASK_NAME="$2"
shift 2
EXTRA_ARGS=("$@")

LOGS_DIR="$PROJECT_ROOT/.agent-workflow/logs"
mkdir -p "$LOGS_DIR"

SAFE_NAME=$(echo "$TASK_NAME" | tr ':' '_' | tr ' ' '_')
TIMESTAMP=$(date -u +"%Y%m%dT%H%M%S")
LOG_FILE="$LOGS_DIR/${SAFE_NAME}-${TIMESTAMP}.log"

START_MS=$(python3 -c "import time; print(int(time.time()*1000))")

cd "$PROJECT_ROOT"
GRADLE_CMD="./gradlew"
[ ! -f "./gradlew" ] && GRADLE_CMD="gradle"

EXIT_CODE=0
$GRADLE_CMD "$TASK_NAME" "${EXTRA_ARGS[@]}" --console=plain > "$LOG_FILE" 2>&1 || EXIT_CODE=$?

END_MS=$(python3 -c "import time; print(int(time.time()*1000))")
DURATION_MS=$((END_MS - START_MS))

TASKS_EXECUTED=$(grep -c "^> Task " "$LOG_FILE" 2>/dev/null || true)
TASKS_UP_TO_DATE=$(grep -c "UP-TO-DATE$" "$LOG_FILE" 2>/dev/null || true)
TASKS_FROM_CACHE=$(grep -c "FROM-CACHE$" "$LOG_FILE" 2>/dev/null || true)

BUILD_SUCCESSFUL=false
grep -q "BUILD SUCCESSFUL" "$LOG_FILE" 2>/dev/null && BUILD_SUCCESSFUL=true

TEST_TASK_DETECTED=false
echo "$TASK_NAME" | grep -qiE "test|check" && TEST_TASK_DETECTED=true

FAILURE_SUMMARY="null"
if [ "$EXIT_CODE" -ne 0 ]; then
  FAIL_MSG=$(sed -n '/^FAILURE:/,/^BUILD FAILED/p' "$LOG_FILE" 2>/dev/null \
    | head -15 | tr '\n' ' ' | tr '"' "'" | head -c 500)
  if [ -n "$FAIL_MSG" ]; then
    FAILURE_SUMMARY="\"$FAIL_MSG\""
  else
    FAIL_MSG=$(grep -v "^$" "$LOG_FILE" | tail -10 | tr '\n' ' ' | tr '"' "'" | head -c 500)
    FAILURE_SUMMARY="\"Gradle exit code $EXIT_CODE. Tail: $FAIL_MSG\""
  fi
fi

OK=true
[ "$EXIT_CODE" -ne 0 ] && OK=false

cat <<EOF
{
  "ok": $OK,
  "task": "$TASK_NAME",
  "exit_code": $EXIT_CODE,
  "duration_ms": $DURATION_MS,
  "log_file": "$LOG_FILE",
  "tasks_executed": $TASKS_EXECUTED,
  "tasks_up_to_date": $TASKS_UP_TO_DATE,
  "tasks_from_cache": $TASKS_FROM_CACHE,
  "build_successful": $BUILD_SUCCESSFUL,
  "test_task_detected": $TEST_TASK_DETECTED,
  "failure_summary": $FAILURE_SUMMARY
}
EOF
