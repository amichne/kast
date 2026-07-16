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
ordinary_cli="${install_bin_dir}/kast"
ordinary_cli_before="${tmp_root}/ordinary-kast-before"
printf '%s\n' 'release-authority-sentinel' >"$ordinary_cli"
chmod 755 "$ordinary_cli"
cp "$ordinary_cli" "$ordinary_cli_before"

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

cmp -s "$ordinary_cli_before" "$ordinary_cli" \
  || die "installDevelopmentCli must leave the ordinary kast authority byte-for-byte unchanged"

profile="${tmp_root}/zshrc"
shell_install_json="${tmp_root}/shell-install.json"
HOME="$home_dir" KAST_CONFIG_HOME="$config_home" \
  "$dev_cli" --output json developer machine shell --shell zsh --profile "$profile" >"$shell_install_json"

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
grep -Fq "kast-dev developer machine completion zsh --command-name kast-dev" "$source_file" \
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
grep -Fq "kast-dev developer machine completion zsh --command-name kast-dev" "${config_home}/shell/kast-dev.zsh" \
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

fake_bin="${tmp_root}/fake-bin"
mkdir -p "$fake_bin"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'printf "%s\n" "${KAST_TEST_PS_OUTPUT:-}"' \
  >"${fake_bin}/ps"
chmod 755 "${fake_bin}/ps"

run_profile_install() {
  local task_config_root="$1"
  local task_ps_output="$2"
  shift 2
  HOME="$home_dir" \
    CARGO_HOME="$cargo_home" \
    RUSTUP_HOME="$rustup_home" \
    GRADLE_USER_HOME="$gradle_user_home" \
    KAST_CONFIG_HOME="$config_home" \
    KAST_BIN_DIR="$install_bin_dir" \
    KAST_TEST_PS_OUTPUT="$task_ps_output" \
    PATH="${fake_bin}:${restricted_path}" \
    "${repo_root}/gradlew" installDevelopmentIdeaPlugin \
      -PkastDevJetBrainsConfigRoot="$task_config_root" \
      --configuration-cache \
      --no-daemon \
      "$@"
}

jetbrains_config_root="${tmp_root}/jetbrains/config"
named_profile="IntelliJIdea2025.2"
newest_profile="IntelliJIdea2026.1"
mkdir -p \
  "${jetbrains_config_root}/${named_profile}" \
  "${jetbrains_config_root}/${newest_profile}"

run_profile_install \
  "$jetbrains_config_root" \
  '' \
  -PkastDevJetBrainsProfile="$named_profile" >/dev/null
[[ -d "${jetbrains_config_root}/${named_profile}/plugins/backend-idea/lib" ]] \
  || die "an explicit JetBrains profile name must select its profile plugins directory"
named_profile_reuse="$(
  run_profile_install \
    "$jetbrains_config_root" \
    '' \
    -PkastDevJetBrainsProfile="$named_profile"
)"
grep -Fq 'Configuration cache entry reused.' <<<"$named_profile_reuse" \
  || die "an executable named-profile install must reuse configuration cache state"

rm -rf \
  "${jetbrains_config_root}/${named_profile}/plugins/backend-idea" \
  "${jetbrains_config_root}/${newest_profile}/plugins/backend-idea"
run_profile_install "$jetbrains_config_root" '' >/dev/null
[[ -d "${jetbrains_config_root}/${newest_profile}/plugins/backend-idea/lib" ]] \
  || die "profile discovery must select the newest available IntelliJ IDEA profile"
[[ ! -e "${jetbrains_config_root}/${named_profile}/plugins/backend-idea" ]] \
  || die "newest-profile discovery must not install into an older profile"

rm -rf \
  "${jetbrains_config_root}/${named_profile}/plugins/backend-idea" \
  "${jetbrains_config_root}/${newest_profile}/plugins/backend-idea"
running_process="/Applications/IntelliJ IDEA.app/Contents/JetBrains/${named_profile}/bin/idea"
run_profile_install "$jetbrains_config_root" "$running_process" >/dev/null
[[ -d "${jetbrains_config_root}/${named_profile}/plugins/backend-idea/lib" ]] \
  || die "a running IntelliJ IDEA profile must take precedence over the newest profile"
