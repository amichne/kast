#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

repo_root="$(resolve_repo_root)"

set -- help
source "${repo_root}/kast.sh" >/dev/null 2>&1
set --

test_tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-installer-selector.XXXXXX")"
capture_file="${test_tmp_dir}/fzf-input"

cleanup_selector_test() {
  rm -rf "$test_tmp_dir"
}

trap cleanup_selector_test EXIT

can_prompt() { return 0; }
_INSTALL_ENV_HAS_FZF="true"

fzf() {
  local line input=""
  if IFS= read -r -t 1 line; then
    input="$line"
    while IFS= read -r -t 0.1 line; do
      input="${input}"$'\n'"${line}"
    done
  else
    input="__NO_INPUT__"
  fi
  printf '%s' "$input" > "$capture_file"
  printf '%s\n' "full"
}

selection="$(_fzf_select "Install mode" "minimal" "full")"
[[ "$selection" == "full" ]] || die "expected fzf selection 'full', got '${selection}'"

expected_input=$'minimal\nfull'
actual_input="$(<"$capture_file")"
[[ "$actual_input" == "$expected_input" ]] || die "fzf did not receive selector items; got '${actual_input}'"

printf '%s\n' "Installer selector test passed"
