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
for resource in operation-registry.json wire-schema.json ide-host.json knowledge/manifest.json; do
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
    names = archive.getnames()
    for launcher in ('bin/kast', 'bin/kast-codex', 'share/kast/libexec/kast-daemon',
                     'share/kast/libexec/kast-service'):
        member = archive.getmember(launcher)
        assert member.isfile() and member.mode & 0o111 == 0o111, (launcher, oct(member.mode))
    forbidden_runtime = ('semantic-runtime', 'kast-indexer', 'topology-', 'runtime-composition', 'workspace-service')
    for member in archive.getmembers():
        if member.name.startswith('share/kast/knowledge/'):
            assert member.isdir() or (member.isfile() and member.name.endswith('.json')), member.name
        else:
            assert not any(token in member.name for token in forbidden_runtime), member.name
    assert any(name.endswith('/share/kast/knowledge/manifest.json') or name == 'share/kast/knowledge/manifest.json' for name in names), names
CHECK
mkdir -p "$fixture/repo"
printf 'rootProject.name = "installed-product"\n' > "$fixture/repo/settings.gradle.kts"
command_environment=("HOME=$fixture/home" "JAVA_OPTS=-Duser.home=$fixture/home" "KAST_RUNTIME_DIRECTORY=$KAST_RUNTIME_DIRECTORY")
daemon="$product_root/share/kast/libexec/kast-daemon"
[[ -x "$daemon" ]] || fail 'private daemon launcher missing'
service="$product_root/share/kast/libexec/kast-service"
[[ -x "$service" ]] || fail 'private service launcher missing'
set +e
env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS "${command_environment[@]}" "$service" unexpected > "$fixture/service.stdout" 2> "$fixture/service.stderr"
service_status=$?
set -e
[[ "$service_status" == 64 && ! -s "$fixture/service.stdout" ]] || fail 'private service accepted unsupported arguments'
python3 - "$fixture/service.stderr" <<'SERVICE'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text().splitlines()[-1]) == {'type': 'arguments'}
SERVICE
set +e
env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u BROKER_SERVICE_IDENTITY -u BROKER_READINESS_FILE "${command_environment[@]}" "$daemon" unexpected > "$fixture/daemon.stdout" 2> "$fixture/daemon.stderr"
daemon_status=$?
set -e
[[ "$daemon_status" == 64 && ! -s "$fixture/daemon.stdout" ]] || fail 'private daemon accepted public arguments'
python3 - "$fixture/daemon.stderr" <<'DAEMON'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {'failure': 'ARGUMENTS_REJECTED'}
DAEMON
set +e
env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u BROKER_SERVICE_IDENTITY -u BROKER_READINESS_FILE "${command_environment[@]}" "$daemon" --login > "$fixture/daemon.stdout" 2> "$fixture/daemon.stderr"
daemon_status=$?
set -e
[[ "$daemon_status" == 64 && ! -s "$fixture/daemon.stdout" ]] || fail 'private login accepted unmanaged invocation'
python3 - "$fixture/daemon.stderr" <<'DAEMON'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {'failure': 'READINESS_REJECTED'}
DAEMON
set +e
env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u BROKER_SERVICE_IDENTITY -u BROKER_READINESS_FILE "${command_environment[@]}" "$daemon" > "$fixture/daemon.stdout" 2> "$fixture/daemon.stderr"
daemon_status=$?
set -e
[[ "$daemon_status" == 64 && ! -s "$fixture/daemon.stdout" ]] || fail 'private daemon accepted unmanaged invocation'
python3 - "$fixture/daemon.stderr" <<'DAEMON'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {'failure': 'READINESS_REJECTED'}
DAEMON
set +e
env "${command_environment[@]}" "$service" register relative > "$fixture/service.stdout" 2> "$fixture/service.stderr"
service_status=$?
set -e
[[ "$service_status" == 64 && ! -s "$fixture/service.stdout" ]] || fail 'private service accepted a relative workspace'
python3 - "$fixture/service.stderr" <<'SERVICE'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text().splitlines()[-1]) == {'type': 'arguments'}
SERVICE
version="$(env "${command_environment[@]}" "$kast" --version)"
[[ "$version" == "kast "*" (IntelliJ plugin)" ]] || fail "unexpected version: $version"
python3 - "$product_root/share/kast/provider-catalog.json" <<'CATALOG'
import json, sys
from pathlib import Path
catalog = json.loads(Path(sys.argv[1]).read_text())
assert catalog['schemaVersion'] == 1
projection = catalog['serverProjection']
assert projection['namespace'] == 'kast'
bootstrap = projection['hostedBootstrap']
assert [tool['name'] for tool in bootstrap['tools']] == [
    'workspace_lifecycle', 'query_symbols', 'symbol_lookup', 'symbol_inspect', 'source_read', 'read_relations',
    'traverse_relations', 'check_diagnostics', 'change_plan', 'change_apply', 'change_recover',
]
assert 'Use kast.query_symbols for declaration-name search' in bootstrap['policy']
assert 'cliInvocations' not in projection
CATALOG
for command in tool ide config codex knowledge; do
  if env "${command_environment[@]}" "$kast" "$command" > "$fixture/out" 2> "$fixture/err"; then
    fail "former public command succeeded: $command"
  fi
done
python3 - "$fixture/err" <<'REJECTED'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text().splitlines()[-1]) == {
    'status': 'rejected', 'boundary': 'usage', 'reason': 'unsupported-private-installer-command'
}
REJECTED
[[ ! -e "$fixture/home/Library/Application Support/JetBrains" && ! -e "$product_root/runtime-payloads" && ! -e "$product_root/state/cache" ]] || fail 'private entry point created IDE or index state'
mkdir -p "$report_directory"
python3 - "$report_directory/topology-installed-product.json" "$version" <<'REPORT'
import json, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({'schemaVersion': 2, 'taskId': 'INSTALLED-PRODUCT', 'outcome': 'COMPLETE', 'product': sys.argv[2], 'semanticAuthority': 'EXISTING_IDE', 'isolatedModules': 'ABSENT', 'missingHost': 'REJECTED', 'knowledge': 'PACKAGED'}, separators=(',', ':')) + '\n')
REPORT
printf 'installed-product: plugin metadata, hosted catalog, and private entry points passed\n'
