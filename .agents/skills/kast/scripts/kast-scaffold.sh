#!/usr/bin/env bash
# kast-scaffold.sh — Gather structural context for LLM code generation.
#
# Resolves the target symbol (if given), collects the file outline, type
# hierarchy, references, and insertion point, then emits a single JSON object
# that contains everything an LLM needs to generate code that implements,
# replaces, consolidates, or extracts a declaration.
#
# Usage:
#   kast-scaffold.sh \
#     --workspace-root=/abs/path \
#     --target-file=/abs/path/to/File.kt \
#     [--target-symbol=MyInterface] \
#     [--mode=implement|replace|consolidate|extract] \
#     [--kind=class|interface|function|property]
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/kast-common.sh"

WORKSPACE_ROOT=""
TARGET_FILE=""
TARGET_SYMBOL=""
MODE="implement"
KIND=""

for arg in "$@"; do
    case "${arg}" in
        --workspace-root=*) WORKSPACE_ROOT="${arg#*=}" ;;
        --target-file=*)    TARGET_FILE="${arg#*=}" ;;
        --target-symbol=*)  TARGET_SYMBOL="${arg#*=}" ;;
        --mode=*)           MODE="${arg#*=}" ;;
        --kind=*)           KIND="${arg#*=}" ;;
        *)
            printf 'Unknown argument: %s\n' "${arg}" >&2
            exit 1
            ;;
    esac
done

kast_wrapper_init "kast-scaffold"

emit_failure() {
    local stage="$1"
    local message="$2"
    local error_file="${3:-}"
    local log_path
    log_path="$(kast_preserve_log_file)"

    python3 - "${stage}" "${message}" "${WORKSPACE_ROOT}" "${TARGET_FILE}" "${TARGET_SYMBOL}" \
        "${MODE}" "${KIND}" "${log_path}" "${error_file}" <<'PY'
import json
import sys
from pathlib import Path

(stage, message, workspace_root, target_file, target_symbol, mode, kind, log_file, error_file) = sys.argv[1:]

payload = {
    "ok": False,
    "stage": stage,
    "message": message,
    "query": {
        "workspace_root": workspace_root,
        "target_file": target_file,
        "target_symbol": target_symbol or None,
        "mode": mode,
        "kind": kind or None,
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

VALID_MODES="implement replace consolidate extract"
if [[ -z "${WORKSPACE_ROOT}" || -z "${TARGET_FILE}" ]]; then
    emit_failure "argument_validation" "--workspace-root and --target-file are required."
    exit 1
fi

if ! echo "${VALID_MODES}" | grep -qw "${MODE}"; then
    emit_failure "argument_validation" "--mode must be one of: ${VALID_MODES}."
    exit 1
fi

# ── 1. Outline the target file ────────────────────────────────────────────────
OUTLINE_RESULT="${TMP_DIR}/outline.json"
if ! kast_run_json \
    "${OUTLINE_RESULT}" \
    "${KAST}" outline \
    --workspace-root="${WORKSPACE_ROOT}" \
    --file-path="${TARGET_FILE}"; then
    emit_failure "outline" "kast outline failed." "${OUTLINE_RESULT}"
    exit 1
fi

# ── 2. Optionally resolve symbol and gather deeper context ────────────────────
RESOLVE_JSON_FILE=""
REFERENCES_RESULT=""
TYPE_HIERARCHY_RESULT=""
INSERTION_POINT_RESULT=""

if [[ -n "${TARGET_SYMBOL}" ]]; then
    if ! kast_resolve_named_symbol_query "${WORKSPACE_ROOT}" "${TARGET_SYMBOL}" "${TARGET_FILE}" "${KIND}" ""; then
        emit_failure "${RESOLVE_ERROR_STAGE}" "${RESOLVE_ERROR_MESSAGE}" "${RESOLVE_ERROR_JSON_FILE:-}"
        exit 1
    fi
    RESOLVE_JSON_FILE="${RESOLVED_JSON_FILE}"

    # References
    REFERENCES_RESULT="${TMP_DIR}/references.json"
    if ! kast_run_json \
        "${REFERENCES_RESULT}" \
        "${KAST}" references \
        --workspace-root="${WORKSPACE_ROOT}" \
        --file-path="${RESOLVED_FILE_PATH}" \
        --offset="${RESOLVED_OFFSET}" \
        --include-declaration=true; then
        emit_failure "references" "kast references failed." "${REFERENCES_RESULT}"
        exit 1
    fi

    # Type hierarchy — best-effort, ignore non-fatal errors
    TYPE_HIERARCHY_RESULT="${TMP_DIR}/type-hierarchy.json"
    if ! kast_run_json \
        "${TYPE_HIERARCHY_RESULT}" \
        "${KAST}" type-hierarchy \
        --workspace-root="${WORKSPACE_ROOT}" \
        --file-path="${RESOLVED_FILE_PATH}" \
        --offset="${RESOLVED_OFFSET}" \
        --direction=both \
        --depth=2 2>/dev/null; then
        TYPE_HIERARCHY_RESULT=""
    fi

    # Insertion point — pick target based on mode
    INSERTION_TARGET="CLASS_BODY_END"
    case "${MODE}" in
        implement|consolidate) INSERTION_TARGET="FILE_BOTTOM" ;;
        replace)               INSERTION_TARGET="CLASS_BODY_END" ;;
        extract)               INSERTION_TARGET="FILE_BOTTOM" ;;
    esac

    INSERTION_POINT_RESULT="${TMP_DIR}/insertion-point.json"
    if ! kast_run_json \
        "${INSERTION_POINT_RESULT}" \
        "${KAST}" insertion-point \
        --workspace-root="${WORKSPACE_ROOT}" \
        --file-path="${RESOLVED_FILE_PATH}" \
        --offset="${RESOLVED_OFFSET}" \
        --target="${INSERTION_TARGET}" 2>/dev/null; then
        INSERTION_POINT_RESULT=""
    fi
