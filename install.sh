#!/usr/bin/env bash
set -Eeuo pipefail

RELEASES_URL="${KAST_RELEASES_URL:-https://github.com/amichne/kast/releases}"
setup_scratch=""

cleanup() {
  if [[ -n "$setup_scratch" && -d "$setup_scratch" ]]; then
    find "$setup_scratch" -depth -delete
  fi
}

trap cleanup EXIT

usage() {
  cat >&2 <<'USAGE'
Usage: install.sh [--source <bundle-directory-or-tar.gz>] [--version <vX.Y.Z>]

Downloads one platform bundle when --source is omitted, then delegates every
installation write to:

  kast setup --source <bundle>

Environment:
  KAST_HOME          Active install root. Defaults to ~/.local/share/kast.
  KAST_RELEASES_URL  Release base URL. Defaults to the Kast GitHub releases.
USAGE
}

die() {
  printf 'kast setup: %s\n' "$*" >&2
  exit 1
}

require() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

platform() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"
  case "${os}:${arch}" in
    Darwin:x86_64) printf 'macos-x64\n' ;;
    Darwin:arm64|Darwin:aarch64) printf 'macos-arm64\n' ;;
    Linux:x86_64|Linux:amd64) printf 'linux-x64\n' ;;
    Linux:arm64|Linux:aarch64) printf 'linux-arm64\n' ;;
    *) die "unsupported platform: ${os} ${arch}" ;;
  esac
}

latest_version() {
  local effective
  effective="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "${RELEASES_URL}/latest")"
  printf '%s\n' "${effective##*/}"
}

main() {
  local source="" version="" bundle_root="" bundle_archive=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --source) [[ $# -ge 2 ]] || die '--source requires a value'; source="$2"; shift 2 ;;
      --version) [[ $# -ge 2 ]] || die '--version requires a value'; version="$2"; shift 2 ;;
      -h|--help|help) usage; return 0 ;;
      *) die "unknown argument: $1" ;;
    esac
  done

  setup_scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-setup.XXXXXX")"

  if [[ -z "$source" ]]; then
    require curl
    version="${version:-$(latest_version)}"
    bundle_archive="${setup_scratch}/kast-bundle.tar.gz"
    source="${RELEASES_URL}/download/${version}/kast-$(platform)-${version}.tar.gz"
    curl -fsSL --output "$bundle_archive" "$source"
    source="$bundle_archive"
  fi

  if [[ -d "$source" ]]; then
    bundle_root="$(cd -- "$source" && pwd -P)"
  else
    require tar
    [[ -f "$source" ]] || die "bundle source does not exist: $source"
    mkdir -p "${setup_scratch}/bundle"
    tar -xzf "$source" -C "${setup_scratch}/bundle"
    bundle_root="$(find "${setup_scratch}/bundle" -mindepth 1 -maxdepth 1 -type d -print -quit)"
    [[ -n "$bundle_root" ]] || die "bundle archive has no root directory: $source"
  fi

  [[ -x "${bundle_root}/bin/kast" ]] || die "bundle CLI is missing: ${bundle_root}/bin/kast"
  "${bundle_root}/bin/kast" setup --source "$bundle_root"
  printf 'Kast is ready at %s/current/bin/kast\n' "${KAST_HOME:-${HOME}/.local/share/kast}"
}

main "$@"
