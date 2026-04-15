#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/kast-common.sh"

WORKSPACE_ROOT=""
MODULE_NAME=""
INCLUDE_FILES="false"

for arg in "$@"; do
    case "${arg}" in
        --workspace-root=*) WORKSPACE_ROOT="${arg#*=}" ;;
        --module-name=*)    MODULE_NAME="${arg#*=}" ;;
        --include-files=*)  INCLUDE_FILES="${arg#*=}" ;;
        *)
            printf 'Unknown argument: %s\n' "${arg}" >&2
            exit 1
            ;;
    esac
done

kast_wrapper_init "kast-workspace-files"

emit_failure() {
    local stage="$1"
    local message="$2"
    local error_file="${3:-}"
    local log_path
    log_path="$(kast_preserve_log_file)"

    python3 - "${stage}" "${message}" "${WORKSPACE_ROOT}" "${MODULE_NAME}" "${INCLUDE_FILES}" \
        "${log_path}" "${error_file}" <<'PY'
import json
import sys
from pathlib import Path

(stage, message, workspace_root, module_name, include_files, log_file, error_file) = sys.argv[1:]

payload = {
    "ok": False,
    "stage": stage,
    "message": message,
    "query": {
        "workspace_root": workspace_root,
        "module_name": module_name or None,
        "include_files": include_files == "true",
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

if [[ -z "${WORKSPACE_ROOT}" ]]; then
    emit_failure "argument_validation" "--workspace-root is required."
    exit 1
fi

if [[ "${INCLUDE_FILES}" != "true" && "${INCLUDE_FILES}" != "false" ]]; then
    emit_failure "argument_validation" "--include-files must be true or false."
    exit 1
fi

if ! kast_resolve_binary; then
    emit_failure "resolve_kast" "Could not resolve the kast binary."
    exit 1
fi

WORKSPACE_FILES_RESULT="${TMP_DIR}/workspace-files.json"

EXTRA_ARGS=()
if [[ -n "${MODULE_NAME}" ]]; then
    EXTRA_ARGS+=("--module-name=${MODULE_NAME}")
fi

if ! kast_run_json \
    "${WORKSPACE_FILES_RESULT}" \
    "${KAST}" workspace files \
    --workspace-root="${WORKSPACE_ROOT}" \
    --include-files="${INCLUDE_FILES}" \
    "${EXTRA_ARGS[@]}"; then
    emit_failure "workspace_files" "kast workspace files failed." "${WORKSPACE_FILES_RESULT}"
    exit 1
fi

LOG_PATH="$(kast_preserve_log_file)"
python3 - "${WORKSPACE_FILES_RESULT}" "${WORKSPACE_ROOT}" "${MODULE_NAME}" "${INCLUDE_FILES}" "${LOG_PATH}" <<'PY'
import json
import sys
from pathlib import Path

(files_result_file, workspace_root, module_name, include_files, log_file) = sys.argv[1:]

files_result = json.loads(Path(files_result_file).read_text(encoding="utf-8"))

payload = {
    "ok": True,
    "query": {
        "workspace_root": workspace_root,
        "module_name": module_name or None,
        "include_files": include_files == "true",
    },
    "modules": files_result.get("modules", []),
    "schema_version": files_result.get("schemaVersion"),
    "log_file": log_file,
}
print(json.dumps(payload, indent=2))
PY
