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

require_log_contains() {
  local log_file="$1"
  local expected="$2"
  local description="$3"
  grep -Fqx -- "$expected" "$log_file" || {
    printf '%s\n' "log contents:" >&2
    cat "$log_file" >&2
    die "${description}: missing '${expected}'"
  }
}

require_log_count() {
  local log_file="$1"
  local expected="$2"
  local expected_count="$3"
  local description="$4"
  local actual_count
  actual_count="$(grep -Fxc -- "$expected" "$log_file" || true)"
  [[ "$actual_count" == "$expected_count" ]] || {
    printf '%s\n' "log contents:" >&2
    cat "$log_file" >&2
    die "${description}: expected ${expected_count}, found ${actual_count}"
  }
}

require_stderr_contains() {
  local stderr_file="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$stderr_file" || {
    printf '%s\n' "stderr contents:" >&2
    cat "$stderr_file" >&2
    die "${description}: missing '${expected}'"
  }
}

require_stderr_not_contains() {
  local stderr_file="$1"
  local unexpected="$2"
  local description="$3"
  if grep -Fq -- "$unexpected" "$stderr_file"; then
    printf '%s\n' "stderr contents:" >&2
    cat "$stderr_file" >&2
    die "${description}: found '${unexpected}'"
  fi
}

require_no_tool_calls() {
  local log_file="$1"
  local description="$2"
  [[ ! -s "$log_file" ]] || {
    printf '%s\n' "unexpected tool calls:" >&2
    cat "$log_file" >&2
    die "$description"
  }
}

require_log_not_contains_prefix() {
  local log_file="$1"
  local unexpected_prefix="$2"
  local description="$3"
  if grep -Fq -- "$unexpected_prefix" "$log_file"; then
    printf '%s\n' "log contents:" >&2
    cat "$log_file" >&2
    die "${description}: found '${unexpected_prefix}'"
  fi
}

write_fake_tools() {
  local bin_dir="$1"
  local log_file="$2"
  mkdir -p "$bin_dir"

  cat >"${bin_dir}/brew" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'brew' >>"${KAST_INSTALL_TEST_LOG:?}"
for arg in "$@"; do
  printf ' %s' "$arg" >>"${KAST_INSTALL_TEST_LOG:?}"
done
printf '\n' >>"${KAST_INSTALL_TEST_LOG:?}"

case "$*" in
  "tap "*|"install kast"|"update"|"upgrade kast"|"reinstall kast")
    exit 0
    ;;
  "--prefix kast")
    printf '%s\n' "${KAST_INSTALL_TEST_FORMULA_PREFIX:?}"
    exit 0
    ;;
  *)
    printf 'unexpected fake brew args:' >&2
    printf ' %s' "$@" >&2
    printf '\n' >&2
    exit 64
    ;;
esac
SH

  mkdir -p "${KAST_INSTALL_TEST_FORMULA_PREFIX:?}/bin"
  cat >"${KAST_INSTALL_TEST_FORMULA_PREFIX}/bin/kast" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'kast' >>"${KAST_INSTALL_TEST_LOG:?}"
for arg in "$@"; do
  printf ' %s' "$arg" >>"${KAST_INSTALL_TEST_LOG:?}"
done
printf '\n' >>"${KAST_INSTALL_TEST_LOG:?}"
exit 0
SH

  cat >"${bin_dir}/ps" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ -e "${KAST_INSTALL_TEST_PROCESS_STATE:?}" ]]; then
  printf '%s\n' '4311 /bin/bash'
  exit 0
fi
case "${KAST_INSTALL_TEST_PS:-}" in
  "Installer Shell")
    if [[ "$*" == "-axo args" ]]; then
      printf '%s\n' '/bin/bash -c process_args="$(ps -axo args)"; [[ "$process_args" == *"/IntelliJ IDEA"*".app/Contents/MacOS/idea"* ]]'
    else
      printf '%s\n' '4311 /bin/bash'
    fi
    ;;
  "IntelliJ IDEA") printf '%s\n' '4312 /Applications/IntelliJ IDEA EAP.app/Contents/MacOS/idea' ;;
  "Android Studio") printf '%s\n' '4313 /Applications/Android Studio Preview.app/Contents/MacOS/studio' ;;
  *) printf '%s\n' 'COMMAND' ;;
