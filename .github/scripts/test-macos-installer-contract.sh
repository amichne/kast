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
    sed -n '1,160p' "$log_file" >&2
    die "${description}: missing '${expected}'"
  }
}

require_log_contains_fragment() {
  local log_file="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$log_file" || {
    sed -n '1,160p' "$log_file" >&2
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
    sed -n '1,160p' "$log_file" >&2
    die "${description}: expected ${expected_count}, found ${actual_count}"
  }
}

require_stderr_contains() {
  local stderr_file="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$stderr_file" || {
    sed -n '1,160p' "$stderr_file" >&2
    die "${description}: missing '${expected}'"
  }
}

require_stderr_not_contains() {
  local stderr_file="$1"
  local unexpected="$2"
  local description="$3"
  if grep -Fq -- "$unexpected" "$stderr_file"; then
    sed -n '1,160p' "$stderr_file" >&2
    die "${description}: found '${unexpected}'"
  fi
}

require_no_tool_calls() {
  local log_file="$1"
  local description="$2"
  [[ ! -s "$log_file" ]] || {
    sed -n '1,160p' "$log_file" >&2
    die "$description"
  }
}

require_log_not_contains_prefix() {
  local log_file="$1"
  local unexpected_prefix="$2"
  local description="$3"
  if grep -Fq -- "$unexpected_prefix" "$log_file"; then
    sed -n '1,160p' "$log_file" >&2
    die "${description}: found '${unexpected_prefix}'"
  fi
}

