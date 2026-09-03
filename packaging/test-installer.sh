#!/usr/bin/env bash
# shellcheck disable=SC2016 # Single-quoted fragments generate isolated fixtures.
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-installer-test.XXXXXX")"
runtime_socket_directory=""

cleanup() {
  rm -rf -- "$fixture_root"
  [[ -z "$runtime_socket_directory" ]] || rm -rf -- "$runtime_socket_directory"
}
trap cleanup EXIT

fail() {
  printf 'installer contract: %s\n' "$*" >&2
  exit 1
}

assert_absent() {
  [[ ! -e "$1" && ! -L "$1" ]] || fail "expected Kast-owned path to be absent: $1"
}

assert_present() {
  [[ -e "$1" || -L "$1" ]] || fail "expected unrelated path to remain: $1"
}

home="$fixture_root/home"
stub_bin="$fixture_root/stub-bin"
java_home="$fixture_root/java-home"
test_state="$fixture_root/state"
assets="$fixture_root/assets/v9.8.7"
mkdir -p "$home" "$stub_bin" "$java_home/bin" "$test_state" "$assets"
java_home="$(CDPATH='' cd -- "$java_home" && pwd -P)"

launchctl_state="$test_state/launchctl-service"
launchctl_log="$test_state/launchctl.log"
brew_state="$test_state/homebrew-formula"
brew_log="$test_state/brew.log"
process_state="$test_state/indexer-process"
process_log="$test_state/process.log"
curl_log="$test_state/curl.log"

printf '%s\n' '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'case "${1:-}" in' \
  '  list)' \
  '    [[ ! -e "${KAST_TEST_LAUNCHCTL_STATE:?}" ]] || printf "321\t0\tio.github.amichne.kast.indexer.fixture\n"' \
  '    printf "654\t0\tcom.example.keep\n"' \
  '    ;;' \
  '  remove)' \
  '    printf "%s\n" "${2:?}" >> "${KAST_TEST_LAUNCHCTL_LOG:?}"' \
  '    rm -f "${KAST_TEST_LAUNCHCTL_STATE:?}"' \
  '    ;;' \
  '  *) exit 64 ;;' \
  'esac' >"$stub_bin/launchctl"
chmod +x "$stub_bin/launchctl"

printf '%s\n' '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'case "${1:-} ${2:-} ${3:-}" in' \
  '  "list --formula kast") [[ -e "${KAST_TEST_BREW_STATE:?}" ]] ;;' \
  '  "uninstall --force kast")' \
  '    printf "uninstall --force kast\n" >> "${KAST_TEST_BREW_LOG:?}"' \
  '    rm -f "${KAST_TEST_BREW_STATE:?}"' \
  '    ;;' \
  '  "list --cask kast") exit 1 ;;' \
  '  *) exit 64 ;;' \
  'esac' >"$stub_bin/brew"
chmod +x "$stub_bin/brew"

printf '%s\n' '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  '[[ ! -e "${KAST_TEST_PROCESS_STATE:?}" ]] || printf "777 io.github.amichne.kast.indexer.KastIndexerMainKt --fixture\n"' \
  'printf "888 com.example.keep --fixture\n"' >"$stub_bin/ps"
chmod +x "$stub_bin/ps"

printf '%s\n' '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'case "${1:?}:${2:?}" in' \
  '  -TERM:777|-KILL:777)' \
  '    printf "%s %s\n" "$1" "$2" >> "${KAST_TEST_PROCESS_LOG:?}"' \
  '    rm -f "${KAST_TEST_PROCESS_STATE:?}"' \
  '    ;;' \
  '  -0:777) [[ -e "${KAST_TEST_PROCESS_STATE:?}" ]] ;;' \
  '  *) exit 64 ;;' \
  'esac' >"$stub_bin/kill"
chmod +x "$stub_bin/kill"

printf '%s\n' '#!/usr/bin/env bash' \
  'case "${1:-}" in' \
  '  -s) printf "Darwin\n" ;;' \
  '  -m) printf "arm64\n" ;;' \
  '  *) exit 64 ;;' \
  'esac' >"$stub_bin/uname"
chmod +x "$stub_bin/uname"

printf '%s\n' '#!/usr/bin/env bash' \
  'printf '\''openjdk version "25.0.3" 2026-04-21\n'\'' >&2' \
  'printf "    java.home = %s\n" "${KAST_TEST_JAVA_HOME:?}" >&2' >"$java_home/bin/java"
chmod +x "$java_home/bin/java"
ln -s "$java_home/bin/java" "$stub_bin/java"