esac
SH

  cat >"${bin_dir}/kill" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'kill' >>"${KAST_INSTALL_TEST_LOG:?}"
for arg in "$@"; do
  printf ' %s' "$arg" >>"${KAST_INSTALL_TEST_LOG:?}"
done
printf '\n' >>"${KAST_INSTALL_TEST_LOG:?}"
case "$*" in
  "-TERM 4312"|"-TERM 4313")
    : >"${KAST_INSTALL_TEST_PROCESS_STATE:?}"
    ;;
  *)
    printf 'unexpected fake kill args:' >&2
    printf ' %s' "$@" >&2
    printf '\n' >&2
    exit 64
    ;;
esac
SH

  chmod +x "${bin_dir}/brew" "${bin_dir}/kill" "${bin_dir}/ps" "${KAST_INSTALL_TEST_FORMULA_PREFIX}/bin/kast"
}

run_installer() {
  local repo_root="$1"
  shift
  KAST_INSTALL_TEST_UNAME="Darwin" "$repo_root/install.sh" "$@"
}

run_installer_noninteractive() {
  local repo_root="$1"
  shift
  NONINTERACTIVE=1 KAST_INSTALL_TEST_UNAME="Darwin" "$repo_root/install.sh" "$@"
}

run_installer_source_noninteractive() {
  local repo_root="$1"
  shift
  NONINTERACTIVE=1 KAST_INSTALL_TEST_UNAME="Darwin" /bin/bash -c "$(<"${repo_root}/install.sh")" -- "$@"
}

repo_root="$(resolve_repo_root)"
installer="${repo_root}/install.sh"
if grep -Fq -- "sudo" "$installer"; then
  die "installer must not invoke or recommend sudo"
fi
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-macos-installer.XXXXXX")"
trap 'rm -rf "$tmp_root"' EXIT

workspace="${tmp_root}/workspace"
mkdir -p "$workspace"
workspace="$(cd -- "$workspace" && pwd -P)"

log_file="${tmp_root}/tool-calls.log"
fake_bin="${tmp_root}/bin"
export KAST_INSTALL_TEST_FORMULA_PREFIX="${tmp_root}/formula"
export KAST_INSTALL_TEST_PROCESS_STATE="${tmp_root}/jetbrains-stopped"
export KAST_INSTALL_TEST_PS=""
write_fake_tools "$fake_bin" "$log_file"
export KAST_INSTALL_TEST_LOG="$log_file"
export PATH="${fake_bin}:/usr/bin:/bin:/usr/sbin:/sbin"

: >"$log_file"
prompt_stderr="${tmp_root}/install-prompt.stderr"
if run_installer "$repo_root" install --workspace-root "$workspace" </dev/null 2>"$prompt_stderr"; then
  die "installer should pause for confirmation before mutating install commands"
fi
require_stderr_contains "$prompt_stderr" "Kast developer install plan" "install should explain the plan before mutation"
require_stderr_contains "$prompt_stderr" "Press RETURN/ENTER to continue" "install should pause before mutation"
require_stderr_contains "$prompt_stderr" "Set NONINTERACTIVE=1 to run without a prompt" "install should document the automation escape hatch"
require_no_tool_calls "$log_file" "unconfirmed install must fail before invoking brew or kast"

: >"$log_file"
install_stderr="${tmp_root}/install.stderr"
CLICOLOR_FORCE=1 run_installer_noninteractive "$repo_root" install --workspace-root "$workspace" 2>"$install_stderr"
require_stderr_contains "$install_stderr" $'\033[1;36mKast developer install\033[0m' "install should use the kast.sh blue section style"
require_stderr_contains "$install_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "install should render the Kast banner"
require_stderr_contains "$install_stderr" "Kotlin semantic analysis — from your terminal" "install should render the original tagline"
require_stderr_contains "$install_stderr" "NONINTERACTIVE=1 set; skipping confirmation prompt" "install should support unattended automation"
require_log_contains "$log_file" "brew tap amichne/kast" "install should tap the default Homebrew repository"
require_log_contains "$log_file" "brew install kast" "install should install the Kast formula"
require_log_contains "$log_file" "kast developer machine plugin" "install should hide the developer plugin command"
require_log_count "$log_file" "kast developer machine plugin" 1 "install should converge plugin state exactly once"
require_log_not_contains_prefix "$log_file" "kast setup" "install should leave macOS workspace setup to the plugin"

