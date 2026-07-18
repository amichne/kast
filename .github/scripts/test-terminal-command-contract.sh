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
require_contains "$setup_help" "--workspace-root" "setup help must expose workspace selection"
require_contains "$setup_help" "--dry-run" "setup help must expose dry-run planning"

ready_help="$(run_kast ready --help)"
require_contains "$ready_help" "--for" "ready help must expose task-scoped readiness"

repair_help="$(run_kast repair --help)"
require_contains "$repair_help" "--apply" "repair help must expose explicit mutation gating"

workspace="$(mktemp -d)"
trap 'rm -rf "$workspace"' EXIT
printf '%s\n' 'pluginManagement {}' >"${workspace}/settings.gradle.kts"

set +e
setup_json="$(
  TERM=dumb run_kast --output json setup \
    --workspace-root "$workspace" \
    --dry-run
)"
setup_status=$?
set -e

python3 - "$setup_json" "$workspace" "$setup_status" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
workspace = sys.argv[2]
status = int(sys.argv[3])

if payload.get("ok") is False:
    assert status != 0, payload
    assert payload["method"] == "setup", payload
    assert payload["error"]["code"] == "AGENT_COMMAND_REMOVED", payload
    replacements = set(payload["error"].get("details", {}).get("replacements", []))
    assert '/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update' in replacements, payload
    assert "Add https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml as a custom plugin repository" in replacements, payload
    assert "kast agent verify --workspace-root <repo>" in replacements, payload
    sys.exit(0)

assert status == 0, payload
assert payload["type"] == "AGENT_SETUP_PLAN", payload
assert payload["dryRun"] is True, payload
assert payload["skillTarget"] == f"{workspace}/.agents/skills/kast", payload
assert len(payload["agentsMdTargets"]) == 1, payload
assert payload["agentsMdTargets"][0]["path"] == f"{workspace}/AGENTS.local.md", payload
assert payload["agentsMdTargets"][0]["willCreate"] is True, payload
assert "--workspace-root" in payload["installCommand"], payload
assert "--backend" not in payload["installCommand"], payload
PY

printf '%s\n' "Terminal command contract passed"
