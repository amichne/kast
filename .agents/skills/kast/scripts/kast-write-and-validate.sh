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
REQUEST_TYPE=""

kast_wrapper_init "kast-write-and-validate"

emit_failure() {
    local stage="$1"
    local message="$2"
    local error_file="${3:-}"
    local log_path
    log_path="$(kast_preserve_log_file)"

    python3 - "${stage}" "${message}" "${REQUEST_TYPE}" "${WORKSPACE_ROOT}" "${FILE_PATH}" \
        "${log_path}" "${error_file}" <<'PY'
import json
import sys
from pathlib import Path

(stage, message, request_type, workspace_root, file_path, log_file, error_file) = sys.argv[1:]

payload = {
    "type": "WRITE_AND_VALIDATE_FAILURE",
    "ok": False,
    "stage": stage,
    "message": message,
    "query": {
        "type": request_type or None,
        "workspace_root": workspace_root,
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

REQUEST_JSON_FILE="${TMP_DIR}/request.json"
if ! kast_load_request "${REQUEST_JSON_FILE}" "$@"; then
    emit_failure "request_validation" "${KAST_REQUEST_ERROR_MESSAGE}" "${KAST_REQUEST_ERROR_JSON_FILE:-}"
    exit 1
fi

eval "$(
    python3 - "${REQUEST_JSON_FILE}" <<'PY'
import json
import shlex
import sys
from pathlib import Path

request = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
fields = {
    "REQUEST_TYPE": request.get("type", ""),
    "WORKSPACE_ROOT": request.get("workspaceRoot", ""),
    "MODE": request.get("mode", ""),
    "FILE_PATH": request.get("filePath", ""),
    "CONTENT": request.get("content", ""),
    "CONTENT_FILE": request.get("contentFile", ""),
    "OFFSET": request.get("offset", ""),
    "START_OFFSET": request.get("startOffset", ""),
    "END_OFFSET": request.get("endOffset", ""),
}
for key, value in fields.items():
    print(f"{key}={shlex.quote('' if value is None else str(value))}")
PY
)"

if [[ -z "${WORKSPACE_ROOT}" ]]; then
    WORKSPACE_ROOT="$(kast_default_workspace_root || true)"
fi

# ── Validate arguments ────────────────────────────────────────────────────────
if [[ -z "${WORKSPACE_ROOT}" || -z "${FILE_PATH}" || ( -z "${REQUEST_TYPE}" && -z "${MODE}" ) ]]; then
    if [[ -z "${REQUEST_TYPE}" ]]; then
        emit_failure "request_validation" "Request must include type and filePath. workspaceRoot is optional only when KAST_WORKSPACE_ROOT or the current git workspace can supply it."
    else
        emit_failure "request_validation" "Request must include filePath. workspaceRoot is optional only when KAST_WORKSPACE_ROOT or the current git workspace can supply it."
    fi
    exit 1
fi

if [[ -z "${REQUEST_TYPE}" ]]; then
    case "${MODE}" in
        create-file) REQUEST_TYPE="CREATE_FILE_REQUEST" ;;
        insert-at-offset) REQUEST_TYPE="INSERT_AT_OFFSET_REQUEST" ;;
        replace-range) REQUEST_TYPE="REPLACE_RANGE_REQUEST" ;;
    esac
fi

case "${REQUEST_TYPE}" in
    CREATE_FILE_REQUEST)
        EXPECTED_MODE="create-file"
        ;;
    INSERT_AT_OFFSET_REQUEST)
        EXPECTED_MODE="insert-at-offset"
        ;;
    REPLACE_RANGE_REQUEST)
        EXPECTED_MODE="replace-range"
        ;;
    *)
        emit_failure "request_validation" \
            "Request type must be CREATE_FILE_REQUEST, INSERT_AT_OFFSET_REQUEST, or REPLACE_RANGE_REQUEST."
        exit 1
        ;;
esac

if [[ -n "${MODE}" && "${MODE}" != "${EXPECTED_MODE}" ]]; then
    emit_failure "request_validation" "Request type ${REQUEST_TYPE} does not match mode ${MODE}."
    exit 1
fi

MODE="${EXPECTED_MODE}"

# Resolve content from content or contentFile
if [[ -n "${CONTENT_FILE}" ]]; then
    if [[ ! -f "${CONTENT_FILE}" ]]; then
        emit_failure "request_validation" "contentFile path does not exist: ${CONTENT_FILE}"
        exit 1
    fi
    CONTENT="$(cat "${CONTENT_FILE}")"
fi

