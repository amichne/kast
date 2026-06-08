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

repo_root="$(resolve_repo_root)"
tmp_parent="${TMPDIR:-/tmp}"
tmp_root="$(mktemp -d "${tmp_parent%/}/kast-dev-cli.XXXXXX")"
trap 'rm -rf "$tmp_root"' EXIT

original_home="${HOME:?HOME must be set}"
cargo_home="${CARGO_HOME:-${original_home}/.cargo}"
rustup_home="${RUSTUP_HOME:-${original_home}/.rustup}"
gradle_user_home="${GRADLE_USER_HOME:-${original_home}/.gradle}"

home_dir="${tmp_root}/home"
config_home="${tmp_root}/config"
install_root="${tmp_root}/install-root"
mkdir -p "$home_dir" "$config_home"

run_install_task() {
  local task_config_home="$1"
  HOME="$home_dir" \
    CARGO_HOME="$cargo_home" \
    RUSTUP_HOME="$rustup_home" \
    GRADLE_USER_HOME="$gradle_user_home" \
    KAST_CONFIG_HOME="$task_config_home" \
    "${repo_root}/gradlew" -q installDevelopmentCli
}

cat >"${config_home}/config.toml" <<EOF
[paths]
installRoot = "${install_root}"
EOF

run_install_task "$config_home"

dev_cli="${install_root}/bin/kast-dev"
[[ -x "$dev_cli" ]] || die "Expected executable development CLI at ${dev_cli}"
"$dev_cli" version >/dev/null

[[ ! -e "${install_root}/bin/kast" ]] || die "installDevelopmentCli must not overwrite the configured kast binary"

profile="${tmp_root}/zshrc"
shell_install_json="${tmp_root}/shell-install.json"
HOME="$home_dir" KAST_CONFIG_HOME="$config_home" \
  "$dev_cli" --output json install shell --shell zsh --profile "$profile" >"$shell_install_json"

grep -Fq '"commandName": "kast-dev"' "$shell_install_json" \
  || die "install shell should target the kast-dev command name"
grep -Fq '"shell": "zsh"' "$shell_install_json" \
  || die "install shell should report zsh integration"
grep -Fq "${install_root}/bin" "$shell_install_json" \
  || die "install shell should use the configured installRoot bin directory"
grep -Fq "# >>> kast shell integration >>>" "$profile" \
  || die "install shell should patch the requested profile with a managed block"
source_file="$(sed -n 's/.*"sourceFile": "\(.*\)",/\1/p' "$shell_install_json" | head -1)"
[[ -n "$source_file" && -f "$source_file" ]] \
  || die "install shell should write the managed source file"
grep -Fq "kast-dev install completion zsh --command-name kast-dev" "$source_file" \
  || die "managed shell source should route completion through install completion"

bin_config_home="${tmp_root}/config-bin-dir"
custom_bin_dir="${tmp_root}/custom-bin"
mkdir -p "$bin_config_home"

cat >"${bin_config_home}/config.toml" <<EOF
[paths]
installRoot = "${tmp_root}/ignored-install-root"
binDir = "${custom_bin_dir}"
EOF

run_install_task "$bin_config_home"

dev_cli="${custom_bin_dir}/kast-dev"
[[ -x "$dev_cli" ]] || die "Expected executable development CLI at configured binDir ${dev_cli}"
"$dev_cli" version >/dev/null

[[ ! -e "${custom_bin_dir}/kast" ]] || die "installDevelopmentCli must not overwrite the configured kast binary"

printf '%s\n' "Development CLI install contract passed"
