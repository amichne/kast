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
install_bin_dir="${tmp_root}/bin"
mkdir -p "$home_dir" "$config_home" "$install_bin_dir"

HOME="$home_dir" \
  CARGO_HOME="$cargo_home" \
  RUSTUP_HOME="$rustup_home" \
  GRADLE_USER_HOME="$gradle_user_home" \
  KAST_CONFIG_HOME="$config_home" \
  KAST_BIN_DIR="$install_bin_dir" \
  "${repo_root}/gradlew" -q installDevelopmentCli

installed_cli="${install_bin_dir}/kast-dev"
[[ -x "$installed_cli" ]] || die "Expected installed development CLI at ${installed_cli}"

KAST_INSTALLED_SELECTOR_WORKFLOW_BINARY="$installed_cli" \
  "$cargo_bin" test \
    --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
    --locked \
    --test selector_handle_installed_workflow

printf '%s\n' "Installed selector handle workflow passed"
