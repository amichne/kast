#!/usr/bin/env bash
# kast-write-and-validate.sh — Apply LLM-generated code with compiler validation.
#
# Takes generated code (as a string or via --content-file), applies it to the
# workspace, cleans up imports, and runs diagnostics to verify the result.
#
# Modes:
#   create-file           Create a new file at --file-path with --content
#   insert-at-offset      Insert --content at --offset in --file-path
#   replace-range         Replace [--start-offset, --end-offset) in --file-path with --content
#
# Usage:
#   kast-write-and-validate.sh \
#     --workspace-root=/abs/path \
#     --mode=create-file \
#     --file-path=/abs/path/to/NewFile.kt \
#     --content="package foo\n\nclass Bar {}"
#
#   kast-write-and-validate.sh \
#     --workspace-root=/abs/path \
#     --mode=insert-at-offset \
#     --file-path=/abs/path/to/File.kt \
#     --offset=512 \
#     --content-file=/tmp/generated.kt
#
#   kast-write-and-validate.sh \
#     --workspace-root=/abs/path \
#     --mode=replace-range \
#     --file-path=/abs/path/to/File.kt \
#     --start-offset=100 \
#     --end-offset=300 \
#     --content="fun newImpl() {}"
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/kast-common.sh"

WORKSPACE_ROOT=""
MODE=""
FILE_PATH=""
CONTENT=""
CONTENT_FILE=""
OFFSET=""
START_OFFSET=""
END_OFFSET=""

for arg in "$@"; do
    case "${arg}" in
        --workspace-root=*) WORKSPACE_ROOT="${arg#*=}" ;;
        --mode=*)           MODE="${arg#*=}" ;;
        --file-path=*)      FILE_PATH="${arg#*=}" ;;
        --content=*)        CONTENT="${arg#*=}" ;;
        --content-file=*)   CONTENT_FILE="${arg#*=}" ;;
        --offset=*)         OFFSET="${arg#*=}" ;;
        --start-offset=*)   START_OFFSET="${arg#*=}" ;;
        --end-offset=*)     END_OFFSET="${arg#*=}" ;;
        *)
            printf 'Unknown argument: %s\n' "${arg}" >&2
            exit 1
            ;;
    esac
done

kast_wrapper_init "kast-write-and-validate"

