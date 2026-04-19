#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/hook-state.sh"

REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"
GRADLE_HOOK_SCRIPT="${REPO_ROOT}/.agents/skills/kotlin-gradle-loop/scripts/gradle/run_gradle_hook.sh"
GRADLE_TASK_SCRIPT="${REPO_ROOT}/.agents/skills/kotlin-gradle-loop/scripts/gradle/run_task.sh"
KAST_RESOLVER_SCRIPT="${REPO_ROOT}/.agents/skills/kast/scripts/resolve-kast.sh"
STATE_FILE="${REPO_ROOT}/.agent-workflow/state.json"
PATH_STATE_FILE="$(hook_state_file "${REPO_ROOT}")"

HOOK_INPUT="$(cat || true)"
export HOOK_INPUT

SESSION_REASON="$(
    python3 - <<'PY'
import json
import os

raw = os.environ.get("HOOK_INPUT", "").strip()
if not raw:
    print("")
else:
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        print("")
    else:
        print(payload.get("reason", ""))
PY
)"

if [[ "${SESSION_REASON}" != "complete" ]]; then
    exit 0
fi

if [[ ! -f "${PATH_STATE_FILE}" ]]; then
    exit 0
fi

mapfile -t CHANGED_FILES < "${PATH_STATE_FILE}"
rm -f "${PATH_STATE_FILE}"

if [[ "${#CHANGED_FILES[@]}" -eq 0 ]]; then
    exit 0
fi

KOTLIN_FILES=()
NEEDS_GRADLE_VALIDATION="false"

for relative_path in "${CHANGED_FILES[@]}"; do
    case "${relative_path}" in
        *.kt)
            KOTLIN_FILES+=("${REPO_ROOT}/${relative_path}")
            NEEDS_GRADLE_VALIDATION="true"
            ;;
        *.kts|build.gradle|build.gradle.kts|settings.gradle|settings.gradle.kts|gradle.properties|gradlew|gradlew.bat|gradle/*|scripts/gradle/*)
            NEEDS_GRADLE_VALIDATION="true"
            ;;
    esac
done

require_ok_json() {
    local label="$1"
    local payload="$2"
    python3 - "${label}" <<'PY' <<<"${payload}"
import json
import sys

label = sys.argv[1]
payload = json.load(sys.stdin)
if payload.get("ok"):
    sys.exit(0)

message = payload.get("error") or payload.get("message") or payload.get("failure_summary") or "unknown failure"
print(f"{label} failed: {message}", file=sys.stderr)
sys.exit(1)
PY
}

if [[ "${#KOTLIN_FILES[@]}" -gt 0 ]]; then
    KAST_BIN="$(bash "${KAST_RESOLVER_SCRIPT}")"
    DIAGNOSTICS_REQUEST="$(
        python3 - "${REPO_ROOT}" "${KOTLIN_FILES[@]}" <<'PY'
import json
import sys

workspace_root = sys.argv[1]
file_paths = sys.argv[2:]
print(json.dumps({"workspaceRoot": workspace_root, "filePaths": file_paths}))
PY
    )"
    DIAGNOSTICS_OUTPUT="$("${KAST_BIN}" skill diagnostics "${DIAGNOSTICS_REQUEST}")"
    python3 - <<'PY' <<<"${DIAGNOSTICS_OUTPUT}"
import json
import sys

payload = json.load(sys.stdin)
if payload.get("ok") and payload.get("clean"):
    sys.exit(0)

message = payload.get("message") or f"Diagnostics reported {payload.get('error_count', 'unknown')} errors"
print(f"kast skill diagnostics failed: {message}", file=sys.stderr)
sys.exit(1)
PY
fi

if [[ "${NEEDS_GRADLE_VALIDATION}" != "true" ]]; then
    exit 0
fi

if [[ -f "${STATE_FILE}" ]]; then
    BUILD_HEALTH_OUTPUT="$(bash "${GRADLE_HOOK_SCRIPT}" "${REPO_ROOT}")"
else
    BUILD_HEALTH_OUTPUT="$(bash "${GRADLE_TASK_SCRIPT}" "${REPO_ROOT}" check)"
fi
require_ok_json "build-health" "${BUILD_HEALTH_OUTPUT}"

TEST_OUTPUT="$(bash "${GRADLE_TASK_SCRIPT}" "${REPO_ROOT}" test)"
require_ok_json "run-tests" "${TEST_OUTPUT}"