[[ ! -e "${jetbrains_config_root}/${newest_profile}/plugins/backend-idea" ]] \
  || die "running-profile discovery must not also install into the newest profile"

rm -rf \
  "${jetbrains_config_root}/${named_profile}/plugins/backend-idea" \
  "${jetbrains_config_root}/${newest_profile}/plugins/backend-idea"
explicit_plugins_dir="${tmp_root}/jetbrains/explicit-plugins"
run_profile_install \
  "$jetbrains_config_root" \
  "$running_process" \
  -PkastDevJetBrainsProfile="$newest_profile" \
  -PkastDevJetBrainsPluginsDir="$explicit_plugins_dir" >/dev/null
[[ -d "${explicit_plugins_dir}/backend-idea/lib" ]] \
  || die "an explicit plugins directory must take precedence over every profile selector"
[[ ! -e "${jetbrains_config_root}/${named_profile}/plugins/backend-idea" ]] \
  || die "explicit plugins-directory selection must not mutate the running profile"
[[ ! -e "${jetbrains_config_root}/${newest_profile}/plugins/backend-idea" ]] \
  || die "explicit plugins-directory selection must not mutate the configured profile"

missing_config_root="${tmp_root}/jetbrains/missing-config"
mkdir -p "$missing_config_root"
set +e
missing_profile_output="$(run_profile_install "$missing_config_root" '' 2>&1)"
missing_profile_status=$?
set -e
[[ "$missing_profile_status" -ne 0 ]] \
  || die "an executable install without any JetBrains profile must fail"
grep -Fq 'No IntelliJIdea profile was found under' <<<"$missing_profile_output" \
  || die "missing-profile failure must retain its actionable profile override guidance"

HOME="$home_dir" \
  CARGO_HOME="$cargo_home" \
  RUSTUP_HOME="$rustup_home" \
  GRADLE_USER_HOME="$gradle_user_home" \
  KAST_CONFIG_HOME="$config_home" \
  PATH="$restricted_path" \
  "${repo_root}/gradlew" -q help --task installDevelopmentLocal >/dev/null

HOME="$home_dir" \
  CARGO_HOME="$cargo_home" \
  RUSTUP_HOME="$rustup_home" \
  GRADLE_USER_HOME="$gradle_user_home" \
  KAST_CONFIG_HOME="$config_home" \
  KAST_BIN_DIR="$install_bin_dir" \
  PATH="$restricted_path" \
  "${repo_root}/gradlew" -q configureDevelopmentMachineDefaults

grep -Fq 'defaultBackend = "idea"' "${config_home}/config.toml" \
  || die "configureDevelopmentMachineDefaults should set the IDEA plugin backend as the developer default"
grep -Fq 'enabled = true' "${config_home}/config.toml" \
  || die "configureDevelopmentMachineDefaults should enable IDEA launch policy"

bin_config_home="${tmp_root}/config-bin-dir"
custom_bin_dir="${tmp_root}/custom-bin"
mkdir -p "$bin_config_home" "$custom_bin_dir"
custom_ordinary_cli="${custom_bin_dir}/kast"
custom_ordinary_cli_before="${tmp_root}/custom-ordinary-kast-before"
printf '%s\n' 'custom-release-authority-sentinel' >"$custom_ordinary_cli"
chmod 755 "$custom_ordinary_cli"
cp "$custom_ordinary_cli" "$custom_ordinary_cli_before"

run_install_task "$bin_config_home" "$custom_bin_dir"

dev_cli="${custom_bin_dir}/kast-dev"
[[ -x "$dev_cli" ]] || die "Expected executable development CLI at configured binDir ${dev_cli}"
"$dev_cli" version >/dev/null

cmp -s "$custom_ordinary_cli_before" "$custom_ordinary_cli" \
  || die "configured binDir install must preserve the ordinary kast authority"

printf '%s\n' "Development CLI install contract passed"
