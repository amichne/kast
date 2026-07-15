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

require_contains() {
  local file_path="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$file_path" || die "${description}: missing '${expected}' in ${file_path}"
}

require_block_contains() {
  local file_path="$1"
  local block_start="$2"
  local block_end="$3"
  local expected="$4"
  local description="$5"
  local block
  block="$(
    awk -v block_start="$block_start" -v block_end="$block_end" '
      index($0, block_start) { in_block = 1 }
      in_block && index($0, block_end) && !index($0, block_start) { exit }
      in_block { print }
    ' "$file_path"
  )"
  [[ -n "$block" ]] || die "${description}: missing block '${block_start}' in ${file_path}"
  grep -Fq -- "$expected" <<< "$block" || die "${description}: missing '${expected}' in '${block_start}' block"
}

require_block_not_contains() {
  local file_path="$1"
  local block_start="$2"
  local block_end="$3"
  local unexpected="$4"
  local description="$5"
  local block
  block="$(
    awk -v block_start="$block_start" -v block_end="$block_end" '
      index($0, block_start) { in_block = 1 }
      in_block && index($0, block_end) && !index($0, block_start) { exit }
      in_block { print }
    ' "$file_path"
  )"
  [[ -n "$block" ]] || die "${description}: missing block '${block_start}' in ${file_path}"
  ! grep -Fq -- "$unexpected" <<< "$block" || die "${description}: found '${unexpected}' in '${block_start}' block"
}

require_block_order() {
  local file_path="$1"
  local block_start="$2"
  local block_end="$3"
  local earlier="$4"
  local later="$5"
  local description="$6"
  local block
  local earlier_line
  local later_line
  block="$(
    awk -v block_start="$block_start" -v block_end="$block_end" '
      index($0, block_start) { in_block = 1 }
      in_block && index($0, block_end) && !index($0, block_start) { exit }
      in_block { print }
    ' "$file_path"
  )"
  [[ -n "$block" ]] || die "${description}: missing block '${block_start}' in ${file_path}"
  earlier_line="$(grep -nF -- "$earlier" <<< "$block" | head -1 | cut -d: -f1)"
  later_line="$(grep -nF -- "$later" <<< "$block" | head -1 | cut -d: -f1)"
  [[ -n "$earlier_line" ]] || die "${description}: missing earlier marker '${earlier}' in '${block_start}' block"
  [[ -n "$later_line" ]] || die "${description}: missing later marker '${later}' in '${block_start}' block"
  [[ "$earlier_line" -lt "$later_line" ]] || die "${description}: '${earlier}' must appear before '${later}' in '${block_start}' block"
}

certificate_fingerprint() {
  openssl x509 -in "$1" -outform DER \
    | openssl dgst -sha256 -r \
    | awk '{ print $1 }'
}

generate_signer() {
  local directory="$1"
  local common_name="$2"
  mkdir -p "$directory"
  openssl genpkey \
    -algorithm RSA \
    -aes-256-cbc \
    -pass pass:test-only-password \
    -pkeyopt rsa_keygen_bits:2048 \
    -out "${directory}/private.pem" \
    >/dev/null 2>&1
  openssl req \
    -new \
    -x509 \
    -sha256 \
    -days 1 \
    -subj "/CN=${common_name}" \
    -key "${directory}/private.pem" \
    -passin pass:test-only-password \
    -out "${directory}/chain.crt" \
    >/dev/null 2>&1
}

