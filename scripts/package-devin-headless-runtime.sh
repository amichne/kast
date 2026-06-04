#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/.." && pwd
}

repo_root="$(resolve_repo_root)"

supports_devin_runtime() {
  "$1" devin-runtime --help >/dev/null 2>&1
}

if [[ -n "${KAST_CLI:-}" ]]; then
  exec "$KAST_CLI" devin-runtime package "$@"
fi

for candidate in \
  "${repo_root}/cli-rs/target/release/kast" \
  "${repo_root}/cli-rs/target/debug/kast"
do
  if [[ -x "$candidate" ]] && supports_devin_runtime "$candidate"; then
    exec "$candidate" devin-runtime package "$@"
  fi
done

if command -v kast >/dev/null 2>&1 && supports_devin_runtime "$(command -v kast)"; then
  exec kast devin-runtime package "$@"
fi

command -v cargo >/dev/null 2>&1 || die "Unable to find kast CLI. Set KAST_CLI or build cli-rs."
exec cargo run --quiet --manifest-path "${repo_root}/cli-rs/Cargo.toml" -- devin-runtime package "$@"
