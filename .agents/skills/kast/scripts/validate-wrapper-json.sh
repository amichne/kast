#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REQUEST_ROOT="${1:-$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

WORKSPACE_ROOT="${TMP_DIR}/workspace"
mkdir -p "${WORKSPACE_ROOT}/src/main/kotlin/sample"

cat >"${WORKSPACE_ROOT}/src/main/kotlin/sample/Greeter.kt" <<'EOF'
package sample

fun greet(name: String): String = "hi $name"
EOF

cat >"${WORKSPACE_ROOT}/src/main/kotlin/sample/UseGreeter.kt" <<'EOF'
package sample

fun greetTwice(): String = greet("kast") + greet("again")
EOF

export KAST_SOURCE_ROOT="${REQUEST_ROOT}"

SAMPLE_SYMBOL="greet"
SAMPLE_FILE="${WORKSPACE_ROOT}/src/main/kotlin/sample/Greeter.kt"
MISSING_SYMBOL="DefinitelyMissingSymbolForWrapperValidation"
SUCCESS_DIAGNOSTICS_FILE="${SAMPLE_FILE}"
FAILURE_DIAGNOSTICS_FILE="${WORKSPACE_ROOT}/definitely-missing-file.kt"

declare -a CHECKS=(
    "kast-resolve.sh|success|bash \"${SCRIPT_DIR}/kast-resolve.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --symbol=\"${SAMPLE_SYMBOL}\" --file=\"${SAMPLE_FILE}\"|true"
    "kast-resolve.sh|failure|bash \"${SCRIPT_DIR}/kast-resolve.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --symbol=\"${MISSING_SYMBOL}\"|false"
    "kast-references.sh|success|bash \"${SCRIPT_DIR}/kast-references.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --symbol=\"${SAMPLE_SYMBOL}\" --file=\"${SAMPLE_FILE}\"|true"
    "kast-references.sh|failure|bash \"${SCRIPT_DIR}/kast-references.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --symbol=\"${MISSING_SYMBOL}\"|false"
    "kast-callers.sh|success|bash \"${SCRIPT_DIR}/kast-callers.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --symbol=\"${SAMPLE_SYMBOL}\" --file=\"${SAMPLE_FILE}\"|true"
    "kast-callers.sh|failure|bash \"${SCRIPT_DIR}/kast-callers.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --symbol=\"${MISSING_SYMBOL}\"|false"
    "kast-diagnostics.sh|success|bash \"${SCRIPT_DIR}/kast-diagnostics.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --file-paths=\"${SUCCESS_DIAGNOSTICS_FILE}\"|true"
    "kast-diagnostics.sh|failure|bash \"${SCRIPT_DIR}/kast-diagnostics.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --file-paths=\"${FAILURE_DIAGNOSTICS_FILE}\"|false"
    "kast-impact.sh|success|bash \"${SCRIPT_DIR}/kast-impact.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --symbol=\"${SAMPLE_SYMBOL}\" --file=\"${SAMPLE_FILE}\"|true"
    "kast-impact.sh|failure|bash \"${SCRIPT_DIR}/kast-impact.sh\" --workspace-root=\"${WORKSPACE_ROOT}\" --symbol=\"${MISSING_SYMBOL}\"|false"
)

RESULTS_FILE="${TMP_DIR}/results.jsonl"
: > "${RESULTS_FILE}"

for check in "${CHECKS[@]}"; do
    IFS='|' read -r script_name mode command expected_ok <<<"${check}"
    STDOUT_FILE="${TMP_DIR}/${script_name}.${mode}.json"
    STDERR_FILE="${TMP_DIR}/${script_name}.${mode}.stderr"

    if eval "${command}" >"${STDOUT_FILE}" 2>"${STDERR_FILE}"; then
        EXIT_CODE=0
    else
        EXIT_CODE=$?
    fi

    python3 - "${script_name}" "${mode}" "${expected_ok}" "${EXIT_CODE}" "${STDOUT_FILE}" "${STDERR_FILE}" >>"${RESULTS_FILE}" <<'PY'
import json
import sys
from pathlib import Path

script_name, mode, expected_ok, exit_code, stdout_file, stderr_file = sys.argv[1:]
stdout_text = Path(stdout_file).read_text(encoding="utf-8")
stderr_text = Path(stderr_file).read_text(encoding="utf-8")
entry = {
    "script": script_name,
    "mode": mode,
    "expected_ok": expected_ok == "true",
    "exit_code": int(exit_code),
    "stderr": stderr_text.strip() or None,
}

try:
    payload = json.loads(stdout_text)
    entry["valid_json"] = True
    entry["ok_value"] = payload.get("ok")
    entry["log_file"] = payload.get("log_file")
    entry["matches_expectation"] = payload.get("ok") == (expected_ok == "true")
except json.JSONDecodeError as error:
    entry["valid_json"] = False
    entry["matches_expectation"] = False
    entry["parse_error"] = str(error)
    entry["stdout"] = stdout_text

print(json.dumps(entry))
PY
done

python3 - "${RESULTS_FILE}" "${REQUEST_ROOT}" "${WORKSPACE_ROOT}" "${SAMPLE_SYMBOL}" "${SAMPLE_FILE}" <<'PY'
import json
import sys
from pathlib import Path

results = [json.loads(line) for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
ok = all(item.get("valid_json") and item.get("matches_expectation") for item in results)
payload = {
    "ok": ok,
    "request_root": sys.argv[2],
    "workspace_root": sys.argv[3],
    "sample_symbol": sys.argv[4],
    "sample_file": sys.argv[5],
    "checks": results,
}
print(json.dumps(payload, indent=2))
raise SystemExit(0 if ok else 1)
PY
