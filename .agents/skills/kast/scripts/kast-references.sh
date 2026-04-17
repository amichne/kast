#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/kast-common.sh"

WORKSPACE_ROOT=""
SYMBOL=""
FILE_HINT=""
INCLUDE_DECLARATION="true"
KIND=""
CONTAINING_TYPE=""

kast_wrapper_init "kast-references"

emit_failure() {
    local stage="$1"
    local message="$2"
    local error_file="${3:-}"
    local log_path
    log_path="$(kast_preserve_log_file)"

    python3 - "${stage}" "${message}" "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" \
        "${CONTAINING_TYPE}" "${INCLUDE_DECLARATION}" "${log_path}" "${error_file}" <<'PY'
import json
import sys
from pathlib import Path

(
    stage,
    message,
    workspace_root,
    symbol,
    file_hint,
    kind,
    containing_type,
    include_declaration,
    log_file,
    error_file,
) = sys.argv[1:]

payload = {
    "ok": False,
    "stage": stage,
    "message": message,
    "query": {
        "workspace_root": workspace_root,
        "symbol": symbol,
        "file_hint": file_hint or None,
        "kind": kind or None,
        "containing_type": containing_type or None,
        "include_declaration": include_declaration == "true",
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
include_declaration = request.get("includeDeclaration")
if include_declaration is None:
    include_declaration = True
fields = {
    "WORKSPACE_ROOT": request.get("workspaceRoot", ""),
    "SYMBOL": request.get("symbol", ""),
    "FILE_HINT": request.get("fileHint", ""),
    "INCLUDE_DECLARATION": str(include_declaration).lower(),
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

if [[ "${INCLUDE_DECLARATION}" != "true" && "${INCLUDE_DECLARATION}" != "false" ]]; then
    emit_failure "request_validation" "includeDeclaration must be true or false."
    exit 1
fi

if ! kast_resolve_named_symbol_query "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" "${CONTAINING_TYPE}"; then
    emit_failure "${RESOLVE_ERROR_STAGE}" "${RESOLVE_ERROR_MESSAGE}" "${RESOLVE_ERROR_JSON_FILE:-}"
    exit 1
fi

REFERENCES_RESULT="${TMP_DIR}/references.json"
if ! kast_run_json \
    "${REFERENCES_RESULT}" \
    "${KAST}" references \
    --workspace-root="${WORKSPACE_ROOT}" \
    --file-path="${RESOLVED_FILE_PATH}" \
    --offset="${RESOLVED_OFFSET}" \
    --include-declaration="${INCLUDE_DECLARATION}"; then
    emit_failure "references" "kast references failed." "${REFERENCES_RESULT}"
    exit 1
fi

LOG_PATH="$(kast_preserve_log_file)"
python3 - "${RESOLVED_JSON_FILE}" "${REFERENCES_RESULT}" "${RESOLVED_FILE_PATH}" "${RESOLVED_OFFSET}" \
    "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" "${CONTAINING_TYPE}" \
    "${INCLUDE_DECLARATION}" "${LOG_PATH}" <<'PY'
import json
import sys
from pathlib import Path

(
    resolve_file,
    references_file,
    file_path,
    offset,
    workspace_root,
    symbol,
    file_hint,
    kind,
    containing_type,
    include_declaration,
    log_file,
) = sys.argv[1:]

resolve_result = json.loads(Path(resolve_file).read_text(encoding="utf-8"))
references_result = json.loads(Path(references_file).read_text(encoding="utf-8"))
payload = {
    "ok": True,
    "query": {
        "workspace_root": workspace_root,
        "symbol": symbol,
        "file_hint": file_hint or None,
        "kind": kind or None,
        "containing_type": containing_type or None,
        "include_declaration": include_declaration == "true",
    },
    "symbol": resolve_result["symbol"],
    "file_path": file_path,
    "offset": int(offset),
    "references": references_result.get("references", []),
    "search_scope": references_result.get("searchScope"),
    "declaration": references_result.get("declaration"),
    "log_file": log_file,
}
print(json.dumps(payload, indent=2))
PY