write_fake_gh() {
  local path="$1"
  python3 - "$path" <<'PY'
import os
import stat
import sys
from pathlib import Path

path = Path(sys.argv[1])
path.write_text(
    """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' \"$*\" >> \"$KAST_TEST_GH_LOG\"
[[ \"${KAST_TEST_GH_VIEW_FAILURE:-0}\" != \"1\" ]] || {
  [[ \"$1 $2\" != \"release view\" ]] || exit 19
}
case \"$1 $2\" in
  \"release view\")
    if [[ -f \"$KAST_TEST_GH_REMOTE_ASSET\" ]]; then
      basename -- \"$KAST_TEST_GH_REMOTE_ASSET\"
    fi
    ;;
  \"release download\")
    destination=\"\"
    pattern=\"\"
    shift 3
    while [[ $# -gt 0 ]]; do
      case \"$1\" in
        --dir) destination=\"$2\"; shift 2 ;;
        --pattern) pattern=\"$2\"; shift 2 ;;
        *) shift ;;
      esac
    done
    [[ -f \"$KAST_TEST_GH_REMOTE_ASSET\" ]] || exit 1
    [[ \"$pattern\" == \"$(basename -- \"$KAST_TEST_GH_REMOTE_ASSET\")\" ]] || exit 1
    mkdir -p \"$destination\"
    cp \"$KAST_TEST_GH_REMOTE_ASSET\" \"$destination/$pattern\"
    ;;
  \"release upload\")
    asset=\"$4\"
    [[ ! -f \"$KAST_TEST_GH_REMOTE_ASSET\" ]] || exit 1
    cp \"$asset\" \"$KAST_TEST_GH_REMOTE_ASSET\"
    ;;
  *)
    exit 2
    ;;
esac
""",
    encoding="utf-8",
)
path.chmod(path.stat().st_mode | stat.S_IXUSR)
PY
}

repo_root="$(resolve_repo_root)"
release_workflow="${repo_root}/.github/workflows/release.yml"
ci_workflow="${repo_root}/.github/workflows/ci.yml"
idea_build_file="${repo_root}/backend-idea/build.gradle.kts"
uploader="${repo_root}/.github/scripts/upload-immutable-release-asset.sh"
artifact_verifier="${repo_root}/scripts/verify-idea-plugin-artifact.py"
release_asset_verifier="${repo_root}/scripts/verify-release-assets.sh"
repository_source="${repo_root}/packaging/jetbrains/plugin-repository.json"
repository_renderer="${repo_root}/.github/scripts/render-jetbrains-plugin-repository.py"

for path in \
  "$release_workflow" \
  "$ci_workflow" \
  "$idea_build_file" \
  "$uploader" \
  "$artifact_verifier" \
  "$release_asset_verifier" \
  "$repository_source" \
  "$repository_renderer"
do
  [[ -f "$path" ]] || die "Required IDEA plugin signing file is missing: $path"
done

[[ -x "$uploader" ]] || die "Immutable release uploader is not executable: $uploader"
[[ -x "$artifact_verifier" ]] || die "IDEA plugin artifact verifier is not executable: $artifact_verifier"
[[ -x "$repository_renderer" ]] || die "JetBrains repository renderer is not executable: $repository_renderer"

require_contains "$idea_build_file" 'providers.environmentVariable("PRIVATE_KEY")' "IDEA signing must read the private key from the environment"
require_contains "$idea_build_file" 'providers.environmentVariable("PRIVATE_KEY_PASSWORD")' "IDEA signing must read the private-key password from the environment"
require_contains "$idea_build_file" 'kast.idea.signing.certificateChainFile' "IDEA signing must use a file-backed public certificate chain"
require_contains "$idea_build_file" 'inputArchiveFile.set(signIdeaPlugin.flatMap { it.signedArchiveFile })' "Signature verification must carry the sign task dependency"

# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" 'IDEA_PLUGIN_CERTIFICATE_CHAIN: ${{ secrets.IDEA_PLUGIN_CERTIFICATE_CHAIN }}' "Release preflight must require the signing certificate"
# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" 'IDEA_PLUGIN_PRIVATE_KEY: ${{ secrets.IDEA_PLUGIN_PRIVATE_KEY }}' "Release preflight must require the signing key"
# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" 'IDEA_PLUGIN_PRIVATE_KEY_PASSWORD: ${{ secrets.IDEA_PLUGIN_PRIVATE_KEY_PASSWORD }}' "Release preflight must require the signing password"
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" "render-jetbrains-plugin-repository.py" "Release preflight must validate the typed signer owner"
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" "--require-configured" "Release preflight must reject an unconfigured signer"
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" "repos/amichne/kast/immutable-releases" "Release preflight must reject disabled GitHub Release immutability before tag creation"
# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  release-preflight:" "  bump-version:" 'GH_TOKEN: ${{ secrets.RELEASE_GITHUB_TOKEN }}' "Immutability preflight must require the admin-capable release token"
# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_not_contains "$release_workflow" "  release-preflight:" "  bump-version:" 'IDEA_PLUGIN_SIGNER_SHA256: ${{ vars.IDEA_PLUGIN_SIGNER_SHA256 }}' "Release preflight must not duplicate signer authority in a repository variable"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ":backend-idea:verifyPlugin" "Release must run JetBrains compatibility verification"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ":backend-idea:signPlugin" "Release must sign the IDEA plugin"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ":backend-idea:verifyPluginSignature" "Release must verify the IDEA plugin signature"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ":backend-idea:stageIdeaPluginSignatureVerifier" "Release must stage the JetBrains verifier used for the published bytes"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "scripts/verify-idea-plugin-artifact.py record" "Release must record signer-bound provenance"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "enrolled-signers" "Release verification must consume signer identities from the typed repository source"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "packaging/jetbrains/plugin-repository.json" "Release verification must use the checked-in signer owner"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "release-idea-signature-verifier" "Release must receipt the Marketplace verifier consumed by Pages"
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" ".github/scripts/upload-immutable-release-asset.sh" "Release must use the immutable uploader"
# shellcheck disable=SC2016 # Release shell expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" 'tag_sha="$(git rev-list -n1 "$tag")"' "Release must resolve the checked-out tag target"
# shellcheck disable=SC2016 # Release shell expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" '--signature-verifier-jar "$signature_verifier_jar"' "Published-byte verification must execute the staged JetBrains verifier"
# shellcheck disable=SC2016 # Release shell expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" '--release-tag "$tag"' "IDEA provenance must use the prepared release tag"
# shellcheck disable=SC2016 # Release shell expressions must remain literal contract strings.
require_block_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" '--release-sha "$release_sha"' "IDEA provenance must use the checked-out release commit"
require_block_order "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "      - name: Build and verify IDEA plugin" "      - name: Sign and verify IDEA plugin" "Release must verify plugin structure and compatibility before signing"
require_block_order "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "      - name: Sign and verify IDEA plugin" "      - name: Stage and upload immutable signed IDEA plugin asset" "Release must verify the signature before publishing the plugin"
require_block_not_contains "$release_workflow" "  build-idea-plugin:" "  build-headless-backend:" "--clobber" "IDEA plugin release assets must never be overwritten"
! grep -Fq -- "--clobber" "$release_workflow" \
  || die "Release workflow must not contain any mutable asset upload"
require_contains "$ci_workflow" "Test IDEA plugin signing and immutability contract" "CI must execute the signing contract gate"
require_contains "$ci_workflow" "Test JetBrains plugin repository contract" "CI must execute the feed-to-asset contract gate"
require_contains "$repository_source" '"activeSignerSha256": null' "Repository source must not invent a production signer"
require_contains "$repository_source" '"state": "unconfigured"' "Repository source must fail closed until signer enrollment"
require_contains "$repository_renderer" "rotation signer must differ" "Repository renderer must reject invalid rotation overlap"
require_contains "$release_asset_verifier" 'signerCertificateSha256' "Downloaded release verification must require signer identity"
require_contains "$release_asset_verifier" 'signatureVerified' "Downloaded release verification must require signature evidence"
require_contains "$release_asset_verifier" 'pluginId' "Downloaded release verification must require plugin identity"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-idea-signing-contract.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
  rm -f "${repo_root}/backend-idea/build/distributions/"*"0.0.0-signing-contract"*.zip
  rm -f "${repo_root}/backend-idea/build/distributions/"*"0.0.0-signing-rotation"*.zip
}
trap cleanup EXIT

mkdir -p "${scratch_dir}/bin" "${scratch_dir}/remote"
write_fake_gh "${scratch_dir}/bin/gh"
local_asset="${scratch_dir}/kast-idea-v0.0.0-test.zip"
remote_asset="${scratch_dir}/remote/kast-idea-v0.0.0-test.zip"
gh_log="${scratch_dir}/gh.log"
printf '%s\n' "first immutable payload" > "$local_asset"
: > "$gh_log"

export PATH="${scratch_dir}/bin:${PATH}"
export KAST_TEST_GH_LOG="$gh_log"
export KAST_TEST_GH_REMOTE_ASSET="$remote_asset"

