#!/bin/sh
set -eu

usage() {
  printf 'Usage: %s --target REPO_ROOT [--force]\n' "${0##*/}" >&2
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

target_root=""
force=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --target)
      [ "$#" -ge 2 ] || die "--target requires a path"
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

if [ -z "$target_root" ]; then
  usage
  die "--target is required"
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" >/dev/null 2>&1 && pwd)"
plugin_root="$(CDPATH= cd -- "$script_dir/.." >/dev/null 2>&1 && pwd)"
cli_root="$(CDPATH= cd -- "$plugin_root/../.." >/dev/null 2>&1 && pwd)"
target_root="$(cd -- "$target_root" >/dev/null 2>&1 && pwd)"

kast_bin="${KAST_BIN:-}"
if [ -z "$kast_bin" ] && command -v kast >/dev/null 2>&1; then
  kast_bin="$(command -v kast)"
fi
if [ -z "$kast_bin" ] && [ -x "$cli_root/target/debug/kast" ]; then
  kast_bin="$cli_root/target/debug/kast"
fi
if [ -z "$kast_bin" ] && [ -x "$cli_root/target/release/kast" ]; then
  kast_bin="$cli_root/target/release/kast"
fi
[ -n "$kast_bin" ] || die "could not find kast; set KAST_BIN or build cli-rs first"

github_dir="$target_root/.github"
if [ "$force" = "true" ]; then
  exec "$kast_bin" agent setup copilot --target-dir "$github_dir" --force
fi
exec "$kast_bin" agent setup copilot --target-dir "$github_dir"
