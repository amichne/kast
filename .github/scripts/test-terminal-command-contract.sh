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

ready_help="$(run_kast ready --help)"
require_contains "$ready_help" "--for" "ready help must expose task-scoped readiness"

repair_help="$(run_kast repair --help)"
require_contains "$repair_help" "--apply" "repair help must expose explicit mutation gating"

printf '%s\n' "Terminal command contract passed"