if [[ -z "${CONTENT}" ]]; then
    emit_failure "request_validation" "Request must include content or contentFile."
    exit 1
fi

case "${MODE}" in
    insert-at-offset)
        if [[ -z "${OFFSET}" ]]; then
            emit_failure "request_validation" "offset is required for insert-at-offset mode."
            exit 1
        fi
        ;;
    replace-range)
        if [[ -z "${START_OFFSET}" || -z "${END_OFFSET}" ]]; then
            emit_failure "request_validation" "startOffset and endOffset are required for replace-range mode."
            exit 1
        fi
        ;;
esac

# ── 1. Build and apply the edit ───────────────────────────────────────────────
if ! kast_resolve_binary; then
    emit_failure "resolve_kast" "Could not resolve the kast binary."
    exit 1
fi

QUERY_FILE="${TMP_DIR}/apply-query.json"

python3 - "${MODE}" "${FILE_PATH}" "${CONTENT}" "${OFFSET}" "${START_OFFSET}" "${END_OFFSET}" \
    "${QUERY_FILE}" <<'PY'
import json
import sys
import hashlib
from pathlib import Path

(mode, file_path, content, offset, start_offset, end_offset, query_file) = sys.argv[1:]

query: dict = {"edits": [], "fileHashes": [], "fileOperations": []}

if mode == "create-file":
    query["fileOperations"] = [{"type": "create", "filePath": file_path, "content": content}]
elif mode == "insert-at-offset":
    off = int(offset)
    existing_text = Path(file_path).read_text(encoding="utf-8") if Path(file_path).exists() else ""
    file_hash = hashlib.sha256(existing_text.encode("utf-8")).hexdigest() if existing_text else None
    edit = {
        "filePath": file_path,
        "startOffset": off,
        "endOffset": off,
        "newText": content,
    }
    query["edits"] = [edit]
    if file_hash:
        query["fileHashes"] = [{"filePath": file_path, "hash": file_hash}]
elif mode == "replace-range":
    s_off = int(start_offset)
    e_off = int(end_offset)
    existing_text = Path(file_path).read_text(encoding="utf-8") if Path(file_path).exists() else ""
    file_hash = hashlib.sha256(existing_text.encode("utf-8")).hexdigest() if existing_text else None
    edit = {
        "filePath": file_path,
        "startOffset": s_off,
        "endOffset": e_off,
        "newText": content,
    }
    query["edits"] = [edit]
    if file_hash:
        query["fileHashes"] = [{"filePath": file_path, "hash": file_hash}]

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
import hashlib
from pathlib import Path

(optimize_file, query_file) = sys.argv[1:]
result = json.loads(Path(optimize_file).read_text(encoding="utf-8"))
edits = result.get("edits", [])
if edits:
    file_paths = list({e["filePath"] for e in edits})
    file_hashes = []
    for fp in sorted(file_paths):
        p = Path(fp)
        if p.exists():
            content = p.read_text(encoding="utf-8")
            h = hashlib.sha256(content.encode("utf-8")).hexdigest()
            file_hashes.append({"filePath": fp, "hash": h})
    Path(query_file).write_text(json.dumps({"edits": edits, "fileHashes": file_hashes, "fileOperations": []}), encoding="utf-8")
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
    "${REQUEST_TYPE}" \
    "${FILE_PATH}" \
    "${OFFSET}" \
    "${START_OFFSET}" \
    "${END_OFFSET}" \
    "${IMPORT_EDITS_APPLIED}" \
    "${LOG_PATH}" <<'PY'
import json
import sys
from pathlib import Path

(
    apply_file,
    diagnostics_file,
    workspace_root,
    request_type,
    file_path,
    offset,
    start_offset,
    end_offset,
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
    "type": "WRITE_AND_VALIDATE_SUCCESS",
    "ok": clean,
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

if request_type == "CREATE_FILE_REQUEST":
    payload["query"] = {
        "type": request_type,
        "workspace_root": workspace_root,
        "file_path": file_path,
    }
elif request_type == "INSERT_AT_OFFSET_REQUEST":
    payload["query"] = {
        "type": request_type,
        "workspace_root": workspace_root,
        "file_path": file_path,
        "offset": int(offset),
    }
else:
    payload["query"] = {
        "type": request_type,
        "workspace_root": workspace_root,
        "file_path": file_path,
        "start_offset": int(start_offset),
        "end_offset": int(end_offset),
    }

if not clean:
    payload["message"] = f"{len(errors)} diagnostic error(s) found after applying edits."

print(json.dumps(payload, indent=2))
PY
