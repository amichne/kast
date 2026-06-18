#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage:
  scripts/verify-devin-snapshot-build.sh --org-id <org-id> --trigger [options]
  scripts/verify-devin-snapshot-build.sh --org-id <org-id> --build-id <build-id> [options]

Trigger or poll a Devin snapshot setup build and wait for a terminal status.

Credentials:
  Set DEVIN_SERVICE_USER_TOKEN, or DEVIN_API_TOKEN as a fallback. Tokens are
  intentionally not accepted as command-line arguments so they do not leak
  through shell history or process listings.

Required permissions:
  --trigger requires ManageOrgSnapshots and polling requires ManageRepoBlueprints.

Options:
  --api-base <url>          Default: https://api.devin.ai/v3beta1
  --timeout-seconds <n>     Default: 3600
  --poll-seconds <n>        Default: 30
  --dry-run                 Print the API plan without making network calls.
  --help, -h                Show this help.
USAGE
}

require_tool() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required tool: $1"
}

require_integer() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || die "${name} must be a non-negative integer: $value"
}

url_path_segment() {
  local value="$1"
  python3 - "$value" <<'PY'
import sys
import urllib.parse

value = sys.argv[1]
if not value:
    raise SystemExit("path segment must not be empty")
if any(ord(ch) < 32 or ord(ch) == 127 for ch in value):
    raise SystemExit("path segment must not contain control characters")
print(urllib.parse.quote(value, safe=""))
PY
}

json_string_field() {
  local json_path="$1"
  local field_name="$2"
  python3 - "$json_path" "$field_name" <<'PY'
import json
import sys
from pathlib import Path

json_path = Path(sys.argv[1])
field_name = sys.argv[2]
try:
    payload = json.loads(json_path.read_text(encoding="utf-8"))
except json.JSONDecodeError as exc:
    raise SystemExit(f"invalid JSON response: {exc}") from exc
if not isinstance(payload, dict):
    raise SystemExit("JSON response must be an object")
value = payload.get(field_name)
if not isinstance(value, str) or not value:
    raise SystemExit(f"JSON response missing non-empty string field: {field_name}")
print(value)
PY
}

validate_build_status() {
  local status="$1"
  case "$status" in
    pending|running|succeeded|failed|cancelled) ;;
    *) die "Unexpected Devin snapshot build status: $status" ;;
  esac
}

truncate_file_for_error() {
  local file_path="$1"
  python3 - "$file_path" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8", errors="replace")
text = text.strip()
if len(text) > 4000:
    text = text[:4000] + "...<truncated>"
print(text if text else "<empty response body>")
PY
}

http_request() {
  local method="$1"
  local url="$2"
  local expected_status="$3"
  local body_path="$4"
  local stderr_path="$5"

  local curl_args=(
    --silent
    --show-error
    --location
    --request "$method"
    --output "$body_path"
    --write-out "%{http_code}"
    --header "Authorization: Bearer ${devin_token}"
  )

  if [[ "$method" == "POST" ]]; then
    curl_args+=(--header "Content-Type: application/json" --data '{}')
  fi

  local http_status
  if ! http_status="$(curl "${curl_args[@]}" "$url" 2>"$stderr_path")"; then
    local curl_error
    curl_error="$(truncate_file_for_error "$stderr_path")"
    die "Devin API ${method} request failed for ${url}: ${curl_error}"
  fi

  if [[ "$http_status" != "$expected_status" ]]; then
    local response_body
    response_body="$(truncate_file_for_error "$body_path")"
    die "Devin API ${method} ${url} returned HTTP ${http_status}; expected ${expected_status}: ${response_body}"
  fi
}

api_base="https://api.devin.ai/v3beta1"
build_id=""
dry_run=false
org_id=""
poll_seconds=30
timeout_seconds=3600
trigger=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --api-base)
      [[ $# -ge 2 ]] || die "Missing value for --api-base"
      api_base="$2"; shift 2 ;;
    --api-base=*)
      api_base="${1#--api-base=}"; shift ;;
    --build-id)
      [[ $# -ge 2 ]] || die "Missing value for --build-id"
      build_id="$2"; shift 2 ;;
    --build-id=*)
      build_id="${1#--build-id=}"; shift ;;
    --dry-run)
      dry_run=true; shift ;;
    --org-id)
      [[ $# -ge 2 ]] || die "Missing value for --org-id"
      org_id="$2"; shift 2 ;;
    --org-id=*)
      org_id="${1#--org-id=}"; shift ;;
    --poll-seconds)
      [[ $# -ge 2 ]] || die "Missing value for --poll-seconds"
      poll_seconds="$2"; shift 2 ;;
    --poll-seconds=*)
      poll_seconds="${1#--poll-seconds=}"; shift ;;
    --timeout-seconds)
      [[ $# -ge 2 ]] || die "Missing value for --timeout-seconds"
      timeout_seconds="$2"; shift 2 ;;
    --timeout-seconds=*)
      timeout_seconds="${1#--timeout-seconds=}"; shift ;;
    --trigger)
      trigger=true; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$org_id" ]] || { usage; die "--org-id is required"; }
