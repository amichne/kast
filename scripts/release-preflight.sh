#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/release-preflight.sh [--release-type major|minor|patch|beta] [--repo owner/name] [--homebrew-tap owner/name]

Check operator-side GitHub prerequisites before dispatching the Kast release
workflow. Stable releases require HOMEBREW_TAP_TOKEN so the release workflow can
update amichne/homebrew-kast without publishing a release first and failing late.
USAGE
}

release_type="patch"
repo="${KAST_RELEASE_REPO:-amichne/kast}"
homebrew_tap="${KAST_RELEASE_TAP_REPO:-amichne/homebrew-kast}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-type)
      [[ $# -ge 2 ]] || die "Missing value for --release-type"
      release_type="$2"; shift 2 ;;
    --release-type=*)
      release_type="${1#--release-type=}"; shift ;;
    --repo)
      [[ $# -ge 2 ]] || die "Missing value for --repo"
      repo="$2"; shift 2 ;;
    --repo=*)
      repo="${1#--repo=}"; shift ;;
    --homebrew-tap)
      [[ $# -ge 2 ]] || die "Missing value for --homebrew-tap"
      homebrew_tap="$2"; shift 2 ;;
    --homebrew-tap=*)
      homebrew_tap="${1#--homebrew-tap=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

case "$release_type" in
  major|minor|patch|beta) ;;
  *) die "--release-type must be one of: major, minor, patch, beta" ;;
esac

command -v gh >/dev/null 2>&1 || die "GitHub CLI is required: install gh and authenticate first"

gh auth status --hostname github.com >/dev/null
gh repo view "$repo" >/dev/null
gh workflow view release.yml --repo "$repo" >/dev/null

if [[ "$release_type" == "beta" ]]; then
  printf '%s\n' "Homebrew token is not required for beta releases"
  printf '%s\n' "Release preflight passed for beta"
  exit 0
fi

secret_names="$(gh secret list --repo "$repo" | awk '{ print $1 }')"
if ! grep -Fxq "HOMEBREW_TAP_TOKEN" <<<"$secret_names"; then
  die "Missing required GitHub secret HOMEBREW_TAP_TOKEN in ${repo}; set it with: gh secret set HOMEBREW_TAP_TOKEN --repo ${repo}"
fi

gh repo view "$homebrew_tap" >/dev/null

printf '%s\n' "Release preflight passed for ${release_type}"
