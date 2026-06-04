#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/verify-maven-central.sh --version <X.Y.Z> [--classify] [--attempts <n>] [--delay-seconds <n>]

Verify the public Kast Maven Central modules for a release version.

Modes:
  default     Wait until all public modules are present, then exit 0.
  --classify Print all, none, or partial for a single version state.
USAGE
}

version=""
classify=false
attempts=1
delay_seconds=0
base_url="${MAVEN_CENTRAL_BASE_URL:-https://repo1.maven.org/maven2}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || die "Missing value for --version"
      version="$2"; shift 2 ;;
    --version=*)
      version="${1#--version=}"; shift ;;
    --classify)
      classify=true; shift ;;
    --attempts)
      [[ $# -ge 2 ]] || die "Missing value for --attempts"
      attempts="$2"; shift 2 ;;
    --attempts=*)
      attempts="${1#--attempts=}"; shift ;;
    --delay-seconds)
      [[ $# -ge 2 ]] || die "Missing value for --delay-seconds"
      delay_seconds="$2"; shift 2 ;;
    --delay-seconds=*)
      delay_seconds="${1#--delay-seconds=}"; shift ;;
    --base-url)
      [[ $# -ge 2 ]] || die "Missing value for --base-url"
      base_url="$2"; shift 2 ;;
    --base-url=*)
      base_url="${1#--base-url=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$version" ]] || { usage; die "--version is required"; }
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9._-]+)?$ ]] || die "--version must look like X.Y.Z: $version"
[[ "$attempts" =~ ^[0-9]+$ ]] || die "--attempts must be a positive integer"
[[ "$delay_seconds" =~ ^[0-9]+$ ]] || die "--delay-seconds must be a non-negative integer"
[[ "$attempts" -ge 1 ]] || die "--attempts must be at least 1"

artifacts=(
  kast-analysis-api
  kast-analysis-server
  kast-index-store
)

url_for() {
  local artifact="$1"
  printf '%s/io/github/amichne/%s/%s/%s-%s.pom' \
    "${base_url%/}" "$artifact" "$version" "$artifact" "$version"
}

classify_once() {
  local present=0
  local artifact
  for artifact in "${artifacts[@]}"; do
    if curl -fsSI --max-time 15 "$(url_for "$artifact")" >/dev/null 2>&1; then
      present=$((present + 1))
    fi
  done

  if [[ "$present" -eq "${#artifacts[@]}" ]]; then
    printf '%s\n' all
  elif [[ "$present" -eq 0 ]]; then
    printf '%s\n' none
  else
    printf '%s\n' partial
  fi
}

last_state=""
for attempt in $(seq 1 "$attempts"); do
  last_state="$(classify_once)"
  if [[ "$classify" == true ]]; then
    printf '%s\n' "$last_state"
    [[ "$last_state" != partial ]] || exit 2
    exit 0
  fi

  if [[ "$last_state" == all ]]; then
    printf 'Verified Maven Central modules for %s\n' "$version"
    exit 0
  fi

  if [[ "$attempt" -lt "$attempts" ]]; then
    printf 'Maven Central state for %s is %s; retrying in %ss (%s/%s)\n' \
      "$version" "$last_state" "$delay_seconds" "$attempt" "$attempts" >&2
    sleep "$delay_seconds"
  fi
done

die "Maven Central modules for ${version} are not fully published; state=${last_state}"
