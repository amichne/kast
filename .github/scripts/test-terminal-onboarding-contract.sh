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

agent_up_help="$(run_kast agent up --help)"
require_contains "$agent_up_help" "--no-onboard" "agent up help must expose the onboarding escape hatch"
require_contains "$agent_up_help" "--dry-run" "agent up help must expose dry-run planning"

ready_help="$(run_kast ready --help)"
require_contains "$ready_help" "--for" "ready help must expose task-scoped readiness"

repair_help="$(run_kast repair --help)"
require_contains "$repair_help" "--apply" "repair help must expose explicit mutation gating"

workspace="$(mktemp -d)"
trap 'rm -rf "$workspace"' EXIT
printf '%s\n' 'pluginManagement {}' >"${workspace}/settings.gradle.kts"

set +e
agent_up_json="$(
  TERM=dumb run_kast --output json agent up \
    --workspace-root "$workspace" \
    --backend idea \
    --dry-run
)"
agent_up_status=$?
set -e

python3 - "$agent_up_json" "$workspace" "$agent_up_status" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
workspace = sys.argv[2]
status = int(sys.argv[3])

if payload.get("ok") is False:
    assert status != 0, payload
    assert payload["method"] == "agent/up", payload
    assert payload["error"]["code"] == "AGENT_COMMAND_REMOVED", payload
    replacements = set(payload["error"].get("details", {}).get("replacements", []))
    assert "brew install amichne/kast/kast" in replacements, payload
    assert "kast developer machine plugin" in replacements, payload
    assert "kast agent verify --workspace-root <repo>" in replacements, payload
    sys.exit(0)

assert status == 0, payload
assert payload["type"] == "AGENT_UP", payload
assert payload["ok"] is True, payload
assert payload["stage"] == "DRY_RUN", payload
assert payload["dryRun"] is True, payload
assert payload["setup"]["type"] == "AGENT_SETUP_PLAN", payload
assert payload["setup"]["skillTarget"] == f"{workspace}/.agents/skills/kast", payload
assert len(payload["setup"]["agentsMdTargets"]) == 1, payload
assert payload["setup"]["agentsMdTargets"][0]["path"] == f"{workspace}/AGENTS.local.md", payload
assert payload["setup"]["agentsMdTargets"][0]["willCreate"] is True, payload
assert payload["nextActions"][0]["label"] == "Run repository bring-up", payload
assert "--workspace-root" in payload["nextActions"][0]["argv"], payload
assert "--backend" in payload["nextActions"][0]["argv"], payload
PY

printf '%s\n' "Terminal onboarding contract passed"
