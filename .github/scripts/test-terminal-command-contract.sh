#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="$repo_root/cli-rs/Cargo.toml"

run_kast() {
  cargo run --quiet --manifest-path "$manifest" --bin kast -- "$@"
}

setup_help="$(run_kast setup --help)"
[[ "$setup_help" == *"--source"* ]] || { printf '%s\n' 'error: setup help must require one bundle source' >&2; exit 1; }
[[ "$setup_help" != *"--dry-run"* ]] || { printf '%s\n' 'error: retired setup planner remains public' >&2; exit 1; }

ready_help="$(run_kast ready --help)"
[[ "$ready_help" == *"--for"* ]] || { printf '%s\n' 'error: ready help must expose task scope' >&2; exit 1; }

for retired in \
  'repair --help' \
  'machine --help' \
  'developer machine --help' \
  'developer release activate --help' \
  'agent setup --help'; do
  read -r -a args <<<"$retired"
  if run_kast "${args[@]}" >/dev/null 2>&1; then
    printf 'error: retired command remains callable: %s\n' "$retired" >&2
    exit 1
  fi
done

printf '%s\n' 'terminal command contract passed'
