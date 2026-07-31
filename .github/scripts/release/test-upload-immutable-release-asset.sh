#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
uploader="$repo_root/.github/scripts/release/upload-immutable-release-asset.sh"
[[ -x "$uploader" ]] || die "Missing release asset uploader: $uploader"
command -v jq >/dev/null 2>&1 || die "jq is required"

scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-upload-test.XXXXXX")"
cleanup() {
  rm -rf "$scratch"
}
trap cleanup EXIT

fake_bin="$scratch/bin"
mkdir -p "$fake_bin"
cat >"$fake_bin/sleep" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x "$fake_bin/sleep"

cat >"$fake_bin/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

increment() {
  local path="$1" value=0
  [[ ! -f "$path" ]] || value="$(<"$path")"
  value=$((value + 1))
  printf '%s\n' "$value" >"$path"
  printf '%s\n' "$value"
}

read_state() {
  asset_state=""
  asset_digest=""
  asset_id=""
  asset_size=""
  [[ ! -s "$FAKE_GH_STATE" ]] \
    || IFS='|' read -r asset_state asset_digest asset_id asset_size <"$FAKE_GH_STATE"
}

asset_json() {
  read_state
  [[ -n "$asset_state" ]] || return 1
  if [[ "$asset_digest" == "null" ]]; then
    jq -cn \
      --arg api_url "https://api.github.com/repos/example/kast/releases/assets/${asset_id}" \
      --arg name "$FAKE_GH_ASSET_NAME" \
      --arg state "$asset_state" \
      --argjson id "$asset_id" \
      --argjson size "${asset_size:-0}" \
      '{apiUrl:$api_url,url:$api_url,digest:null,id:$id,name:$name,size:$size,state:$state}'
  else
    jq -cn \
      --arg api_url "https://api.github.com/repos/example/kast/releases/assets/${asset_id}" \
      --arg digest "$asset_digest" \
      --arg name "$FAKE_GH_ASSET_NAME" \
      --arg state "$asset_state" \
      --argjson id "$asset_id" \
      --argjson size "${asset_size:-1}" \
      '{apiUrl:$api_url,url:$api_url,digest:$digest,id:$id,name:$name,size:$size,state:$state}'
  fi
}

apply_jq() {
  local payload="$1" expression="$2"
  if [[ -n "$expression" ]]; then
    jq -r "$expression" <<<"$payload"
  else
    printf '%s\n' "$payload"
  fi
}

printf '%s\n' "$*" >>"$FAKE_GH_LOG"

if [[ "${1:-} ${2:-}" == "release view" ]]; then
  inspect_count="$(increment "$FAKE_GH_INSPECT_COUNT")"
  if [[ "$FAKE_GH_SCENARIO" == "inspect-retry" && "$inspect_count" -eq 1 ]]; then
    exit 88
  fi
  if [[ "$FAKE_GH_SCENARIO" == "inspect-timeout" ]]; then
    /bin/sleep 5
    exit 1
  fi
  [[ "$FAKE_GH_SCENARIO" != "inspect-fail" ]] || exit 88
  shift 2
  expression=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --jq|-q)
        expression="$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done
  if asset="$(asset_json)"; then
    payload="$(jq -cn --argjson asset "$asset" '{assets:[$asset]}')"
  else
    payload='{"assets":[]}'
  fi
  apply_jq "$payload" "$expression"
  exit 0
fi

if [[ "${1:-} ${2:-}" == "release download" ]]; then
  increment "$FAKE_GH_DOWNLOAD_COUNT" >/dev/null
  read_state
  [[ "$asset_state" == "uploaded" ]] || exit 1
  shift 2
  destination=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dir)
        destination="$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done
  [[ -n "$destination" ]] || exit 2
  mkdir -p "$destination"
  cp "$FAKE_GH_REMOTE_ASSET" "$destination/$FAKE_GH_ASSET_NAME"
  exit 0
fi

if [[ "${1:-} ${2:-}" == "release upload" ]]; then
  [[ " $* " != *" --clobber "* ]] || exit 96
  upload_count="$(increment "$FAKE_GH_UPLOAD_COUNT")"
  case "$FAKE_GH_SCENARIO" in
    starter-retry)
      if [[ "$upload_count" -eq 1 ]]; then
        printf 'starter|null|101|0\n' >"$FAKE_GH_STATE"
        exit 1
      fi
      cp "$4" "$FAKE_GH_REMOTE_ASSET"
      printf 'uploaded|sha256:%s|102|1\n' "$FAKE_GH_LOCAL_DIGEST" >"$FAKE_GH_STATE"
      exit 0
      ;;
    starter-becomes-uploaded)
      cp "$4" "$FAKE_GH_REMOTE_ASSET"
      printf 'starter|null|103|0\n' >"$FAKE_GH_STATE"
      exit 1
      ;;
    timeout)
      /bin/sleep 5
      exit 1
      ;;
    always-fail)
      printf 'starter|null|%s|0\n' "$((100 + upload_count))" >"$FAKE_GH_STATE"
      exit 1
      ;;
    inspect-retry)
      cp "$4" "$FAKE_GH_REMOTE_ASSET"
      printf 'uploaded|sha256:%s|104|1\n' "$FAKE_GH_LOCAL_DIGEST" >"$FAKE_GH_STATE"
      exit 0
      ;;
    *)
      exit 97
      ;;
  esac
