#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

is_positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

is_retryable_gradle_failure() {
  local log_file="$1"
  grep -Eq \
    'Received status code (429|5[0-9][0-9])|Connection reset|Connection refused|Read timed out|Remote host terminated the handshake|Connection timed out|temporarily unavailable|Temporary failure in name resolution|java\.nio\.file\.ClosedFileSystemException' \
    "$log_file"
}

is_closed_file_system_failure() {
  local log_file="$1"
  grep -Fq 'java.nio.file.ClosedFileSystemException' "$log_file"
}

attempts="${KAST_CI_GRADLE_ATTEMPTS:-3}"
delay_seconds="${KAST_CI_GRADLE_RETRY_DELAY_SECONDS:-20}"

is_positive_integer "$attempts" || die "KAST_CI_GRADLE_ATTEMPTS must be a positive integer: $attempts"
[[ "$delay_seconds" =~ ^[0-9]+$ ]] || die "KAST_CI_GRADLE_RETRY_DELAY_SECONDS must be a non-negative integer: $delay_seconds"
[[ $# -gt 0 ]] || die "Usage: scripts/ci-gradle-retry.sh <gradle-command> [args...]"

attempt=1
while true; do
  log_file="$(mktemp "${TMPDIR:-/tmp}/kast-ci-gradle-retry.XXXXXX")"
  set +e
  "$@" 2>&1 | tee "$log_file"
  status="${PIPESTATUS[0]}"
  set -e

  if [[ "$status" -eq 0 ]]; then
    rm -f "$log_file"
    exit 0
  fi

  if [[ "$attempt" -ge "$attempts" ]] || ! is_retryable_gradle_failure "$log_file"; then
    rm -f "$log_file"
    exit "$status"
  fi

  if is_closed_file_system_failure "$log_file" && [[ "$(basename -- "$1")" == "gradlew" ]]; then
    "$1" --stop >/dev/null 2>&1 || true
  fi

  printf 'Gradle command failed with retryable infrastructure error; retrying in %ss (%s/%s).\n' \
    "$delay_seconds" "$attempt" "$attempts" >&2
  rm -f "$log_file"
  sleep "$delay_seconds"
  attempt=$((attempt + 1))
done