: >"$log_file"
update_stderr="${tmp_root}/update.stderr"
run_installer_noninteractive "$repo_root" update \
  --tap custom/tap \
  --tap-url https://git.example.test/homebrew/kast.git \
  --workspace-root "$workspace" 2>"$update_stderr"
require_stderr_contains "$update_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "update should render the Kast banner"
require_stderr_contains "$update_stderr" "Kotlin semantic analysis — from your terminal" "update should render the original tagline"
require_log_contains "$log_file" "brew tap custom/tap https://git.example.test/homebrew/kast.git" "update should accept an explicit tap URL for custom hosts"
require_log_contains "$log_file" "brew update" "update should refresh Homebrew metadata"
require_log_contains "$log_file" "brew upgrade kast" "update should upgrade the Kast formula"
require_log_contains "$log_file" "kast developer machine plugin" "update should converge plugin state through the Homebrew binary"
require_log_count "$log_file" "kast developer machine plugin" 1 "update should converge plugin state exactly once"
require_log_not_contains_prefix "$log_file" "kast developer machine plugin --force" "update should not force a second cask reinstall"
require_log_not_contains_prefix "$log_file" "kast setup" "update should leave macOS workspace setup to the plugin"

: >"$log_file"
verify_stderr="${tmp_root}/verify.stderr"
run_installer "$repo_root" verify --workspace-root "$workspace" 2>"$verify_stderr"
require_stderr_not_contains "$verify_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "verify should remain banner-free"
require_log_contains "$log_file" "brew --prefix kast" "verify should prove Homebrew owns the formula"
require_log_contains "$log_file" "kast ready --for agent --workspace-root ${workspace}" "verify should check repository readiness"

help_stderr="${tmp_root}/help.stderr"
run_installer "$repo_root" --help 2>"$help_stderr"
require_stderr_not_contains "$help_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "help should remain banner-free"

: >"$log_file"
self_detection_stderr="${tmp_root}/self-detection.stderr"
KAST_INSTALL_TEST_PS="Installer Shell" run_installer_source_noninteractive "$repo_root" install \
  --workspace-root "$workspace" 2>"$self_detection_stderr"
require_stderr_not_contains "$self_detection_stderr" "Close IntelliJ IDEA" "source entrypoint must not detect its own shell as IntelliJ IDEA"
require_log_contains "$log_file" "brew install kast" "source entrypoint should proceed when no IDE executable is running"

: >"$log_file"
rm -f "$KAST_INSTALL_TEST_PROCESS_STATE"
stderr_file="${tmp_root}/running-idea-accepted.stderr"
printf 'y\n\n' | KAST_INSTALL_TEST_PS="IntelliJ IDEA" run_installer "$repo_root" update \
  --workspace-root "$workspace" 2>"$stderr_file"
require_stderr_contains "$stderr_file" "Detected IntelliJ IDEA (PID 4312)" "interactive preflight should identify the exact process"
require_stderr_contains "$stderr_file" "Close the detected editor and continue? [y/N]" "interactive preflight should offer to close the editor"
require_stderr_contains "$stderr_file" "IntelliJ IDEA closed" "interactive preflight should wait for editor closure"
require_log_contains "$log_file" "kill -TERM 4312" "interactive acceptance should terminate the detected editor"
require_log_contains "$log_file" "brew upgrade kast" "install should continue after the editor exits"

: >"$log_file"
rm -f "$KAST_INSTALL_TEST_PROCESS_STATE"
stderr_file="${tmp_root}/running-idea-declined.stderr"
if printf 'n\n' | KAST_INSTALL_TEST_PS="IntelliJ IDEA" run_installer "$repo_root" update \
  --workspace-root "$workspace" 2>"$stderr_file"; then
  die "installer should exit when interactive editor closure is declined"
fi
require_stderr_contains "$stderr_file" "kill -TERM 4312" "declined closure should print the exact manual stop command"
require_log_not_contains_prefix "$log_file" "kill " "declined closure must not stop the editor"
require_no_tool_calls "$log_file" "declined closure must fail before invoking brew or kast"

: >"$log_file"
rm -f "$KAST_INSTALL_TEST_PROCESS_STATE"
stderr_file="${tmp_root}/running-idea-eof.stderr"
if KAST_INSTALL_TEST_PS="IntelliJ IDEA" run_installer "$repo_root" update \
  --workspace-root "$workspace" </dev/null 2>"$stderr_file"; then
  die "installer should exit when the editor closure prompt reaches EOF"
