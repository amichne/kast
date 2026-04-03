#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '%s\n' "$*" >&2
}

die() {
  log "error: $*"
  exit 1
}

if [[ $# -ne 1 ]]; then
  die "Usage: scripts/validate-instance.sh <name>"
fi

instance_name="$1"
if [[ "$instance_name" =~ [^a-zA-Z0-9._-] ]]; then
  die "Instance name may contain only letters, digits, dot, underscore, and dash"
fi

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
smoke_script="${repo_root}/.github/scripts/smoke-kast-cli.sh"
launcher="${HOME}/.local/bin/kast-${instance_name}"

[[ -x "$smoke_script" ]] || die "Smoke script not found: $smoke_script"
[[ -x "$launcher" ]] || die "Launcher not found or not executable: $launcher"

log "Running smoke checks for instance '${instance_name}' via ${launcher}"
"$smoke_script" "$launcher"