if [[ "$trigger" == true && -n "$build_id" ]]; then
  die "--trigger and --build-id are mutually exclusive"
fi
if [[ "$trigger" == false && -z "$build_id" ]]; then
  usage
  die "--build-id is required unless --trigger is set"
fi

require_integer "--timeout-seconds" "$timeout_seconds"
require_integer "--poll-seconds" "$poll_seconds"
[[ "$timeout_seconds" -gt 0 ]] || die "--timeout-seconds must be greater than zero"
[[ "$poll_seconds" -gt 0 ]] || die "--poll-seconds must be greater than zero"

case "$api_base" in
  http://*|https://*) ;;
  *) die "--api-base must start with http:// or https://: $api_base" ;;
esac
[[ "$api_base" != *$'\n'* && "$api_base" != *$'\r'* ]] \
  || die "--api-base must not contain newlines"
api_base="${api_base%/}"

require_tool python3
org_id_path="$(url_path_segment "$org_id")"
builds_url="${api_base}/organizations/${org_id_path}/snapshot-setup/builds"

if [[ -n "$build_id" ]]; then
  build_id_path="$(url_path_segment "$build_id")"
  build_url="${builds_url}/${build_id_path}"
else
  build_url=""
fi

if [[ "$dry_run" == true ]]; then
  if [[ "$trigger" == true ]]; then
    printf 'Would trigger Devin snapshot build with POST %s\n' "$builds_url"
    printf 'Would poll the returned build until status is succeeded, failed, cancelled, or timeout.\n'
  else
    printf 'Would poll Devin snapshot build with GET %s\n' "$build_url"
    printf 'Would wait until status is succeeded, failed, cancelled, or timeout.\n'
  fi
  exit 0
fi

require_tool curl
devin_token="${DEVIN_SERVICE_USER_TOKEN:-${DEVIN_API_TOKEN:-}}"
[[ -n "$devin_token" ]] \
  || die "Set DEVIN_SERVICE_USER_TOKEN, or DEVIN_API_TOKEN as a fallback"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-devin-snapshot.XXXXXX")"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

if [[ "$trigger" == true ]]; then
  response_body="${tmp_dir}/trigger-response.json"
  response_stderr="${tmp_dir}/trigger-stderr.txt"
  http_request "POST" "$builds_url" "201" "$response_body" "$response_stderr"
  build_id="$(json_string_field "$response_body" "build_id")"
  initial_status="$(json_string_field "$response_body" "status")"
  validate_build_status "$initial_status"
  build_id_path="$(url_path_segment "$build_id")"
  build_url="${builds_url}/${build_id_path}"
  printf 'Triggered Devin snapshot build %s with initial status %s\n' "$build_id" "$initial_status"
fi

deadline=$(( $(date +%s) + timeout_seconds ))
while true; do
  response_body="${tmp_dir}/poll-response.json"
  response_stderr="${tmp_dir}/poll-stderr.txt"
  http_request "GET" "$build_url" "200" "$response_body" "$response_stderr"
  observed_build_id="$(json_string_field "$response_body" "build_id")"
  status="$(json_string_field "$response_body" "status")"
  validate_build_status "$status"

  if [[ "$observed_build_id" != "$build_id" ]]; then
    die "Polled build id mismatch: expected ${build_id}, got ${observed_build_id}"
  fi

  printf 'Devin snapshot build %s status: %s\n' "$build_id" "$status"
  case "$status" in
    succeeded)
      printf 'Devin snapshot build %s succeeded\n' "$build_id"
      exit 0 ;;
    failed|cancelled)
      die "Devin snapshot build ${build_id} finished with status ${status}" ;;
  esac

  now="$(date +%s)"
  if [[ "$now" -ge "$deadline" ]]; then
    die "Timed out after ${timeout_seconds}s waiting for Devin snapshot build ${build_id}; last status: ${status}"
  fi

  sleep "$poll_seconds"
done
