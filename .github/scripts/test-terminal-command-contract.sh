#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

require_contains() {
  local content="$1"
  local expected="$2"
  local description="$3"
  [[ "$content" == *"$expected"* ]] || die "${description}: missing '${expected}'"
}

repo_root="$(resolve_repo_root)"
manifest="${repo_root}/cli-rs/Cargo.toml"

run_kast() {
  cargo run --quiet --manifest-path "$manifest" --bin kast -- "$@"
}

setup_help="$(run_kast setup --help)"
require_contains "$setup_help" "Retired repository setup command" "setup help must identify the retired command"

ready_help="$(run_kast ready --help)"
require_contains "$ready_help" "--for" "ready help must expose task-scoped readiness"

repair_help="$(run_kast repair --help)"
require_contains "$repair_help" "--apply" "repair help must expose explicit mutation gating"

workspace="$(mktemp -d)"
trap 'rm -rf "$workspace"' EXIT
printf '%s\n' 'pluginManagement {}' >"${workspace}/settings.gradle.kts"

set +e
setup_json="$(
  TERM=dumb run_kast --output json setup
)"
setup_status=$?
set -e

python3 - "$setup_json" "$workspace" "$setup_status" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
status = int(sys.argv[3])

assert status != 0, payload
assert payload["method"] == "setup", payload
assert payload["error"]["code"] == "AGENT_COMMAND_REMOVED", payload
replacements = set(payload["error"].get("details", {}).get("replacements", []))
assert "kast machine reconcile" in replacements, payload
assert "codex plugin marketplace add amichne/kast-marketplace --ref main" in replacements, payload
assert "codex plugin add kast@kast" in replacements, payload
PY

printf '%s\n' "Terminal command contract passed"
