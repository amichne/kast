#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-portable-install-test.XXXXXX")"

cleanup() {
  find "$scratch" -depth -delete
}
trap cleanup EXIT

make_bundle() {
  local version="$1"
  local bundle_root="$scratch/bundles/kast-${version}"
  mkdir -p "$bundle_root/bin" "$bundle_root/libexec/kast-indexer"
  printf '#!/bin/sh\nprintf "kast %s\\n"\n' "$version" >"$bundle_root/bin/kast"
  printf '#!/bin/sh\nexit 0\n' >"$bundle_root/libexec/kast-indexer/kast-indexer"
  chmod +x "$bundle_root/bin/kast" "$bundle_root/libexec/kast-indexer/kast-indexer"
}

assert_install() {
  local fixture_root="$1"
  local version="$2"
  local kast_home="$fixture_root/.local/share/kast"
  local active="$kast_home/current"
  local launcher="$fixture_root/.local/bin/kast"

  [[ "$(readlink "$active")" == "releases/$version" ]]
  [[ "$(readlink "$launcher")" == "$kast_home/current/bin/kast" ]]
  [[ -x "$active/bin/kast" ]]
  [[ -x "$active/libexec/kast-indexer/kast-indexer" ]]
  [[ ! -e "$active/libexec/kastctl" ]]
  [[ "$("$launcher")" == "kast $version" ]]
}

make_release_fixture() {
  local version="$1"
  local release_root="$scratch/releases"
  local archive="kast-portable-${version}.tar.gz"
  tar -czf "$release_root/$archive" -C "$scratch/bundles" "kast-${version}"
  local digest
  digest="$(shasum -a 256 "$release_root/$archive" | awk '{print $1}')"
  printf '%s  %s\n' "$digest" "$archive" >"$release_root/${archive}.sha256"
}

mkdir -p "$scratch/bundles" "$scratch/releases" "$scratch/bin"
make_bundle v9.8.7
make_bundle v9.8.8
make_bundle v9.8.9
make_release_fixture v9.8.8
make_release_fixture v9.8.9

cat >"$scratch/bin/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
output=""
url=""
while (($# > 0)); do
  case "$1" in
    --output)
      output="$2"
      shift 2
      ;;
    -*)
      shift
      ;;
    *)
      url="$1"
      shift
      ;;
  esac
done
[[ -n "$output" && -n "$url" ]]
cp "$KAST_TEST_RELEASE_DIR/${url##*/}" "$output"
CURL
chmod +x "$scratch/bin/curl"

local_home="$scratch/local-home"
HOME="$local_home" \
  KAST_HOME="$local_home/.local/share/kast" \
  KAST_ASCII=1 \
  NO_COLOR=1 \
  bash "$repository_root/install.sh" \
    --source "$scratch/bundles/kast-v9.8.7" \
    --version v9.8.7
assert_install "$local_home" v9.8.7

release_home="$scratch/release-home"
HOME="$release_home" \
  KAST_HOME="$release_home/.local/share/kast" \
  KAST_RELEASES_URL="https://releases.invalid" \
  KAST_TEST_RELEASE_DIR="$scratch/releases" \
  KAST_ASCII=1 \
  NO_COLOR=1 \
  PATH="$scratch/bin:$PATH" \
  bash "$repository_root/install.sh" --version v9.8.8
assert_install "$release_home" v9.8.8

printf '%064d  %s\n' 0 "kast-portable-v9.8.9.tar.gz" \
  >"$scratch/releases/kast-portable-v9.8.9.tar.gz.sha256"
rejected_home="$scratch/rejected-home"
if HOME="$rejected_home" \
  KAST_HOME="$rejected_home/.local/share/kast" \
  KAST_RELEASES_URL="https://releases.invalid" \
  KAST_TEST_RELEASE_DIR="$scratch/releases" \
  KAST_ASCII=1 \
  NO_COLOR=1 \
  PATH="$scratch/bin:$PATH" \
  bash "$repository_root/install.sh" --version v9.8.9; then
  printf 'expected checksum mismatch rejection\n' >&2
  exit 1
fi
[[ ! -e "$rejected_home/.local/share/kast/releases/v9.8.9" ]]

printf 'ok: portable installer\n'
