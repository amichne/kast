#!/usr/bin/env bash
# kast-rename.sh — Full kast rename workflow in one invocation.
#
# Symbol mode (recommended — resolves the symbol first):
#   bash kast-rename.sh \
#     --workspace-root=/abs/workspace \
#     --symbol=OldName \
#     [--file=<hint>] [--kind=class|function|property] [--containing-type=Outer] \
#     --new-name=NewName
#
# Offset mode (when exact position is already known):
#   bash kast-rename.sh \
#     --workspace-root=/abs/workspace \
#     --file-path=/abs/path/to/File.kt \
#     --offset=556 \
#     --new-name=NewName
#
# Steps executed:
#   1. Resolve kast binary + ensure workspace daemon is READY
#   2. (Symbol mode) Resolve symbol name → file-path + offset via kast-common.sh
#   3. Plan rename (dry-run) → plan.json
#   4. Extract apply-request → apply.json  (via kast-plan-utils.py, no jq)
#   5. Apply edits
#   6. Run diagnostics on affected files
#   7. Emit wrapper JSON (ok, query, edit_count, affected_files, apply_result,
#      diagnostics, log_file); exit 1 if any ERROR-severity diagnostics found
#
# stdout: wrapper JSON with ok boolean and structured result fields.
# stderr: step-by-step progress prefixed with [kast-rename].

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/kast-common.sh"

WORKSPACE_ROOT=""
SYMBOL=""
FILE_HINT=""
KIND=""
CONTAINING_TYPE=""
FILE_PATH=""
OFFSET=""
NEW_NAME=""

for arg in "$@"; do
    case "${arg}" in
        --workspace-root=*)  WORKSPACE_ROOT="${arg#*=}" ;;
        --symbol=*)          SYMBOL="${arg#*=}" ;;
        --file=*)            FILE_HINT="${arg#*=}" ;;
        --kind=*)            KIND="${arg#*=}" ;;
        --containing-type=*) CONTAINING_TYPE="${arg#*=}" ;;
        --file-path=*)       FILE_PATH="${arg#*=}" ;;
        --offset=*)          OFFSET="${arg#*=}" ;;
        --new-name=*)        NEW_NAME="${arg#*=}" ;;
        *)
            printf 'Unknown argument: %s\n' "${arg}" >&2
            exit 1
            ;;
    esac
done

kast_wrapper_init "kast-rename"

PLAN_UTILS="${SCRIPT_DIR}/kast-plan-utils.py"
PLAN_FILE="${TMP_DIR}/rename-plan.json"
APPLY_REQ_FILE="${TMP_DIR}/apply-request.json"
APPLY_RESULT_FILE="${TMP_DIR}/apply-result.json"
DIAGNOSTICS_FILE="${TMP_DIR}/diagnostics.json"

emit_failure() {
    local stage="$1"
    local message="$2"
    local error_file="${3:-}"
    local log_path
    log_path="$(kast_preserve_log_file)"

    python3 - "${stage}" "${message}" "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" \
        "${CONTAINING_TYPE}" "${FILE_PATH}" "${OFFSET}" "${NEW_NAME}" "${log_path}" "${error_file}" <<'PY'
import json
import sys
from pathlib import Path

(
    stage, message, workspace_root, symbol, file_hint, kind,
    containing_type, file_path, offset, new_name, log_file, error_file,
) = sys.argv[1:]

payload = {
    "ok": False,
    "stage": stage,
    "message": message,
    "query": {
        "workspace_root": workspace_root,
        "symbol": symbol or None,
        "file_hint": file_hint or None,
        "kind": kind or None,
        "containing_type": containing_type or None,
        "file_path": file_path or None,
        "offset": int(offset) if offset else None,
        "new_name": new_name,
    },
    "log_file": log_file,
}

if error_file:
    error_path = Path(error_file)
    if error_path.exists():
        raw = error_path.read_text(encoding="utf-8").strip()
        if raw:
            try:
                payload["error"] = json.loads(raw)
            except json.JSONDecodeError:
                payload["error_text"] = raw

print(json.dumps(payload, indent=2))
PY
}

