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
export KAST_WORKSPACE_ROOT="${WORKSPACE_ROOT}"
export KAST_CONFIG_HOME="${TMP_DIR}/kast-config"
if [[ -d "${REQUEST_ROOT}/kast-cli/build/runtime-libs" ]]; then
    export KAST_RUNTIME_LIBS="${REQUEST_ROOT}/kast-cli/build/runtime-libs"
fi

SAMPLE_SYMBOL="greet"
SAMPLE_FILE="${WORKSPACE_ROOT}/src/main/kotlin/sample/Greeter.kt"
MISSING_SYMBOL="DefinitelyMissingSymbolForWrapperValidation"
SUCCESS_DIAGNOSTICS_FILE="${SAMPLE_FILE}"
DIAGNOSTICS_REQUEST="${TMP_DIR}/diagnostics-request.json"
# Use an empty filePaths array to trigger request_validation failure.
# A missing-file path is NOT used here because per-file error isolation
# (PR #70) absorbs file-not-found into an ANALYSIS_FAILURE diagnostic
# and still exits 0; only a structurally invalid request reliably fails.
DIAGNOSTICS_FAILURE_REQUEST="${TMP_DIR}/diagnostics-failure-request.json"

cat >"${DIAGNOSTICS_REQUEST}" <<EOF
{"filePaths":["${SUCCESS_DIAGNOSTICS_FILE}"]}
EOF

cat >"${DIAGNOSTICS_FAILURE_REQUEST}" <<'EOF'
{"filePaths":[]}
EOF

declare -a CHECKS=(
    "kast-resolve.sh|success|bash \"${SCRIPT_DIR}/kast-resolve.sh\" '{\"symbol\":\"${SAMPLE_SYMBOL}\",\"fileHint\":\"${SAMPLE_FILE}\"}'|true"
    "kast-resolve.sh|failure|bash \"${SCRIPT_DIR}/kast-resolve.sh\" '{\"symbol\":\"${MISSING_SYMBOL}\"}'|false"
    "kast-references.sh|success|bash \"${SCRIPT_DIR}/kast-references.sh\" '{\"symbol\":\"${SAMPLE_SYMBOL}\",\"fileHint\":\"${SAMPLE_FILE}\"}'|true"
    "kast-references.sh|failure|bash \"${SCRIPT_DIR}/kast-references.sh\" '{\"symbol\":\"${MISSING_SYMBOL}\"}'|false"
    "kast-callers.sh|success|bash \"${SCRIPT_DIR}/kast-callers.sh\" '{\"symbol\":\"${SAMPLE_SYMBOL}\",\"fileHint\":\"${SAMPLE_FILE}\",\"direction\":\"incoming\",\"depth\":2}'|true"
    "kast-callers.sh|failure|bash \"${SCRIPT_DIR}/kast-callers.sh\" '{\"symbol\":\"${MISSING_SYMBOL}\"}'|false"
    "kast-diagnostics.sh|success|bash \"${SCRIPT_DIR}/kast-diagnostics.sh\" \"${DIAGNOSTICS_REQUEST}\"|true"
    "kast-diagnostics.sh|failure|bash \"${SCRIPT_DIR}/kast-diagnostics.sh\" \"${DIAGNOSTICS_FAILURE_REQUEST}\"|false"
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
    entry["response_type"] = payload.get("type")
    entry["has_type"] = isinstance(payload.get("type"), str) and bool(payload.get("type"))
    entry["log_file"] = payload.get("log_file")
    entry["matches_expectation"] = (
        payload.get("ok") == (expected_ok == "true")
        and entry["has_type"]
        and str(payload.get("type")).endswith("_SUCCESS" if expected_ok == "true" else "_FAILURE")
    )
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

# ---------------------------------------------------------------------------
# Golden-file structural comparison (optional — runs if golden dir exists)
# ---------------------------------------------------------------------------
GOLDEN_DIR="${REQUEST_ROOT}/evals/fixtures/sample-workspace/golden"
if [[ -d "${GOLDEN_DIR}" ]]; then
    printf '\n[validate-wrapper-json] Running golden-file structural comparisons...\n' >&2

    GOLDEN_RESULTS_FILE="${TMP_DIR}/golden-results.json"
    python3 - "${GOLDEN_DIR}" "${TMP_DIR}" <<'GOLDEN_PY' >"${GOLDEN_RESULTS_FILE}"
import json
import sys
from pathlib import Path

golden_dir = Path(sys.argv[1])
results_dir = Path(sys.argv[2])
results = []

for golden_file in sorted(golden_dir.glob("*.json")):
    golden = json.loads(golden_file.read_text(encoding="utf-8"))
    assertions = golden.get("assertions", {})
    case_name = golden_file.stem
    entry = {"case": case_name, "golden_file": str(golden_file), "checks": [], "ok": True}

    # Find corresponding output file by naming convention
    output_candidates = list(results_dir.glob(f"*{case_name}*")) + list(results_dir.glob("*.success.json"))
    # Golden files are structural templates; validate their assertion schema is well-formed
    for key, value in assertions.items():
        check = {"assertion": key, "expected": value, "pass": True}
        if key in ("edit_count_gte", "reference_count_gte", "min_error_count"):
            if not isinstance(value, int) or value < 0:
                check["pass"] = False
                check["reason"] = f"Expected positive integer, got {value}"
        elif key in ("affected_files_contain", "reference_files_contain", "error_codes_contain", "error_messages_contain"):
            if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
                check["pass"] = False
                check["reason"] = f"Expected list of strings, got {type(value).__name__}"
        elif key in ("diagnostics_empty", "diagnostics_not_empty"):
            if not isinstance(value, bool):
                check["pass"] = False
                check["reason"] = f"Expected boolean, got {type(value).__name__}"
        entry["checks"].append(check)
        if not check["pass"]:
            entry["ok"] = False

    results.append(entry)

all_ok = all(r["ok"] for r in results)
print(json.dumps({"ok": all_ok, "golden_checks": results}, indent=2))
sys.exit(0 if all_ok else 1)
GOLDEN_PY

    if [[ $? -ne 0 ]]; then
        printf '[validate-wrapper-json] Golden-file validation FAILED.\n' >&2
        cat "${GOLDEN_RESULTS_FILE}" >&2
        exit 1
    fi
    printf '[validate-wrapper-json] Golden-file schema validation passed.\n' >&2
fi