"$uploader" --tag v0.0.0-test --asset "$local_asset"
cmp -s "$local_asset" "$remote_asset" || die "First immutable upload did not preserve bytes"

"$uploader" --tag v0.0.0-test --asset "$local_asset"
[[ "$(grep -c '^release upload ' "$gh_log")" -eq 1 ]] || die "Byte-identical replay uploaded the plugin twice"

printf '%s\n' "different payload" > "$local_asset"
if "$uploader" --tag v0.0.0-test --asset "$local_asset" >"${scratch_dir}/mismatch.out" 2>"${scratch_dir}/mismatch.err"; then
  die "Different immutable plugin bytes unexpectedly replaced the release asset"
fi
grep -Fq "differs from immutable release asset" "${scratch_dir}/mismatch.err" \
  || die "Immutable mismatch did not name the byte-identity failure"

rm -f "$remote_asset"
export KAST_TEST_GH_VIEW_FAILURE=1
if "$uploader" --tag v0.0.0-test --asset "$local_asset" >"${scratch_dir}/view.out" 2>"${scratch_dir}/view.err"; then
  die "Release lookup failure unexpectedly fell through to upload"
fi
unset KAST_TEST_GH_VIEW_FAILURE
[[ "$(grep -c '^release upload ' "$gh_log")" -eq 1 ]] || die "Release lookup failure attempted an upload"
! grep -Fq -- "--clobber" "$gh_log" || die "Immutable uploader invoked --clobber"

generate_signer "${scratch_dir}/signer-a" "Kast Contract Signer A"
generate_signer "${scratch_dir}/signer-b" "Kast Contract Signer B"
fingerprint_a="$(certificate_fingerprint "${scratch_dir}/signer-a/chain.crt")"
fingerprint_b="$(certificate_fingerprint "${scratch_dir}/signer-b/chain.crt")"
version="0.0.0-signing-contract"

rm -f "${repo_root}/backend-idea/build/distributions/"*"${version}"*.zip
"${repo_root}/scripts/ci-gradle-retry.sh" \
  "${repo_root}/gradlew" \
  :backend-idea:buildPlugin \
  :backend-idea:verifyPluginStructure \
  :backend-idea:verifyPluginXmlPresent \
  -Pversion="$version" \
  --no-daemon
PRIVATE_KEY="$(base64 < "${scratch_dir}/signer-a/private.pem" | tr -d '\n')" \
PRIVATE_KEY_PASSWORD="test-only-password" \
  "${repo_root}/scripts/ci-gradle-retry.sh" \
  "${repo_root}/gradlew" \
  :backend-idea:signPlugin \
  :backend-idea:verifyPluginSignature \
  :backend-idea:stageIdeaPluginSignatureVerifier \
  -Pkast.idea.signing.certificateChainFile="${scratch_dir}/signer-a/chain.crt" \
  -Pversion="$version" \
  --no-daemon

mapfile -t signed_archives < <(find "${repo_root}/backend-idea/build/distributions" -maxdepth 1 -type f -name "*${version}-signed.zip" -print | sort)
[[ "${#signed_archives[@]}" -eq 1 ]] || die "Expected one signed IDEA plugin archive, found ${#signed_archives[@]}"
signed_archive="${signed_archives[0]}"
signed_release_archive="${scratch_dir}/kast-idea-v0.0.0-signing-contract.zip"
cp "$signed_archive" "$signed_release_archive"
provenance="${scratch_dir}/build-provenance-idea.json"
signature_verifier_jar="${repo_root}/backend-idea/build/idea-plugin-signature-verifier/marketplace-zip-signer-cli.jar"
[[ -f "$signature_verifier_jar" ]] || die "Staged Marketplace ZIP Signer is missing: $signature_verifier_jar"
release_tag="v0.0.0-signing-contract"
release_sha="0123456789abcdef0123456789abcdef01234567"

GITHUB_RUN_ID=123 \
GITHUB_RUN_NUMBER=7 \
GITHUB_RUN_ATTEMPT=1 \
GITHUB_WORKFLOW_REF=amichne/kast/.github/workflows/release.yml@refs/tags/v0.0.0-signing-contract \
GITHUB_ACTOR=contract-test \
  "$artifact_verifier" record \
  --plugin-zip "$signed_release_archive" \
  --certificate-chain "${scratch_dir}/signer-a/chain.crt" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --release-tag "$release_tag" \
  --release-sha "$release_sha" \
  --output "$provenance" \
  --asset-name "kast-idea-v0.0.0-signing-contract.zip" \
  --expected-signer-sha256 "$fingerprint_a"

