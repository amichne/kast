#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/kast-common.sh"

WORKSPACE_ROOT=""
SYMBOL=""
FILE_HINT=""
DIRECTION="incoming"
DEPTH="2"
KIND=""
CONTAINING_TYPE=""

for arg in "$@"; do
    case "${arg}" in
        --workspace-root=*) WORKSPACE_ROOT="${arg#*=}" ;;
        --symbol=*) SYMBOL="${arg#*=}" ;;
        --file=*) FILE_HINT="${arg#*=}" ;;
        --direction=*) DIRECTION="${arg#*=}" ;;
        --depth=*) DEPTH="${arg#*=}" ;;
        --kind=*) KIND="${arg#*=}" ;;
        --containing-type=*) CONTAINING_TYPE="${arg#*=}" ;;
        *)
            printf 'Unknown argument: %s\n' "${arg}" >&2
            exit 1
            ;;
    esac
done

kast_wrapper_init "kast-callers"

emit_failure() {
    local stage="$1"
    local message="$2"
    local error_file="${3:-}"
    local log_path
    log_path="$(kast_preserve_log_file)"

    python3 - "${stage}" "${message}" "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" \
        "${CONTAINING_TYPE}" "${DIRECTION}" "${DEPTH}" "${log_path}" "${error_file}" <<'PY'
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
    direction,
    depth,
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
        "direction": direction,
        "depth": int(depth),
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

if [[ -z "${WORKSPACE_ROOT}" || -z "${SYMBOL}" ]]; then
    emit_failure "argument_validation" "Both --workspace-root and --symbol are required."
    exit 1
fi

if [[ "${DIRECTION}" != "incoming" && "${DIRECTION}" != "outgoing" ]]; then
    emit_failure "argument_validation" "--direction must be incoming or outgoing."
    exit 1
fi

if ! [[ "${DEPTH}" =~ ^[0-9]+$ ]]; then
    emit_failure "argument_validation" "--depth must be a non-negative integer."
    exit 1
fi

if ! kast_resolve_named_symbol_query "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" "${CONTAINING_TYPE}"; then
    emit_failure "${RESOLVE_ERROR_STAGE}" "${RESOLVE_ERROR_MESSAGE}" "${RESOLVE_ERROR_JSON_FILE:-}"
    exit 1
fi

CALLERS_RESULT="${TMP_DIR}/callers.json"
if ! kast_run_json \
    "${CALLERS_RESULT}" \
    "${KAST}" call-hierarchy \
    --workspace-root="${WORKSPACE_ROOT}" \
    --file-path="${RESOLVED_FILE_PATH}" \
    --offset="${RESOLVED_OFFSET}" \
    --direction="${DIRECTION}" \
    --depth="${DEPTH}"; then
    emit_failure "call_hierarchy" "kast call-hierarchy failed." "${CALLERS_RESULT}"
    exit 1
fi

LOG_PATH="$(kast_preserve_log_file)"
python3 - "${RESOLVED_JSON_FILE}" "${CALLERS_RESULT}" "${RESOLVED_FILE_PATH}" "${RESOLVED_OFFSET}" \
    "${WORKSPACE_ROOT}" "${SYMBOL}" "${FILE_HINT}" "${KIND}" "${CONTAINING_TYPE}" \
    "${DIRECTION}" "${DEPTH}" "${LOG_PATH}" <<'PY'
import json
import sys
from pathlib import Path

(
    resolve_file,
    callers_file,
    file_path,
    offset,
    workspace_root,
    symbol,
    file_hint,
    kind,
    containing_type,
    direction,
    depth,
    log_file,
) = sys.argv[1:]

resolve_result = json.loads(Path(resolve_file).read_text(encoding="utf-8"))
callers_result = json.loads(Path(callers_file).read_text(encoding="utf-8"))
payload = {
    "ok": True,
    "query": {
        "workspace_root": workspace_root,
        "symbol": symbol,
        "file_hint": file_hint or None,
        "kind": kind or None,
        "containing_type": containing_type or None,
        "direction": direction,
        "depth": int(depth),
    },
    "symbol": resolve_result["symbol"],
    "file_path": file_path,
    "offset": int(offset),
    "root": callers_result.get("root"),
    "stats": callers_result.get("stats"),
    "log_file": log_file,
}
print(json.dumps(payload, indent=2))
PY
