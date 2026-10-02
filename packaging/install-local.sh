#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

fail() {
  printf 'install-local: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

install_prefix="${KAST_LOCAL_PREFIX:-}"
control_product="${KAST_LOCAL_CONTROL_PRODUCT:-}"
plugin_archive="${KAST_LOCAL_HOSTED_PLUGIN_ARCHIVE:-}"

[[ -n "${HOME:-}" ]] || fail "HOME is required"
[[ -z "$install_prefix" || "$install_prefix" == "$HOME/.local" ]] || fail "KAST_LOCAL_PREFIX must be the sole user prefix at HOME/.local"
install_prefix="$HOME/.local"
[[ "${KAST_LOCAL_PROFILE:-persistent}" == persistent ]] || fail "session installations are retired; use persistent"
[[ -n "${control_product}" ]] || fail "KAST_LOCAL_CONTROL_PRODUCT is required"
component="${KAST_LOCAL_COMPONENT:-pair}"
case "$component" in pair|control) ;; *) fail "KAST_LOCAL_COMPONENT must be pair or control" ;; esac
if [[ "$component" == pair ]]; then
  [[ -f "$plugin_archive" && ! -L "$plugin_archive" ]] || fail "KAST_LOCAL_HOSTED_PLUGIN_ARCHIVE must be a regular file"
fi

case "${install_prefix}" in
  /*) ;;
  *) fail "installation prefix must be absolute: ${install_prefix}" ;;
esac
[[ "${install_prefix}" != "/" ]] || fail "installation prefix cannot be the filesystem root"
[[ -d "${control_product}" && ! -L "${control_product}" ]] ||
  fail "control product is not a directory: ${control_product}"
[[ -x "${control_product}/bin/kast" ]] ||
  fail "control product has no executable bin/kast"
[[ -x "${control_product}/share/kast/libexec/kast-service" ]] ||
  fail "control product has no private installer"
[[ -f "${control_product}/share/kast/ide-host.json" ]] ||
  fail "control product has no hosted plugin manifest"

# Delegate all activation and retirement authority to the release installer.
# A local numeric version is explicit; the complete payload digest distinguishes rebuilds.
version="$(python3 - "$control_product/share/kast/ide-host.json" <<'VERSION'
import json, re, sys
with open(sys.argv[1]) as stream:
    version = json.load(stream)["productVersion"]
if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is None:
    sys.exit("install-local: supply -Pversion=<major>.<minor>.<patch> for a versioned local installation")
print(version)
VERSION
)"
installer="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd -P)/install.sh"
[[ -f "$installer" && ! -L "$installer" ]] || fail "versioned installer is unavailable"
assets="$(mktemp -d "${TMPDIR:-/tmp}/kast-local-assets.XXXXXX")"
cleanup() { rm -rf -- "$assets"; }
trap cleanup EXIT
control_name="kast-control-v${version}-macos-aarch64.tar.gz"
# Name the admitted top-level payloads explicitly; archiving `.` creates a root
# member that the release installer's traversal-safe extractor correctly rejects.
tar -czf "$assets/$control_name" -C "$control_product" bin lib share
plugin_name="${plugin_archive##*/}"
asset_names=("$control_name")
host_version=""
installer_options=()
if [[ "$component" == pair ]]; then
  host_version="$(python3 - "$plugin_name" <<'VERSION'
import re, sys
match = re.fullmatch(r"kast-ide-hosted-v([0-9]+\.[0-9]+\.[0-9]+)-idea-[0-9]+\.zip", sys.argv[1])
if match is None: sys.exit("install-local: hosted artifact name rejected")
print(match.group(1))
VERSION
)"
  cp "$plugin_archive" "$assets/$plugin_name"
  host_record="${KAST_LOCAL_HOST_RELEASE_RECORD:-}"
  [[ -f "$host_record" && ! -L "$host_record" ]] || fail "KAST_LOCAL_HOST_RELEASE_RECORD must be the independently generated host release record"
  record_name="kast-host-release-v$host_version.json"
  cp "$host_record" "$assets/$record_name"
  cp "$control_product/share/kast/host-installation.py" "$assets/host-installation.py"
  asset_names+=("$plugin_name" "$record_name" host-installation.py)
else
  installer_options+=(--control-only)
fi
for name in "${asset_names[@]}"; do
  (cd "$assets" && shasum -a 256 "$name" > "$name.sha256")
done
KAST_VERSION="$version" \
KAST_HOST_VERSION="$host_version" \
KAST_RELEASE_BASE_URL="https://github.com/amichne/kast/releases/download" \
KAST_INSTALL_ASSETS_DIRECTORY="$assets" \
KAST_INSTALL_ROOT="$install_prefix/share/kast" \
KAST_BIN_DIR="$install_prefix/bin" \
KAST_INSTALL_PROFILE="persistent" \
  bash "$installer" ${installer_options[@]+"${installer_options[@]}"}