"$artifact_verifier" verify \
  --plugin-zip "$signed_release_archive" \
  --certificate-chain "${scratch_dir}/signer-a/chain.crt" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --release-tag "$release_tag" \
  --release-sha "$release_sha" \
  --provenance "$provenance" \
  --expected-signer-sha256 "$fingerprint_a"

configured_repository_source="${scratch_dir}/plugin-repository.json"
python3 - "$repository_source" "$configured_repository_source" "$fingerprint_a" <<'PY'
import json
import sys
from pathlib import Path


source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["signing"] = {
    "activeSignerSha256": sys.argv[3],
    "rotation": {"state": "stable", "nextSignerSha256": None},
}
Path(sys.argv[2]).write_text(json.dumps(source, indent=2) + "\n", encoding="utf-8")
PY
"$repository_renderer" render \
  --source "$configured_repository_source" \
  --plugin-zip "$signed_release_archive" \
  --provenance "$provenance" \
  --certificate-chain "${scratch_dir}/signer-a/chain.crt" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --output-directory "${scratch_dir}/signed-repository"
[[ -f "${scratch_dir}/signed-repository/updatePlugins.xml" ]] \
  || die "Cryptographically verified signed plugin did not render a repository feed"

unsigned_archive="${scratch_dir}/kast-idea-v0.0.0-unsigned.zip"
cp "${repo_root}/backend-idea/build/distributions/backend-idea-${version}.zip" "$unsigned_archive"
if GITHUB_RUN_ID=123 \
  GITHUB_RUN_NUMBER=7 \
  GITHUB_RUN_ATTEMPT=1 \
  GITHUB_WORKFLOW_REF=amichne/kast/.github/workflows/release.yml@refs/tags/v0.0.0-signing-contract \
  GITHUB_ACTOR=contract-test \
  "$artifact_verifier" record \
    --plugin-zip "$unsigned_archive" \
    --certificate-chain "${scratch_dir}/signer-a/chain.crt" \
    --signature-verifier-jar "$signature_verifier_jar" \
    --release-tag "$release_tag" \
    --release-sha "$release_sha" \
    --output "${scratch_dir}/unsigned-provenance.json" \
    --asset-name "$(basename -- "$unsigned_archive")" \
    --expected-signer-sha256 "$fingerprint_a" \
    >"${scratch_dir}/unsigned.out" 2>"${scratch_dir}/unsigned.err"
then
  die "Unsigned IDEA plugin archive unexpectedly produced signed provenance"
fi
grep -Fq "Marketplace ZIP Signer rejected" "${scratch_dir}/unsigned.err" \
  || die "Unsigned archive failure did not name signature rejection"

if "$artifact_verifier" verify \
  --plugin-zip "$signed_release_archive" \
  --certificate-chain "${scratch_dir}/signer-b/chain.crt" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --release-tag "$release_tag" \
  --release-sha "$release_sha" \
  --provenance "$provenance" \
  --expected-signer-sha256 "$fingerprint_a" \
  --expected-signer-sha256 "$fingerprint_b" \
  >"${scratch_dir}/wrong-certificate.out" 2>"${scratch_dir}/wrong-certificate.err"
then
  die "Plugin signed by signer A unexpectedly verified against signer B"
fi
grep -Fq "Marketplace ZIP Signer rejected" "${scratch_dir}/wrong-certificate.err" \
  || die "Wrong-certificate failure did not name signature rejection"

if "$artifact_verifier" verify \
  --plugin-zip "$signed_release_archive" \
  --certificate-chain "${scratch_dir}/signer-a/chain.crt" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --release-tag "$release_tag" \
  --release-sha "1123456789abcdef0123456789abcdef01234567" \
  --provenance "$provenance" \
  --expected-signer-sha256 "$fingerprint_a" \
  >"${scratch_dir}/wrong-release.out" 2>"${scratch_dir}/wrong-release.err"
then
  die "Plugin provenance unexpectedly verified against a different release commit"
