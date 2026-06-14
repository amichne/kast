#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-hooks-test.XXXXXX")"
trap 'rm -rf -- "$tmp_dir"' EXIT

export KAST_HOOK_REPO_ROOT="$repo_root"
export KAST_HOOK_STATE_DIR="$tmp_dir/state"
export KAST_HOOK_RUN_DIAGNOSTICS=0

run_hook() {
  local event="$1"
  local input="$2"
  python3 "$repo_root/cli-rs/resources/plugin/hooks/kast-hook-policy.py" "$event" <<<"$input"
}

expect_block() {
  local name="$1"
  local event="$2"
  local input="$3"
  set +e
  output="$(run_hook "$event" "$input" 2>&1)"
  status=$?
  set -e
  if [[ "$status" -eq 0 ]]; then
    printf 'expected block for %s, got success: %s\n' "$name" "$output" >&2
    exit 1
  fi
  python3 - "$name" "$output" <<'PY'
import json
import sys
name, raw = sys.argv[1], sys.argv[2]
payload = json.loads(raw)
assert payload["ok"] is False, name
assert payload["action"] == "block", name
assert payload.get("alternatives"), name
PY
}

expect_allow() {
  local name="$1"
  local event="$2"
  local input="$3"
  output="$(run_hook "$event" "$input")"
  python3 - "$name" "$output" <<'PY'
import json
import sys
name, raw = sys.argv[1], sys.argv[2]
payload = json.loads(raw)
assert payload["ok"] is True, name
assert payload["action"] == "allow", name
PY
}

expect_allow "session start" sessionStart '{}'
expect_block "broad kotlin search" preToolUse '{"toolName":"shell","command":"rg AnalysisBackend src/main/kotlin"}'
expect_allow "targeted non-kotlin search" preToolUse '{"toolName":"shell","command":"rg hooks.json AGENTS.md"}'
expect_block "generated edit" preToolUse '{"toolName":"edit","path":"site/index.html","mutation":true}'
expect_block "public api edit" preToolUse '{"toolName":"edit","path":"analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/AnalysisBackend.kt","mutation":true}'
expect_block "rename without symbol resolve" preToolUse '{"toolName":"shell","command":"perl -pi -e s/foo/bar/g analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/Symbol.kt","mutation":true}'
expect_allow "rename with symbol resolve" preToolUse '{"toolName":"shell","command":"perl -pi -e s/foo/bar/g backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessRuntime.kt","mutation":true,"symbolResolved":true}'

post_output="$(run_hook postToolUse '{"toolName":"edit","path":"backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessRuntime.kt","mutation":true}')"
python3 - "$post_output" "$KAST_HOOK_STATE_DIR/state.json" <<'PY'
import json
import pathlib
import sys
payload = json.loads(sys.argv[1])
state = json.loads(pathlib.Path(sys.argv[2]).read_text())
assert payload["ok"] is True
assert state["changedFileSummary"]["kotlin"] == 1
assert state["validation"]["status"] == "skipped"
PY

expect_block "session end blocks skipped diagnostics" sessionEnd '{}'
KAST_HOOK_ALLOW_UNVALIDATED=1 expect_allow "session end override" sessionEnd '{}'

python3 - <<PY
import json
from pathlib import Path
manifest = json.loads(Path("$repo_root/cli-rs/resources/plugin/hooks/hooks.json").read_text())
assert manifest["version"] == 1
for event in ["sessionStart", "preToolUse", "postToolUse", "sessionEnd"]:
    assert event in manifest["hooks"], event
    assert manifest["hooks"][event][0]["type"] == "command", event
PY

python3 - "$repo_root" <<'PY'
import importlib.util
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
module_path = root / "cli-rs/resources/plugin/hooks/kast-hook-policy.py"
spec = importlib.util.spec_from_file_location("kast_hook_policy", module_path)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

paths = module.extract_paths_from_command(
    "perl -pi -e s/foo/bar/g analysis-api/src/main/kotlin/Symbol.kt"
)
assert paths == ["analysis-api/src/main/kotlin/Symbol.kt"], paths

repeated_slashes = "/" * 10_000
assert module.extract_paths_from_command(f"cat {repeated_slashes}") == [repeated_slashes]
PY

printf 'Kast hook tests passed\n'