printf '%s\n' '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'destination=""' \
  'source_url=""' \
  'while [[ $# -gt 0 ]]; do' \
  '  case "$1" in' \
  '    --output) destination="${2:?}"; shift 2 ;;' \
  '    --retry-delay|--write-out) shift 2 ;;' \
  '    --fail|--location|--silent|--show-error|--retry) shift ;;' \
  '    http*|file:*) source_url="$1"; shift ;;' \
  '    *) shift ;;' \
  '  esac' \
  'done' \
  '[[ -n "$destination" && -n "$source_url" ]] || exit 64' \
  'printf "%s\n" "$source_url" >> "${KAST_TEST_CURL_LOG:?}"' \
  'cp "${KAST_TEST_ASSET_DIR:?}/${destination##*/}" "$destination"' >"$stub_bin/curl"
chmod +x "$stub_bin/curl"

default_data="$home/xdg-data/kast"
legacy_data="$home/.local/share/kast"
custom_install="$home/custom/install"
custom_bin="$home/custom/bin"
default_cache="$home/.cache/kast"
xdg_config="$home/xdg-config/kast"
legacy_config="$home/.config/kast"
application_support="$home/Library/Application Support/Kast"
legacy_plugin="$home/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/kast-indexer"
plugin_sibling="$home/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/keep"
runtime_directory="$fixture_root/tmp/kast-runtime"
runtime_socket_directory="/tmp/kast-runtime-$(
  printf '%s' "$runtime_directory" \
    | sed -E 's:/+:/:g' \
    | shasum -a 256 \
    | awk '{ print substr($1, 1, 24) }'
)"

seed_prior_installations() {
  mkdir -p "$default_data" "$legacy_data" "$custom_install" "$custom_bin" \
    "$default_cache" "$xdg_config" "$legacy_config" "$application_support" \
    "$legacy_plugin" "$plugin_sibling" "$runtime_directory" \
    "$runtime_socket_directory" "$home/.local/bin"
  printf 'old launcher\n' >"$custom_bin/kast"
  printf 'old launcher\n' >"$home/.local/bin/kast"
  printf 'old control launcher\n' >"$home/.local/bin/_kastctl"
  printf 'preserve\n' >"$custom_bin/keep"
  printf 'preserve\n' >"$plugin_sibling/marker"
  printf 'installed\n' >"$launchctl_state"
  printf 'installed\n' >"$brew_state"
}

installer_environment=(
  "HOME=$home"
  "XDG_DATA_HOME=$home/xdg-data"
  "XDG_CONFIG_HOME=$home/xdg-config"
  "KAST_INSTALL_ROOT=$custom_install"
  "KAST_BIN_DIR=$custom_bin"
  "KAST_RUNTIME_DIRECTORY=$runtime_directory"
  "KAST_TEST_LAUNCHCTL_STATE=$launchctl_state"
  "KAST_TEST_LAUNCHCTL_LOG=$launchctl_log"
  "KAST_TEST_BREW_STATE=$brew_state"
  "KAST_TEST_BREW_LOG=$brew_log"
  "KAST_TEST_PROCESS_STATE=$process_state"
  "KAST_TEST_PROCESS_LOG=$process_log"
  "KAST_TEST_CURL_LOG=$curl_log"
  "KAST_TEST_ASSET_DIR=$assets"
  "KAST_TEST_JAVA_HOME=$java_home"
  "KAST_INSTALL_PROCESS_TABLE_COMMAND=$stub_bin/ps"
  "KAST_INSTALL_PROCESS_KILL_COMMAND=$stub_bin/kill"
  "TMPDIR=$fixture_root/tmp"
  "JAVA_HOME="
  "PATH=$stub_bin:/usr/bin:/bin"
)

mkdir -p "$fixture_root/tmp"
seed_prior_installations
printf 'running\n' >"$process_state"
env "${installer_environment[@]}" bash "$repository_root/install.sh" uninstall \
  >"$fixture_root/uninstall.out" 2>&1

for removed in "$default_data" "$legacy_data" "$custom_install" "$custom_bin/kast" \
  "$default_cache" "$xdg_config" "$legacy_config" "$application_support" \
  "$legacy_plugin" "$runtime_directory" "$runtime_socket_directory" \
  "$home/.local/bin/kast" \
  "$home/.local/bin/_kastctl"; do
  assert_absent "$removed"
done
assert_present "$custom_bin/keep"
assert_present "$plugin_sibling/marker"
grep -Fxq 'io.github.amichne.kast.indexer.fixture' "$launchctl_log" ||
  fail "launchd service was not removed"
grep -Fxq 'uninstall --force kast' "$brew_log" || fail "Homebrew formula was not removed"
grep -Fxq -- '-TERM 777' "$process_log" || fail "orphaned indexer was not stopped"
grep -Fq '888' "$process_log" && fail "unrelated process was stopped"

env "${installer_environment[@]}" bash "$repository_root/install.sh" uninstall \
  >"$fixture_root/uninstall-again.out" 2>&1 || fail "second uninstall was not idempotent"

unsafe_marker="$home/unsafe-marker"
printf 'preserve\n' >"$unsafe_marker"
if env "${installer_environment[@]}" bash "$repository_root/install.sh" uninstall \
  --install-root "$home" >"$fixture_root/unsafe.out" 2>&1; then
  fail "uninstall accepted HOME as a recursive cleanup root"
fi
assert_present "$unsafe_marker"

control_name="kast-control-v9.8.7-macos-aarch64.tar.gz"
runtime_name="kast-semantic-runtime-9.8.7-macos-aarch64.zip"
runtime_fixture="$fixture_root/runtime-fixture"
mkdir -p "$runtime_fixture/runtime-libs" "$runtime_fixture/private-plugins/kast-indexer/lib"
printf '#!/bin/sh\nexit 0\n' >"$runtime_fixture/kast-indexer"
chmod +x "$runtime_fixture/kast-indexer"
printf 'launcher\n' >"$runtime_fixture/runtime-libs/launcher.jar"
printf 'private extension\n' >"$runtime_fixture/private-plugins/kast-indexer/lib/kast-indexer.jar"
(cd "$runtime_fixture" && zip -qr "$assets/$runtime_name" .)
runtime_sha="$(shasum -a 256 "$assets/$runtime_name" | awk '{ print $1 }')"
runtime_bytes="$(wc -c <"$assets/$runtime_name" | tr -d ' ')"
printf '%s  %s\n' "$runtime_sha" "$runtime_name" >"$assets/$runtime_name.sha256"

control_root="$fixture_root/control"
mkdir -p "$control_root/bin" "$control_root/share/kast"
printf '%s\n' '#!/usr/bin/env bash' \
  'case "${1:-}" in' \
  '  --version) printf "kast 9.8.7 (IntelliJ sidecar)\n" ;;' \
  '  --schema) printf "{}\n" ;;' \
  '  *) exit 64 ;;' \
  'esac' >"$control_root/bin/kast"
chmod +x "$control_root/bin/kast"
printf '{}\n' >"$control_root/share/kast/operation-registry.json"
printf '{}\n' >"$control_root/share/kast/wire-schema.json"
runtime_url="file://$fixture_root/assets/v9.8.7/$runtime_name"
printf '{"archive":{"fileName":"%s","url":"%s","sha256":"sha256:%s","bytes":%s}}\n' \
  "$runtime_name" "$runtime_url" "$runtime_sha" "$runtime_bytes" \
  >"$control_root/share/kast/semantic-runtime.json"
tar -czf "$assets/$control_name" -C "$control_root" .
control_sha="$(shasum -a 256 "$assets/$control_name" | awk '{ print $1 }')"
printf '%s  %s\n' "$control_sha" "$control_name" >"$assets/$control_name.sha256"

seed_prior_installations
printf 'old\n' >"$custom_install/old-marker"
incomplete_assets="$fixture_root/incomplete-assets"
mkdir -p "$incomplete_assets"
cp "$assets/$control_name" "$assets/$control_name.sha256" \
  "$assets/$runtime_name.sha256" "$incomplete_assets"
if env "${installer_environment[@]}" KAST_TEST_ASSET_DIR="$incomplete_assets" \
  bash "$repository_root/install.sh" install --purge-existing --version 9.8.7 \
  --repository example/kast >"$fixture_root/unavailable.out" 2>&1; then
  fail "installation succeeded without the private sidecar payload"
fi
assert_present "$custom_install/old-marker"
assert_present "$custom_bin/kast"
assert_present "$runtime_socket_directory"
grep -Fq 'purging prior Kast installations' "$fixture_root/unavailable.out" &&
  fail "purge removed the prior installation before artifact verification"

install_output="$fixture_root/install.out"
env "${installer_environment[@]}" bash "$repository_root/install.sh" install \
  --purge-existing --version 9.8.7 --release-base-url "file://$fixture_root/assets" \
  >"$install_output" 2>&1 || {
  cat "$install_output" >&2
  fail "plugin-free installation failed"
}

version_root="$custom_install/versions/9.8.7"
[[ -x "$version_root/bin/kast" ]] || fail "verified control release was not installed"
[[ -x "$version_root/bin/kast-complete" ]] || fail "complete launcher was not installed"
grep -Fq "export JAVA='$java_home/bin/java'" "$version_root/bin/kast-complete" ||
  fail "complete launcher did not retain the installation-proven Java executable"
grep -Fq "export JAVA_HOME='$java_home'" "$version_root/bin/kast-complete" ||
  fail "complete launcher did not retain the installation-proven Java home"
[[ -f "$version_root/share/kast/runtime/$runtime_name" ]] ||
  fail "private sidecar archive was not installed"
[[ -L "$custom_install/current" && -L "$custom_bin/kast" ]] ||
  fail "managed command links are absent"
[[ ! -e "$custom_install/old-marker" ]] || fail "prior install survived purge"
assert_absent "$runtime_socket_directory"
assert_absent "$legacy_plugin"
assert_present "$plugin_sibling/marker"
if find "$version_root" \( -name 'idea-home' -o -name 'product-info.json' \
  -o -name 'kast-ide-plugin*' \) -print -quit | grep -q .; then
  fail "plugin-free product retained public plugin or IDEA distribution content"
fi
[[ "$("$custom_bin/kast" --version)" == "kast 9.8.7 (IntelliJ sidecar)" ]] ||
  fail "public command did not select the sidecar control"
grep -Fq 'installed Kast 9.8.7' "$install_output" || fail "installation did not complete"
grep -Fxq "file://$fixture_root/assets/v9.8.7/$control_name" "$curl_log" ||
  fail "control asset URL was not requested"
grep -Fxq "$runtime_url" "$curl_log" || fail "sidecar asset URL was not requested"

printf 'installer contract: all checks passed\n'