write_fake_tools() {
  local bin_dir="$1"
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
if [[ "$*" == "version" ]]; then
  printf '%s\n' "Kast CLI 0.13.0"
fi
SH

  cat >"${bin_dir}/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl' >>"${KAST_INSTALL_TEST_LOG:?}"
for arg in "$@"; do
  printf ' %s' "$arg" >>"${KAST_INSTALL_TEST_LOG:?}"
done
printf '\n' >>"${KAST_INSTALL_TEST_LOG:?}"
output=""
while [[ $# -gt 0 ]]; do
  if [[ "$1" == "--output" ]]; then
    output="$2"
    break
  fi
  shift
done
[[ -n "$output" ]] || exit 64
printf 'plugin' >"$output"
SH

  chmod +x "${bin_dir}/brew" "${bin_dir}/curl" "${KAST_INSTALL_TEST_FORMULA_PREFIX}/bin/kast"
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
if grep -Fq -- 'run `install.sh update` to install the release-matched plugin' \
  "${repo_root}/cli-rs/src/install/repair.rs" \
  "${repo_root}/cli-rs/src/runtime/descriptors.rs"; then
  die "missing-plugin recovery must use the installer install command"
fi
if grep -F 'Kast plugin does not appear' "${repo_root}/docs/troubleshoot.md" \
  | grep -Fq 'install.sh update'; then
  die "missing-plugin troubleshooting must use the installer install command"
fi
if grep -Fq -- "sudo" "$installer"; then
  die "installer must not invoke or recommend sudo"
fi
if grep -En "developer machine plugin|brew .*--cask|ps -axo|kill -TERM|KAST_JETBRAINS_CONFIG_ROOT" "$installer"; then
  die "installer retains forbidden IDE mutation authority"
fi
if grep -Fq -- "installPlugins" "$installer"; then
  die "installer must use one processless machine reconciliation path"
fi

tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-macos-installer.XXXXXX")"
trap 'rm -rf "$tmp_root"' EXIT
workspace="${tmp_root}/workspace"
mkdir -p "$workspace"
workspace="$(cd -- "$workspace" && pwd -P)"
log_file="${tmp_root}/tool-calls.log"
fake_bin="${tmp_root}/bin"
export KAST_INSTALL_TEST_FORMULA_PREFIX="${tmp_root}/Cellar/kast/0.13.0"
export KAST_INSTALL_TEST_LOG="$log_file"
write_fake_tools "$fake_bin"
export PATH="${fake_bin}:/usr/bin:/bin:/usr/sbin:/sbin"

: >"$log_file"
prompt_stderr="${tmp_root}/install-prompt.stderr"
if run_installer "$repo_root" install --workspace-root "$workspace" </dev/null 2>"$prompt_stderr"; then
  die "installer should pause for confirmation before mutating install commands"
fi
require_stderr_contains "$prompt_stderr" "Kast developer install plan" "install should explain the plan before mutation"
require_stderr_contains "$prompt_stderr" "Press RETURN/ENTER to continue" "install should pause before mutation"
require_stderr_contains "$prompt_stderr" "Set NONINTERACTIVE=1 to run without a prompt" "install should document unattended use"
require_no_tool_calls "$log_file" "confirmation EOF must fail before invoking brew or kast"

: >"$log_file"
declined_stderr="${tmp_root}/install-declined.stderr"
if printf 'no\n' | run_installer "$repo_root" install --workspace-root "$workspace" 2>"$declined_stderr"; then
  die "installer should stop when confirmation is declined"
fi
require_stderr_contains "$declined_stderr" "Aborted" "declined confirmation should fail clearly"
require_no_tool_calls "$log_file" "declined confirmation must fail before invoking brew or kast"

: >"$log_file"
install_stderr="${tmp_root}/install.stderr"
CLICOLOR_FORCE=1 run_installer_noninteractive "$repo_root" install --workspace-root "$workspace" 2>"$install_stderr"
require_stderr_contains "$install_stderr" $'\033[1;36mKast developer install\033[0m' "install should use the Kast section style"
require_stderr_contains "$install_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "install should render the Kast banner"
require_stderr_contains "$install_stderr" "Kotlin semantic analysis — from your terminal" "install should render the tagline"
require_stderr_contains "$install_stderr" "NONINTERACTIVE=1 set; skipping confirmation prompt" "install should support automation"
require_log_contains "$log_file" "brew tap amichne/kast" "install should tap the default repository"
require_log_contains "$log_file" "brew install kast" "install should install the formula"
require_log_contains "$log_file" "brew --prefix kast" "install should resolve the formula-owned binary"
require_log_contains "$log_file" "kast version" "install should derive the plugin feed from the installed CLI"
require_log_contains_fragment "$log_file" "curl -fsSL --output " "install should download the exact plugin ZIP"
require_log_contains_fragment "$log_file" "https://github.com/amichne/kast/releases/download/v0.13.0/kast-idea-v0.13.0.zip" "install should select the release-matched plugin"
require_log_contains_fragment "$log_file" "kast machine activate --idea-plugin " "install should activate one machine bundle"
require_log_contains "$log_file" "kast machine reconcile" "install should synchronously reconcile the plugin and resources"
require_log_not_contains_prefix "$log_file" "kast setup" "install should leave workspace setup to JetBrains"

: >"$log_file"
update_stderr="${tmp_root}/update.stderr"
run_installer_noninteractive "$repo_root" update \
  --tap custom/tap \
  --tap-url https://git.example.test/homebrew/kast.git \
  --workspace-root "$workspace" 2>"$update_stderr"
require_stderr_contains "$update_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "update should render the Kast banner"
require_log_contains "$log_file" "brew tap custom/tap https://git.example.test/homebrew/kast.git" "update should accept a custom tap URL"
require_log_contains "$log_file" "brew update" "update should refresh Homebrew metadata"
require_log_contains "$log_file" "brew upgrade kast" "update should upgrade the formula"
require_log_contains "$log_file" "kast version" "update should derive the expected plugin release from the installed CLI"
require_log_contains_fragment "$log_file" "kast machine activate --idea-plugin " "update should replace the machine bundle"
require_log_contains "$log_file" "kast machine reconcile" "update should synchronously reconcile the plugin and resources"
require_log_not_contains_prefix "$log_file" "kast setup" "update should leave workspace setup to JetBrains"

: >"$log_file"
verify_stderr="${tmp_root}/verify.stderr"
run_installer "$repo_root" verify --workspace-root "$workspace" 2>"$verify_stderr"
require_stderr_not_contains "$verify_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "verify should remain banner-free"
require_log_contains "$log_file" "brew --prefix kast" "verify should prove Homebrew formula authority"
require_log_contains "$log_file" "kast agent verify --workspace-root ${workspace} --backend idea" "verify should run typed IDEA admission on demand"

help_stderr="${tmp_root}/help.stderr"
run_installer "$repo_root" --help 2>"$help_stderr"
require_stderr_not_contains "$help_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "help should remain banner-free"

: >"$log_file"
source_stderr="${tmp_root}/source-entrypoint.stderr"
run_installer_source_noninteractive "$repo_root" install --workspace-root "$workspace" 2>"$source_stderr"
require_log_contains "$log_file" "brew install kast" "the curl-style source entrypoint should install the formula"
require_log_contains_fragment "$log_file" "kast machine activate --idea-plugin " "the source entrypoint should establish machine authority"

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
require_stderr_contains "$stderr_file" "Invalid tap: invalid" "invalid taps should fail loudly"
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

bash -n "$installer"
printf '%s\n' "macOS developer installer contract passed"
