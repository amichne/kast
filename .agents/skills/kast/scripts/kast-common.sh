#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

kast_wrapper_init() {
    WRAPPER_NAME="$1"
    TMP_DIR="$(mktemp -d)"
    LOG_DIR="${TMP_DIR}/logs"
    mkdir -p "${LOG_DIR}"
    LOG_FILE="${LOG_DIR}/${WRAPPER_NAME}.log"
    : > "${LOG_FILE}"
    PRESERVED_LOG_FILE=""
    trap kast_wrapper_cleanup EXIT
}

kast_wrapper_cleanup() {
    if [[ -n "${TMP_DIR:-}" && -d "${TMP_DIR}" ]]; then
        rm -rf "${TMP_DIR}"
    fi
}

kast_preserve_log_file() {
    if [[ -z "${PRESERVED_LOG_FILE:-}" ]]; then
        PRESERVED_LOG_FILE="$(mktemp "${TMPDIR:-/tmp}/${WRAPPER_NAME}.log.XXXXXX")"
        if [[ -f "${LOG_FILE}" ]]; then
            cp "${LOG_FILE}" "${PRESERVED_LOG_FILE}"
        else
            : > "${PRESERVED_LOG_FILE}"
        fi
    fi
    printf '%s\n' "${PRESERVED_LOG_FILE}"
}

kast_resolve_binary() {
    if [[ -n "${KAST:-}" ]]; then
        return 0
    fi
    KAST="$(bash "${SCRIPT_DIR}/resolve-kast.sh" 2>>"${LOG_FILE}")"
}

kast_run_json() {
    local output_file="$1"
    local exit_code
    shift
    : > "${output_file}"
    if "$@" >"${output_file}" 2>>"${LOG_FILE}"; then
        if [[ -s "${output_file}" ]]; then
            return 0
        fi
        return 3
    else
        exit_code=$?
        return "${exit_code}"
    fi
}

kast_build_search_regex() {
    python3 - "$1" "${2:-}" <<'PY'
import re
import sys

symbol = sys.argv[1].strip("`")
kind = sys.argv[2].strip().lower()
escaped = re.escape(symbol)
optional_backticks = rf"`?{escaped}`?"

patterns_by_kind = {
    "class": [
        rf"\bclass\s+{optional_backticks}\b",
        rf"\bobject\s+{optional_backticks}\b",
        rf"\binterface\s+{optional_backticks}\b",
        rf"\benum\s+class\s+{optional_backticks}\b",
        rf"\bannotation\s+class\s+{optional_backticks}\b",
        rf"\bvalue\s+class\s+{optional_backticks}\b",
        rf"\btypealias\s+{optional_backticks}\b",
    ],
    "function": [
        rf"\bfun\b[^\n{{=]*{optional_backticks}\s*\(",
    ],
    "property": [
        rf"\b(?:val|var)\s+{optional_backticks}\b",
    ],
}

selected = []
for key in ("class", "function", "property"):
    if kind in ("", key):
        selected.extend(patterns_by_kind[key])

print("|".join(selected))
PY
}

kast_collect_candidate_files() {
    local workspace_root="$1"
    local symbol="$2"
    local file_hint="${3:-}"
    local kind="${4:-}"
    local candidate_file="${TMP_DIR}/candidate-files.raw"
    local regex
    regex="$(kast_build_search_regex "${symbol}" "${kind}")"
    : > "${candidate_file}"

    python3 - "${workspace_root}" "${regex}" "${symbol}" "${candidate_file}" <<'PY' 2>>"${LOG_FILE}"
import re
import sys
from pathlib import Path

workspace_root = Path(sys.argv[1]).resolve()
regex = re.compile(sys.argv[2], re.MULTILINE)
symbol = sys.argv[3]
candidate_file = Path(sys.argv[4])

def matching_files(predicate):
    matches = []
    for path in sorted(workspace_root.rglob("*.kt")):
        if not path.is_file():
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if predicate(content):
            matches.append(str(path))
    return matches

matches = matching_files(lambda content: bool(regex.search(content)))
if not matches:
    print(
        f"No declaration-pattern candidates found for {symbol}; widening to plain symbol search.",
        file=sys.stderr,
    )
    matches = matching_files(lambda content: symbol in content)

candidate_file.write_text(
    "".join(f"{path}\n" for path in matches),
    encoding="utf-8",
)
PY

    python3 - "${workspace_root}" "${file_hint}" "${candidate_file}" <<'PY'
from fnmatch import fnmatchcase
from pathlib import Path
import sys

workspace_root = Path(sys.argv[1]).resolve()
file_hint = sys.argv[2].strip()
candidate_file = Path(sys.argv[3])

paths: list[Path] = []
seen: set[Path] = set()
for raw_line in candidate_file.read_text(encoding="utf-8").splitlines():
    value = raw_line.strip()
    if not value:
        continue
    candidate = Path(value)
    if not candidate.is_absolute():
        normalized = (workspace_root / value.lstrip("./")).resolve()
    else:
        normalized = candidate.resolve()
    if normalized in seen or not normalized.exists():
        continue
    seen.add(normalized)
    paths.append(normalized)

if not file_hint:
    for path in paths:
        print(path)
    raise SystemExit(0)

hint_path = Path(file_hint)
exact_path = None
if hint_path.is_absolute():
    exact_path = hint_path.resolve()
else:
    candidate = (workspace_root / file_hint).resolve()
    if candidate.exists():
        exact_path = candidate

has_glob = any(char in file_hint for char in "*?[")

def safe_relative(path: Path) -> str:
    try:
        return path.relative_to(workspace_root).as_posix()
    except ValueError:
        return str(path)

def matches(path: Path) -> bool:
    absolute = str(path)
    relative = safe_relative(path)
    name = path.name
    if exact_path is not None:
        return path == exact_path
    if has_glob:
        return (
            fnmatchcase(relative, file_hint)
            or fnmatchcase(name, file_hint)
            or fnmatchcase(absolute, file_hint)
        )
    return (
        file_hint in relative
        or file_hint in name
        or file_hint in absolute
    )

for path in paths:
    if matches(path):
        print(path)
PY
}

