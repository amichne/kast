#!/usr/bin/env bash
set -Eeuo pipefail

RELEASES_URL="${KAST_RELEASES_URL:-https://github.com/amichne/kast/releases}"
setup_scratch=""
install_candidate=""

cleanup() {
  if [[ -n "$install_candidate" && -d "$install_candidate" ]]; then
    find "$install_candidate" -depth -delete
  fi
  if [[ -n "$setup_scratch" && -d "$setup_scratch" ]]; then
    find "$setup_scratch" -depth -delete
  fi
}
trap cleanup EXIT

usage() {
  cat >&2 <<'USAGE'
Usage: install.sh [--source <bundle-directory-or-tar.gz>] [--version <vX.Y.Z>] [--force]

Installs the latest portable Kast release. Exact-version downloads are checked
against the SHA-256 sidecar published with the release before they are installed.

Options:
  --source PATH      Install a local portable bundle directory or tar.gz archive.
  --version VERSION  Install an exact release instead of the latest release.
  --force            Replace an existing installation of the selected version.
  -h, --help         Show this help.

Environment:
  KAST_HOME          Install root. Defaults to ~/.local/share/kast.
  KAST_RELEASES_URL  Release base URL. Defaults to the Kast GitHub releases.
USAGE
}

supports_color() {
  [[ -z "${NO_COLOR:-}" ]] || return 1
  [[ "${CLICOLOR_FORCE:-}" != "1" ]] || return 0
  [[ -t 2 && "${TERM:-}" != "dumb" ]]
}

colorize() {
  local code="$1"
  shift
  if supports_color; then
    printf '\033[%sm%s\033[0m' "$code" "$*"
  else
    printf '%s' "$*"
  fi
}

ui_line() {
  local glyph="$1" color="$2"
  shift 2
  printf '  %s %s\n' "$(colorize "$color" "$glyph")" "$*" >&2
}
ui_step() { ui_line '*' 36 "$*"; }
ui_success() { ui_line '+' 32 "$*"; }
ui_warning() { ui_line '!' 33 "$*"; }

die() {
  ui_line 'x' 31 "$*"
  exit 1
}

require() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

latest_version() {
  local effective version
  effective="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "${RELEASES_URL}/latest")"
  version="${effective##*/}"
  valid_version "$version" || die "latest release has an invalid version: $version"
  printf '%s\n' "$version"
}

valid_version() {
  [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

download() {
  local label="$1" url="$2" destination="$3"
  ui_step "Downloading ${label}"
  curl -fsSL --output "$destination" "$url"
}

sha256() {
  local archive="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$archive" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$archive" | awk '{print $1}'
  else
    die 'missing required command: shasum or sha256sum'
  fi
}

verify_checksum() {
  local archive="$1" checksum_file="$2" archive_name="$3"
  local expected_digest expected_name extra actual_digest line_count
  line_count="$(wc -l <"$checksum_file" | tr -d '[:space:]')"
  [[ "$line_count" == 1 ]] || die "invalid checksum sidecar for $archive_name"
  read -r expected_digest expected_name extra <"$checksum_file"
  [[ -z "${extra:-}" ]] || die "invalid checksum sidecar for $archive_name"
  expected_name="${expected_name#\*}"
  [[ "$expected_digest" =~ ^[0-9a-fA-F]{64}$ && "$expected_name" == "$archive_name" ]] \
    || die "invalid checksum sidecar for $archive_name"
  actual_digest="$(sha256 "$archive")"
  expected_digest="$(printf '%s' "$expected_digest" | tr '[:upper:]' '[:lower:]')"
  [[ "$actual_digest" == "$expected_digest" ]] || die "checksum mismatch for $archive_name"
  ui_success 'Checksum verified'
}

infer_version() {
  local source="$1" source_name
  source_name="${source%/}"
  source_name="${source_name##*/}"
  case "$source_name" in
    kast-v[0-9]*.[0-9]*.[0-9]*) printf '%s\n' "${source_name#kast-}" ;;
    kast-portable-v[0-9]*.[0-9]*.[0-9]*.tar.gz)
      source_name="${source_name#kast-portable-}"
      printf '%s\n' "${source_name%.tar.gz}"
      ;;
    *) return 1 ;;
  esac
}

extract_bundle() {
  local archive="$1" extraction_root="$2" discovered_root="" root_count=0 entry
  require tar
  mkdir -p "$extraction_root"
  ui_step 'Extracting Kast bundle'
  tar -xzf "$archive" -C "$extraction_root"
  while IFS= read -r entry; do
    discovered_root="$entry"
    root_count=$((root_count + 1))
  done < <(find "$extraction_root" -mindepth 1 -maxdepth 1 -type d -print)
  [[ "$root_count" == 1 ]] || die "bundle archive must contain exactly one root directory: $archive"
  [[ -z "$(find "$extraction_root" -mindepth 1 -maxdepth 1 ! -type d -print -quit)" ]] \
    || die "bundle archive contains content outside its root directory: $archive"
  printf '%s\n' "$discovered_root"
}

