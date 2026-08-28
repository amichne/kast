#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "installed-product: $*" >&2
  exit 1
}

product_root="${KAST_INSTALLED_PRODUCT:?KAST_INSTALLED_PRODUCT must name the staged product}"
control_archive="${KAST_CONTROL_ARCHIVE:?KAST_CONTROL_ARCHIVE must name the control archive}"
report_directory="${KAST_INSTALLED_REPORT_DIRECTORY:?report directory is required}"
kast="${product_root}/bin/kast"

[[ -x "${kast}" ]] || fail "staged public command is missing"
[[ -f "${control_archive}" ]] || fail "hosted control archive is missing"
[[ -f "${product_root}/share/kast/operation-registry.json" ]] ||
  fail "operation registry is missing"
[[ -f "${product_root}/share/kast/wire-schema.json" ]] || fail "wire schema is missing"
[[ ! -e "${product_root}/share/kast/semantic-runtime.json" ]] ||
  fail "hosted product retained a semantic-runtime manifest"
if find "${product_root}" \( -name 'kast-indexer' -o -name 'idea-home' \
  -o -name 'semantic-runtime.json' \) -print -quit | grep -q .; then
  fail "hosted product retained isolated-runtime payload"
fi

version="$(${kast} --version)"
[[ "${version}" == "kast "*" (IDE-hosted)" ]] ||
  fail "version does not identify the hosted product: ${version}"
schema="$(${kast} --schema)"
python3 - "${schema}" "${product_root}/share/kast/operation-registry.json" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(sys.argv[1])
registry = json.loads(Path(sys.argv[2]).read_text())
assert document["operationRegistry"] == registry, document
assert "semanticRuntime" not in document, document
assert document["cliProjection"]["commands"], document
PY

fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-hosted-missing-endpoint.XXXXXX")"
cleanup() {
  rm -rf -- "${fixture}"
}
trap cleanup EXIT
git -C "${fixture}" init -q
before_indexers="$(pgrep -f 'io\.github\.amichne\.kast\.indexer\.KastIndexerMainKt' || true)"
if (cd "${fixture}" && "${kast}" workspace inspect) >"${fixture}/missing.json" \
  2>"${fixture}/missing.err"; then
  fail "semantic demand succeeded without an exact-root hosted endpoint"
fi
after_indexers="$(pgrep -f 'io\.github\.amichne\.kast\.indexer\.KastIndexerMainKt' || true)"
[[ "${before_indexers}" == "${after_indexers}" ]] ||
  fail "missing hosted endpoint started an isolated indexer"

mkdir -p "${report_directory}"
python3 - "${report_directory}/topology-installed-product.json" "${version}" <<'PY'
import json
from pathlib import Path
import sys

document = {
    "schemaVersion": 1,
    "taskId": "INSTALLED-PRODUCT",
    "outcome": "COMPLETE",
    "product": sys.argv[2],
    "semanticRuntimeManifest": "ABSENT",
    "missingEndpoint": "REJECTED",
    "isolatedIndexerProcessDelta": 0,
}
path = Path(sys.argv[1])
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(document, separators=(",", ":")) + "\n")
temporary.replace(path)
PY

echo "installed-product: hosted metadata and fail-closed demand passed"