# ---------------------------------------------------------------------------
# Validate arguments
# ---------------------------------------------------------------------------
if [[ -z "${WORKSPACE_ROOT}" || -z "${NEW_NAME}" ]]; then
    emit_failure "argument_validation" "--workspace-root and --new-name are required."
    exit 1
fi

if [[ -n "${SYMBOL}" ]]; then
    RENAME_MODE="symbol"
elif [[ -n "${FILE_PATH}" && -n "${OFFSET}" ]]; then
    RENAME_MODE="offset"
    if ! [[ "${OFFSET}" =~ ^[0-9]+$ ]]; then
        emit_failure "argument_validation" "--offset must be a non-negative integer."
        exit 1
    fi
else
    emit_failure "argument_validation" \
        "Provide --symbol (symbol mode) or both --file-path and --offset (offset mode)."
    exit 1
fi

# ---------------------------------------------------------------------------
# Resolve binary + workspace; resolve symbol name when in symbol mode
# ---------------------------------------------------------------------------
printf '[kast-rename] Resolving kast binary and workspace...\n' >&2

if [[ "${RENAME_MODE}" == "symbol" ]]; then
    # kast_resolve_named_symbol_query handles binary resolution and workspace ensure
    if ! kast_resolve_named_symbol_query \
            "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" "${CONTAINING_TYPE}"; then
        emit_failure "${RESOLVE_ERROR_STAGE}" "${RESOLVE_ERROR_MESSAGE}" "${RESOLVE_ERROR_JSON_FILE:-}"
        exit 1
    fi
    FILE_PATH="${RESOLVED_FILE_PATH}"
    OFFSET="${RESOLVED_OFFSET}"
    printf '[kast-rename] Resolved %s → %s offset=%s\n' "${SYMBOL}" "${FILE_PATH}" "${OFFSET}" >&2
else
    kast_resolve_binary
    ENSURE_FILE="${TMP_DIR}/workspace-ensure.json"
    if ! kast_run_json "${ENSURE_FILE}" "${KAST}" workspace ensure \
            --workspace-root="${WORKSPACE_ROOT}"; then
        emit_failure "workspace_ensure" "workspace ensure failed." "${ENSURE_FILE}"
        exit 1
    fi
    printf '[kast-rename] Workspace ready.\n' >&2
fi

# ---------------------------------------------------------------------------
# Plan rename
# ---------------------------------------------------------------------------
printf '[kast-rename] Planning rename (dry-run) → new-name=%s\n' "${NEW_NAME}" >&2
if ! kast_run_json \
        "${PLAN_FILE}" \
        "${KAST}" rename \
        --workspace-root="${WORKSPACE_ROOT}" \
        --file-path="${FILE_PATH}" \
        --offset="${OFFSET}" \
        --new-name="${NEW_NAME}" \
        --dry-run=true; then
    emit_failure "rename_plan" "kast rename --dry-run failed." "${PLAN_FILE}"
    exit 1
fi

EDIT_COUNT="$(python3 "${PLAN_UTILS}" count-edits "${PLAN_FILE}")"
printf '[kast-rename] Plan produced %s edit(s)\n' "${EDIT_COUNT}" >&2
printf '[kast-rename] Affected files:\n' >&2
python3 "${PLAN_UTILS}" affected-files-list "${PLAN_FILE}" | sed 's/^/  /' >&2

# ---------------------------------------------------------------------------
# Extract apply-request (edits + fileHashes)
# ---------------------------------------------------------------------------
python3 "${PLAN_UTILS}" extract-apply-request "${PLAN_FILE}" "${APPLY_REQ_FILE}"

# ---------------------------------------------------------------------------
# Apply edits
# ---------------------------------------------------------------------------
printf '[kast-rename] Applying edits...\n' >&2
if ! kast_run_json \
        "${APPLY_RESULT_FILE}" \
        "${KAST}" apply-edits \
        --workspace-root="${WORKSPACE_ROOT}" \
        --request-file="${APPLY_REQ_FILE}"; then
    emit_failure "apply_edits" "kast apply-edits failed." "${APPLY_RESULT_FILE}"
    exit 1
