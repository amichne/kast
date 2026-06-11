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

expect_failure_contains() {
  local expected="$1"
  shift
  local output_path="${scratch_dir}/expected-failure.out"

  set +e
  "$@" >"$output_path" 2>&1
  local exit_code="$?"
  set -e

  [[ "$exit_code" -ne 0 ]] || die "Command unexpectedly succeeded: $*"
  grep -Fq "$expected" "$output_path" || {
    cat "$output_path" >&2
    die "Expected failure output to contain: $expected"
  }
}

repo_root="$(resolve_repo_root)"
retry="${repo_root}/scripts/ci-gradle-retry.sh"
[[ -x "$retry" ]] || die "Missing executable retry helper: $retry"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-ci-gradle-retry-test.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

success_script="${scratch_dir}/success.sh"
cat > "$success_script" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "success"
SCRIPT
chmod +x "$success_script"

KAST_CI_GRADLE_ATTEMPTS=2 KAST_CI_GRADLE_RETRY_DELAY_SECONDS=0 "$retry" "$success_script" \
  >"${scratch_dir}/success.out" 2>&1
grep -Fq "success" "${scratch_dir}/success.out" || die "Retry helper did not run successful command"

transient_script="${scratch_dir}/transient.sh"
cat > "$transient_script" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
count_file="${1:?count file}"
count=0
if [[ -f "$count_file" ]]; then
  count="$(cat "$count_file")"
fi
count=$((count + 1))
printf '%s\n' "$count" > "$count_file"
if [[ "$count" -eq 1 ]]; then
  printf '%s\n' "Received status code 502 from server: Bad Gateway"
  exit 42
fi
printf '%s\n' "retried"
SCRIPT
chmod +x "$transient_script"

KAST_CI_GRADLE_ATTEMPTS=2 KAST_CI_GRADLE_RETRY_DELAY_SECONDS=0 "$retry" "$transient_script" "${scratch_dir}/transient-count" \
  >"${scratch_dir}/transient.out" 2>&1
grep -Fq "retried" "${scratch_dir}/transient.out" || die "Retry helper did not retry transient failure"
[[ "$(cat "${scratch_dir}/transient-count")" == "2" ]] || die "Transient command was not attempted twice"

deterministic_script="${scratch_dir}/deterministic.sh"
cat > "$deterministic_script" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "compiler error"
exit 17
SCRIPT
chmod +x "$deterministic_script"

set +e
KAST_CI_GRADLE_ATTEMPTS=3 KAST_CI_GRADLE_RETRY_DELAY_SECONDS=0 "$retry" "$deterministic_script" \
  >"${scratch_dir}/deterministic.out" 2>&1
deterministic_exit="$?"
set -e
[[ "$deterministic_exit" -eq 17 ]] || die "Retry helper did not preserve deterministic failure exit code"
[[ "$(grep -Fc "compiler error" "${scratch_dir}/deterministic.out")" == "1" ]] || die "Retry helper retried deterministic failure"

expect_failure_contains \
  "KAST_CI_GRADLE_ATTEMPTS must be a positive integer" \
  env KAST_CI_GRADLE_ATTEMPTS=0 KAST_CI_GRADLE_RETRY_DELAY_SECONDS=0 "$retry" "$success_script"

printf '%s\n' "CI Gradle retry helper contract passed"