emit_failure() {
    local stage="$1"
    local message="$2"
    local error_file="${3:-}"
    local log_path
    log_path="$(kast_preserve_log_file)"

    python3 - "${stage}" "${message}" "${WORKSPACE_ROOT}" "${MODE}" "${FILE_PATH}" \
        "${log_path}" "${error_file}" <<'PY'
import json
import sys
from pathlib import Path

(stage, message, workspace_root, mode, file_path, log_file, error_file) = sys.argv[1:]

payload = {
    "ok": False,
    "stage": stage,
    "message": message,
    "query": {
        "workspace_root": workspace_root,
        "mode": mode,
        "file_path": file_path,
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

# ── Validate arguments ────────────────────────────────────────────────────────
VALID_MODES="create-file insert-at-offset replace-range"
if [[ -z "${WORKSPACE_ROOT}" || -z "${MODE}" || -z "${FILE_PATH}" ]]; then
    emit_failure "argument_validation" "--workspace-root, --mode, and --file-path are required."
    exit 1
fi

if ! echo "${VALID_MODES}" | grep -qw "${MODE}"; then
    emit_failure "argument_validation" "--mode must be one of: ${VALID_MODES}."
    exit 1
fi

# Resolve content from --content or --content-file
if [[ -n "${CONTENT_FILE}" ]]; then
    if [[ ! -f "${CONTENT_FILE}" ]]; then
        emit_failure "argument_validation" "--content-file path does not exist: ${CONTENT_FILE}"
        exit 1
    fi
    CONTENT="$(cat "${CONTENT_FILE}")"
fi

if [[ -z "${CONTENT}" ]]; then
    emit_failure "argument_validation" "Either --content or --content-file is required."
    exit 1
fi

case "${MODE}" in
    insert-at-offset)
        if [[ -z "${OFFSET}" ]]; then
            emit_failure "argument_validation" "--offset is required for insert-at-offset mode."
            exit 1
        fi
        ;;
    replace-range)
        if [[ -z "${START_OFFSET}" || -z "${END_OFFSET}" ]]; then
            emit_failure "argument_validation" "--start-offset and --end-offset are required for replace-range mode."
            exit 1
        fi
        ;;
esac

# ── 1. Build and apply the edit ───────────────────────────────────────────────
QUERY_FILE="${TMP_DIR}/apply-query.json"

python3 - "${MODE}" "${FILE_PATH}" "${CONTENT}" "${OFFSET}" "${START_OFFSET}" "${END_OFFSET}" \
    "${QUERY_FILE}" <<'PY'
import json
import sys
import hashlib
from pathlib import Path

(mode, file_path, content, offset, start_offset, end_offset, query_file) = sys.argv[1:]

query: dict = {"edits": [], "fileOperations": []}

if mode == "create-file":
    query["fileOperations"] = [{"type": "create", "filePath": file_path, "content": content}]
elif mode == "insert-at-offset":
    off = int(offset)
    existing = Path(file_path).read_bytes() if Path(file_path).exists() else b""
    file_hash = hashlib.sha256(existing).hexdigest() if existing else None
    edit = {
        "filePath": file_path,
        "startOffset": off,
        "endOffset": off,
        "newText": content,
    }
    if file_hash:
        edit["expectedHash"] = file_hash
    query["edits"] = [edit]
elif mode == "replace-range":
    s_off = int(start_offset)
    e_off = int(end_offset)
    existing = Path(file_path).read_bytes() if Path(file_path).exists() else b""
    file_hash = hashlib.sha256(existing).hexdigest() if existing else None
    edit = {
        "filePath": file_path,
        "startOffset": s_off,
        "endOffset": e_off,
        "newText": content,
    }
    if file_hash:
        edit["expectedHash"] = file_hash
    query["edits"] = [edit]

Path(query_file).write_text(json.dumps(query), encoding="utf-8")
PY

APPLY_RESULT="${TMP_DIR}/apply-result.json"
if ! kast_run_json \
    "${APPLY_RESULT}" \
    "${KAST}" apply-edits \
    --workspace-root="${WORKSPACE_ROOT}" \
    --request-file="${QUERY_FILE}"; then
    emit_failure "apply_edits" "kast apply-edits failed." "${APPLY_RESULT}"
    exit 1
fi

# ── 2. Refresh workspace if a new file was created ────────────────────────────
if [[ "${MODE}" == "create-file" ]]; then
    REFRESH_RESULT="${TMP_DIR}/refresh.json"
    if ! kast_run_json \
        "${REFRESH_RESULT}" \
        "${KAST}" workspace refresh \
        --workspace-root="${WORKSPACE_ROOT}" \
        --file-paths="${FILE_PATH}" 2>/dev/null; then
        : # non-fatal — diagnostics will still run
    fi
fi

# ── 3. Optimize imports ───────────────────────────────────────────────────────
OPTIMIZE_RESULT="${TMP_DIR}/optimize-imports.json"
IMPORT_EDITS_APPLIED=0

if kast_run_json \
    "${OPTIMIZE_RESULT}" \
    "${KAST}" optimize-imports \
    --workspace-root="${WORKSPACE_ROOT}" \
    --file-paths="${FILE_PATH}" 2>/dev/null; then

    # Apply import edits if any were returned
    python3 - "${OPTIMIZE_RESULT}" "${TMP_DIR}/import-apply-query.json" <<'PY'
import json
import sys
from pathlib import Path

(optimize_file, query_file) = sys.argv[1:]
result = json.loads(Path(optimize_file).read_text(encoding="utf-8"))
edits = result.get("edits", [])
if edits:
    Path(query_file).write_text(json.dumps({"edits": edits, "fileOperations": []}), encoding="utf-8")
else:
    Path(query_file).write_text("", encoding="utf-8")
PY

    if [[ -s "${TMP_DIR}/import-apply-query.json" ]]; then
        IMPORT_APPLY_RESULT="${TMP_DIR}/import-apply-result.json"
        if kast_run_json \
            "${IMPORT_APPLY_RESULT}" \
            "${KAST}" apply-edits \
            --workspace-root="${WORKSPACE_ROOT}" \
            --request-file="${TMP_DIR}/import-apply-query.json" 2>/dev/null; then
            IMPORT_EDITS_APPLIED="$(python3 -c "
import json, sys
from pathlib import Path
r = json.loads(Path('${OPTIMIZE_RESULT}').read_text(encoding='utf-8'))
print(len(r.get('edits', [])))
" 2>/dev/null || echo 0)"
        fi
    fi
fi

# ── 4. Run diagnostics ────────────────────────────────────────────────────────
DIAGNOSTICS_RESULT="${TMP_DIR}/diagnostics.json"
if ! kast_run_json \
    "${DIAGNOSTICS_RESULT}" \
    "${KAST}" diagnostics \
    --workspace-root="${WORKSPACE_ROOT}" \
    --file-paths="${FILE_PATH}"; then
    emit_failure "diagnostics" "kast diagnostics failed." "${DIAGNOSTICS_RESULT}"
    exit 1
fi

LOG_PATH="$(kast_preserve_log_file)"
python3 - \
    "${APPLY_RESULT}" \
    "${DIAGNOSTICS_RESULT}" \
    "${WORKSPACE_ROOT}" \
    "${MODE}" \
    "${FILE_PATH}" \
    "${IMPORT_EDITS_APPLIED}" \
    "${LOG_PATH}" <<'PY'
import json
import sys
from pathlib import Path

(
    apply_file,
    diagnostics_file,
    workspace_root,
    mode,
    file_path,
    import_edits_applied,
    log_file,
) = sys.argv[1:]

apply_result = json.loads(Path(apply_file).read_text(encoding="utf-8"))
diag_result = json.loads(Path(diagnostics_file).read_text(encoding="utf-8"))

all_diags = diag_result.get("diagnostics", [])
errors = [d for d in all_diags if d.get("severity") == "ERROR"]
warnings = [d for d in all_diags if d.get("severity") == "WARNING"]
clean = len(errors) == 0

payload = {
    "ok": clean,
    "query": {
        "workspace_root": workspace_root,
        "mode": mode,
        "file_path": file_path,
    },
    "applied_edits": len(apply_result.get("appliedEdits", apply_result.get("edits", []))),
    "import_changes": int(import_edits_applied),
    "diagnostics": {
        "clean": clean,
        "error_count": len(errors),
        "warning_count": len(warnings),
        "errors": errors,
    },
    "log_file": log_file,
}

if not clean:
    payload["message"] = f"{len(errors)} diagnostic error(s) found after applying edits."

print(json.dumps(payload, indent=2))
PY