fi
grep -Fq "does not match the checked-out release tag and commit" "${scratch_dir}/wrong-release.err" \
  || die "Wrong-release failure did not name release identity mismatch"

rotation_version="0.0.0-signing-rotation"
PRIVATE_KEY="$(base64 < "${scratch_dir}/signer-b/private.pem" | tr -d '\n')" \
PRIVATE_KEY_PASSWORD="test-only-password" \
  "${repo_root}/scripts/ci-gradle-retry.sh" \
  "${repo_root}/gradlew" \
  :backend-idea:signPlugin \
  :backend-idea:verifyPluginSignature \
  :backend-idea:stageIdeaPluginSignatureVerifier \
  -Pkast.idea.signing.certificateChainFile="${scratch_dir}/signer-b/chain.crt" \
  -Pversion="$rotation_version" \
  --no-daemon

mapfile -t rotation_archives < <(find "${repo_root}/backend-idea/build/distributions" -maxdepth 1 -type f -name "*${rotation_version}-signed.zip" -print | sort)
[[ "${#rotation_archives[@]}" -eq 1 ]] || die "Expected one rotation-signed IDEA plugin archive, found ${#rotation_archives[@]}"
rotation_release_archive="${scratch_dir}/kast-idea-v0.0.0-signing-rotation.zip"
cp "${rotation_archives[0]}" "$rotation_release_archive"
rotation_provenance="${scratch_dir}/build-provenance-idea-rotation.json"

GITHUB_RUN_ID=124 \
GITHUB_RUN_NUMBER=8 \
GITHUB_RUN_ATTEMPT=1 \
GITHUB_WORKFLOW_REF=amichne/kast/.github/workflows/release.yml@refs/tags/v0.0.0-signing-rotation \
GITHUB_ACTOR=contract-test \
  "$artifact_verifier" record \
  --plugin-zip "$rotation_release_archive" \
  --certificate-chain "${scratch_dir}/signer-b/chain.crt" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --release-tag "v0.0.0-signing-rotation" \
  --release-sha "$release_sha" \
  --output "$rotation_provenance" \
  --asset-name "kast-idea-v0.0.0-signing-rotation.zip" \
  --expected-signer-sha256 "$fingerprint_a" \
  --expected-signer-sha256 "$fingerprint_b"

"$artifact_verifier" verify \
  --plugin-zip "$rotation_release_archive" \
  --certificate-chain "${scratch_dir}/signer-b/chain.crt" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --release-tag "v0.0.0-signing-rotation" \
  --release-sha "$release_sha" \
  --provenance "$rotation_provenance" \
  --expected-signer-sha256 "$fingerprint_a" \
  --expected-signer-sha256 "$fingerprint_b"

if "$artifact_verifier" verify \
  --plugin-zip "$rotation_release_archive" \
  --certificate-chain "${scratch_dir}/signer-b/chain.crt" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --release-tag "v0.0.0-signing-rotation" \
  --release-sha "$release_sha" \
  --provenance "$rotation_provenance" \
  --expected-signer-sha256 "$fingerprint_a" \
  >"${scratch_dir}/rotation.out" 2>"${scratch_dir}/rotation.err"
then
  die "Unenrolled replacement signer unexpectedly verified"
fi
grep -Fq "signer certificate is not enrolled" "${scratch_dir}/rotation.err" \
  || die "Unenrolled signer failure did not name trust enrollment"

if "$artifact_verifier" verify \
  --plugin-zip "$signed_release_archive" \
  --certificate-chain "${scratch_dir}/signer-a/chain.crt" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --release-tag "$release_tag" \
  --release-sha "$release_sha" \
  --provenance "$provenance" \
  --expected-signer-sha256 invalid \
  >"${scratch_dir}/invalid.out" 2>"${scratch_dir}/invalid.err"
then
  die "Invalid enrolled signer fingerprint unexpectedly verified"
fi
grep -Fq "expected signer fingerprint must be 64 lowercase hexadecimal characters" "${scratch_dir}/invalid.err" \
  || die "Invalid signer fingerprint failure did not name the typed format"

printf '%s\n' "IDEA plugin signing and immutability contract passed (${fingerprint_a}, overlap fixture ${fingerprint_b})"
