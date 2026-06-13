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

require_contains() {
  local file_path="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$file_path" || die "${description}: missing '${expected}' in ${file_path}"
}

require_output_contains() {
  local output="$1"
  local expected="$2"
  local description="$3"
  printf '%s\n' "$output" | grep -Fq -- "$expected" || die "${description}: missing '${expected}'"
}

expect_failure_contains() {
  local expected="$1"
  shift
  local output_path="${scratch_dir}/expected-failure.out"

  set +e
  "$@" >"$output_path" 2>&1
  local exit_code="$?"
  set -e

  [[ "$exit_code" -ne 0 ]] || die "Command unexpectedly succeeded: $*"
  require_contains "$output_path" "$expected" "Expected failure output"
}

repo_root="$(resolve_repo_root)"
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-installer-contract.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

fake_bin="${scratch_dir}/bin"
artifact_dir="${scratch_dir}/artifacts"
brew_log="${scratch_dir}/brew.log"
kast_log="${scratch_dir}/kast.log"
mkdir -p "$fake_bin" "$artifact_dir"

cat > "${fake_bin}/brew" <<'FAKE_BREW'
#!/usr/bin/env bash
set -euo pipefail

printf 'brew' >> "$BREW_LOG"
for arg in "$@"; do
  printf ' %s' "$arg" >> "$BREW_LOG"
done
printf '\n' >> "$BREW_LOG"

if [[ "${1:-}" == "list" ]]; then
  exit 1
fi

for arg in "$@"; do
  if [[ "$arg" == *.rb && -f "$arg" ]]; then
    {
      printf '%s\n' "--- ${arg} ---"
      sed -n '1,220p' "$arg"
      printf '%s\n' '--- end ---'
    } >> "$BREW_LOG"
  fi
done
FAKE_BREW
chmod +x "${fake_bin}/brew"

cat > "${fake_bin}/kast" <<'FAKE_KAST'
#!/usr/bin/env bash
set -euo pipefail

printf 'kast' >> "$KAST_LOG"
for arg in "$@"; do
  printf ' %s' "$arg" >> "$KAST_LOG"
done
printf '\n' >> "$KAST_LOG"

case "${1:-}" in
  setup|doctor) exit 0 ;;
  version|--version) printf '%s\n' "Kast CLI 9.8.7" ;;
esac
FAKE_KAST
chmod +x "${fake_bin}/kast"

cat > "${fake_bin}/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail

output=""
url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      output="$2"; shift 2 ;;
    --output=*)
      output="${1#--output=}"; shift ;;
    -*)
      shift ;;
    *)
      url="$1"; shift ;;
  esac
done

[[ -n "$output" ]] || { printf '%s\n' "missing --output" >&2; exit 2; }
[[ -n "$url" ]] || { printf '%s\n' "missing URL" >&2; exit 2; }

case "$url" in
  */SHA256SUMS)
    {
      printf '%s  %s\n' "1111111111111111111111111111111111111111111111111111111111111111" "kast-v9.8.7-macos-arm64.zip"
      printf '%s  %s\n' "2222222222222222222222222222222222222222222222222222222222222222" "kast-idea-v9.8.7.zip"
    } > "$output"
    ;;
  *)
    printf '%s\n' "fake download for ${url}" > "$output"
    ;;
esac
FAKE_CURL
chmod +x "${fake_bin}/curl"

bash -n "$repo_root/kast.sh"
[[ ! -e "$repo_root/scripts/install-ubuntu-debian.sh" ]] || die "Deprecated scripts/install-ubuntu-debian.sh wrapper must not exist"

install_help="$("$repo_root/kast.sh" install --help 2>&1)"
require_output_contains "$install_help" "curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/kast.sh | bash" "Install help must document curl-pipe usage"
require_output_contains "$install_help" "--from PATH_OR_URL" "Install help must document explicit artifact sources"
require_output_contains "$install_help" "macOS installs through Homebrew" "Install help must document macOS Homebrew ownership"
require_output_contains "$install_help" "Ubuntu/Debian Linux x86_64" "Install help must document Linux support"
if printf '%s\n' "$install_help" | grep -Eq 'KAST_UBUNTU_DEBIAN_(VERSION|ARTIFACT_PATH|BASE_URL)'; then
  die "Install help must not expose deprecated Linux source-selection environment variables"
fi

