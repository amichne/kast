#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  printf 'Usage: %s --target REPO_ROOT [--force]\n' "${0##*/}" >&2
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

target_root=""
force=false
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --target)
      [[ "$#" -ge 2 ]] || die "--target requires a path"
      target_root="$2"
      shift 2
      ;;
    --force)
      force=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "$target_root" ]] || { usage; die "--target is required"; }
plugin_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
target_root="$(cd -- "$target_root" >/dev/null 2>&1 && pwd)"

install_file() {
  local source="$1"
  local target="$2"
  if [[ -e "$target" && "$force" != true ]]; then
    die "refusing to overwrite ${target}; pass --force"
  fi
  mkdir -p -- "$(dirname -- "$target")"
  cp -- "$source" "$target"
}

install_file "${plugin_root}/lsp.json" "${target_root}/.github/lsp.json"

printf '{"ok":true,"installedAt":"%s"}\n' "$target_root"
