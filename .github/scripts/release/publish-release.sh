#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "publish-release: $*" >&2
  exit 1
}

release=""
commit=""
assets_directory=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release)
      [[ $# -ge 2 ]] || fail "--release requires a value"
      release="$2"
      shift 2
      ;;
    --commit)
      [[ $# -ge 2 ]] || fail "--commit requires a value"
      commit="$2"
      shift 2
      ;;
    --assets-directory)
      [[ $# -ge 2 ]] || fail "--assets-directory requires a value"
      assets_directory="$2"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "${release}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  fail "release must be v<major>.<minor>.<patch>"
[[ "${commit}" =~ ^[0-9a-f]{40}$ ]] || fail "commit must be one full Git identity"
[[ -d "${assets_directory}" ]] || fail "assets directory does not exist"
[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required"
repository="${GITHUB_REPOSITORY:-amichne/kast}"
version="${release#v}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "${repository_root}"
source_revision="$(
  "${repository_root}/.github/scripts/release/admit-source.sh" \
    --repository-root "${repository_root}" \
    --expected-source-revision "${commit}"
)"
[[ "${source_revision}" == "${commit}" ]] ||
  fail "source admission returned a mismatched release commit"

python3 distribution/release/release_gate.py verify \
  --source-root "${repository_root}" --assets-directory "${assets_directory}" \
  --version "${version}" --source-revision "${commit}"

# A newly published stable baseline must not appear between capture and publish.
compatibility_observation="$(mktemp "${TMPDIR:-/tmp}/kast-release-compatibility.XXXXXX")"
python3 distribution/release/compatibility.py verify \
  --candidate "${assets_directory}/kast-compatibility-v${version}.json" \
  --repository "${repository}" --receipt "${compatibility_observation}"
python3 - "${assets_directory}/kast-release-receipt-v${version}.json" "${compatibility_observation}" <<'PY'
import json
import sys
from pathlib import Path
recorded = json.loads(Path(sys.argv[1]).read_text())["dependencies"]["compatibility"]["receipt"]
current = json.loads(Path(sys.argv[2]).read_text())
if any(recorded.get(key) != current.get(key) for key in ("candidateDigest", "baseline", "comparison")):
    raise SystemExit("publish-release: compatibility baseline changed after the release proof")
PY
rm -f -- "${compatibility_observation}"

git fetch --no-tags origin main
main_commit="$(git rev-parse origin/main)"
[[ "${main_commit}" == "${commit}" ]] ||
  fail "release commit ${commit} is not the exact origin/main commit ${main_commit}"
check_observation="$(mktemp "${TMPDIR:-/tmp}/kast-release-check.XXXXXX")"
gh api "repos/${repository}/commits/${commit}/check-runs?check_name=Release%20gate&filter=latest" \
  >"${check_observation}"
python3 - "${check_observation}" "${commit}" <<'PY'
import json
import sys
from pathlib import Path
document = json.loads(Path(sys.argv[1]).read_text())
checks = [check for check in document.get("check_runs", [])
          if check.get("name") == "Release gate" and check.get("head_sha") == sys.argv[2]
          and check.get("app", {}).get("slug") == "github-actions"]
if not checks:
    raise SystemExit("publish-release: exact SHA has no authoritative Release gate check")
latest = max(checks, key=lambda check: check["id"])
if latest.get("status") != "completed" or latest.get("conclusion") != "success":
    raise SystemExit("publish-release: exact SHA Release gate must pass before publication")
PY
rm -f -- "${check_observation}"
if git ls-remote --exit-code --tags origin "refs/tags/${release}" >/dev/null 2>&1; then
  fail "tag already exists: ${release}"
fi
if gh release view "${release}" --repo "${repository}" >/dev/null 2>&1; then
  fail "release already exists: ${release}"
fi

assets_directory="$(cd "${assets_directory}" && pwd -P)"
control="${assets_directory}/kast-control-v${version}-macos-aarch64.tar.gz"
sidecar="${assets_directory}/kast-semantic-runtime-${version}-macos-aarch64.zip"
schema="${assets_directory}/kast-cli-schema-v${version}.json"
knowledge="${assets_directory}/kast-module-knowledge-v${version}.json"
receipt="${assets_directory}/kast-release-receipt-v${version}.json"
sbom="${assets_directory}/kast-sbom-v${version}.cdx.json"
compatibility="${assets_directory}/kast-compatibility-v${version}.json"
assets=(
  "${control}"
  "${control}.sha256"
  "${sidecar}"
  "${sidecar}.sha256"
  "${schema}"
  "${schema}.sha256"
  "${knowledge}"
  "${knowledge}.sha256"
  "${receipt}"
  "${receipt}.sha256"
  "${sbom}"
  "${sbom}.sha256"
  "${compatibility}"
  "${compatibility}.sha256"
)
for asset in "${assets[@]}"; do
  [[ -f "${asset}" ]] || fail "missing release asset: ${asset}"
done

upload_assets=(
  "${control}#Control - public CLI, lifecycle, schemas, broker, and wire transport"
  "${control}.sha256"
  "${sidecar}#Private semantic runtime - headless IntelliJ indexer and compiler integration"
  "${sidecar}.sha256"
  "${schema}"
  "${schema}.sha256"
  "${knowledge}"
  "${knowledge}.sha256"
  "${receipt}"
  "${receipt}.sha256"
  "${sbom}"
  "${sbom}.sha256"
  "${compatibility}"
  "${compatibility}.sha256"
)
release_notes="$(
  printf '%s\n' \
    "Kast installs one public \`kast\` command from two matched, digest-bound payloads:" \
    '' \
    '- **Control:** CLI parsing, lifecycle, schemas, broker, and typed wire transport. It contains no IntelliJ semantic implementation.' \
    '- **Private semantic runtime:** the headless indexer and compiler integration loaded with the supported local IDEA. It contains no IDEA distribution.' \
    '' \
    'The control manifest pins the semantic runtime URL, size, and SHA-256 digest; the two payloads form one versioned product.'
)"

gh release create "${release}" "${upload_assets[@]}" \
  --repo "${repository}" \
  --target "${commit}" \
  --title "Kast ${release}" \
  --generate-notes \
  --notes "${release_notes}" \
  --draft

verification_directory="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-draft.XXXXXX")"
cleanup() {
  rm -rf -- "${verification_directory}"
}
trap cleanup EXIT
gh release download "${release}" --repo "${repository}" --dir "${verification_directory}"
python3 distribution/release/verify_assets.py \
  --directory "${verification_directory}" \
  --release "${release}" \
  --source-revision "${commit}" \
  --source-root "${repository_root}" \
  --repository "${repository}"
cmp "${receipt}" "${verification_directory}/${receipt##*/}" ||
  fail "uploaded release receipt differs from the admitted proof"
python3 distribution/release/release_gate.py verify \
  --source-root "${repository_root}" --assets-directory "${verification_directory}" \
  --version "${version}" --source-revision "${commit}"
gh release edit "${release}" --repo "${repository}" --draft=false --latest
gh release view "${release}" --repo "${repository}" \
  --json tagName,targetCommitish,isDraft,publishedAt,url,assets