fi

if [[ "${1:-}" == "api" ]]; then
  shift
  method="GET"
  expression=""
  endpoint=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --method|-X)
        method="$2"
        shift 2
        ;;
      --jq|-q)
        expression="$2"
        shift 2
        ;;
      --silent)
        shift
        ;;
      *)
        endpoint="$1"
        shift
        ;;
    esac
  done
  read_state
  if [[ "$method" == "DELETE" ]]; then
    [[ "$asset_state" == "starter" && "$asset_digest" == "null" ]] || exit 98
    [[ "$endpoint" == *"/releases/assets/${asset_id}" ]] || exit 99
    increment "$FAKE_GH_DELETE_COUNT" >/dev/null
    rm -f "$FAKE_GH_STATE"
    exit 0
  fi
  if [[ "$FAKE_GH_SCENARIO" == "starter-becomes-uploaded" ]]; then
    api_get_count="$(increment "$FAKE_GH_API_GET_COUNT")"
    if [[ "$api_get_count" -eq 1 ]]; then
      asset="$(asset_json)" || exit 1
      printf 'uploaded|sha256:%s|103|1\n' "$FAKE_GH_LOCAL_DIGEST" >"$FAKE_GH_STATE"
      apply_jq "$asset" "$expression"
      exit 0
    fi
  fi
  asset="$(asset_json)" || exit 1
  apply_jq "$asset" "$expression"
  exit 0
fi

exit 100
SH
chmod +x "$fake_bin/gh"

asset="$scratch/asset.bin"
remote_asset="$scratch/remote.bin"
state_file="$scratch/state"
log_file="$scratch/gh.log"
upload_count_file="$scratch/upload-count"
download_count_file="$scratch/download-count"
delete_count_file="$scratch/delete-count"
api_get_count_file="$scratch/api-get-count"
inspect_count_file="$scratch/inspect-count"
printf '%s\n' "release bytes" >"$asset"
local_digest="$(openssl dgst -sha256 -r "$asset" | awk '{ print $1 }')"
asset_name="$(basename -- "$asset")"
failures=0

counter() {
  local path="$1"
  if [[ -f "$path" ]]; then
    cat "$path"
  else
    printf '0\n'
  fi
}

reset_case() {
  rm -f \
    "$state_file" \
    "$remote_asset" \
    "$upload_count_file" \
    "$download_count_file" \
    "$delete_count_file" \
    "$api_get_count_file" \
    "$inspect_count_file"
  : >"$log_file"
}

run_uploader() {
  local scenario="$1" output="$2" attempts="${3:-3}"
  local timeout_seconds="${4:-300}" metadata_timeout_seconds="${5:-30}"
  set +e
  PATH="$fake_bin:$PATH" \
    FAKE_GH_ASSET_NAME="$asset_name" \
    FAKE_GH_API_GET_COUNT="$api_get_count_file" \
    FAKE_GH_DELETE_COUNT="$delete_count_file" \
    FAKE_GH_DOWNLOAD_COUNT="$download_count_file" \
    FAKE_GH_INSPECT_COUNT="$inspect_count_file" \
    FAKE_GH_LOCAL_DIGEST="$local_digest" \
    FAKE_GH_LOG="$log_file" \
    FAKE_GH_REMOTE_ASSET="$remote_asset" \
    FAKE_GH_SCENARIO="$scenario" \
    FAKE_GH_STATE="$state_file" \
    FAKE_GH_UPLOAD_COUNT="$upload_count_file" \
    KAST_RELEASE_METADATA_TIMEOUT_SECONDS="$metadata_timeout_seconds" \
    KAST_RELEASE_UPLOAD_ATTEMPTS="$attempts" \
    KAST_RELEASE_UPLOAD_SETTLE_ATTEMPTS=3 \
    KAST_RELEASE_UPLOAD_TIMEOUT_SECONDS="$timeout_seconds" \
    "$uploader" --tag v1.2.3 --asset "$asset" >"$output" 2>&1
  uploader_status="$?"
  set -e
}

expect_equal() {
  local expected="$1" actual="$2" message="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf 'error: %s: expected %s, got %s\n' "$message" "$expected" "$actual" >&2
    failures=$((failures + 1))
  fi
}

reset_case
run_uploader starter-retry "$scratch/starter-retry.out"
expect_equal 0 "$uploader_status" "interrupted upload recovery status"
expect_equal 2 "$(counter "$upload_count_file")" "interrupted upload attempts"
expect_equal 1 "$(counter "$delete_count_file")" "incomplete starter cleanup"

