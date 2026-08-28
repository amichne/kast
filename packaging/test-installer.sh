#!/usr/bin/env bash
# shellcheck disable=SC2016 # Single-quoted fragments generate isolated command fixtures.
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-installer-test.XXXXXX")"

cleanup() {
  rm -rf "$fixture_root"
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
test_state="$fixture_root/state"
assets="$fixture_root/assets"
mkdir -p "$home" "$stub_bin" "$test_state" "$assets"

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
  '    if [[ -e "${KAST_TEST_LAUNCHCTL_STATE:?}" ]]; then' \
  '      printf "321\\t0\\tio.github.amichne.kast.indexer.fixture\\n"' \
  '    fi' \
  '    printf "654\\t0\\tcom.example.keep\\n"' \
  '    ;;' \
  '  remove)' \
  '    printf "%s\\n" "${2:?}" >> "${KAST_TEST_LAUNCHCTL_LOG:?}"' \
  '    rm -f "${KAST_TEST_LAUNCHCTL_STATE:?}"' \
  '    ;;' \
  '  *) exit 64 ;;' \
  'esac' > "$stub_bin/launchctl"
chmod +x "$stub_bin/launchctl"

printf '%s\n' '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'case "${1:-} ${2:-} ${3:-}" in' \
  '  "list --formula kast") [[ -e "${KAST_TEST_BREW_STATE:?}" ]] ;;' \
  '  "uninstall --force kast")' \
  '    printf "uninstall --force kast\\n" >> "${KAST_TEST_BREW_LOG:?}"' \
  '    rm -f "${KAST_TEST_BREW_STATE:?}"' \
  '    ;;' \
  '  *) exit 64 ;;' \
  'esac' > "$stub_bin/brew"
chmod +x "$stub_bin/brew"

printf '%s\n' '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'if [[ -e "${KAST_TEST_PROCESS_STATE:?}" ]]; then' \
  '  printf "777 io.github.amichne.kast.indexer.KastIndexerMainKt --fixture\\n"' \
  'fi' \
  'printf "888 com.example.keep --fixture\\n"' > "$stub_bin/ps"
chmod +x "$stub_bin/ps"

printf '%s\n' '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'signal="${1:?}"' \
  'pid="${2:?}"' \
  'case "$signal:$pid" in' \
  '  -TERM:777|-KILL:777)' \
  '    printf "%s %s\\n" "$signal" "$pid" >> "${KAST_TEST_PROCESS_LOG:?}"' \
  '    rm -f "${KAST_TEST_PROCESS_STATE:?}"' \
  '    ;;' \
  '  -0:777) [[ -e "${KAST_TEST_PROCESS_STATE:?}" ]] ;;' \
  '  *) exit 64 ;;' \
  'esac' > "$stub_bin/kill"
chmod +x "$stub_bin/kill"

printf '%s\n' '#!/usr/bin/env bash' \
  'case "${1:-}" in' \
  '  -s) printf "Darwin\\n" ;;' \
  '  -m) printf "arm64\\n" ;;' \
  '  *) exit 64 ;;' \
  'esac' > "$stub_bin/uname"
chmod +x "$stub_bin/uname"

printf '%s\n' '#!/usr/bin/env bash' \
  "printf '%s\\n' 'openjdk version \"21.0.4\" 2024-07-16' >&2" > "$stub_bin/java"
chmod +x "$stub_bin/java"

