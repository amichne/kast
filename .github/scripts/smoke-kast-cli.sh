#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '%s\n' "$*" >&2
}

die() {
  log "error: $*"
  exit 1
}

if [[ $# -ne 1 ]]; then
  die "Usage: $0 /absolute/path/to/kast"
fi

readonly KAST_CMD="$1"
[[ -x "$KAST_CMD" ]] || die "Kast command is not executable: $KAST_CMD"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-smoke.XXXXXX")"
workspace_dir="${tmp_dir}/workspace"
instance_dir="${tmp_dir}/instance"
daemon_log=""

cleanup() {
  if [[ -d "$workspace_dir" ]]; then
    KAST_CONFIG_HOME="$instance_dir" \
      "$KAST_CMD" workspace stop --workspace-root="$workspace_dir" >/dev/null 2>&1 || true
  fi
  rm -rf "$tmp_dir"
}

dump_logs() {
  local candidate_log="$daemon_log"
  if [[ -z "$candidate_log" ]]; then
    candidate_log="${workspace_dir}/.kast/logs/standalone-daemon.log"  # legacy fallback path
  fi

  if [[ -f "$candidate_log" ]]; then
    log "Kast daemon log:"
    cat "$candidate_log" >&2
  fi
}

trap cleanup EXIT
trap 'status=$?; dump_logs; exit "$status"' ERR

mkdir -p "$workspace_dir/src/main/kotlin/sample" "$instance_dir"

python3 - "$workspace_dir" <<'PY'
import sys
from pathlib import Path

workspace_dir = Path(sys.argv[1])
source_root = workspace_dir / "src/main/kotlin/sample"

(source_root / "Greeter.kt").write_text(
    'package sample\n\nfun greet(name: String): String = "hi $name"\n',
    encoding="utf-8",
)
(source_root / "Use.kt").write_text(
    'package sample\n\nfun use(): String = greet("kast")\n',
    encoding="utf-8",
)
(source_root / "SecondaryUse.kt").write_text(
    'package sample\n\nfun useAgain(): String = greet("again")\n',
    encoding="utf-8",
)
(source_root / "Broken.kt").write_text(
    "package sample\n\nfun broken(): String = missingValue()\n",
    encoding="utf-8",
)
PY

use_file="${workspace_dir}/src/main/kotlin/sample/Use.kt"
broken_file="${workspace_dir}/src/main/kotlin/sample/Broken.kt"

offset="$(
  python3 - "$use_file" <<'PY'
import sys
from pathlib import Path

use_file = Path(sys.argv[1])
print(use_file.read_text(encoding="utf-8").index("greet"))
PY
)"

KAST_CONFIG_HOME="$instance_dir" \
  "$KAST_CMD" workspace ensure \
  --workspace-root="$workspace_dir" \
  --wait-timeout-ms=180000 >"${tmp_dir}/ensure.json"

daemon_log="$(
  python3 - "${tmp_dir}/ensure.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(payload.get("logFile", ""))
PY
)"

KAST_CONFIG_HOME="$instance_dir" \
  "$KAST_CMD" capabilities \
  --workspace-root="$workspace_dir" \
  --wait-timeout-ms=180000 >"${tmp_dir}/capabilities.json"

KAST_CONFIG_HOME="$instance_dir" \
  "$KAST_CMD" resolve \
  --workspace-root="$workspace_dir" \
  --file-path="$use_file" \
  --offset="$offset" \
  --wait-timeout-ms=180000 >"${tmp_dir}/symbol.json"

KAST_CONFIG_HOME="$instance_dir" \
  "$KAST_CMD" references \
  --workspace-root="$workspace_dir" \
  --file-path="$use_file" \
  --offset="$offset" \
  --include-declaration=true \
  --wait-timeout-ms=180000 >"${tmp_dir}/references.json"

KAST_CONFIG_HOME="$instance_dir" \
  "$KAST_CMD" diagnostics \
  --workspace-root="$workspace_dir" \
  --file-paths="$broken_file" \
  --wait-timeout-ms=180000 >"${tmp_dir}/diagnostics.json"

KAST_CONFIG_HOME="$instance_dir" \
  "$KAST_CMD" rename \
  --workspace-root="$workspace_dir" \
  --file-path="$use_file" \
  --offset="$offset" \
  --new-name=welcome \
  --wait-timeout-ms=180000 >"${tmp_dir}/rename.json"

python3 - "$tmp_dir" "$workspace_dir" <<'PY'
import json
import sys
from pathlib import Path

tmp_dir = Path(sys.argv[1])
workspace_dir = Path(sys.argv[2])
broken_file = workspace_dir / "src/main/kotlin/sample/Broken.kt"

ensure = json.loads((tmp_dir / "ensure.json").read_text(encoding="utf-8"))
capabilities = json.loads((tmp_dir / "capabilities.json").read_text(encoding="utf-8"))
symbol = json.loads((tmp_dir / "symbol.json").read_text(encoding="utf-8"))
references = json.loads((tmp_dir / "references.json").read_text(encoding="utf-8"))
diagnostics = json.loads((tmp_dir / "diagnostics.json").read_text(encoding="utf-8"))
rename = json.loads((tmp_dir / "rename.json").read_text(encoding="utf-8"))

selected = ensure["selected"]
assert ensure["workspaceRoot"] == str(workspace_dir)
assert selected["pidAlive"] is True
assert selected["reachable"] is True
assert selected["ready"] is True
assert selected["runtimeStatus"]["state"] == "READY"
assert selected["capabilities"]["backendName"] == "standalone"

assert capabilities["backendName"] == "standalone"
assert "RESOLVE_SYMBOL" in capabilities["readCapabilities"]
assert "FIND_REFERENCES" in capabilities["readCapabilities"]
assert "DIAGNOSTICS" in capabilities["readCapabilities"]
assert "RENAME" in capabilities["mutationCapabilities"]

assert symbol["symbol"]["fqName"] == "sample.greet"
assert symbol["symbol"]["location"]["filePath"].endswith("Greeter.kt")

reference_files = {Path(entry["filePath"]).name for entry in references["references"]}
assert Path(references["declaration"]["location"]["filePath"]).name == "Greeter.kt"
assert reference_files == {"Use.kt", "SecondaryUse.kt"}

assert diagnostics["diagnostics"], "expected at least one standalone diagnostic"
assert Path(diagnostics["diagnostics"][0]["location"]["filePath"]).name == broken_file.name
assert diagnostics["diagnostics"][0]["code"] == "UNRESOLVED_REFERENCE"

edit_files = {Path(edit["filePath"]).name for edit in rename["edits"]}
assert edit_files == {"Greeter.kt", "Use.kt", "SecondaryUse.kt"}
assert set(Path(path).name for path in rename["affectedFiles"]) == edit_files
PY

KAST_CONFIG_HOME="$instance_dir" \
  "$KAST_CMD" workspace stop \
  --workspace-root="$workspace_dir" >"${tmp_dir}/stop.json"

for _ in $(seq 1 30); do
  if ! find "$instance_dir" -name '*.json' -print -quit | grep -q .; then
    break
  fi
  sleep 1
done

if find "$instance_dir" -name '*.json' -print -quit | grep -q .; then
  die "descriptor file was not removed on shutdown"
fi

log "Portable Kast smoke test passed for $KAST_CMD"
