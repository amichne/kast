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
preflight="${repo_root}/scripts/release-preflight.sh"
[[ -x "$preflight" ]] || die "release preflight helper is missing or not executable: $preflight"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-preflight.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

fake_bin="${scratch_dir}/bin"
mkdir -p "$fake_bin"
cat > "${fake_bin}/gh" <<'GH'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "${GH_CALL_LOG:?}"

case "$1" in
  auth)
    [[ "$2" == "status" ]]
    exit 0
    ;;
  workflow)
    [[ "$2" == "view" && "$3" == "release.yml" ]]
    exit 0
    ;;
  repo)
    [[ "$2" == "view" ]]
    exit 0
    ;;
  secret)
    [[ "$2" == "list" ]]
    if [[ "${FAKE_HOMEBREW_SECRET:-missing}" == "present" ]]; then
      printf 'CLAUDE_CODE_OAUTH_TOKEN\t2026-04-15T01:16:57Z\n'
      printf 'HOMEBREW_TAP_TOKEN\t2026-05-21T00:00:00Z\n'
    else
      printf 'CLAUDE_CODE_OAUTH_TOKEN\t2026-04-15T01:16:57Z\n'
    fi
    ;;
  *)
    printf 'unexpected gh command: %s\n' "$*" >&2
    exit 2
    ;;
esac
GH
chmod +x "${fake_bin}/gh"

export PATH="${fake_bin}:${PATH}"
export GH_CALL_LOG="${scratch_dir}/gh-calls.log"

if FAKE_HOMEBREW_SECRET=missing "$preflight" --release-type patch >"${scratch_dir}/missing.out" 2>"${scratch_dir}/missing.err"; then
  die "stable release preflight unexpectedly passed without HOMEBREW_TAP_TOKEN"
fi
grep -Fq "Missing required GitHub secret HOMEBREW_TAP_TOKEN" "${scratch_dir}/missing.err" || die "missing-secret failure did not name HOMEBREW_TAP_TOKEN"

: > "$GH_CALL_LOG"
FAKE_HOMEBREW_SECRET=missing "$preflight" --release-type beta >"${scratch_dir}/beta.out"
grep -Fq "Homebrew token is not required for beta releases" "${scratch_dir}/beta.out" || die "beta preflight did not explain Homebrew token scope"

: > "$GH_CALL_LOG"
FAKE_HOMEBREW_SECRET=present "$preflight" --release-type patch >"${scratch_dir}/stable.out"
grep -Fq "Release preflight passed for patch" "${scratch_dir}/stable.out" || die "stable preflight did not pass with HOMEBREW_TAP_TOKEN"
grep -Fq "auth status --hostname github.com" "$GH_CALL_LOG" || die "preflight did not check GitHub auth"
grep -Fq "workflow view release.yml --repo amichne/kast" "$GH_CALL_LOG" || die "preflight did not check the release workflow"
grep -Fq "repo view amichne/homebrew-kast" "$GH_CALL_LOG" || die "preflight did not check Homebrew tap visibility"

printf '%s\n' "Release preflight test passed"