fi

# ── 3. Read file content ──────────────────────────────────────────────────────
FILE_CONTENT=""
if [[ -f "${TARGET_FILE}" ]]; then
    FILE_CONTENT="$(cat "${TARGET_FILE}")"
fi

LOG_PATH="$(kast_preserve_log_file)"
python3 - \
    "${OUTLINE_RESULT}" \
    "${RESOLVE_JSON_FILE}" \
    "${REFERENCES_RESULT}" \
    "${TYPE_HIERARCHY_RESULT}" \
    "${INSERTION_POINT_RESULT}" \
    "${TARGET_FILE}" \
    "${WORKSPACE_ROOT}" \
    "${TARGET_SYMBOL}" \
    "${MODE}" \
    "${KIND}" \
    "${LOG_PATH}" <<PYEOF
import json
import sys
from pathlib import Path

(
    outline_file,
    resolve_file,
    references_file,
    type_hierarchy_file,
    insertion_point_file,
    target_file,
    workspace_root,
    target_symbol,
    mode,
    kind,
    log_file,
) = sys.argv[1:]

outline_result = json.loads(Path(outline_file).read_text(encoding="utf-8"))

payload = {
    "ok": True,
    "query": {
        "workspace_root": workspace_root,
        "target_file": target_file,
        "target_symbol": target_symbol or None,
        "mode": mode,
        "kind": kind or None,
    },
    "outline": outline_result.get("declarations", []),
    "file_content": Path(target_file).read_text(encoding="utf-8") if Path(target_file).exists() else None,
    "log_file": log_file,
}

if resolve_file:
    resolve_result = json.loads(Path(resolve_file).read_text(encoding="utf-8"))
    payload["symbol"] = resolve_result.get("symbol")

if references_file:
    references_result = json.loads(Path(references_file).read_text(encoding="utf-8"))
    payload["references"] = {
        "locations": references_result.get("references", []),
        "count": len(references_result.get("references", [])),
        "search_scope": references_result.get("searchScope"),
        "declaration": references_result.get("declaration"),
    }

if type_hierarchy_file:
    th_result = json.loads(Path(type_hierarchy_file).read_text(encoding="utf-8"))
    payload["type_hierarchy"] = {
        "root": th_result.get("root"),
        "stats": th_result.get("stats"),
    }

if insertion_point_file:
    ip_result = json.loads(Path(insertion_point_file).read_text(encoding="utf-8"))
    payload["insertion_point"] = ip_result.get("insertionPoint")

print(json.dumps(payload, indent=2))
PYEOF