reset_case
cp "$asset" "$remote_asset"
printf 'uploaded|sha256:%s|201|1\n' "$local_digest" >"$state_file"
run_uploader forbid-upload "$scratch/reuse.out"
expect_equal 0 "$uploader_status" "matching immutable asset status"
expect_equal 0 "$(counter "$download_count_file")" "matching digest downloads"
expect_equal 0 "$(counter "$upload_count_file")" "matching digest uploads"
expect_equal 0 "$(counter "$delete_count_file")" "matching digest deletions"

reset_case
printf '%s\n' "different bytes" >"$remote_asset"
remote_digest="$(openssl dgst -sha256 -r "$remote_asset" | awk '{ print $1 }')"
printf 'uploaded|sha256:%s|301|1\n' "$remote_digest" >"$state_file"
run_uploader forbid-upload "$scratch/mismatch.out"
[[ "$uploader_status" -ne 0 ]] || {
  printf '%s\n' 'error: mismatched immutable asset unexpectedly succeeded' >&2
  failures=$((failures + 1))
}
expect_equal 0 "$(counter "$upload_count_file")" "mismatch uploads"
expect_equal 0 "$(counter "$delete_count_file")" "mismatch deletions"

reset_case
run_uploader always-fail "$scratch/bounded.out"
[[ "$uploader_status" -ne 0 ]] || {
  printf '%s\n' 'error: exhausted upload attempts unexpectedly succeeded' >&2
  failures=$((failures + 1))
}
expect_equal 3 "$(counter "$upload_count_file")" "bounded upload attempts"
expect_equal 3 "$(counter "$delete_count_file")" "bounded starter cleanup"

reset_case
cp "$asset" "$remote_asset"
printf 'uploaded|null|401|1\n' >"$state_file"
run_uploader forbid-upload "$scratch/no-digest.out"
expect_equal 0 "$uploader_status" "uploaded asset without API digest status"
expect_equal 1 "$(counter "$download_count_file")" "uploaded asset fallback download"
expect_equal 0 "$(counter "$upload_count_file")" "uploaded asset fallback uploads"
expect_equal 0 "$(counter "$delete_count_file")" "uploaded asset fallback deletions"

reset_case
printf 'starter|null|501|4\n' >"$state_file"
run_uploader forbid-upload "$scratch/nonempty-starter.out"
[[ "$uploader_status" -ne 0 ]] || {
  printf '%s\n' 'error: non-empty starter asset unexpectedly succeeded' >&2
  failures=$((failures + 1))
}
expect_equal 0 "$(counter "$upload_count_file")" "non-empty starter uploads"
expect_equal 0 "$(counter "$delete_count_file")" "non-empty starter deletions"

reset_case
run_uploader starter-becomes-uploaded "$scratch/starter-transition.out"
expect_equal 0 "$uploader_status" "starter transition status"
expect_equal 1 "$(counter "$upload_count_file")" "starter transition uploads"
expect_equal 0 "$(counter "$delete_count_file")" "starter transition deletions"

reset_case
run_uploader inspect-retry "$scratch/inspect-retry.out"
expect_equal 0 "$uploader_status" "transient release inspection status"
expect_equal 3 "$(counter "$inspect_count_file")" "transient release inspection attempts"
expect_equal 1 "$(counter "$upload_count_file")" "transient release inspection uploads"

reset_case
run_uploader inspect-fail "$scratch/inspect-fail.out"
[[ "$uploader_status" -ne 0 ]] || {
  printf '%s\n' 'error: failed release inspection unexpectedly succeeded' >&2
  failures=$((failures + 1))
}
expect_equal 0 "$(counter "$upload_count_file")" "inspection failure uploads"
expect_equal 0 "$(counter "$delete_count_file")" "inspection failure deletions"

reset_case
run_uploader inspect-timeout "$scratch/inspect-timeout.out" 1 1 1
[[ "$uploader_status" -ne 0 ]] || {
  printf '%s\n' 'error: timed-out release inspection unexpectedly succeeded' >&2
  failures=$((failures + 1))
}
expect_equal 0 "$(counter "$upload_count_file")" "inspection timeout uploads"
expect_equal 0 "$(counter "$delete_count_file")" "inspection timeout deletions"

reset_case
run_uploader timeout "$scratch/timeout.out" 1 1
[[ "$uploader_status" -ne 0 ]] || {
  printf '%s\n' 'error: timed-out release upload unexpectedly succeeded' >&2
  failures=$((failures + 1))
}
expect_equal 1 "$(counter "$upload_count_file")" "timed-out upload attempts"
expect_equal 0 "$(counter "$delete_count_file")" "timed-out upload deletions"

[[ "$failures" -eq 0 ]] || die "${failures} immutable release upload contract checks failed"
printf '%s\n' "immutable release upload recovery contract passed"
