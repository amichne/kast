#!/usr/bin/env bash
set -Eeuo pipefail

source_path="${1:-${BUNDLE_PATH:-}}"
[[ -n "$source_path" ]] || { printf 'usage: verify-setup-bundle.sh <bundle-directory-or-tar.gz>\n' >&2; exit 2; }

scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-bundle-verify.XXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT
export HOME="${scratch}/home"
export KAST_HOME="${scratch}/kast-home"
mkdir -p "$HOME"

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
first="$(${bundle_root}/libexec/kastctl --output json setup --source "$bundle_root")"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"ACTIVATED"' <<<"$first"
second="$(${KAST_HOME}/current/libexec/kastctl --output json setup --source "$bundle_root")"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"CURRENT"' <<<"$second"
"${KAST_HOME}/current/libexec/kastctl" ready --for machine >/dev/null
printf 'setup bundle verification passed\n'
