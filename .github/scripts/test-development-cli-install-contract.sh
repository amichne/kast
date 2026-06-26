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
restricted_path="/usr/bin:/bin:/usr/sbin:/sbin"

home_dir="${tmp_root}/home"
config_home="${tmp_root}/config"
install_bin_dir="${tmp_root}/install-bin"
mkdir -p "$home_dir" "$config_home" "$install_bin_dir"

run_install_task() {
  local task_config_home="$1"
  local task_bin_dir="$2"
  HOME="$home_dir" \
    CARGO_HOME="$cargo_home" \
    RUSTUP_HOME="$rustup_home" \
    GRADLE_USER_HOME="$gradle_user_home" \
    KAST_CONFIG_HOME="$task_config_home" \
    KAST_BIN_DIR="$task_bin_dir" \
    PATH="$restricted_path" \
    "${repo_root}/gradlew" -q installDevelopmentCli
}

run_install_task "$config_home" "$install_bin_dir"

dev_cli="${install_bin_dir}/kast-dev"
[[ -x "$dev_cli" ]] || die "Expected executable development CLI at ${dev_cli}"
"$dev_cli" version >/dev/null

[[ ! -e "${install_bin_dir}/kast" ]] || die "installDevelopmentCli must not overwrite the configured kast binary"

profile="${tmp_root}/zshrc"
shell_install_json="${tmp_root}/shell-install.json"
HOME="$home_dir" KAST_CONFIG_HOME="$config_home" \
  "$dev_cli" --output json machine shell --shell zsh --profile "$profile" >"$shell_install_json"

grep -Fq '"commandName": "kast-dev"' "$shell_install_json" \
  || die "install shell should target the kast-dev command name"
grep -Fq '"shell": "zsh"' "$shell_install_json" \
  || die "install shell should report zsh integration"
grep -Fq "${install_bin_dir}" "$shell_install_json" \
  || die "install shell should use the configured bin directory"
grep -Fq "# >>> kast shell integration >>>" "$profile" \
  || die "install shell should patch the requested profile with a managed block"
source_file="$(sed -n 's/.*"sourceFile": "\(.*\)",/\1/p' "$shell_install_json" | head -1)"
[[ -n "$source_file" && -f "$source_file" ]] \
  || die "install shell should write the managed source file"
grep -Fq "kast-dev machine completion zsh --command-name kast-dev" "$source_file" \
  || die "managed shell source should route completion through machine completion"

gradle_profile="${tmp_root}/gradle-zshrc"
HOME="$home_dir" \
  CARGO_HOME="$cargo_home" \
  RUSTUP_HOME="$rustup_home" \
  GRADLE_USER_HOME="$gradle_user_home" \
  KAST_CONFIG_HOME="$config_home" \
  KAST_BIN_DIR="$install_bin_dir" \
  PATH="$restricted_path" \
  "${repo_root}/gradlew" -q installDevelopmentShell \
  -PkastDevShell=zsh \
  -PkastDevShellProfile="$gradle_profile"

grep -Fq "# >>> kast shell integration >>>" "$gradle_profile" \
  || die "installDevelopmentShell should patch the requested profile"
grep -Fq "kast-dev machine completion zsh --command-name kast-dev" "${config_home}/shell/kast-dev.zsh" \
  || die "installDevelopmentShell should route completions through kast-dev"

plugins_dir="${tmp_root}/jetbrains/plugins"
mkdir -p "${plugins_dir}/backend-idea"
printf '%s\n' "stale" >"${plugins_dir}/backend-idea/stale.txt"

HOME="$home_dir" \
  CARGO_HOME="$cargo_home" \
  RUSTUP_HOME="$rustup_home" \
  GRADLE_USER_HOME="$gradle_user_home" \
  KAST_CONFIG_HOME="$config_home" \
  KAST_BIN_DIR="$install_bin_dir" \
  PATH="$restricted_path" \
  "${repo_root}/gradlew" -q installDevelopmentIdeaPlugin \
  -PkastDevJetBrainsPluginsDir="$plugins_dir"

[[ -d "${plugins_dir}/backend-idea/lib" ]] \
  || die "installDevelopmentIdeaPlugin should extract the development plugin directory"
[[ ! -e "${plugins_dir}/backend-idea/stale.txt" ]] \
  || die "installDevelopmentIdeaPlugin should replace the previous development plugin directory"
find "${plugins_dir}/backend-idea/lib" -name 'backend-idea-*.jar' -print -quit | grep -q . \
  || die "installDevelopmentIdeaPlugin should install the backend IDEA plugin jar"

HOME="$home_dir" \
  CARGO_HOME="$cargo_home" \
  RUSTUP_HOME="$rustup_home" \
  GRADLE_USER_HOME="$gradle_user_home" \
  KAST_CONFIG_HOME="$config_home" \
  PATH="$restricted_path" \
  "${repo_root}/gradlew" -q help --task installDevelopmentLocal >/dev/null

bin_config_home="${tmp_root}/config-bin-dir"
custom_bin_dir="${tmp_root}/custom-bin"
mkdir -p "$bin_config_home"

run_install_task "$bin_config_home" "$custom_bin_dir"

dev_cli="${custom_bin_dir}/kast-dev"
[[ -x "$dev_cli" ]] || die "Expected executable development CLI at configured binDir ${dev_cli}"
"$dev_cli" version >/dev/null

[[ ! -e "${custom_bin_dir}/kast" ]] || die "installDevelopmentCli must not overwrite the configured kast binary"

printf '%s\n' "Development CLI install contract passed"
