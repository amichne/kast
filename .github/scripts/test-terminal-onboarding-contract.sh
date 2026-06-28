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

package_verify_help="$(run_kast agent workflow package-verify --help)"
require_contains "$package_verify_help" "--require-copilot" "package-verify help must expose required Copilot checks"
require_contains "$package_verify_help" "--copilot-target-dir" "package-verify help must expose explicit Copilot target checks"
require_contains "$package_verify_help" "--require-instructions" "package-verify help must expose instruction checks"

workspace="$(mktemp -d)"
trap 'rm -rf "$workspace"' EXIT
printf '%s\n' 'pluginManagement {}' >"${workspace}/settings.gradle.kts"

agent_up_json="$(
  TERM=dumb run_kast --output json agent up \
    --workspace-root "$workspace" \
    --backend idea \
    --dry-run
)"

python3 - "$agent_up_json" "$workspace" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
workspace = sys.argv[2]

assert payload["type"] == "AGENT_UP", payload
assert payload["ok"] is True, payload
assert payload["stage"] == "DRY_RUN", payload
assert payload["dryRun"] is True, payload
assert payload["setup"]["type"] == "AGENT_SETUP_PLAN", payload
assert payload["setup"]["skillTarget"] == f"{workspace}/.agents/skills/kast", payload
assert payload["setup"]["agentsMdTargets"] == [], payload
assert payload["nextActions"][0]["label"] == "Run repository bring-up", payload
assert "--workspace-root" in payload["nextActions"][0]["argv"], payload
assert "--backend" in payload["nextActions"][0]["argv"], payload
PY

printf '%s\n' "Terminal onboarding contract passed"
