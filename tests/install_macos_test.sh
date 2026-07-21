#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-install-test.XXXXXX")"
cleanup() {
  status=$?
  trap - EXIT
  if [[ $status -ne 0 && -f "$scratch/stderr" ]]; then
    sed -n '1,160p' "$scratch/stderr" >&2
  fi
  find "$scratch" -depth -delete
  exit "$status"
}
trap cleanup EXIT

mkdir -p "$scratch/bin" "$scratch/home"
printf '%s\n' '#!/bin/sh' 'if [ "$1" = "-s" ]; then printf "%s\\n" "${KAST_TEST_OS:-Darwin}"; else printf "%s\\n" "${KAST_TEST_ARCH:-arm64}"; fi' > "$scratch/bin/uname"
printf '%s\n' '#!/bin/sh' 'output=""' 'url=""' 'while [ "$#" -gt 0 ]; do case "$1" in --output) output="$2"; shift 2 ;; *) url="$1"; shift ;; esac; done' 'printf "%s\\n" "$url" >> "$KAST_TEST_CURL_LOG"' ': > "$output"' > "$scratch/bin/curl"
printf '%s\n' '#!/bin/sh' 'destination=""' 'while [ "$#" -gt 0 ]; do case "$1" in -d) destination="$2"; shift 2 ;; *) shift ;; esac; done' 'mkdir -p "$destination"' 'printf "%s\n" "#!/bin/sh" "printf \"%s\\n\" \"\$*\" > \"\$KAST_TEST_SETUP_ARGS\"" > "$destination/kast"' 'chmod 755 "$destination/kast"' > "$scratch/bin/unzip"
printf '%s\n' '#!/bin/sh' 'destination=""' 'while [ "$#" -gt 0 ]; do case "$1" in -C) destination="$2"; shift 2 ;; *) shift ;; esac; done' 'mkdir -p "$destination/bundle/bin"' 'printf "%s\n" "#!/bin/sh" "printf \"%s\\n\" \"\$*\" > \"\$KAST_TEST_SETUP_ARGS\"" > "$destination/bundle/bin/kast"' 'chmod 755 "$destination/bundle/bin/kast"' > "$scratch/bin/tar"
chmod 755 "$scratch/bin/uname" "$scratch/bin/curl" "$scratch/bin/unzip" "$scratch/bin/tar"

export PATH="$scratch/bin:$PATH"
export HOME="$scratch/home"
export KAST_RELEASES_URL="https://releases.test"
export KAST_TEST_CURL_LOG="$scratch/curl.log"
export KAST_TEST_SETUP_ARGS="$scratch/setup.args"
export CLICOLOR_FORCE=1

bash "$repo_root/install.sh" --version v1.2.3 >"$scratch/stdout" 2>"$scratch/stderr"

grep -Fqx 'https://releases.test/download/v1.2.3/kast-v1.2.3-macos-arm64.zip' "$scratch/curl.log"
grep -Fqx 'https://releases.test/download/v1.2.3/kast-idea-v1.2.3.zip' "$scratch/curl.log"
grep -Eq '^setup --idea-plugin .*/kast-idea-v1\.2\.3\.zip$' "$scratch/setup.args"
grep -Fq $'\033[1;36m  ██╗  ██╗ █████╗ ███████╗████████╗\033[0m' "$scratch/stderr"
if grep -Fq 'kast-macos-arm64-v1.2.3.tar.gz' "$scratch/curl.log"; then
  printf '%s\n' 'macOS installer selected the headless bundle' >&2
  exit 1
fi

: > "$scratch/curl.log"
KAST_TEST_OS=Linux KAST_TEST_ARCH=x86_64 bash "$repo_root/install.sh" --version v1.2.3 >"$scratch/stdout" 2>"$scratch/stderr"
grep -Fqx 'https://releases.test/download/v1.2.3/kast-linux-x64-v1.2.3.tar.gz' "$scratch/curl.log"
