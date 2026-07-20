#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_parent="${TMPDIR:-/tmp}"
tmp_root="$(mktemp -d "${tmp_parent%/}/kast-selector-workflow.XXXXXX")"
trap 'rm -rf "$tmp_root"' EXIT

original_home="${HOME:?HOME must be set}"
cargo_bin="$(command -v cargo)"
cargo_home="${CARGO_HOME:-${original_home}/.cargo}"
rustup_home="${RUSTUP_HOME:-${original_home}/.rustup}"
gradle_user_home="${GRADLE_USER_HOME:-${original_home}/.gradle}"
home_dir="${tmp_root}/home"
config_home="${tmp_root}/config"
mkdir -p "$home_dir" "$config_home"

if [[ -n "${KAST_INSTALLED_SELECTOR_WORKFLOW_BINARY:-}" || -n "${KAST_INSTALLED_SELECTOR_WORKFLOW_LAUNCHER:-}" ]]; then
  [[ -n "${KAST_INSTALLED_SELECTOR_WORKFLOW_BINARY:-}" ]] || die "Packaged workflow requires KAST_INSTALLED_SELECTOR_WORKFLOW_BINARY"
  [[ -n "${KAST_INSTALLED_SELECTOR_WORKFLOW_LAUNCHER:-}" ]] || die "Packaged workflow requires KAST_INSTALLED_SELECTOR_WORKFLOW_LAUNCHER"
  installed_cli="$KAST_INSTALLED_SELECTOR_WORKFLOW_BINARY"
  installed_launcher="$KAST_INSTALLED_SELECTOR_WORKFLOW_LAUNCHER"
else
  HOME="$home_dir" \
    CARGO_HOME="$cargo_home" \
    RUSTUP_HOME="$rustup_home" \
    GRADLE_USER_HOME="$gradle_user_home" \
    KAST_CONFIG_HOME="$config_home" \
    "${repo_root}/gradlew" -q activateDevelopmentMachine

  machine_bin="${home_dir}/Library/Application Support/Kast/machine/bin"
  installed_cli="${machine_bin}/kast"
  installed_launcher="${machine_bin}/kast-agent-task"
fi
[[ -x "$installed_cli" ]] || die "Expected installed CLI at ${installed_cli}"
[[ -x "$installed_launcher" ]] || die "Expected installed task launcher at ${installed_launcher}"

KAST_INSTALLED_SELECTOR_WORKFLOW_BINARY="$installed_cli" \
  KAST_INSTALLED_SELECTOR_WORKFLOW_LAUNCHER="$installed_launcher" \
  "$cargo_bin" test \
    --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
    --locked \
    --test selector_handle_installed_workflow

printf '%s\n' "Installed selector handle workflow passed"
