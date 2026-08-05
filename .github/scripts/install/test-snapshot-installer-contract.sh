#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'snapshot installer contract: %s\n' "$*" >&2
  exit 1
}

require() {
  local file="$1" text="$2" message="$3"
  grep -Fq -- "$text" "$file" || die "$message"
}

reject() {
  local file="$1" text="$2" message="$3"
  ! grep -Fq -- "$text" "$file" || die "$message"
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
installer="$repo_root/install.sh"
workflow="$repo_root/.github/workflows/snapshot.yml"
setup_builder="$repo_root/.github/scripts/release/actions/build-setup-bundle/action.yml"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-snapshot-installer-contract.XXXXXX")"
cleanup() {
  find "$scratch" -depth -delete
}
trap cleanup EXIT

bundle="$scratch/kast-snapshot"
mkdir -p "$bundle/bin" "$bundle/libexec" "$scratch/bin" "$scratch/user"

cat >"$bundle/bin/kast" <<'SH'
#!/usr/bin/env bash
exit 0
SH
cat >"$bundle/libexec/kastctl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${KAST_SNAPSHOT_TEST_SETUP_LOG:?}"
SH
chmod 755 "$bundle/bin/kast" "$bundle/libexec/kastctl"
tar -czf "$scratch/kast-snapshot.tar.gz" -C "$scratch" "$(basename -- "$bundle")"

cat >"$scratch/bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${KAST_SNAPSHOT_TEST_CURL_LOG:?}"
output=""
url=""
while (($# > 0)); do
  case "$1" in
    -o|--output)
      [[ $# -ge 2 ]]
      output="$2"
      shift 2
      ;;
    *) url="$1"; shift ;;
  esac
done
if [[ -n "$output" ]]; then
  cp "${KAST_SNAPSHOT_TEST_ARCHIVE:?}" "$output"
else
  [[ "$url" == "${KAST_SNAPSHOT_TEST_API_URL:?}" ]]
  printf '[\n  {"tag_name":"%s","prerelease":true}' "${KAST_SNAPSHOT_TEST_TAG:?}"
  for ((index = 0; index < 8192; index++)); do
    printf ',\n  {"ignored_release":"%080d"}' "$index"
  done
  printf '\n]\n'
  printf '%s\n' drained >"${KAST_SNAPSHOT_TEST_API_DRAIN_LOG:?}"
fi
SH
chmod 755 "$scratch/bin/curl"

case "$(uname -s):$(uname -m)" in
  Darwin:x86_64) platform=macos-x64 ;;
  Darwin:arm64|Darwin:aarch64) platform=macos-arm64 ;;
  Linux:x86_64|Linux:amd64) platform=linux-x64 ;;
  Linux:arm64|Linux:aarch64) platform=linux-arm64 ;;
  *) die "test host platform is unsupported" ;;
esac

run_installer() {
  : >"$scratch/curl.log"
  : >"$scratch/setup.log"
  : >"$scratch/api-drain.log"
  HOME="$scratch/user" \
    PATH="$scratch/bin:$PATH" \
    KAST_HOME="$scratch/kast-home" \
    KAST_RELEASES_URL="https://downloads.example.invalid/releases" \
    KAST_RELEASES_API_URL="https://api.example.invalid/releases" \
    KAST_SNAPSHOT_TEST_ARCHIVE="$scratch/kast-snapshot.tar.gz" \
    KAST_SNAPSHOT_TEST_API_URL="https://api.example.invalid/releases" \
    KAST_SNAPSHOT_TEST_API_DRAIN_LOG="$scratch/api-drain.log" \
    KAST_SNAPSHOT_TEST_TAG="snapshot-ce211e2a805f" \
    KAST_SNAPSHOT_TEST_CURL_LOG="$scratch/curl.log" \
    KAST_SNAPSHOT_TEST_SETUP_LOG="$scratch/setup.log" \
    "$installer" "$@" >"$scratch/stdout" 2>"$scratch/stderr"
}

run_installer --snapshot --harness none \
  || { sed -n '1,120p' "$scratch/stderr" >&2; die "--snapshot installation failed"; }
grep -Fq -- \
  "https://api.example.invalid/releases" \
  "$scratch/curl.log" \
  || die "--snapshot did not resolve the newest published snapshot"