fi

# ---------------------------------------------------------------------------
# Diagnostics on affected files
# ---------------------------------------------------------------------------
DIAG_CLEAN="true"
DIAG_ERROR_COUNT="0"
DIAG_WARNING_COUNT="0"
FILES_CSV="$(python3 "${PLAN_UTILS}" affected-files-csv "${PLAN_FILE}")"

if [[ -n "${FILES_CSV}" ]]; then
    printf '[kast-rename] Running diagnostics on affected files...\n' >&2
    if kast_run_json \
            "${DIAGNOSTICS_FILE}" \
            "${KAST}" diagnostics \
            --workspace-root="${WORKSPACE_ROOT}" \
            --file-paths="${FILES_CSV}"; then
        DIAG_ERROR_COUNT="$(python3 "${PLAN_UTILS}" check-diagnostics "${DIAGNOSTICS_FILE}" \
            2>>"${LOG_FILE}" || true)"
        if [[ "${DIAG_ERROR_COUNT}" != "0" ]]; then
            DIAG_CLEAN="false"
        fi
        DIAG_WARNING_COUNT="$(python3 - "${DIAGNOSTICS_FILE}" <<'PY'
import json, sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(sum(1 for d in data.get("diagnostics", []) if d.get("severity") == "WARNING"))
PY
)"
    else
        printf '[kast-rename] Warning: diagnostics command failed; skipping diagnostics check.\n' >&2
    fi
fi

# ---------------------------------------------------------------------------
# Emit wrapper JSON
# ---------------------------------------------------------------------------
LOG_PATH="$(kast_preserve_log_file)"

python3 - \
    "${PLAN_FILE}" "${APPLY_RESULT_FILE}" "${DIAGNOSTICS_FILE}" \
    "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" "${CONTAINING_TYPE}" \
    "${FILE_PATH}" "${OFFSET}" "${NEW_NAME}" \
    "${EDIT_COUNT}" "${DIAG_CLEAN}" "${DIAG_ERROR_COUNT}" "${DIAG_WARNING_COUNT}" \
    "${LOG_PATH}" <<'PY'
import json
import sys
from pathlib import Path

(
    plan_file, apply_result_file, diagnostics_file,
    workspace_root, symbol, file_hint, kind, containing_type,
    file_path, offset, new_name,
    edit_count, diag_clean, diag_error_count, diag_warning_count,
    log_file,
) = sys.argv[1:]

plan = json.loads(Path(plan_file).read_text(encoding="utf-8"))
apply_result = json.loads(Path(apply_result_file).read_text(encoding="utf-8"))

diag_summary: dict = {
    "clean": diag_clean == "true",
    "error_count": int(diag_error_count),
    "warning_count": int(diag_warning_count),
}
diag_path = Path(diagnostics_file)
if diag_path.exists() and diag_path.stat().st_size > 0:
    try:
        raw_diag = json.loads(diag_path.read_text(encoding="utf-8"))
        errors = [d for d in raw_diag.get("diagnostics", []) if d.get("severity") == "ERROR"]
        if errors:
            diag_summary["errors"] = errors
    except (json.JSONDecodeError, OSError):
        pass

payload = {
    "ok": diag_clean == "true",
    "query": {
        "workspace_root": workspace_root,
        "symbol": symbol or None,
        "file_hint": file_hint or None,
        "kind": kind or None,
        "containing_type": containing_type or None,
        "file_path": file_path,
        "offset": int(offset),
        "new_name": new_name,
    },
    "edit_count": int(edit_count),
    "affected_files": plan.get("affectedFiles", []),
    "apply_result": apply_result,
    "diagnostics": diag_summary,
    "log_file": log_file,
}
print(json.dumps(payload, indent=2))
PY

if [[ "${DIAG_CLEAN}" == "false" ]]; then
    printf '[kast-rename] %s diagnostic error(s) found after apply.\n' "${DIAG_ERROR_COUNT}" >&2
    exit 1
fi

printf '[kast-rename] Done — %s complete, diagnostics clean.\n' "${NEW_NAME}" >&2
