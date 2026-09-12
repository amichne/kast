#!/usr/bin/env bash
set -euo pipefail
if [[ $# == 0 ]]; then exec python3 "$(dirname "$0")/run-installed-product.py"; fi
[[ $# == 10 && "$1" == --isolated-fixture && "$3" == --product && "$5" == --control-archive && "$7" == --plugin-archive && "$9" == --report-directory ]] || exit 2
fixture="$2"
product_root="$4"
control_archive="$6"
plugin_archive="$8"
report_directory="${10}"
[[ "$HOME" == "$fixture/home" && "$KAST_RUNTIME_DIRECTORY" == "$fixture/product/state/run" ]] || exit 1
fail() { printf 'installed-product: %s\n' "$*" >&2; exit 1; }
kast="$product_root/bin/kast"
[[ -x "$kast" && -f "$control_archive" && -f "$plugin_archive" ]] || fail 'release inputs missing'
for resource in operation-registry.json wire-schema.json ide-host.json; do
  [[ -f "$product_root/share/kast/$resource" ]] || fail "missing resource: $resource"
done
[[ ! -e "$product_root/share/kast/semantic-runtime.json" ]] || fail 'retired manifest shipped'
python3 - "$product_root/share/kast/ide-host.json" "$plugin_archive" "$control_archive" <<'CHECK'
import hashlib, json, sys, tarfile, zipfile
from pathlib import Path
metadata = json.loads(Path(sys.argv[1]).read_text())
plugin = Path(sys.argv[2])
assert set(metadata) == {'schemaVersion', 'productVersion', 'execution', 'ideaBuild', 'kotlinPluginBuild', 'fileName', 'sha256', 'bytes'}
assert metadata['schemaVersion'] == 1 and metadata['execution'] == 'existing_ide'
assert metadata['kotlinPluginBuild'] == metadata['ideaBuild'] + '-IJ'
assert metadata['fileName'] == plugin.name and metadata['bytes'] == plugin.stat().st_size
assert metadata['sha256'] == 'sha256:' + hashlib.sha256(plugin.read_bytes()).hexdigest()
with zipfile.ZipFile(plugin) as archive:
    names = archive.namelist()
    assert all(name.startswith('kast-ide-hosted/') for name in names)
    assert any('/lib/kast-ide-hosted-' in name and name.endswith('.jar') for name in names)
    assert not any(any(token in name for token in ('indexer', 'topology-', 'runtime-composition', 'workspace-service', 'idea-home')) for name in names)
with tarfile.open(sys.argv[3]) as archive:
    assert not any(any(token in name for token in ('semantic-runtime', 'kast-indexer', 'topology-', 'runtime-composition', 'workspace-service')) for name in archive.getnames())
CHECK
mkdir -p "$fixture/repo"
printf 'rootProject.name = "installed-product"\n' > "$fixture/repo/settings.gradle.kts"
command_environment=("HOME=$fixture/home" "JAVA_OPTS=-Duser.home=$fixture/home" "KAST_RUNTIME_DIRECTORY=$KAST_RUNTIME_DIRECTORY")
version="$(env "${command_environment[@]}" "$kast" --version)"
[[ "$version" == "kast "*" (IntelliJ plugin)" ]] || fail "unexpected version: $version"
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
    "ide supertype <qualified-name> [--root <path>]", "ide generate-completion <shell>", "ide trust-broker",
    "app-server register", "app-server enable", "app-server repair --destructive",
    "app-server status", "app-server stop", "app-server disable",
    "app-server control claim", "app-server control release",
], document["cliProjection"]["localCommands"]
projection = document["serverProjection"]
bootstrap = projection["hostedBootstrap"]
invocations = projection["cliInvocations"]["operations"]
expected_tools = [
    "search_classes",
    "search_functions",
    "search_declarations",
    "query_symbols",
    "symbol_lookup",
    "symbol_inspect",
    "source_read",
    "semantic_query",
    "impact_analyze",
    "check_diagnostics",
    "change_plan",
    "change_apply",
    "change_recover",
]
assert [tool["name"] for tool in bootstrap["tools"]] == expected_tools, [tool["name"] for tool in bootstrap["tools"]]
assert "compiler-grounded Kotlin source intelligence" in bootstrap["policy"], bootstrap
assert {tool["operationId"] for tool in bootstrap["tools"]} == {
    invocation["operationId"] for invocation in invocations
}, projection
assert all("bindings" not in invocation["invocation"] for invocation in invocations), invocations
assert all("invocation" not in tool and "cliUsage" not in tool for tool in bootstrap["tools"]), bootstrap
PY

help="$(env "${command_environment[@]}" "$kast" --help)"
for command in tool symbol source relation traversal diagnostic change codex index ide; do
  grep -Eq "^  ${command}[[:space:]]" <<<"$help" || fail "missing command: $command"
done
for command in start stop topology; do
  if grep -Eq "^  ${command}[[:space:]]" <<<"$help"; then fail "retired command is public: $command"; fi
done
inspection="$(cd "$fixture/repo" && env "${command_environment[@]}" "$kast")"
python3 - "$inspection" <<'CHECK'
import json, sys
value = json.loads(sys.argv[1])
assert set(value) == {'operation', 'productVersion', 'semanticAuthority', 'workspace'}
assert value['operation'] == 'product.inspect' and value['semanticAuthority'] == 'existing_ide'
assert value['workspace']['type'] == 'resolved'
CHECK
for command in start stop; do
  if (cd "$fixture/repo" && env "${command_environment[@]}" "$kast" "$command" > "$fixture/out" 2> "$fixture/err"); then fail "retired command succeeded: $command"; fi
done
if (cd "$fixture/repo" && env "${command_environment[@]}" "$kast" ide status > "$fixture/out" 2> "$fixture/err"); then fail 'missing IDE was treated as ready'; fi
[[ ! -e "$fixture/home/Library/Application Support/JetBrains" && ! -e "$product_root/runtime-payloads" && ! -e "$product_root/state/cache" ]] || fail 'passive command created IDE or index state'
mkdir -p "$report_directory"
python3 - "$report_directory/topology-installed-product.json" "$version" <<'REPORT'
import json, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({'schemaVersion': 2, 'taskId': 'INSTALLED-PRODUCT', 'outcome': 'COMPLETE', 'product': sys.argv[2], 'semanticAuthority': 'EXISTING_IDE', 'isolatedModules': 'ABSENT', 'missingHost': 'REJECTED'}, separators=(',', ':')) + '\n')
REPORT
printf 'installed-product: plugin metadata and fail-closed IDE admission passed\n'
