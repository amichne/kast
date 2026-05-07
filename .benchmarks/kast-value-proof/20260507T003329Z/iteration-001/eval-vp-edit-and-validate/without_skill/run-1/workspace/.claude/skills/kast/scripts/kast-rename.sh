#!/usr/bin/env bash
# kast-rename.sh — Full kast rename workflow in one invocation.
#
# Usage:
#   bash kast-rename.sh \
#     --workspace-root=/abs/path/to/workspace \
#     --file-path=/abs/path/to/File.kt \
#     --offset=556 \
#     --new-name=NewName
#
# Steps executed:
#   1. Resolve kast binary
#   2. Ensure workspace daemon is READY
#   3. Plan rename (dry-run) → plan.json
#   4. Extract apply-request → apply.json  (via kast-plan-utils.py, no jq)
#   5. Apply edits
#   6. Run diagnostics on all affected files
#   7. Print diagnostic error count; exit 1 if any ERROR-severity diagnostics found
#
# All JSON manipulation is done via kast-plan-utils.py (Python) — no jq quoting issues.
# All temp files live under mktemp -d and are removed on exit.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PLAN_UTILS="${SCRIPT_DIR}/kast-plan-utils.py"

# ---------------------------------------------------------------------------
# 1.  Parse arguments
# ---------------------------------------------------------------------------
WORKSPACE_ROOT=""
FILE_PATH=""
OFFSET=""
NEW_NAME=""

for arg in "$@"; do
    case "${arg}" in
        --workspace-root=*) WORKSPACE_ROOT="${arg#*=}" ;;
        --file-path=*)      FILE_PATH="${arg#*=}" ;;
        --offset=*)         OFFSET="${arg#*=}" ;;
        --new-name=*)       NEW_NAME="${arg#*=}" ;;
        *)
            printf 'Unknown argument: %s\n' "${arg}" >&2
            printf 'Usage: %s --workspace-root=<path> --file-path=<path> --offset=<n> --new-name=<name>\n' \
                "${BASH_SOURCE[0]}" >&2
            exit 1
            ;;
    esac
done

if [[ -z "${WORKSPACE_ROOT}" || -z "${FILE_PATH}" || -z "${OFFSET}" || -z "${NEW_NAME}" ]]; then
    printf 'Error: all four arguments are required.\n' >&2
    printf 'Usage: %s --workspace-root=<path> --file-path=<path> --offset=<n> --new-name=<name>\n' \
        "${BASH_SOURCE[0]}" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 2.  Resolve kast binary
# ---------------------------------------------------------------------------
if command -v kast >/dev/null 2>&1; then
    KAST="$(command -v kast)"
else
    KAST="$(bash "${SCRIPT_DIR}/resolve-kast.sh")"
fi

printf '[kast-rename] Using kast: %s\n' "${KAST}" >&2

# ---------------------------------------------------------------------------
# 3.  Temp directory — cleaned up on any exit
# ---------------------------------------------------------------------------
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

PLAN_FILE="${TMP_DIR}/rename-plan.json"
APPLY_REQ_FILE="${TMP_DIR}/apply-request.json"
APPLY_RESULT_FILE="${TMP_DIR}/apply-result.json"
DIAGNOSTICS_FILE="${TMP_DIR}/diagnostics.json"

# ---------------------------------------------------------------------------
# 4.  Ensure workspace daemon is READY
# ---------------------------------------------------------------------------
printf '[kast-rename] Step 1/4: ensuring workspace daemon is ready...\n' >&2
"${KAST}" workspace ensure --workspace-root="${WORKSPACE_ROOT}" >/dev/null

# ---------------------------------------------------------------------------
# 5.  Plan rename
# ---------------------------------------------------------------------------
printf '[kast-rename] Step 2/4: planning rename (dry-run)  offset=%s  new-name=%s\n' \
    "${OFFSET}" "${NEW_NAME}" >&2
"${KAST}" rename \
    --workspace-root="${WORKSPACE_ROOT}" \
    --file-path="${FILE_PATH}" \
    --offset="${OFFSET}" \
    --new-name="${NEW_NAME}" \
    --dry-run=true > "${PLAN_FILE}"

EDIT_COUNT="$(python3 "${PLAN_UTILS}" count-edits "${PLAN_FILE}")"
printf '[kast-rename] Plan produced %s edit(s)\n' "${EDIT_COUNT}" >&2

printf '[kast-rename] Affected files:\n' >&2
python3 "${PLAN_UTILS}" affected-files-list "${PLAN_FILE}" | sed 's/^/  /' >&2

# ---------------------------------------------------------------------------
# 6.  Extract apply request (edits + fileHashes)
# ---------------------------------------------------------------------------
python3 "${PLAN_UTILS}" extract-apply-request "${PLAN_FILE}" "${APPLY_REQ_FILE}"

# ---------------------------------------------------------------------------
# 7.  Apply edits
# ---------------------------------------------------------------------------
printf '[kast-rename] Step 3/4: applying edits...\n' >&2
"${KAST}" edits apply \
    --workspace-root="${WORKSPACE_ROOT}" \
    --request-file="${APPLY_REQ_FILE}" > "${APPLY_RESULT_FILE}"

# ---------------------------------------------------------------------------
# 8.  Diagnostics
# ---------------------------------------------------------------------------
printf '[kast-rename] Step 4/4: running diagnostics on affected files...\n' >&2
FILES_CSV="$(python3 "${PLAN_UTILS}" affected-files-csv "${PLAN_FILE}")"

if [[ -n "${FILES_CSV}" ]]; then
    "${KAST}" diagnostics \
        --workspace-root="${WORKSPACE_ROOT}" \
        --file-paths="${FILES_CSV}" > "${DIAGNOSTICS_FILE}"

    ERROR_COUNT="$(python3 "${PLAN_UTILS}" check-diagnostics "${DIAGNOSTICS_FILE}")" || {
        printf '[kast-rename] %s diagnostic error(s) detected — see details above.\n' \
            "${ERROR_COUNT}" >&2
        exit 1
    }
    printf '[kast-rename] Diagnostics clean (%s errors).\n' "${ERROR_COUNT}" >&2
else
    printf '[kast-rename] No affected files reported; skipping diagnostics.\n' >&2
fi

printf '[kast-rename] Done — %s rename complete.\n' "${NEW_NAME}" >&2

# Emit apply result JSON to stdout so callers can inspect it.
cat "${APPLY_RESULT_FILE}"
