#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'installed-product: %s\n' "$*" >&2
  exit 1
}

product_root="${KAST_INSTALLED_PRODUCT:?KAST_INSTALLED_PRODUCT must name the staged product}"
control_archive="${KAST_CONTROL_ARCHIVE:?KAST_CONTROL_ARCHIVE must name the control archive}"
runtime_archive="${KAST_SEMANTIC_RUNTIME_ARCHIVE:?sidecar archive is required}"
report_directory="${KAST_INSTALLED_REPORT_DIRECTORY:?report directory is required}"
kast="${product_root}/bin/kast"

[[ -x "$kast" ]] || fail "staged public command is missing"
[[ -f "$control_archive" ]] || fail "control archive is missing"
[[ -f "$runtime_archive" ]] || fail "private sidecar archive is missing"
for resource in operation-registry.json wire-schema.json semantic-runtime.json; do
  [[ -f "$product_root/share/kast/$resource" ]] || fail "control resource is missing: $resource"
done
python3 - "$product_root/share/kast/semantic-runtime.json" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(Path(sys.argv[1]).read_text())
assert document["ideaBuild"] == "262.9437.185", document
assert document["kotlinPluginBuild"] == "262.9437.185-IJ", document
assert document["kastPluginSha256"].startswith("sha256:"), document
PY
if find "$product_root" \( -name 'kast-indexer' -o -name 'idea-home' \
  -o -name 'product-info.json' -o -name 'kast-ide-plugin*' \) -print -quit | grep -q .; then
  fail "control product contains sidecar, public plugin, or IDEA distribution content"
fi
if grep -Eq '(^|/)idea-home/|product-info\.json|kast-ide-plugin' \
  < <(unzip -Z1 "$runtime_archive"); then
  fail "private sidecar contains an IDEA distribution or public plugin"
fi
grep -Fxq 'kast-indexer' < <(unzip -Z1 "$runtime_archive") ||
  fail "private sidecar executable is missing"
grep -Eq '^private-plugins/kast-indexer/lib/.+' < <(unzip -Z1 "$runtime_archive") ||
  fail "private sidecar extension is missing"

fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-sidecar-product.XXXXXX")"
cleanup() {
  rm -rf -- "$fixture"
}
trap cleanup EXIT
mkdir -p "$fixture/home" "$fixture/runtime" "$fixture/repo"
printf 'rootProject.name = "installed-product"\n' >"$fixture/repo/settings.gradle.kts"
command_environment=(
  "HOME=$fixture/home"
  "JAVA_OPTS=-Duser.home=$fixture/home"
  "KAST_RUNTIME_ARCHIVE=$runtime_archive"
  "KAST_RUNTIME_STORE=$fixture/store"
  "KAST_RUNTIME_DIRECTORY=$fixture/runtime"
  "KAST_CACHE_ROOT=$fixture/cache"
)

version="$(env "${command_environment[@]}" "$kast" --version)"
[[ "$version" == "kast "*" (IntelliJ sidecar)" ]] ||
  fail "version does not identify the sidecar product: $version"
schema="$(env "${command_environment[@]}" "$kast" --schema)"
python3 - "$schema" "$product_root/share/kast/operation-registry.json" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(sys.argv[1])
registry = json.loads(Path(sys.argv[2]).read_text())
assert document["operationRegistry"] == registry, document
assert document["cliProjection"]["commands"], document
assert document["cliProjection"]["localCommands"] == ["product inspect"], document
PY

inspection="$(cd "$fixture/repo" && env "${command_environment[@]}" "$kast" product inspect)"
python3 - "$inspection" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "product.inspect", document
assert document["status"] == "complete", document
assert document["control"]["execution"] == "isolated-intellij-sidecar", document
assert document["control"]["runtimeId"].startswith("sha256:"), document
assert document["workspace"]["type"] == "observed", document
assert document["workspace"]["cache"]["type"] == "absent", document
PY

passive_state_manifest() {
  for path in "$fixture/runtime" "$fixture/store" "$fixture/cache"; do
    [[ ! -e "$path" ]] || find "$path" -print
  done | LC_ALL=C sort
}
before_status_state="$(passive_state_manifest)"
status="$(cd "$fixture/repo" && env "${command_environment[@]}" "$kast" status)"
after_status_state="$(passive_state_manifest)"
[[ "$before_status_state" == "$after_status_state" ]] ||
  fail "status mutated isolated runtime or cache state"
if ps -axo command= \
  | grep -F 'io.github.amichne.kast.indexer.KastIndexerMainKt' \
  | grep -F -- "$fixture/runtime" \
  | grep -q .; then
  fail "status started its isolated sidecar"
fi
python3 - "$status" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["command"] == "status", document
assert document["runtime"] == "stopped", document
assert document["cache"] == {"state": "absent"}, document
PY
[[ ! -e "$fixture/home/Library/Application Support/JetBrains" ]] ||
  fail "metadata or status wrote a JetBrains plugin path"

mkdir -p "$report_directory"
python3 - "$report_directory/topology-installed-product.json" "$version" <<'PY'
import json
from pathlib import Path
import sys

document = {
    "schemaVersion": 1,
    "taskId": "INSTALLED-PRODUCT",
    "outcome": "COMPLETE",
    "product": sys.argv[2],
    "semanticRuntimeManifest": "PRESENT",
    "productInspection": "SIDECAR",
    "passiveStatus": "STOPPED",
    "isolatedIndexerProcessDelta": 0,
}
path = Path(sys.argv[1])
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(document, separators=(",", ":")) + "\n")
temporary.replace(path)
PY

printf 'installed-product: sidecar metadata and passive lifecycle passed\n'
