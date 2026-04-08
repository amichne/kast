#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/kast-common.sh"

WORKSPACE_ROOT=""
FILE_PATHS=""

for arg in "$@"; do
    case "${arg}" in
        --workspace-root=*) WORKSPACE_ROOT="${arg#*=}" ;;
        --file-paths=*) FILE_PATHS="${arg#*=}" ;;
        *)
            printf 'Unknown argument: %s\n' "${arg}" >&2
            exit 1
            ;;
    esac
done

kast_wrapper_init "kast-diagnostics"

emit_failure() {
    local stage="$1"
    local message="$2"
    local error_file="${3:-}"
    local log_path
    log_path="$(kast_preserve_log_file)"

    python3 - "${stage}" "${message}" "${WORKSPACE_ROOT}" "${FILE_PATHS}" "${log_path}" "${error_file}" <<'PY'
import json
import sys
from pathlib import Path

stage, message, workspace_root, file_paths, log_file, error_file = sys.argv[1:]
payload = {
    "ok": False,
    "stage": stage,
    "message": message,
    "query": {
        "workspace_root": workspace_root,
        "file_paths": [entry for entry in file_paths.split(",") if entry],
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

if [[ -z "${WORKSPACE_ROOT}" || -z "${FILE_PATHS}" ]]; then
    emit_failure "argument_validation" "Both --workspace-root and --file-paths are required."
    exit 1
fi

NORMALIZED_FILE_PATHS="$(
    python3 - "${WORKSPACE_ROOT}" "${FILE_PATHS}" <<'PY'
from pathlib import Path
import sys

workspace_root = Path(sys.argv[1]).resolve()
entries = [entry.strip() for entry in sys.argv[2].split(",") if entry.strip()]
normalized = []
for entry in entries:
    path = Path(entry)
    if path.is_absolute():
        normalized.append(str(path.resolve()))
    else:
        normalized.append(str((workspace_root / entry).resolve()))
print(",".join(normalized))
PY
)"

if ! kast_resolve_binary; then
    emit_failure "resolve_kast" "Could not resolve the kast binary."
    exit 1
fi

ENSURE_RESULT="${TMP_DIR}/workspace-ensure.json"
if ! kast_run_json "${ENSURE_RESULT}" "${KAST}" workspace ensure --workspace-root="${WORKSPACE_ROOT}"; then
    emit_failure "workspace_ensure" "workspace ensure failed." "${ENSURE_RESULT}"
    exit 1
fi

DIAGNOSTICS_RESULT="${TMP_DIR}/diagnostics.json"
if ! kast_run_json \
    "${DIAGNOSTICS_RESULT}" \
    "${KAST}" diagnostics \
    --workspace-root="${WORKSPACE_ROOT}" \
    --file-paths="${NORMALIZED_FILE_PATHS}"; then
    emit_failure "diagnostics" "kast diagnostics failed." "${DIAGNOSTICS_RESULT}"
    exit 1
fi

ERROR_COUNT="$(
    python3 "${SCRIPT_DIR}/kast-plan-utils.py" check-diagnostics "${DIAGNOSTICS_RESULT}" 2>>"${LOG_FILE}" || true
)"

LOG_PATH="$(kast_preserve_log_file)"
python3 - "${DIAGNOSTICS_RESULT}" "${NORMALIZED_FILE_PATHS}" "${WORKSPACE_ROOT}" "${ERROR_COUNT}" "${LOG_PATH}" <<'PY'
import json
import sys
from pathlib import Path

diagnostics_file, file_paths_csv, workspace_root, error_count, log_file = sys.argv[1:]
diagnostics_result = json.loads(Path(diagnostics_file).read_text(encoding="utf-8"))
diagnostics = diagnostics_result.get("diagnostics", [])
warning_count = sum(1 for item in diagnostics if item.get("severity") == "WARNING")
info_count = sum(1 for item in diagnostics if item.get("severity") == "INFO")

payload = {
    "ok": True,
    "query": {
        "workspace_root": workspace_root,
        "file_paths": [entry for entry in file_paths_csv.split(",") if entry],
    },
    "clean": int(error_count or "0") == 0,
    "error_count": int(error_count or "0"),
    "warning_count": warning_count,
    "info_count": info_count,
    "diagnostics": diagnostics,
    "log_file": log_file,
}
print(json.dumps(payload, indent=2))
PY