printf '%s\n' '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'destination=""' \
  'source_url=""' \
  'while [[ $# -gt 0 ]]; do' \
  '  case "$1" in' \
  '    --output) destination="${2:?}"; shift 2 ;;' \
  '    --retry-delay) shift 2 ;;' \
  '    --write-out) shift 2 ;;' \
  '    --fail|--location|--silent|--show-error|--retry) shift ;;' \
  '    http*|file:*) source_url="$1"; shift ;;' \
  '    *) shift ;;' \
  '  esac' \
  'done' \
  '[[ -n "$destination" ]] || exit 64' \
  '[[ -n "$source_url" ]] || exit 64' \
  'printf "%s\n" "$source_url" >> "${KAST_TEST_CURL_LOG:?}"' \
  'cp "${KAST_TEST_ASSET_DIR:?}/${destination##*/}" "$destination"' > "$stub_bin/curl"
chmod +x "$stub_bin/curl"

default_data="$home/xdg-data/kast"
legacy_data="$home/.local/share/kast"
custom_install="$home/custom/install"
custom_bin="$home/custom/bin"
default_cache="$home/.cache/kast"
xdg_config="$home/xdg-config/kast"
legacy_config="$home/.config/kast"
application_support="$home/Library/Application Support/Kast"
idea_plugin_directory="$home/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins"
idea_plugin="$idea_plugin_directory/kast-indexer"
idea_sibling="$home/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/keep"
runtime_directory="$fixture_root/tmp/kast-runtime"

seed_prior_installations() {
  mkdir -p \
    "$default_data" \
    "$legacy_data" \
    "$custom_install" \
    "$custom_bin" \
    "$default_cache" \
    "$xdg_config" \
    "$legacy_config" \
    "$application_support" \
    "$idea_plugin" \
    "$idea_sibling" \
    "$runtime_directory" \
    "$home/.local/bin"
  printf 'old launcher\n' > "$custom_bin/kast"
  printf 'old launcher\n' > "$home/.local/bin/kast"
  printf 'old control launcher\n' > "$home/.local/bin/_kastctl"
  printf 'preserve\n' > "$custom_bin/keep"
  printf 'preserve\n' > "$idea_sibling/marker"
  printf 'installed\n' > "$launchctl_state"
  printf 'installed\n' > "$brew_state"
}

installer_environment=(
  "HOME=$home"
  "XDG_DATA_HOME=$home/xdg-data"
  "XDG_CONFIG_HOME=$home/xdg-config"
  "KAST_INSTALL_ROOT=$custom_install"
  "KAST_BIN_DIR=$custom_bin"
  "KAST_IDE_PLUGIN_DIRECTORY=$idea_plugin_directory"
  "KAST_TEST_LAUNCHCTL_STATE=$launchctl_state"
  "KAST_TEST_LAUNCHCTL_LOG=$launchctl_log"
  "KAST_TEST_BREW_STATE=$brew_state"
  "KAST_TEST_BREW_LOG=$brew_log"
  "KAST_TEST_PROCESS_STATE=$process_state"
  "KAST_TEST_PROCESS_LOG=$process_log"
  "KAST_TEST_CURL_LOG=$curl_log"
  "KAST_TEST_ASSET_DIR=$assets"
  "KAST_INSTALL_PROCESS_TABLE_COMMAND=$stub_bin/ps"
  "KAST_INSTALL_PROCESS_KILL_COMMAND=$stub_bin/kill"
  "TMPDIR=$fixture_root/tmp"
  "JAVA_HOME="
  "PATH=$stub_bin:/usr/bin:/bin"
)

mkdir -p "$fixture_root/tmp"
seed_prior_installations
printf 'running\n' > "$process_state"

uninstall_output="$fixture_root/uninstall.out"
env "${installer_environment[@]}" bash "$repository_root/install.sh" uninstall \
  > "$uninstall_output" 2>&1

for removed in \
  "$default_data" \
  "$legacy_data" \
  "$custom_install" \
  "$custom_bin/kast" \
  "$default_cache" \
  "$xdg_config" \
  "$legacy_config" \
  "$application_support" \
  "$idea_plugin" \
  "$runtime_directory" \
  "$home/.local/bin/kast" \
  "$home/.local/bin/_kastctl"; do
  assert_absent "$removed"
done
assert_present "$custom_bin/keep"
assert_present "$idea_sibling/marker"
grep -Fxq 'io.github.amichne.kast.indexer.fixture' "$launchctl_log" ||
  fail "launchd service was not removed"
grep -Fxq 'uninstall --force kast' "$brew_log" ||
  fail "Homebrew formula was not removed"
grep -Fxq -- '-TERM 777' "$process_log" || fail "orphaned indexer was not stopped"
if grep -Fq '888' "$process_log"; then
  fail "unrelated process was stopped"
fi

if ! env "${installer_environment[@]}" bash "$repository_root/install.sh" uninstall \
  > "$fixture_root/uninstall-again.out" 2>&1; then
  cat "$fixture_root/uninstall-again.out" >&2
  fail "second uninstall was not idempotent"
fi

unsafe_marker="$home/unsafe-marker"
printf 'preserve\n' > "$unsafe_marker"
if env "${installer_environment[@]}" bash "$repository_root/install.sh" uninstall \
  --install-root "$home" > "$fixture_root/unsafe.out" 2>&1; then
  fail "uninstall accepted HOME as a recursive cleanup root"
fi
assert_present "$unsafe_marker"

control_root="$fixture_root/control"
control_name="kast-control-v9.8.7-macos-aarch64.tar.gz"
plugin_name="kast-ide-plugin-9.8.7.zip"
plugin_root="$fixture_root/plugin/kast-indexer"
command -v zip >/dev/null 2>&1 || fail "zip is required by the installer contract"
mkdir -p "$plugin_root/lib"
printf 'fixture\n' > "$plugin_root/lib/kast-ide-plugin-9.8.7.jar"
(cd "$fixture_root/plugin" && zip -qr "$assets/$plugin_name" kast-indexer)
plugin_sha="$(shasum -a 256 "$assets/$plugin_name" | awk '{ print $1 }')"
printf '%s  %s\n' "$plugin_sha" "$plugin_name" > "$assets/$plugin_name.sha256"

mkdir -p "$control_root/bin" "$control_root/share/kast"
printf '%s\n' '#!/usr/bin/env bash' \
  'case "${1:-}" in' \
  '  --version) printf "kast 9.8.7 (IDE-hosted)\\n" ;;' \
  '  --schema) printf "{}\\n" ;;' \
  '  *) exit 64 ;;' \
  'esac' > "$control_root/bin/kast"
chmod +x "$control_root/bin/kast"
printf '{}\n' > "$control_root/share/kast/operation-registry.json"
printf '{}\n' > "$control_root/share/kast/wire-schema.json"
tar -czf "$assets/$control_name" -C "$control_root" .
control_sha="$(shasum -a 256 "$assets/$control_name" | awk '{ print $1 }')"
printf '%s  %s\n' "$control_sha" "$control_name" > "$assets/$control_name.sha256"

seed_prior_installations
printf 'old\n' > "$custom_install/old-marker"
incomplete_assets="$fixture_root/incomplete-assets"
mkdir -p "$incomplete_assets"
cp \
  "$assets/$control_name" \
  "$assets/$control_name.sha256" \
  "$assets/$plugin_name.sha256" \
  "$incomplete_assets"
unavailable_output="$fixture_root/unavailable.out"
if env "${installer_environment[@]}" KAST_TEST_ASSET_DIR="$incomplete_assets" \
  bash "$repository_root/install.sh" install \
  --purge-existing --version 9.8.7 --repository example/kast \
  > "$unavailable_output" 2>&1; then
  fail "installation succeeded without the hosted IDE plugin"
fi
assert_present "$custom_install/old-marker"
assert_present "$custom_bin/kast"
if grep -Fq 'purging prior Kast installations' "$unavailable_output"; then
  fail "purge-first removed the prior installation before artifact verification"
fi

install_output="$fixture_root/install.out"
if ! env "${installer_environment[@]}" bash "$repository_root/install.sh" install \
  --purge-existing --version 9.8.7 \
  --release-base-url "file://$assets" \
  > "$install_output" 2>&1; then
  cat "$install_output" >&2
  fail "purge-first installation failed"
fi

if [[ ! -x "$custom_install/versions/9.8.7/bin/kast" ]]; then
  cat "$install_output" >&2
  fail "verified control release was not installed"
fi
[[ -L "$custom_install/current" ]] || fail "current release link is absent"
[[ -L "$custom_bin/kast" ]] || fail "public command link is absent"
[[ ! -e "$custom_install/old-marker" ]] || fail "prior install survived purge-first"
[[ -L "$idea_plugin_directory/kast-indexer" ]] || fail "hosted IDE plugin link is absent"
installed_plugin="$custom_install/versions/9.8.7/share/kast/ide-plugin/kast-indexer"
[[ -f "$installed_plugin/lib/kast-ide-plugin-9.8.7.jar" ]] ||
  fail "matched hosted IDE plugin was not installed"
if find "$custom_install/versions/9.8.7" \( -name 'semantic-runtime*' -o -name 'idea-home' \) \
  -print -quit | grep -q .; then
  fail "default installation retained isolated-runtime payload"
fi
[[ "$("$custom_bin/kast" --version)" == "kast 9.8.7 (IDE-hosted)" ]] ||
  fail "public command did not select the hosted control"
grep -Fq 'purging prior Kast installations' "$install_output" ||
  fail "purge-first did not report the shared cleanup operation"
grep -Fq 'installed Kast 9.8.7' "$install_output" || fail "installation did not complete"
grep -Fxq "file://$assets/v9.8.7/$control_name" "$curl_log" ||
  fail "release-base override did not own the selected control artifact"

printf 'installer contract: all checks passed\n'
