#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/kast-common.sh"

WORKSPACE_ROOT=""
SYMBOL=""
FILE_HINT=""
KIND=""
CONTAINING_TYPE=""

kast_wrapper_init "kast-resolve"

emit_failure() {
    local stage="$1"
    local message="$2"
    local error_file="${3:-}"
    local log_path
    log_path="$(kast_preserve_log_file)"

    python3 - "${stage}" "${message}" "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" \
        "${CONTAINING_TYPE}" "${log_path}" "${error_file}" <<'PY'
import json
import sys
from pathlib import Path

stage, message, workspace_root, symbol, file_hint, kind, containing_type, log_file, error_file = sys.argv[1:]
payload = {
    "type": "RESOLVE_FAILURE",
    "ok": False,
    "stage": stage,
    "message": message,
    "query": {
        "workspace_root": workspace_root,
        "symbol": symbol,
        "file_hint": file_hint or None,
        "kind": kind or None,
        "containing_type": containing_type or None,
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
    "WORKSPACE_ROOT": request.get("workspaceRoot", ""),
    "SYMBOL": request.get("symbol", ""),
    "FILE_HINT": request.get("fileHint", ""),
    "KIND": request.get("kind", ""),
    "CONTAINING_TYPE": request.get("containingType", ""),
}
for key, value in fields.items():
    print(f"{key}={shlex.quote('' if value is None else str(value))}")
PY
)"

if [[ -z "${WORKSPACE_ROOT}" ]]; then
    WORKSPACE_ROOT="$(kast_default_workspace_root || true)"
fi

if [[ -z "${WORKSPACE_ROOT}" || -z "${SYMBOL}" ]]; then
    emit_failure "request_validation" "Request must include symbol. workspaceRoot is optional only when KAST_WORKSPACE_ROOT or the current git workspace can supply it."
    exit 1
fi

if ! kast_resolve_named_symbol_query "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" "${CONTAINING_TYPE}"; then
    emit_failure "${RESOLVE_ERROR_STAGE}" "${RESOLVE_ERROR_MESSAGE}" "${RESOLVE_ERROR_JSON_FILE:-}"
    exit 1
fi

LOG_PATH="$(kast_preserve_log_file)"
python3 - "${RESOLVED_JSON_FILE}" "${RESOLVED_FILE_PATH}" "${RESOLVED_OFFSET}" "${RESOLVED_LINE}" \
    "${RESOLVED_COLUMN}" "${RESOLVED_CONTEXT}" "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" \
    "${KIND}" "${CONTAINING_TYPE}" "${LOG_PATH}" <<'PY'
import json
import sys
from pathlib import Path

(
    resolve_file,
    file_path,
    offset,
    line,
    column,
    context,
    workspace_root,
    symbol,
    file_hint,
    kind,
    containing_type,
    log_file,
) = sys.argv[1:]

result = json.loads(Path(resolve_file).read_text(encoding="utf-8"))
payload = {
    "type": "RESOLVE_SUCCESS",
    "ok": True,
    "query": {
        "workspace_root": workspace_root,
        "symbol": symbol,
        "file_hint": file_hint or None,
        "kind": kind or None,
        "containing_type": containing_type or None,
    },
    "symbol": result["symbol"],
    "file_path": file_path,
    "offset": int(offset),
    "candidate": {
        "line": int(line),
        "column": int(column),
        "context": context,
    },
    "log_file": log_file,
}
print(json.dumps(payload, indent=2))
PY
