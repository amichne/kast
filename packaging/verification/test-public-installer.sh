#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-public-installer.XXXXXX")"
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/home"

bash -n "$root/install.sh"

run_until_java_check() {
  local output="$1"
  shift
  if env \
    HOME="$fixture/home" \
    JAVA_HOME="$fixture/missing-java" \
    "$@" \
    bash "$root/install.sh" >"$output" 2>&1; then
    echo "public-installer: missing-Java fixture unexpectedly succeeded" >&2
    exit 1
  fi
  grep -F "kast-install: JAVA_HOME has no executable bin/java" "$output" >/dev/null
}

unicode_output="$fixture/unicode.out"
run_until_java_check "$unicode_output" LANG=en_US.UTF-8 NO_COLOR=1
grep -F '██╗  ██╗ █████╗ ███████╗████████╗' "$unicode_output" >/dev/null
grep -F 'Compiler-grounded Kotlin evidence from your terminal' "$unicode_output" >/dev/null
if LC_ALL=C grep -q $'\033' "$unicode_output"; then
  echo "public-installer: NO_COLOR output contains an escape sequence" >&2
  exit 1
fi

ascii_output="$fixture/ascii.out"
run_until_java_check "$ascii_output" LANG=en_US.UTF-8 NO_COLOR=1 KAST_ASCII=1
grep -F '* KAST INSTALLER' "$ascii_output" >/dev/null
if grep -F '██╗  ██╗' "$ascii_output" >/dev/null; then
  echo "public-installer: ASCII fallback contains the Unicode banner" >&2
  exit 1
fi

color_output="$fixture/color.out"
run_until_java_check "$color_output" \
  LANG=en_US.UTF-8 NO_COLOR= KAST_ASCII=1 CLICOLOR_FORCE=1
if ! LC_ALL=C grep -F -q $'\033[1;36m' "$color_output"; then
  echo "public-installer: forced color did not style the compact banner" >&2
  exit 1
fi

echo "public-installer: banner, ASCII fallback, color, and failure output passed"
