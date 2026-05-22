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

test_detects_idea_and_android_studio_processes() {
  _INSTALL_ENV_HAS_JAVA="false"
  _INSTALL_ENV_HAS_FZF="false"
  _INSTALL_ENV_EXISTING_VERSION=""
  _INSTALL_ENV_INTELLIJ_PIDS=()
  _INSTALL_ENV_INTELLIJ_APPS=()
  _INSTALL_ENV_INTELLIJ_LABELS=()

  uname() {
    printf '%s\n' "Darwin"
  }

  command() {
    return 1
  }

  ps() {
    cat <<'PS'
user 100 0.0 0.0 0 0 ?? S 0:00 /Applications/IntelliJ IDEA.app/Contents/MacOS/idea
user 101 0.0 0.0 0 0 ?? S 0:00 /Applications/Android Studio.app/Contents/MacOS/studio
PS
  }

  _install_detect_env

  [[ "${#_INSTALL_ENV_INTELLIJ_PIDS[@]}" == "2" ]] \
    || die "expected IDEA and Android Studio processes to be detected, got ${#_INSTALL_ENV_INTELLIJ_PIDS[@]}"
  [[ "${_INSTALL_ENV_INTELLIJ_APPS[0]}" == "/Applications/IntelliJ IDEA.app" ]] \
    || die "unexpected IDEA app path: ${_INSTALL_ENV_INTELLIJ_APPS[0]}"
  [[ "${_INSTALL_ENV_INTELLIJ_APPS[1]}" == "/Applications/Android Studio.app" ]] \
    || die "unexpected Android Studio app path: ${_INSTALL_ENV_INTELLIJ_APPS[1]}"
}

test_detects_idea_and_android_studio_processes

test_download_uses_progress_bar_when_requested() {
  local args_file="${test_tmp_dir}/download-progress-args"

  curl() {
    printf '%s\n' "$@" > "$args_file"
  }

  KAST_DOWNLOAD_PROGRESS=always _install_download_file "https://example.invalid/kast.zip" "${test_tmp_dir}/kast.zip"

  local actual_args
  actual_args="$(<"$args_file")"
  [[ "$actual_args" == *"--progress-bar"* ]] || die "expected progress-bar curl output, got '${actual_args}'"
  [[ "$actual_args" != *"--silent"* ]] || die "progress download should not pass --silent, got '${actual_args}'"
}

test_download_stays_quiet_when_requested() {
  local args_file="${test_tmp_dir}/download-quiet-args"

  curl() {
    printf '%s\n' "$@" > "$args_file"
  }

  KAST_DOWNLOAD_PROGRESS=never _install_download_file "https://example.invalid/kast.zip" "${test_tmp_dir}/kast.zip"

  local actual_args
  actual_args="$(<"$args_file")"
  [[ "$actual_args" == *"--silent"* ]] || die "quiet download should pass --silent, got '${actual_args}'"
  [[ "$actual_args" == *"--show-error"* ]] || die "quiet download should pass --show-error, got '${actual_args}'"
  [[ "$actual_args" != *"--progress-bar"* ]] || die "quiet download should not pass --progress-bar, got '${actual_args}'"
}

test_download_uses_progress_bar_when_requested
test_download_stays_quiet_when_requested

printf '%s\n' "Installer selector test passed"
