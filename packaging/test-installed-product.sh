#!/usr/bin/env bash
set -euo pipefail

if [[ $# == 0 ]]; then
  exec python3 "$(dirname "$0")/run-installed-product.py"
fi
[[ $# == 10 && "$1" == --isolated-fixture && "$3" == --product && "$5" == --control-archive &&
   "$7" == --runtime-archive && "$9" == --report-directory ]] || exit 2
fixture="$2"
[[ "$HOME" == "$fixture/home" && "$KAST_RUNTIME_DIRECTORY" == "$fixture/product/state/run" ]] || exit 1

fail() {
  printf 'installed-product: %s\n' "$*" >&2
  exit 1
}

# Artifact paths belong to the harness invocation, never the production process environment.
product_root="$4"
control_archive="$6"
runtime_archive="$8"
report_directory="${10}"
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
assert document["ideaBuild"] == "262.10315.125", document
assert document["kotlinPluginBuild"] == "262.10315.125-IJ", document
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

runtime_directory="$KAST_RUNTIME_DIRECTORY"
runtime_socket_directory="$runtime_directory"
# The Python owner removes only its exclusive fixture after successful validation.
# A failed passive check retains evidence, including any unexpected socket state.
mkdir -p "$fixture/repo"
printf 'rootProject.name = "installed-product"\n' >"$fixture/repo/settings.gradle.kts"
command_environment=(
  "HOME=$fixture/home"
  "JAVA_OPTS=-Duser.home=$fixture/home"
  "KAST_RUNTIME_ARCHIVE=$runtime_archive"
  "KAST_RUNTIME_STORE=$product_root/runtime-payloads"
  "KAST_RUNTIME_DIRECTORY=$runtime_directory"
  "KAST_CACHE_ROOT=$product_root/state/cache"
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
assert document["cliProjection"]["localCommands"] == [
    "codex", "codex desktop",
    "index status [--root <path>]", "index classes <name> [--root <path>]",
    "index supertype <qualified-name> [--root <path>]", "index generate-completion <shell>",
    "ide status [--root <path>]", "ide classes <name> [--root <path>]",
    "ide supertype <qualified-name> [--root <path>]", "ide generate-completion <shell>",
    "app-server register", "app-server enable", "app-server repair --destructive",
    "app-server status", "app-server stop", "app-server disable",
    "app-server control claim", "app-server control release",
], document["cliProjection"]["localCommands"]
projection = document["serverProjection"]
bootstrap = projection["hostedBootstrap"]
invocations = projection["cliInvocations"]["operations"]
expected_tools = [
    "query",
    "symbol_lookup",
    "symbol_inspect",
    "source_read",
    "semantic_query",
    "impact_analyze",
    "diagnostic_check",
    "change_plan",
    "change_apply",
    "change_recover",
]
assert [tool["name"] for tool in bootstrap["tools"]] == expected_tools, bootstrap
assert "compiler-grounded Kotlin source intelligence" in bootstrap["policy"], bootstrap
assert {tool["operationId"] for tool in bootstrap["tools"]} == {
    invocation["operationId"] for invocation in invocations
}, projection
assert all("bindings" not in invocation["invocation"] for invocation in invocations), invocations
assert all("invocation" not in tool and "cliUsage" not in tool for tool in bootstrap["tools"]), bootstrap
PY

help="$(env "${command_environment[@]}" "$kast" --help)"
for command in symbol source relation traversal diagnostic change codex index ide start stop; do
  grep -Eq "^  ${command}[[:space:]]" <<<"$help" || fail "public command is absent: $command"
done
for command in product status topology broker; do
  if grep -Eq "^  ${command}[[:space:]]" <<<"$help"; then
    fail "retired command is public: $command"
  fi
done
[[ -x "$product_root/bin/kast-codex" ]] || fail "installed integration host is missing"

inspection="$(cd "$fixture/repo" && env "${command_environment[@]}" "$kast")"
python3 - "$inspection" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "inspect", document
assert document["status"] == "complete", document
assert document["control"]["execution"] == "isolated-intellij-sidecar", document
assert document["control"]["runtimeId"].startswith("sha256:"), document
assert document["workspace"]["type"] == "observed", document
assert document["workspace"]["cache"]["type"] == "absent", document
PY

passive_state_manifest() {
  for path in "$runtime_directory" "$runtime_socket_directory" \
    "$product_root/runtime-payloads" "$product_root/state/cache"; do
    [[ ! -e "$path" ]] || find "$path" -print
  done | LC_ALL=C sort
}
before_status_state="$(passive_state_manifest)"
status="$(cd "$fixture/repo" && env "${command_environment[@]}" "$kast")"
after_status_state="$(passive_state_manifest)"
[[ "$before_status_state" == "$after_status_state" ]] ||
  fail "bare inspection mutated isolated runtime or cache state"
if pgrep -fl 'io[.]github[.]amichne[.]kast[.]indexer[.]KastIndexerMainKt' \
  | grep -F -- "$runtime_socket_directory" \
  | grep -q .; then
  fail "bare inspection started its isolated sidecar"
fi
python3 - "$status" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "inspect", document
assert document["status"] == "complete", document
assert document["runtime"] == "stopped", document
assert document["cache"] == {"state": "absent"}, document
PY
[[ ! -e "$fixture/home/Library/Application Support/JetBrains" ]] ||
  fail "metadata or inspection wrote a JetBrains plugin path"

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
    "passiveInspection": "SIDECAR_STOPPED",
    "isolatedIndexerProcessDelta": 0,
}
path = Path(sys.argv[1])
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(document, separators=(",", ":")) + "\n")
temporary.replace(path)
PY

printf 'installed-product: sidecar metadata and passive lifecycle passed\n'
