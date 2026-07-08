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

target_root="$(cd -- "$target_root" >/dev/null 2>&1 && pwd)"

printf '%s\n' '{
  "ok": false,
  "method": "plugin/install-local",
  "error": {
    "code": "PLUGIN_INSTALL_REMOVED",
    "message": "The repository-local Copilot package installer has been retired. Load this plugin source directly in Copilot CLI, or use the Homebrew-distributed Kast IntelliJ plugin for managed macOS setup.",
    "details": {
      "replacements": [
        "copilot --plugin-dir cli-rs/resources/plugin",
        "brew install amichne/kast/kast",
        "kast developer machine plugin",
        "kast agent verify --workspace-root <repo>"
      ]
    }
  },
  "schemaVersion": 1
}'
exit 1