BREW_LOG="$brew_log" \
KAST_LOG="$kast_log" \
PATH="$fake_bin:$PATH" \
KAST_INSTALL_TEST_UNAME_S=Darwin \
KAST_INSTALL_TEST_UNAME_M=arm64 \
"$repo_root/kast.sh" install --yes

require_contains "$brew_log" "brew tap amichne/kast" "Default macOS install must tap Homebrew"
require_contains "$brew_log" "brew install amichne/kast/kast" "Default macOS install must install the formula"
require_contains "$brew_log" "brew install --cask amichne/kast/kast-plugin" "Default macOS install must install the cask"
require_contains "$kast_log" "kast setup" "Default macOS install must run kast setup"

: > "$brew_log"
: > "$kast_log"
BREW_LOG="$brew_log" \
KAST_LOG="$kast_log" \
PATH="$fake_bin:$PATH" \
KAST_INSTALL_TEST_UNAME_S=Darwin \
KAST_INSTALL_TEST_UNAME_M=arm64 \
bash -s -- --skip-setup --yes < "$repo_root/kast.sh"

require_contains "$brew_log" "brew tap amichne/kast" "Curl-piped Bash must dispatch to install"
if [[ -s "$kast_log" ]]; then
  die "Curl-piped install with --skip-setup must not run kast setup"
fi

cli_artifact="${artifact_dir}/kast-v9.8.7-macos-arm64.zip"
plugin_artifact="${artifact_dir}/kast-idea-v9.8.7.zip"
printf '%s\n' "fake cli" > "$cli_artifact"
printf '%s\n' "fake plugin" > "$plugin_artifact"

: > "$brew_log"
BREW_LOG="$brew_log" \
KAST_LOG="$kast_log" \
PATH="$fake_bin:$PATH" \
KAST_INSTALL_TEST_UNAME_S=Darwin \
KAST_INSTALL_TEST_UNAME_M=arm64 \
"$repo_root/kast.sh" install --from "$cli_artifact" --skip-setup

require_contains "$brew_log" "Formula/kast.rb" "macOS --from must install through a temporary Homebrew formula"
require_contains "$brew_log" "Casks/kast-plugin.rb" "macOS --from must install through a temporary Homebrew cask"
require_contains "$brew_log" "version \"9.8.7\"" "Temporary formula must pin the artifact version"
require_contains "$brew_log" "file://" "Temporary Homebrew packages must use local artifact file URLs"
require_contains "$brew_log" "kast-v9.8.7-macos-arm64.zip" "Temporary formula must point at the CLI artifact"
require_contains "$brew_log" "kast-idea-v9.8.7.zip" "Temporary cask must point at the matching IDEA plugin artifact"
require_contains "$brew_log" "cask \"kast-plugin\"" "Temporary cask must preserve the plugin cask token"

: > "$brew_log"
BREW_LOG="$brew_log" \
KAST_LOG="$kast_log" \
PATH="$fake_bin:$PATH" \
KAST_INSTALL_TEST_UNAME_S=Darwin \
KAST_INSTALL_TEST_UNAME_M=arm64 \
"$repo_root/kast.sh" install --from "https://mirror.example/kast-v9.8.7-macos-arm64.zip" --skip-setup

require_contains "$brew_log" "https://mirror.example/kast-v9.8.7-macos-arm64.zip" "macOS HTTP --from must point the formula at the source URL"
require_contains "$brew_log" "https://mirror.example/kast-idea-v9.8.7.zip" "macOS HTTP --from must infer the matching plugin URL"

expect_failure_contains \
  "--from supports only local file paths or HTTP(S) URLs" \
  env \
  BREW_LOG="$brew_log" \
  KAST_LOG="$kast_log" \
  PATH="$fake_bin:$PATH" \
  KAST_INSTALL_TEST_UNAME_S=Darwin \
  KAST_INSTALL_TEST_UNAME_M=arm64 \
  "$repo_root/kast.sh" install --from ftp://example.invalid/kast.zip

expect_failure_contains \
  "does not match expected kast-ubuntu-debian-headless-x86_64" \
  env \
  PATH="$fake_bin:$PATH" \
  KAST_INSTALL_TEST_UNAME_S=Linux \
  KAST_INSTALL_TEST_UNAME_M=x86_64 \
  KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK=true \
  KAST_JAVA_CMD=sh \
  "$repo_root/kast.sh" install --from "$cli_artifact"

printf '%s\n' "Kast installer contract passed"
