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

runtime_name="${runtime_archive##*/}"
[[ "${runtime_name}" =~ ^kast-semantic-runtime-[A-Za-z0-9._-]+-macos-aarch64\.zip$ ]] ||
  fail "semantic runtime archive has an unexpected name: ${runtime_name}"

for command_name in cp chmod mkdir mktemp mv rm sed; do
  require_command "${command_name}"
done

shell_single_quote() {
  printf "'"
  printf '%s' "$1" | sed "s/'/'\"'\"'/g"
  printf "'"
}

kast_root="${install_prefix}/share/kast"
local_product="${kast_root}/local"
legacy_control="${kast_root}/control"
legacy_runtime="${kast_root}/runtime"
public_bin="${install_prefix}/bin"
public_launcher="${public_bin}/kast"

mkdir -p -- "${kast_root}" "${public_bin}"
[[ -d "${kast_root}" && ! -L "${kast_root}" ]] ||
  fail "Kast installation root is not a directory: ${kast_root}"
[[ -d "${public_bin}" && ! -L "${public_bin}" ]] ||
  fail "public binary root is not a directory: ${public_bin}"
if [[ -e "${public_launcher}" && -d "${public_launcher}" && ! -L "${public_launcher}" ]]; then
  fail "public launcher path is a directory: ${public_launcher}"
fi

staged_product="$(mktemp -d "${kast_root}/.local.XXXXXX")"
staged_launcher="$(mktemp "${public_bin}/.kast.XXXXXX")"
cleanup() {
  [[ -z "${staged_product}" ]] || rm -rf -- "${staged_product}"
  [[ -z "${staged_launcher}" ]] || rm -f -- "${staged_launcher}"
}
trap cleanup EXIT

cp -R "${control_product}/." "${staged_product}/"
mkdir -p -- "${staged_product}/share/kast/runtime"
cp "${runtime_archive}" "${staged_product}/share/kast/runtime/${runtime_name}"

{
  printf '%s\n' '#!/bin/sh' 'set -eu' ''
  printf '%s\n' 'script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"'
  printf '%s\n' 'install_prefix="$(CDPATH= cd -- "${script_dir}/.." && pwd -P)"'
  printf '%s\n' 'local_product="${install_prefix}/share/kast/local"'
  printf '%s\n' 'control_executable="${local_product}/bin/kast"'
  printf 'runtime_archive="${local_product}/share/kast/runtime/%s"\n' "${runtime_name}"
  printf '%s\n' '' 'if [ ! -x "${control_executable}" ]; then'
  printf '%s\n' '  echo "kast: local control executable is missing: ${control_executable}" >&2'
  printf '%s\n' '  exit 1' 'fi'
  printf '%s\n' 'if [ ! -f "${runtime_archive}" ]; then'
  printf '%s\n' '  echo "kast: local sidecar payload is missing: ${runtime_archive}" >&2'
  printf '%s\n' '  exit 1' 'fi' ''
  printf 'export JAVA=%s\n' "$(shell_single_quote "${java_executable}")"
  printf 'export JAVA_HOME=%s\n' "$(shell_single_quote "${java_home}")"
  printf '%s\n' 'export KAST_RUNTIME_ARCHIVE="${runtime_archive}"'
  printf '%s\n' 'exec "${control_executable}" "$@"'
} >"${staged_launcher}"
chmod 755 "${staged_launcher}"

[[ -x "${staged_product}/bin/kast" ]] || fail "staged control executable is missing"
[[ -f "${staged_product}/share/kast/runtime/${runtime_name}" ]] ||
  fail "staged semantic runtime archive is missing"

# These exact siblings are owned by the superseded split local-install tasks.
# Removing a symbolic link here removes the link itself; no target is followed.
rm -rf -- "${legacy_control}" "${legacy_runtime}" "${local_product}"
mv -- "${staged_product}" "${local_product}"
staged_product=""
mv -f -- "${staged_launcher}" "${public_launcher}"
staged_launcher=""
trap - EXIT

printf 'install-local: installed %s\n' "${public_launcher}"