fi
require_stderr_contains "$stderr_file" "kill -TERM 4312" "EOF should print the exact manual stop command"
require_log_not_contains_prefix "$log_file" "kill " "EOF must not stop the editor"
require_no_tool_calls "$log_file" "EOF must fail before invoking brew or kast"

: >"$log_file"
rm -f "$KAST_INSTALL_TEST_PROCESS_STATE"
stderr_file="${tmp_root}/running-idea.stderr"
if KAST_INSTALL_TEST_PS="IntelliJ IDEA" run_installer_noninteractive "$repo_root" update --workspace-root "$workspace" 2>"$stderr_file"; then
  die "installer should stop before plugin mutation while IntelliJ IDEA is running"
fi
require_stderr_contains "$stderr_file" "Detected IntelliJ IDEA (PID 4312)" "non-interactive preflight should identify the exact process"
require_stderr_contains "$stderr_file" "kill -TERM 4312" "non-interactive preflight should print the exact manual stop command"
require_stderr_not_contains "$stderr_file" "Close the detected editor and continue?" "non-interactive preflight must not prompt"
require_no_tool_calls "$log_file" "running IDE preflight must fail before invoking brew or kast"

: >"$log_file"
rm -f "$KAST_INSTALL_TEST_PROCESS_STATE"
stderr_file="${tmp_root}/running-android-studio.stderr"
if KAST_INSTALL_TEST_PS="Android Studio" run_installer_noninteractive "$repo_root" install --workspace-root "$workspace" 2>"$stderr_file"; then
  die "installer should stop before plugin mutation while Android Studio is running"
fi
require_stderr_contains "$stderr_file" "Detected Android Studio (PID 4313)" "running IDE preflight should name the exact Android Studio process"
require_stderr_contains "$stderr_file" "kill -TERM 4313" "Android Studio preflight should print the exact manual stop command"
require_no_tool_calls "$log_file" "running Android Studio preflight must fail before invoking brew or kast"

: >"$log_file"
stderr_file="${tmp_root}/unsupported-os.stderr"
if KAST_INSTALL_TEST_UNAME="Linux" "$installer" install --workspace-root "$workspace" 2>"$stderr_file"; then
  die "installer should reject non-macOS hosts"
fi
require_stderr_contains "$stderr_file" "only supports macOS" "unsupported OS should fail loudly"
require_no_tool_calls "$log_file" "unsupported OS must fail before invoking brew or kast"

: >"$log_file"
stderr_file="${tmp_root}/unknown-flag.stderr"
if run_installer "$repo_root" install --bogus 2>"$stderr_file"; then
  die "installer should reject unknown flags"
fi
require_stderr_contains "$stderr_file" "Unknown argument: --bogus" "unknown flags should fail loudly"
require_no_tool_calls "$log_file" "unknown flags must fail before invoking brew or kast"

: >"$log_file"
stderr_file="${tmp_root}/invalid-tap.stderr"
if run_installer "$repo_root" install --tap invalid 2>"$stderr_file"; then
  die "installer should reject invalid tap values"
fi
require_stderr_contains "$stderr_file" "Invalid tap: invalid" "invalid tap values should fail loudly"
require_no_tool_calls "$log_file" "invalid tap must fail before invoking brew or kast"

: >"$log_file"
stderr_file="${tmp_root}/invalid-tap-url.stderr"
if run_installer "$repo_root" install --tap custom/tap --tap-url not-a-url 2>"$stderr_file"; then
  die "installer should reject invalid tap URLs"
fi
require_stderr_contains "$stderr_file" "Invalid tap URL: not-a-url" "invalid tap URLs should fail loudly"
require_no_tool_calls "$log_file" "invalid tap URL must fail before invoking brew or kast"

: >"$log_file"
stderr_file="${tmp_root}/missing-workspace.stderr"
if run_installer "$repo_root" install --workspace-root "${tmp_root}/missing" 2>"$stderr_file"; then
  die "installer should reject missing workspace roots"
fi
require_stderr_contains "$stderr_file" "Workspace root does not exist" "missing workspace roots should fail loudly"
require_no_tool_calls "$log_file" "missing workspace root must fail before invoking brew or kast"

printf '%s\n' "macOS installer contract passed"
