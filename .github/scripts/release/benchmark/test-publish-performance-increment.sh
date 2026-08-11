#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'performance increment publisher contract: %s\n' "$*" >&2
  exit 1
}

require_text() {
  local haystack="$1"
  local needle="$2"
  local message="$3"
  [[ "$haystack" == *"$needle"* ]] || die "$message"
}

reject_text() {
  local haystack="$1"
  local needle="$2"
  local message="$3"
  [[ "$haystack" != *"$needle"* ]] || die "$message"
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../../.." && pwd -P)"
publisher="$repo_root/scripts/release/publish-performance-increment.sh"
[[ -x "$publisher" ]] || die 'publisher is missing or not executable'
bash -n "$publisher" || die 'publisher has invalid Bash syntax'

help="$($publisher --help)"
for required_help in \
  'one intentionally small, evidence-backed performance increment' \
  'enterprise machine' \
  'checkpoint' \
  'release' \
  '--branch <branch>' \
  '--evidence <path>' \
  '--repository <owner/name>'; do
  require_text "$help" "$required_help" "help is missing: $required_help"
done

scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-performance-publisher-test.XXXXXX")"
trap 'find "$scratch" -depth -delete' EXIT
fake_bin="$scratch/bin"
fake_repo="$scratch/repository"
fake_state="$scratch/state"
call_log="$scratch/calls.log"
mkdir -p "$fake_bin" "$fake_repo/evidence" "$fake_state"
printf '%s\n' '{"machine":"enterprise","samples":3}' \
  > "$fake_repo/evidence/enterprise.json"
: > "$call_log"

cat > "$fake_bin/git" <<'FAKE_GIT'
#!/usr/bin/env bash
set -euo pipefail
printf 'git' >> "$TEST_CALL_LOG"
printf ' %q' "$@" >> "$TEST_CALL_LOG"
printf '\n' >> "$TEST_CALL_LOG"

case "${1:-}" in
  rev-parse)
    case "${2:-}" in
      --show-toplevel) printf '%s\n' "$TEST_REPO" ;;
      HEAD) printf '%s\n' "$TEST_HEAD" ;;
      *) exit 2 ;;
    esac
    ;;
  status)
    printf '%s' "${TEST_GIT_STATUS:-}"
    ;;
  check-ref-format)
    [[ "${3:-}" == performance/* ]] || exit 1
    ;;
  ls-files)
    [[ "${TEST_EVIDENCE_COMMITTED:-true}" == true ]] || exit 1
    printf '%s\n' "${@: -1}"
    ;;
  cat-file)
    [[ "${TEST_EVIDENCE_COMMITTED:-true}" == true ]] || exit 1
    ;;
  push)
    ;;
  ls-remote)
    requested_ref=""
    for argument in "$@"; do
      requested_ref="$argument"
    done
    case "$requested_ref" in
      refs/heads/main)
        printf '%s\t%s\n' "${TEST_MAIN_HEAD:-$TEST_HEAD}" refs/heads/main
        ;;
      refs/heads/performance/enterprise-nibbles)
        printf '%s\t%s\n' "$TEST_HEAD" refs/heads/performance/enterprise-nibbles
        ;;
      refs/tags/v\*)
        printf '%s\t%s\n' 1111111111111111111111111111111111111111 refs/tags/v0.23.0
        if [[ -f "$TEST_STATE/release-published" ]]; then
          printf '%s\t%s\n' "$TEST_HEAD" refs/tags/v0.23.1
        fi
        ;;
      *) exit 2 ;;
    esac
    ;;
  *)
    exit 2
    ;;
esac
FAKE_GIT

cat > "$fake_bin/gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail
printf 'gh' >> "$TEST_CALL_LOG"
printf ' %q' "$@" >> "$TEST_CALL_LOG"
printf '\n' >> "$TEST_CALL_LOG"

case "${1:-} ${2:-}" in
  'run list')
    case "${TEST_CI_STATE:-success}" in
      success)
        printf '[{"databaseId":77,"headSha":"%s","headBranch":"main","event":"push","status":"completed","conclusion":"success","url":"https://example.test/actions/runs/77"}]\n' "$TEST_HEAD"
        ;;
      pending)
        printf '[{"databaseId":77,"headSha":"%s","headBranch":"main","event":"push","status":"in_progress","conclusion":"","url":"https://example.test/actions/runs/77"}]\n' "$TEST_HEAD"
        ;;
      *) printf '[]\n' ;;
    esac
    ;;
  'workflow run')
    printf '%s\n' 'https://example.test/actions/runs/88'
    ;;
  'run watch')
    touch "$TEST_STATE/release-published"
    ;;
  'release view')
    [[ -f "$TEST_STATE/release-published" ]] || exit 1
    printf '%s\n' '{"isDraft":false,"isPrerelease":false,"tagName":"v0.23.1","url":"https://example.test/releases/v0.23.1"}'
    ;;
  *)
    exit 2
    ;;
esac
FAKE_GH
chmod 700 "$fake_bin/git" "$fake_bin/gh"

export PATH="$fake_bin:$PATH"
export TEST_CALL_LOG="$call_log"
export TEST_HEAD=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export TEST_REPO="$fake_repo"
export TEST_STATE="$fake_state"

: > "$call_log"
if unknown_output="$($publisher checkpoint --unknown value 2>&1)"; then
  die 'unknown checkpoint flag unexpectedly succeeded'
else
  unknown_status=$?
fi
[[ "$unknown_status" -eq 2 ]] || die 'unknown checkpoint flag did not exit 2'
require_text "$unknown_output" 'code: "unknown_argument"' \
  'unknown checkpoint flag did not produce a structured usage error'
[[ ! -s "$call_log" ]] || die 'unknown arguments reached a dependency before validation'

: > "$call_log"
checkpoint_output="$($publisher checkpoint \
  --branch performance/enterprise-nibbles \
  --evidence evidence/enterprise.json)"
require_text "$checkpoint_output" 'status: "checkpointed"' \
  'checkpoint did not report success'
require_text "$checkpoint_output" 'head: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' \
  'checkpoint did not report exact HEAD'
checkpoint_calls="$(<"$call_log")"
require_text "$checkpoint_calls" \
  'git push origin HEAD:refs/heads/performance/enterprise-nibbles' \
  'checkpoint did not push exact HEAD to the selected branch'
reject_text "$checkpoint_calls" 'gh ' \
  'checkpoint unexpectedly crossed the release boundary'

: > "$call_log"
export TEST_EVIDENCE_COMMITTED=false
if evidence_output="$($publisher checkpoint \
    --branch performance/enterprise-nibbles \
    --evidence evidence/enterprise.json 2>&1)"; then
  die 'uncommitted evidence unexpectedly passed checkpoint admission'
fi
unset TEST_EVIDENCE_COMMITTED
require_text "$evidence_output" 'code: "evidence_not_committed"' \
  'uncommitted evidence did not produce a typed failure'
reject_text "$(<"$call_log")" 'git push' \
  'uncommitted evidence reached the push boundary'

: > "$call_log"
export TEST_MAIN_HEAD=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
if main_output="$($publisher release \
    --branch performance/enterprise-nibbles \
    --evidence evidence/enterprise.json \
    --repository amichne/kast 2>&1)"; then
  die 'release unexpectedly accepted a different remote main commit'
fi
unset TEST_MAIN_HEAD
require_text "$main_output" 'code: "main_not_exact_head"' \
  'remote main mismatch did not produce a typed failure'
reject_text "$(<"$call_log")" 'gh workflow run' \
  'remote main mismatch reached release dispatch'

: > "$call_log"
export TEST_CI_STATE=pending
if ci_output="$($publisher release \
    --branch performance/enterprise-nibbles \
    --evidence evidence/enterprise.json \
    --repository amichne/kast 2>&1)"; then
  die 'release unexpectedly accepted nonterminal exact-source CI'
fi
unset TEST_CI_STATE
require_text "$ci_output" 'code: "exact_source_ci_not_green"' \
  'nonterminal CI did not produce a typed failure'
reject_text "$(<"$call_log")" 'gh workflow run' \
  'nonterminal CI reached release dispatch'

: > "$call_log"
rm -f "$fake_state/release-published"
release_output="$($publisher release \
  --branch performance/enterprise-nibbles \
  --evidence evidence/enterprise.json \
  --repository amichne/kast)"
require_text "$release_output" 'status: "released"' \
  'release did not report terminal publication'
require_text "$release_output" 'tag: "v0.23.1"' \
  'release did not report the next patch tag'
release_calls="$(<"$call_log")"
require_text "$release_calls" \
  'gh workflow run cut-release.yml --repo amichne/kast --ref main --raw-field release_type=patch' \
  'release did not dispatch the authoritative patch workflow on main'
require_text "$release_calls" \
  'gh run watch 88 --repo amichne/kast --exit-status --compact' \
  'release did not wait for terminal workflow state'
require_text "$release_calls" \
  'gh release view v0.23.1 --repo amichne/kast --json isDraft\,isPrerelease\,tagName\,url' \
  'release did not verify the published stable release'

printf '%s\n' 'performance increment publisher contract: ok'
