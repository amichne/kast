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
runtime_archive="${KAST_LOCAL_RUNTIME_ARCHIVE:-}"
java_executable="${KAST_LOCAL_JAVA_EXECUTABLE:-}"
java_home="${KAST_LOCAL_JAVA_HOME:-}"

[[ -n "${install_prefix}" ]] || fail "KAST_LOCAL_PREFIX is required"
[[ -n "${control_product}" ]] || fail "KAST_LOCAL_CONTROL_PRODUCT is required"
[[ -n "${runtime_archive}" ]] || fail "KAST_LOCAL_RUNTIME_ARCHIVE is required"
[[ -n "${java_executable}" ]] || fail "KAST_LOCAL_JAVA_EXECUTABLE is required"
[[ -n "${java_home}" ]] || fail "KAST_LOCAL_JAVA_HOME is required"

case "${install_prefix}" in
  /*) ;;
  *) fail "installation prefix must be absolute: ${install_prefix}" ;;
esac
[[ "${install_prefix}" != "/" ]] || fail "installation prefix cannot be the filesystem root"
[[ -d "${control_product}" && ! -L "${control_product}" ]] ||
  fail "control product is not a directory: ${control_product}"
[[ -x "${control_product}/bin/kast" ]] ||
  fail "control product has no executable bin/kast"
[[ -f "${control_product}/share/kast/semantic-runtime.json" ]] ||
  fail "control product has no semantic runtime manifest"
[[ -f "${runtime_archive}" && ! -L "${runtime_archive}" ]] ||
  fail "semantic runtime archive is not a regular file: ${runtime_archive}"
case "${java_executable}" in
  /*) ;;
  *) fail "Java executable must be absolute: ${java_executable}" ;;
esac
case "${java_home}" in
  /*) ;;
  *) fail "Java home must be absolute: ${java_home}" ;;
esac
[[ -x "${java_executable}" ]] || fail "Java executable is unavailable: ${java_executable}"
[[ -d "${java_home}" ]] || fail "Java home is unavailable: ${java_home}"

# Delegate all activation and retirement authority to the release installer.
# A local numeric version is explicit; the complete payload digest distinguishes rebuilds.
version="$(python3 - "$control_product/share/kast/semantic-runtime.json" <<'VERSION'
import json, re, sys
with open(sys.argv[1]) as stream:
    version = json.load(stream)["productVersion"]
if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is None:
    sys.exit("install-local: supply -Pversion=<major>.<minor>.<patch> for a versioned local installation")
print(version)
VERSION
)"
runtime_name="kast-semantic-runtime-${version}-macos-aarch64.zip"
[[ "${runtime_archive##*/}" == "$runtime_name" ]] || fail "runtime archive and control version differ"
installer="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd -P)/install.sh"
[[ -f "$installer" && ! -L "$installer" ]] || fail "versioned installer is unavailable"
assets="$(mktemp -d "${TMPDIR:-/tmp}/kast-local-assets.XXXXXX")"
cleanup() { rm -rf -- "$assets"; }
trap cleanup EXIT
control_name="kast-control-v${version}-macos-aarch64.tar.gz"
tar -czf "$assets/$control_name" -C "$control_product" .
cp "$runtime_archive" "$assets/$runtime_name"
for name in "$control_name" "$runtime_name"; do
  (cd "$assets" && shasum -a 256 "$name" > "$name.sha256")
done
bash "$installer" --version "$version" --assets-directory "$assets" \
  --install-root "$install_prefix/share/kast" --bin-dir "$install_prefix/bin" \
  --enable-launchd 0 --enable-app-server 0