validate_bundle() {
  local bundle_root="$1"
  [[ -x "$bundle_root/bin/kast" ]] || die "bundle agent CLI is missing: $bundle_root/bin/kast"
  [[ -x "$bundle_root/libexec/kast-indexer/kast-indexer" ]] \
    || die "bundle indexer is missing: $bundle_root/libexec/kast-indexer/kast-indexer"
}

atomic_link() {
  local link_target="$1" link_path="$2" temporary_link
  temporary_link="${link_path}.kast-install.$$"
  [[ ! -e "$temporary_link" && ! -L "$temporary_link" ]] \
    || die "temporary activation path already exists: $temporary_link"
  ln -s "$link_target" "$temporary_link"
  mv -f "$temporary_link" "$link_path"
}

install_bundle() {
  local bundle_root="$1" version="$2" force="$3"
  local install_root release_root active_path bin_directory launcher
  install_root="${KAST_HOME:-${HOME:?HOME must be set}/.local/share/kast}"
  release_root="$install_root/releases/$version"
  active_path="$install_root/current"
  bin_directory="${HOME:?HOME must be set}/.local/bin"
  launcher="$bin_directory/kast"

  [[ ! -e "$active_path" || -L "$active_path" ]] || die "activation path is not a symlink: $active_path"
  [[ ! -e "$launcher" || -L "$launcher" ]] || die "launcher path is not a symlink: $launcher"
  mkdir -p "$install_root/releases" "$bin_directory"

  if [[ -e "$release_root" || -L "$release_root" ]]; then
    ((force == 1)) || die "Kast $version is already installed; use --force to replace it"
    find "$release_root" -depth -delete
  fi

  install_candidate="$(mktemp -d "$install_root/.install-${version}.XXXXXX")"
  cp -R "$bundle_root/." "$install_candidate"
  validate_bundle "$install_candidate"
  mv "$install_candidate" "$release_root"
  install_candidate=""

  atomic_link "releases/$version" "$active_path"
  atomic_link "$install_root/current/bin/kast" "$launcher"
  ui_success "Kast $version installed"
  printf '    %s -> %s\n' "$launcher" "$install_root/current/bin/kast" >&2
  if [[ ":${PATH:-}:" != *":${bin_directory}:"* ]]; then
    ui_warning "$bin_directory is not on PATH"
    # This is a command for the user's shell.
    # shellcheck disable=SC2016
    printf '    export PATH="$HOME/.local/bin:$PATH"\n' >&2
  fi
}

main() {
  local source="" version="" bundle_root="" bundle_archive="" checksum_file=""
  local archive_name release_url inferred_version="" force=0

  while (($# > 0)); do
    case "$1" in
      --source) [[ $# -ge 2 ]] || die '--source requires a value'; source="$2"; shift 2 ;;
      --version) [[ $# -ge 2 ]] || die '--version requires a value'; version="$2"; shift 2 ;;
      --force) force=1; shift ;;
      -h|--help|help) usage; return 0 ;;
      *) die "unknown argument: $1" ;;
    esac
  done

  setup_scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"
  if [[ -n "$source" && -z "$version" ]]; then
    inferred_version="$(infer_version "$source" || true)"
    version="$inferred_version"
  fi

  if [[ -z "$source" ]]; then
    require curl
    version="${version:-$(latest_version)}"
    valid_version "$version" || die "invalid release version: $version"
    archive_name="kast-portable-${version}.tar.gz"
    release_url="${RELEASES_URL}/download/${version}"
    bundle_archive="$setup_scratch/$archive_name"
    checksum_file="$setup_scratch/${archive_name}.sha256"
    download "Kast $version" "$release_url/$archive_name" "$bundle_archive"
    download 'SHA-256 checksum' "$release_url/${archive_name}.sha256" "$checksum_file"
    verify_checksum "$bundle_archive" "$checksum_file" "$archive_name"
    source="$bundle_archive"
  fi

  valid_version "$version" || die 'a local bundle requires --version vX.Y.Z or a versioned bundle name'
  if [[ -d "$source" ]]; then
    bundle_root="$(cd -- "$source" && pwd -P)"
  else
    [[ -f "$source" ]] || die "bundle source does not exist: $source"
    bundle_root="$(extract_bundle "$source" "$setup_scratch/bundle")"
  fi

  validate_bundle "$bundle_root"
  install_bundle "$bundle_root" "$version" "$force"
}

main "$@"