grep -Fq -- \
  "https://downloads.example.invalid/releases/download/snapshot-ce211e2a805f/kast-${platform}-snapshot.tar.gz" \
  "$scratch/curl.log" \
  || die "--snapshot did not download the resolved immutable snapshot asset"
grep -Fqx -- drained "$scratch/api-drain.log" \
  || die "--snapshot did not consume the complete releases response"
grep -Eq '^setup --source .*/kast-snapshot$' "$scratch/setup.log" \
  || die "--snapshot did not delegate installation to kastctl"

run_installer --version v9.8.7 --harness none \
  || { sed -n '1,120p' "$scratch/stderr" >&2; die "stable version installation regressed"; }
grep -Fq -- \
  "https://downloads.example.invalid/releases/download/v9.8.7/kast-${platform}-v9.8.7.tar.gz" \
  "$scratch/curl.log" \
  || die "stable version download contract changed"

if run_installer --snapshot --version v9.8.7 --harness none; then
  die "--snapshot must reject --version"
fi

require "$installer" '[--snapshot]' "installer usage must advertise --snapshot"
require "$installer" '--snapshot         Install the latest snapshot build.' \
  "installer help must define --snapshot"

for job in build-cli build-indexer build-setup-bundles publish-snapshot-release; do
  require "$workflow" "  ${job}:" "snapshot workflow is missing ${job}"
done
for platform_id in linux-x64 linux-arm64 macos-x64 macos-arm64; do
  require "$workflow" "asset_id: ${platform_id}" \
    "snapshot CLI matrix is missing ${platform_id}"
  require "$workflow" "- ${platform_id}" \
    "snapshot setup matrix is missing ${platform_id}"
done

require "$workflow" "github.event.workflow_run.conclusion == 'success'" \
  "snapshot publication must require successful main CI"
require "$workflow" 'ref: ${{ needs.validate.outputs.source-sha }}' \
  "snapshot builds must check out the exact successful main source"
require "$workflow" 'bash .github/scripts/install/test-snapshot-installer-contract.sh' \
  "snapshot publication must run its installer contract before building"
require "$workflow" 'uses: ./.github/scripts/release/actions/build-cli' \
  "snapshot CLI builds must reuse the retained-byte builder"
require "$workflow" 'uses: ./.github/scripts/release/actions/build-setup-bundle' \
  "snapshot setup builds must reuse the release bundle builder"
require "$workflow" 'release_tag: snapshot' \
  "snapshot build inputs must use the rolling snapshot asset name"
require "$workflow" 'version: ${{ needs.validate.outputs.snapshot-tag }}' \
  "snapshot bundles must embed the resolved snapshot version"
require "$workflow" 'snapshot_tag="snapshot-${source_sha:0:12}"' \
  "snapshot publication must derive an immutable source tag"
require "$workflow" 'gh release create "$snapshot_tag"' \
  "snapshot publication must create a source-specific prerelease"
require "$workflow" '--draft' \
  "snapshot assets must upload before immutable publication"
require "$workflow" '--draft=false' \
  "snapshot publication must make the verified draft visible"
require "$workflow" '--prerelease' \
  "snapshot release must not be presented as stable"
require "$workflow" 'gh release upload "$snapshot_tag"' \
  "snapshot publication must upload installable bundles"
require "$workflow" 'dist/kast-*-snapshot.tar.gz' \
  "snapshot publication must use installer-stable asset names"
require "$workflow" '--clobber' \
  "draft snapshot reruns must replace incomplete assets"
require "$workflow" 'isImmutable' \
  "snapshot publication must verify the immutable result"
reject "$workflow" 'git/refs/tags/snapshot' \
  "snapshot publication must not move a shared release tag"

require "$setup_builder" 'version:' \
  "setup bundle builder must accept an explicit bundle version"
require "$setup_builder" 'version="${{ inputs.version }}"' \
  "setup bundle builder must resolve its explicit version"
require "$setup_builder" '[[ -n "$version" ]] || version="$tag"' \
  "stable setup bundles must retain tag-based version behavior"
require "$setup_builder" '--version "$version"' \
  "setup bundle builder must embed the resolved bundle version"

bash -n "$installer"
printf '%s\n' 'snapshot installer contract passed'
