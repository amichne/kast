#!/usr/bin/env bash
set -euo pipefail

: "${KAST_TEST_STATE:?}"
: "${KAST_TEST_PLATFORM_HOME:?}"
: "${KAST_TEST_JAVA_HOME:?}"
: "${KAST_TEST_KAST_STUB:?}"
: "${KAST_INSTALL_IDEA_HOME:?}"
: "${XDG_CONFIG_HOME:?}"

idea_home="$(CDPATH='' cd -- "$KAST_INSTALL_IDEA_HOME" && pwd -P)"
platform_home="$(CDPATH='' cd -- "$KAST_TEST_PLATFORM_HOME" && pwd -P)"
java_home="$(CDPATH='' cd -- "$KAST_TEST_JAVA_HOME" && pwd -P)"
home="$(CDPATH='' cd -- "$HOME" && pwd -P)"
config_home="$(CDPATH='' cd -- "$XDG_CONFIG_HOME" && pwd -P)"
[[ -f "$idea_home/Resources/build.txt" ]] || exit 65
[[ -d "$idea_home/plugins/Kotlin" ]] || exit 66
cmp "$platform_home/build.txt" "$idea_home/Resources/build.txt" >/dev/null || exit 67
[[ "$(CDPATH='' cd -- "$idea_home/jbr/Contents/Home" && pwd -P)" == "$java_home" ]] || exit 68

printf '%s\n' "$home" "$config_home" "$idea_home" >"$KAST_TEST_STATE"
mkdir -p "$XDG_CONFIG_HOME/kast"
printf 'isolated\n' >"$XDG_CONFIG_HOME/kast/environment"

version=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) version="${2:?}"; shift 2 ;;
    *) shift ;;
  esac
done
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 69
export KAST_TEST_RELEASE_VERSION="$version"

control_root="$KAST_INSTALL_ROOT/versions/$version"
runtime_name="kast-semantic-runtime-${version}-macos-aarch64.zip"
mkdir -p "$KAST_BIN_DIR" "$control_root/bin" "$control_root/share/kast/runtime"
cp "$KAST_TEST_KAST_STUB" "$KAST_BIN_DIR/kast"
cp "$KAST_TEST_KAST_STUB" "$control_root/bin/kast"
chmod +x "$KAST_BIN_DIR/kast" "$control_root/bin/kast"
printf '{}\n' >"$control_root/share/kast/operation-registry.json"
printf '{}\n' >"$control_root/share/kast/semantic-runtime.json"

runtime_fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-published-stub.XXXXXX")"
trap 'rm -rf -- "$runtime_fixture"' EXIT
mkdir -p "$runtime_fixture/private-plugins/kast-indexer/lib"
printf 'fixture\n' >"$runtime_fixture/private-plugins/kast-indexer/lib/kast-indexer.jar"
(cd "$runtime_fixture" && zip -qr "$control_root/share/kast/runtime/$runtime_name" .)
