#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "build-release-assets: $*" >&2
  exit 1
}

version=""
expected_source_revision=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || fail "--version requires a value"
      version="$2"
      shift 2
      ;;
    --source-revision)
      [[ $# -ge 2 ]] || fail "--source-revision requires a value"
      expected_source_revision="$2"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must be <major>.<minor>.<patch>"
[[ "${expected_source_revision}" =~ ^[0-9a-f]{40}$ ]] || fail "source revision must be one full Git identity"

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "${repository_root}"
source_revision="$(
  "${repository_root}/.github/scripts/release/admit-source.sh" \
    --repository-root "${repository_root}" \
    --expected-source-revision "${expected_source_revision}"
)"

./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  -PkastNativeBuildMetrics=true -Pversion="${version}" -PkastSourceRevision="${source_revision}" \
  productBuildGate assembleRelease generateKastModuleKnowledge --profile --info

"${repository_root}/.github/scripts/release/admit-source.sh" \
  --repository-root "${repository_root}" \
  --expected-source-revision "${source_revision}" >/dev/null

uv run --locked --script .github/scripts/release/finalize_assets.py \
  --version "$version" --source-revision "$source_revision"
printf '%s\n' "build-release-assets: built exact-source artifacts for ${source_revision}"
