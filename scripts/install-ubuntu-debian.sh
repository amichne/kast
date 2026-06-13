#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_script_dir() {
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
}

main() {
  local script_dir
  script_dir="$(resolve_script_dir)"
  local root_installer="${script_dir}/../kast.sh"
  [[ -x "$root_installer" ]] || die "Missing executable root installer: ${root_installer}"

  if [[ $# -eq 0 ]]; then
    exec "$root_installer" install
  fi
  case "$1" in
    --help|-h|help)
      exec "$root_installer" install --help
      ;;
  esac
  exec "$root_installer" "$@"
}

main "$@"
