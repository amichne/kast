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
args_file="${test_tmp_dir}/fzf-args"

cleanup_selector_test() {
  rm -rf "$test_tmp_dir"
}

trap cleanup_selector_test EXIT

can_prompt() { return 0; }
_INSTALL_ENV_HAS_FZF="true"

fzf() {
  local input=""
  if input="$(cat)"; then
    [[ -n "$input" ]] || input="__NO_INPUT__"
  else
    input="__NO_INPUT__"
  fi
  printf '%s' "$input" > "$capture_file"
  printf '%s\n' "$@" > "$args_file"
  printf '%s\n' "full"
}

selection="$(_fzf_select "Install mode" "minimal" "full")"
[[ "$selection" == "full" ]] || die "expected fzf selection 'full', got '${selection}'"

expected_input=$'minimal\nfull'
actual_input="$(<"$capture_file")"
[[ "$actual_input" == "$expected_input" ]] || die "fzf did not receive selector items; got '${actual_input}'"

actual_args="$(<"$args_file")"
[[ "$actual_args" == *"--prompt=→ Install mode: "* ]] || die "expected decorated fzf prompt, got '${actual_args}'"
[[ "$actual_args" == *"--pointer=→"* ]] || die "expected arrow pointer styling, got '${actual_args}'"
[[ "$actual_args" == *"--header=enter = select · ctrl-c = cancel"* ]] || die "expected helpful selector header, got '${actual_args}'"
[[ "$actual_args" == *"--color=prompt:blue,pointer:green,info:blue,header:yellow,hl:cyan,hl+:cyan,border:blue"* ]] || die "expected colorized fzf styling, got '${actual_args}'"

printf '%s\n' "Installer selector test passed"
