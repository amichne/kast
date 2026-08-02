#!/usr/bin/env bash
set -Eeuo pipefail

source_path="${1:-${BUNDLE_PATH:-}}"
[[ -n "$source_path" ]] || { printf 'usage: verify-setup-bundle.sh <bundle-directory-or-tar.gz>\n' >&2; exit 2; }

scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-bundle-verify.XXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT
export HOME="${scratch}/home"
export KAST_HOME="${scratch}/kast-home"
mkdir -p "$HOME"

run_setup() {
  local label="$1"
  shift
  local output
  local status
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e
  if [[ "$status" -ne 0 ]]; then
    printf '%s failed with exit %s:\n%s\n' "$label" "$status" "$output" >&2
    return "$status"
  fi
  printf '%s\n' "$output"
}

if [[ -d "$source_path" ]]; then
  bundle_root="$(cd -- "$source_path" && pwd -P)"
else
  mkdir -p "${scratch}/bundle"
  tar -xzf "$source_path" -C "${scratch}/bundle"
  bundle_root="$(find "${scratch}/bundle" -mindepth 1 -maxdepth 1 -type d -print -quit)"
fi

[[ -x "${bundle_root}/libexec/kastctl" ]] || { printf 'bundle control CLI is missing\n' >&2; exit 1; }
[[ -x "${bundle_root}/bin/kast" ]] || { printf 'bundle agent CLI is missing\n' >&2; exit 1; }
cmp -s "${bundle_root}/libexec/kastctl" "${bundle_root}/bin/kast" \
  || { printf 'bundle entrypoints are not byte-identical\n' >&2; exit 1; }
first="$(run_setup "initial bundle setup" "${bundle_root}/libexec/kastctl" --output json setup --source "$bundle_root")"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"ACTIVATED"' <<<"$first" \
  || { printf 'initial bundle setup returned an unexpected result:\n%s\n' "$first" >&2; exit 1; }
second="$(run_setup "idempotent bundle setup" "${KAST_HOME}/current/libexec/kastctl" --output json setup --source "$bundle_root")"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"CURRENT"' <<<"$second" \
  || { printf 'idempotent bundle setup returned an unexpected result:\n%s\n' "$second" >&2; exit 1; }
"${KAST_HOME}/current/libexec/kastctl" ready --for machine >/dev/null
printf 'setup bundle verification passed\n'