kast_result_matches_query() {
    local result_file="$1"
    local symbol="$2"
    local kind="${3:-}"
    local containing_type="${4:-}"

    python3 - "${result_file}" "${symbol}" "${kind}" "${containing_type}" <<'PY'
import json
import sys
from pathlib import Path

result_file = Path(sys.argv[1])
symbol = sys.argv[2]
kind = sys.argv[3].strip().lower()
containing_type = sys.argv[4].strip()

data = json.loads(result_file.read_text(encoding="utf-8"))
candidate = None
if isinstance(data.get("symbol"), dict):
    candidate = data["symbol"]
elif isinstance(data.get("declaration"), dict):
    candidate = data["declaration"]
elif isinstance(data.get("root"), dict) and isinstance(data["root"].get("symbol"), dict):
    candidate = data["root"]["symbol"]

if not candidate:
    raise SystemExit(1)

fq_name = candidate.get("fqName") or ""
preview = ""
location = candidate.get("location") or {}
if isinstance(location, dict):
    preview = location.get("preview") or ""

needles = []
if symbol:
    needles.append(symbol)
trimmed = symbol.strip("`")
if trimmed and trimmed not in needles:
    needles.append(trimmed)

if not any(needle and (needle in fq_name or needle in preview) for needle in needles):
    raise SystemExit(1)

actual_kind = (candidate.get("kind") or "").upper()

def kind_matches(expected: str, actual: str) -> bool:
    if not expected:
        return True
    if expected == "class":
        return actual.endswith("CLASS") or actual in {"OBJECT", "INTERFACE", "TYPEALIAS", "ENUM_ENTRY"}
    if expected == "function":
        return actual in {"FUNCTION", "CONSTRUCTOR"}
    if expected == "property":
        return actual in {"PROPERTY", "FIELD", "LOCAL_VARIABLE", "VALUE_PARAMETER"}
    return True

if not kind_matches(kind, actual_kind):
    raise SystemExit(1)

if containing_type and containing_type not in fq_name:
    raise SystemExit(1)
PY
}

kast_resolve_named_symbol_query() {
    local workspace_root="$1"
    local symbol="$2"
    local file_hint="${3:-}"
    local kind="${4:-}"
    local containing_type="${5:-}"
    local ensure_file="${TMP_DIR}/workspace-ensure.json"
    local candidate_files="${TMP_DIR}/candidate-files.txt"
    local offsets_file="${TMP_DIR}/offsets.tsv"
    local resolve_file="${TMP_DIR}/resolve.json"
    local attempt_file="${TMP_DIR}/resolve-attempt.json"
    local last_error_file=""

    kast_resolve_binary

    if ! kast_run_json "${ensure_file}" "${KAST}" workspace ensure --workspace-root="${workspace_root}"; then
        RESOLVE_ERROR_STAGE="workspace_ensure"
        RESOLVE_ERROR_MESSAGE="workspace ensure failed"
        RESOLVE_ERROR_JSON_FILE="${ensure_file}"
        return 1
    fi

    kast_collect_candidate_files "${workspace_root}" "${symbol}" "${file_hint}" "${kind}" >"${candidate_files}"
    if [[ ! -s "${candidate_files}" ]]; then
        RESOLVE_ERROR_STAGE="candidate_search"
        RESOLVE_ERROR_MESSAGE="No declaration candidates matched the symbol query."
        RESOLVE_ERROR_JSON_FILE=""
        return 1
    fi

    while IFS= read -r candidate_file; do
        if ! python3 "${SCRIPT_DIR}/find-symbol-offset.py" "${candidate_file}" --symbol "${symbol}" >"${offsets_file}" 2>>"${LOG_FILE}"; then
            continue
        fi
        if [[ ! -s "${offsets_file}" ]]; then
            continue
        fi

        while IFS=$'\t' read -r offset line column context; do
            if kast_run_json \
                "${attempt_file}" \
                "${KAST}" resolve \
                --workspace-root="${workspace_root}" \
                --file-path="${candidate_file}" \
                --offset="${offset}"; then
                if kast_result_matches_query "${attempt_file}" "${symbol}" "${kind}" "${containing_type}"; then
                    cp "${attempt_file}" "${resolve_file}"
                    RESOLVED_FILE_PATH="${candidate_file}"
                    RESOLVED_OFFSET="${offset}"
                    RESOLVED_LINE="${line}"
                    RESOLVED_COLUMN="${column}"
                    RESOLVED_CONTEXT="${context}"
                    RESOLVED_JSON_FILE="${resolve_file}"
                    return 0
                fi
            else
                last_error_file="${attempt_file}"
            fi
        done <"${offsets_file}"
    done <"${candidate_files}"

    RESOLVE_ERROR_STAGE="symbol_resolve"
    RESOLVE_ERROR_MESSAGE="No resolved symbol matched the symbol query."
    RESOLVE_ERROR_JSON_FILE="${last_error_file}"
    return 1
}
