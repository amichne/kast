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
  grep -Fq -- "$expected" "$file_path" \
    || die "${description}: missing '${expected}' in ${file_path}"
}

require_block_contains() {
  local file_path="$1"
  local block_start="$2"
  local block_end="$3"
  local expected="$4"
  local description="$5"
  local block
  block="$({
    awk -v block_start="$block_start" -v block_end="$block_end" '
      index($0, block_start) { in_block = 1 }
      in_block && index($0, block_end) && !index($0, block_start) { exit }
      in_block { print }
    ' "$file_path"
  })"
  [[ -n "$block" ]] || die "${description}: missing block '${block_start}' in ${file_path}"
  grep -Fq -- "$expected" <<< "$block" \
    || die "${description}: missing '${expected}' in '${block_start}' block"
}

require_order() {
  local file_path="$1"
  local earlier="$2"
  local later="$3"
  local description="$4"
  local earlier_line
  local later_line
  earlier_line="$(grep -nF -- "$earlier" "$file_path" | head -1 | cut -d: -f1)"
  later_line="$(grep -nF -- "$later" "$file_path" | head -1 | cut -d: -f1)"
  [[ -n "$earlier_line" ]] || die "${description}: missing earlier marker '${earlier}'"
  [[ -n "$later_line" ]] || die "${description}: missing later marker '${later}'"
  [[ "$earlier_line" -lt "$later_line" ]] \
    || die "${description}: '${earlier}' must appear before '${later}'"
}

expect_failure() {
  local label="$1"
  shift
  if "$@" >"${scratch_dir}/${label}.out" 2>"${scratch_dir}/${label}.err"; then
    die "${label} unexpectedly succeeded"
  fi
  [[ -s "${scratch_dir}/${label}.err" ]] \
    || die "${label} did not explain its failure on stderr"
}

repo_root="$(resolve_repo_root)"
renderer="${repo_root}/.github/scripts/render-jetbrains-plugin-repository.py"
materializer="${repo_root}/.github/scripts/materialize-jetbrains-plugin-repository-pages.sh"
source_file="${repo_root}/packaging/jetbrains/plugin-repository.json"
packaging_guide="${repo_root}/packaging/jetbrains/AGENTS.md"
github_guide="${repo_root}/.github/AGENTS.md"
release_workflow="${repo_root}/.github/workflows/release.yml"
docs_workflow="${repo_root}/.github/workflows/docs.yml"

for path in \
  "$renderer" \
  "$materializer" \
  "$source_file" \
  "$packaging_guide" \
  "$github_guide" \
  "$release_workflow" \
  "$docs_workflow"
do
  [[ -f "$path" ]] || die "Required JetBrains repository contract file is missing: $path"
done

[[ -x "$renderer" ]] || die "JetBrains repository renderer is not executable: $renderer"
[[ -x "$materializer" ]] || die "JetBrains Pages materializer is not executable: $materializer"

scratch_dir="$(mktemp -d)"
trap 'rm -rf "$scratch_dir"' EXIT

python3 - "$scratch_dir" <<'PY'
from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from copy import deepcopy
from pathlib import Path


root = Path(sys.argv[1])
plugin_id = "io.github.amichne.kast"
tag = "v1.2.3"
version = "1.2.3"
asset_name = f"kast-idea-{tag}.zip"
signer = "a" * 64
release_sha = "1" * 40


def write_plugin(path: Path, *, plugin_version: str = version) -> None:
    descriptor = f"""<?xml version="1.0" encoding="UTF-8"?>
<idea-plugin>
  <id>{plugin_id}</id>
  <name>Kast</name>
  <version>{plugin_version}</version>
  <idea-version since-build="253"/>
</idea-plugin>
"""
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/plugin.xml", descriptor)


plugin_zip = root / asset_name
write_plugin(plugin_zip)
write_plugin(root / "wrong-version.zip", plugin_version="1.2.4")
digest = hashlib.sha256(plugin_zip.read_bytes()).hexdigest()

source = {
    "schemaVersion": 1,
    "repository": {
        "feedUrl": "https://kast.michne.com/jetbrains/updatePlugins.xml",
        "pluginId": plugin_id,
        "releaseAssetUrlTemplate": (
            "https://github.com/amichne/kast/releases/download/{tag}/kast-idea-{tag}.zip"
        ),
    },
    "signing": {
        "activeSignerSha256": signer,
        "rotation": {
            "state": "stable",
            "nextSignerSha256": None,
        },
    },
}
provenance = {
    "sha": release_sha,
    "ref": f"refs/tags/{tag}",
    "platformId": "idea",
    "assetName": asset_name,
    "assetDigest": f"sha256:{digest}",
    "pluginId": plugin_id,
    "signerCertificateSha256": signer,
    "signatureVerified": True,
    "verificationTasks": [
        ":backend-idea:verifyPluginStructure",
        ":backend-idea:verifyPluginXmlPresent",
        ":backend-idea:verifyPlugin",
        ":backend-idea:verifyPluginSignature",
    ],
}


def dump(name: str, payload: object) -> None:
    (root / name).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


dump("source.json", source)
dump("provenance.json", provenance)
dump("build-provenance.json", {"builds": [provenance]})

bad_order = {
    "builds": [
        {"platformId": "z-last"},
        provenance,
    ]
}
dump("bad-order.json", bad_order)

bad_digest = deepcopy(provenance)
bad_digest["assetDigest"] = f"sha256:{'0' * 64}"
dump("bad-digest.json", bad_digest)

bad_signer = deepcopy(provenance)
bad_signer["signerCertificateSha256"] = "b" * 64
dump("bad-signer.json", bad_signer)

bad_tag = deepcopy(provenance)
bad_tag["ref"] = "refs/tags/v1.2.4"
dump("bad-version.json", bad_tag)

bad_url = deepcopy(source)
bad_url["repository"]["releaseAssetUrlTemplate"] = (
    "http://github.com/amichne/kast/releases/download/{tag}/kast-idea-{tag}.zip"
)
dump("bad-url.json", bad_url)

bad_rotation = deepcopy(source)
bad_rotation["signing"]["rotation"] = {
    "state": "overlap",
    "nextSignerSha256": None,
}
dump("bad-rotation.json", bad_rotation)

bad_schema = deepcopy(source)
bad_schema["schemaVersion"] = 2
dump("bad-schema.json", bad_schema)
PY

fake_artifact_verifier="${scratch_dir}/verify-idea-plugin-artifact.py"
python3 - "$fake_artifact_verifier" <<'PY'
from __future__ import annotations

import stat
import sys
from pathlib import Path


path = Path(sys.argv[1])
path.write_text(
    """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$KAST_TEST_ARTIFACT_VERIFIER_LOG"
[[ "$1" == "verify" ]]
[[ " $* " == *" --plugin-zip "* ]]
[[ " $* " == *" --certificate-chain "* ]]
[[ " $* " == *" --signature-verifier-jar "* ]]
[[ " $* " == *" --expected-signer-sha256 "* ]]
[[ "${KAST_TEST_ARTIFACT_VERIFIER_REJECT:-0}" != "1" ]]
""",
    encoding="utf-8",
)
path.chmod(path.stat().st_mode | stat.S_IXUSR)
PY
certificate_chain="${scratch_dir}/chain.crt"
signature_verifier_jar="${scratch_dir}/marketplace-zip-signer-cli.jar"
printf 'test certificate boundary\n' > "$certificate_chain"
printf 'test verifier boundary\n' > "$signature_verifier_jar"
export KAST_TEST_ARTIFACT_VERIFIER_LOG="${scratch_dir}/artifact-verifier.log"
verification_args=(
  --certificate-chain "$certificate_chain"
  --signature-verifier-jar "$signature_verifier_jar"
  --artifact-verifier "$fake_artifact_verifier"
)

"$renderer" validate-source --source "$source_file"
[[ "$("$renderer" signing-state --source "$source_file")" == "unconfigured" ]] \
  || die "Checked-in repository source must honestly report its unconfigured production signer state"
expect_failure production-source-not-enrolled \
  "$renderer" enrolled-signers --source "$source_file" --require-configured

"$renderer" validate-source --source "${scratch_dir}/source.json" --require-configured
[[ "$("$renderer" signing-state --source "${scratch_dir}/source.json")" == "stable" ]] \
  || die "Configured repository fixture did not report stable signing state"
[[ "$("$renderer" asset-name --source "${scratch_dir}/source.json" --tag v1.2.3)" == "kast-idea-v1.2.3.zip" ]] \
  || die "Repository source did not deterministically derive the release asset name"
[[ "$("$renderer" manifest-url --source "${scratch_dir}/source.json")" == "https://kast.michne.com/jetbrains/plugin-repository-manifest.json" ]] \
  || die "Repository source did not deterministically derive the published manifest URL"

"$renderer" render \
  --source "${scratch_dir}/source.json" \
  --plugin-zip "${scratch_dir}/kast-idea-v1.2.3.zip" \
  --provenance "${scratch_dir}/provenance.json" \
  "${verification_args[@]}" \
  --output-directory "${scratch_dir}/output-a"
"$renderer" render \
  --source "${scratch_dir}/source.json" \
  --plugin-zip "${scratch_dir}/kast-idea-v1.2.3.zip" \
  --provenance "${scratch_dir}/provenance.json" \
  "${verification_args[@]}" \
  --output-directory "${scratch_dir}/output-b"

require_contains \
  "$KAST_TEST_ARTIFACT_VERIFIER_LOG" \
  "verify --plugin-zip" \
  "Rendering must invoke signed-byte verification before emitting a feed"
expect_failure rejected-signature \
  env KAST_TEST_ARTIFACT_VERIFIER_REJECT=1 \
  "$renderer" render \
    --source "${scratch_dir}/source.json" \
    --plugin-zip "${scratch_dir}/kast-idea-v1.2.3.zip" \
    --provenance "${scratch_dir}/provenance.json" \
    "${verification_args[@]}" \
    --output-directory "${scratch_dir}/rejected-signature-output"

cmp "${scratch_dir}/output-a/updatePlugins.xml" "${scratch_dir}/output-b/updatePlugins.xml"
cmp \
  "${scratch_dir}/output-a/plugin-repository-manifest.json" \
  "${scratch_dir}/output-b/plugin-repository-manifest.json"
[[ "$("$renderer" published-release-tag \
  --source "${scratch_dir}/source.json" \
  --manifest "${scratch_dir}/output-a/plugin-repository-manifest.json")" == "v1.2.3" ]] \
  || die "Published manifest did not preserve its finalized release tag"
[[ "$("$renderer" verify-published \
  --source "${scratch_dir}/source.json" \
  --manifest "${scratch_dir}/output-a/plugin-repository-manifest.json" \
  --xml "${scratch_dir}/output-a/updatePlugins.xml")" == "v1.2.3" ]] \
  || die "Published feed did not validate against its manifest"
cp "${scratch_dir}/output-a/updatePlugins.xml" "${scratch_dir}/tampered-updatePlugins.xml"
printf '<!-- stale or tampered -->\n' >> "${scratch_dir}/tampered-updatePlugins.xml"
expect_failure mismatched-published-xml \
  "$renderer" verify-published \
    --source "${scratch_dir}/source.json" \
    --manifest "${scratch_dir}/output-a/plugin-repository-manifest.json" \
    --xml "${scratch_dir}/tampered-updatePlugins.xml"

python3 - "${scratch_dir}/output-a" "${scratch_dir}/kast-idea-v1.2.3.zip" <<'PY'
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from xml.etree import ElementTree


output = Path(sys.argv[1])
plugin_zip = Path(sys.argv[2])
names = sorted(path.name for path in output.iterdir())
assert names == ["plugin-repository-manifest.json", "updatePlugins.xml"], names
xml_path = output / "updatePlugins.xml"
xml_text = xml_path.read_text(encoding="utf-8")
root = ElementTree.parse(xml_path).getroot()
assert root.tag == "plugins"
plugins = root.findall("plugin")
assert len(plugins) == 1
plugin = plugins[0]
assert plugin.attrib == {
    "id": "io.github.amichne.kast",
    "url": "https://github.com/amichne/kast/releases/download/v1.2.3/kast-idea-v1.2.3.zip",
    "version": "1.2.3",
}
idea_version = plugin.find("idea-version")
assert idea_version is not None
assert idea_version.attrib == {"since-build": "253"}
assert "sha256=" in xml_text
assert "signer-sha256=" in xml_text

manifest = json.loads((output / "plugin-repository-manifest.json").read_text(encoding="utf-8"))
assert manifest["schemaVersion"] == 1
assert manifest["feedUrl"] == "https://kast.michne.com/jetbrains/updatePlugins.xml"
assert manifest["releaseTag"] == "v1.2.3"
assert manifest["releaseSha"] == "1" * 40
assert len(manifest["entries"]) == 1
entry = manifest["entries"][0]
assert entry == {
    "pluginId": "io.github.amichne.kast",
    "version": "1.2.3",
    "url": "https://github.com/amichne/kast/releases/download/v1.2.3/kast-idea-v1.2.3.zip",
    "sha256": hashlib.sha256(plugin_zip.read_bytes()).hexdigest(),
    "signerSha256": "a" * 64,
    "ideaBuildRange": {"sinceBuild": "253", "untilBuild": None},
}
assert len(entry["sha256"]) == 64
assert set(entry["sha256"]) <= set("0123456789abcdef")
PY

expect_failure invalid-digest \
  "$renderer" render \
    --source "${scratch_dir}/source.json" \
    --plugin-zip "${scratch_dir}/kast-idea-v1.2.3.zip" \
    --provenance "${scratch_dir}/bad-digest.json" \
    "${verification_args[@]}" \
    --output-directory "${scratch_dir}/bad-digest-output"
expect_failure invalid-signer \
  "$renderer" render \
    --source "${scratch_dir}/source.json" \
    --plugin-zip "${scratch_dir}/kast-idea-v1.2.3.zip" \
    --provenance "${scratch_dir}/bad-signer.json" \
    "${verification_args[@]}" \
    --output-directory "${scratch_dir}/bad-signer-output"
expect_failure invalid-version \
  "$renderer" render \
    --source "${scratch_dir}/source.json" \
    --plugin-zip "${scratch_dir}/kast-idea-v1.2.3.zip" \
    --provenance "${scratch_dir}/bad-version.json" \
    "${verification_args[@]}" \
    --output-directory "${scratch_dir}/bad-version-output"
expect_failure invalid-plugin-version \
  "$renderer" render \
    --source "${scratch_dir}/source.json" \
    --plugin-zip "${scratch_dir}/wrong-version.zip" \
    --provenance "${scratch_dir}/provenance.json" \
    "${verification_args[@]}" \
    --output-directory "${scratch_dir}/bad-plugin-version-output"
expect_failure invalid-url \
  "$renderer" validate-source --source "${scratch_dir}/bad-url.json" --require-configured
expect_failure invalid-rotation-state \
  "$renderer" validate-source --source "${scratch_dir}/bad-rotation.json" --require-configured
expect_failure invalid-schema \
  "$renderer" validate-source --source "${scratch_dir}/bad-schema.json" --require-configured
expect_failure invalid-ordering \
  "$renderer" render \
    --source "${scratch_dir}/source.json" \
    --plugin-zip "${scratch_dir}/kast-idea-v1.2.3.zip" \
    --provenance "${scratch_dir}/bad-order.json" \
    "${verification_args[@]}" \
    --output-directory "${scratch_dir}/bad-order-output"

fake_gh="${scratch_dir}/gh"
python3 - "$fake_gh" <<'PY'
from __future__ import annotations

import stat
import sys
from pathlib import Path


path = Path(sys.argv[1])
path.write_text(
    """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$KAST_TEST_GH_LOG"
case "$1 $2" in
  "release list")
    printf '%s\\n' "$KAST_TEST_RELEASE_TAG"
    ;;
  "release view")
    printf '%s\\t%s\\t%s\\n' \\
      "$KAST_TEST_RELEASE_TAG" \\
      "${KAST_TEST_RELEASE_DRAFT:-false}" \\
      "${KAST_TEST_RELEASE_PRERELEASE:-false}"
    ;;
  "release verify")
    [[ "${KAST_TEST_RELEASE_VERIFY_FAILURE:-0}" != "1" ]]
    ;;
  "release download")
    destination=""
    pattern=""
    shift 3
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --dir) destination="$2"; shift 2 ;;
        --pattern) pattern="$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    [[ -n "$destination" && -n "$pattern" ]]
    mkdir -p "$destination"
    cp "$KAST_TEST_RELEASE_DIRECTORY/$pattern" "$destination/$pattern"
    ;;
  "api repos/amichne/kast/releases/tags/$KAST_TEST_RELEASE_TAG")
    printf '%s\n' "${KAST_TEST_RELEASE_IMMUTABLE:-true}"
    ;;
  "api repos/amichne/kast/commits/$KAST_TEST_RELEASE_TAG")
    printf '%s\n' "${KAST_TEST_RELEASE_SHA:-1111111111111111111111111111111111111111}"
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

release_directory="${scratch_dir}/release"
mkdir -p "$release_directory"
cp "${scratch_dir}/kast-idea-v1.2.3.zip" "$release_directory/"
cp "${scratch_dir}/build-provenance.json" "$release_directory/"

env \
  KAST_TEST_GH_LOG="${scratch_dir}/gh.log" \
  KAST_TEST_RELEASE_TAG=v1.2.3 \
  KAST_TEST_RELEASE_DIRECTORY="$release_directory" \
  "$materializer" \
    --source "${scratch_dir}/source.json" \
    --output-directory "${scratch_dir}/materialized" \
    --tag v1.2.3 \
    "${verification_args[@]}" \
    --gh-bin "$fake_gh"
cmp \
  "${scratch_dir}/output-a/updatePlugins.xml" \
  "${scratch_dir}/materialized/updatePlugins.xml"
cmp \
  "${scratch_dir}/output-a/plugin-repository-manifest.json" \
  "${scratch_dir}/materialized/plugin-repository-manifest.json"

env \
  KAST_TEST_GH_LOG="${scratch_dir}/latest-gh.log" \
  KAST_TEST_RELEASE_TAG=v1.2.3 \
  KAST_TEST_RELEASE_DIRECTORY="$release_directory" \
  "$materializer" \
    --source "${scratch_dir}/source.json" \
    --output-directory "${scratch_dir}/latest-materialized" \
    --latest-stable \
    "${verification_args[@]}" \
    --gh-bin "$fake_gh"
require_contains \
  "${scratch_dir}/latest-gh.log" \
  "release list --repo amichne/kast --exclude-drafts --exclude-pre-releases" \
  "Latest-feed materialization must select a finalized stable release"

fake_curl="${scratch_dir}/curl"
python3 - "$fake_curl" <<'PY'
from __future__ import annotations

import stat
import sys
from pathlib import Path


path = Path(sys.argv[1])
path.write_text(
    """#!/usr/bin/env bash
set -euo pipefail
output=""
url="${!#}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    *) shift ;;
  esac
done
status="${KAST_TEST_HTTP_STATUS:-200}"
if [[ "$status" == "200" ]]; then
  case "$url" in
    */plugin-repository-manifest.json)
      cp "$KAST_TEST_PUBLISHED_MANIFEST" "$output"
      ;;
    */updatePlugins.xml)
      cp "$KAST_TEST_PUBLISHED_XML" "$output"
      ;;
    *)
      exit 2
      ;;
  esac
fi
printf '%s' "$status"
""",
    encoding="utf-8",
)
path.chmod(path.stat().st_mode | stat.S_IXUSR)
PY

rm -f "${scratch_dir}/published-gh.log"
env \
  KAST_TEST_PUBLISHED_MANIFEST="${scratch_dir}/output-a/plugin-repository-manifest.json" \
  KAST_TEST_PUBLISHED_XML="${scratch_dir}/output-a/updatePlugins.xml" \
  "$materializer" \
    --source "${scratch_dir}/source.json" \
    --output-directory "${scratch_dir}/published-materialized" \
    --published-release \
    --curl-bin "$fake_curl"
cmp \
  "${scratch_dir}/output-a/updatePlugins.xml" \
  "${scratch_dir}/published-materialized/updatePlugins.xml"
cmp \
  "${scratch_dir}/output-a/plugin-repository-manifest.json" \
  "${scratch_dir}/published-materialized/plugin-repository-manifest.json"
[[ ! -e "${scratch_dir}/published-gh.log" ]] \
  || die "Published-feed preservation unexpectedly queried or advanced a GitHub Release"

env \
  KAST_TEST_HTTP_STATUS=404 \
  KAST_TEST_PUBLISHED_MANIFEST="${scratch_dir}/output-a/plugin-repository-manifest.json" \
  KAST_TEST_PUBLISHED_XML="${scratch_dir}/output-a/updatePlugins.xml" \
  "$materializer" \
    --source "${scratch_dir}/source.json" \
    --output-directory "${scratch_dir}/unpublished-materialized" \
    --published-release \
    --allow-unpublished \
    --curl-bin "$fake_curl"
[[ ! -e "${scratch_dir}/unpublished-materialized" ]] \
  || die "Missing published feed unexpectedly emitted repository output"

expect_failure draft-release \
  env \
    KAST_TEST_GH_LOG="${scratch_dir}/draft-gh.log" \
    KAST_TEST_RELEASE_TAG=v1.2.3 \
    KAST_TEST_RELEASE_DRAFT=true \
    KAST_TEST_RELEASE_DIRECTORY="$release_directory" \
    "$materializer" \
      --source "${scratch_dir}/source.json" \
      --output-directory "${scratch_dir}/draft-materialized" \
      --tag v1.2.3 \
      "${verification_args[@]}" \
      --gh-bin "$fake_gh"

expect_failure mutable-release \
  env \
    KAST_TEST_GH_LOG="${scratch_dir}/mutable-gh.log" \
    KAST_TEST_RELEASE_TAG=v1.2.3 \
    KAST_TEST_RELEASE_IMMUTABLE=false \
    KAST_TEST_RELEASE_DIRECTORY="$release_directory" \
    "$materializer" \
      --source "${scratch_dir}/source.json" \
      --output-directory "${scratch_dir}/mutable-materialized" \
      --tag v1.2.3 \
      "${verification_args[@]}" \
      --gh-bin "$fake_gh"

expect_failure mismatched-release-sha \
  env \
    KAST_TEST_GH_LOG="${scratch_dir}/sha-gh.log" \
    KAST_TEST_RELEASE_TAG=v1.2.3 \
    KAST_TEST_RELEASE_SHA=2222222222222222222222222222222222222222 \
    KAST_TEST_RELEASE_DIRECTORY="$release_directory" \
    "$materializer" \
      --source "${scratch_dir}/source.json" \
      --output-directory "${scratch_dir}/sha-materialized" \
      --tag v1.2.3 \
      "${verification_args[@]}" \
      --gh-bin "$fake_gh"

tampered_release_directory="${scratch_dir}/tampered-release"
mkdir -p "$tampered_release_directory"
cp "${scratch_dir}/kast-idea-v1.2.3.zip" "$tampered_release_directory/"
cp "${scratch_dir}/build-provenance.json" "$tampered_release_directory/"
printf 'tampered' >> "$tampered_release_directory/kast-idea-v1.2.3.zip"
expect_failure tampered-finalized-asset \
  env \
    KAST_TEST_GH_LOG="${scratch_dir}/tampered-gh.log" \
    KAST_TEST_RELEASE_TAG=v1.2.3 \
    KAST_TEST_RELEASE_DIRECTORY="$tampered_release_directory" \
    "$materializer" \
      --source "${scratch_dir}/source.json" \
      --output-directory "${scratch_dir}/tampered-materialized" \
      --tag v1.2.3 \
      "${verification_args[@]}" \
      --gh-bin "$fake_gh"

rm -f "${scratch_dir}/unconfigured-gh.log"
"$materializer" \
  --source "$source_file" \
  --output-directory "${scratch_dir}/unconfigured-materialized" \
  --tag v1.2.3 \
  --allow-unconfigured \
  --gh-bin "$fake_gh"
[[ ! -e "${scratch_dir}/unconfigured-materialized" ]] \
  || die "Unconfigured production source unexpectedly emitted a feed"
[[ ! -e "${scratch_dir}/unconfigured-gh.log" ]] \
  || die "Unconfigured production source unexpectedly queried GitHub"

require_block_contains \
  "$release_workflow" \
  "  build-jetbrains-plugin-repository-pages:" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "      - publish-release" \
  "JetBrains documentation build must wait for finalized release publication"
require_block_contains \
  "$release_workflow" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "  verify-release-state:" \
  ".github/scripts/materialize-jetbrains-plugin-repository-pages.sh" \
  "JetBrains Pages deployment must verify and render the finalized release asset under its lock"
require_block_contains \
  "$release_workflow" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "  verify-release-state:" \
  "      group: github-pages" \
  "Release Pages materialization and deployment must share the global Pages lock"
require_block_contains \
  "$release_workflow" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "  verify-release-state:" \
  "      - build-jetbrains-plugin-repository-pages" \
  "JetBrains Pages deployment must consume the built documentation artifact"
require_block_contains \
  "$release_workflow" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "  verify-release-state:" \
  '          name: idea-plugin-${{ github.run_id }}' \
  "JetBrains Pages deployment must consume the verifier captured with the signed plugin build"
require_block_contains \
  "$release_workflow" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "  verify-release-state:" \
  "--certificate-chain" \
  "JetBrains Pages deployment must reverify the finalized signed ZIP"
require_block_contains \
  "$release_workflow" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "  verify-release-state:" \
  "release-idea-signature-verifier" \
  "JetBrains Pages deployment must verify the producer receipt for its signature verifier"
require_block_contains \
  "$release_workflow" \
  "  release-preflight:" \
  "  bump-version:" \
  "repos/amichne/kast/immutable-releases" \
  "Release preflight must reject disabled immutability before creating a tag or release"
# shellcheck disable=SC2016 # GitHub expressions must remain literal contract strings.
require_block_contains \
  "$release_workflow" \
  "  release-preflight:" \
  "  bump-version:" \
  'GH_TOKEN: ${{ secrets.RELEASE_GITHUB_TOKEN }}' \
  "Immutability preflight must use the admin-capable release token without a GITHUB_TOKEN fallback"
require_block_contains \
  "$release_workflow" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "  verify-release-state:" \
  "      pages: write" \
  "JetBrains Pages deployment must have Pages write permission"
require_block_contains \
  "$release_workflow" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "  verify-release-state:" \
  "      id-token: write" \
  "JetBrains Pages deployment must have OIDC permission"
require_block_contains \
  "$release_workflow" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "  verify-release-state:" \
  "      name: github-pages" \
  "JetBrains Pages deployment must use the protected Pages environment"
require_order \
  "$release_workflow" \
  "  publish-release:" \
  "  build-jetbrains-plugin-repository-pages:" \
  "Release publication must precede JetBrains repository rendering"
require_order \
  "$release_workflow" \
  "  build-jetbrains-plugin-repository-pages:" \
  "  deploy-jetbrains-plugin-repository-pages:" \
  "JetBrains repository rendering must precede Pages deployment"
require_block_contains \
  "$docs_workflow" \
  "  deploy:" \
  "      - uses: actions/deploy-pages@v5" \
  ".github/scripts/materialize-jetbrains-plugin-repository-pages.sh" \
  "Documentation deployments must retain the latest finalized JetBrains feed"
require_block_contains \
  "$docs_workflow" \
  "  deploy:" \
  "      - uses: actions/deploy-pages@v5" \
  "--published-release" \
  "Documentation deployments must preserve the previously published feed instead of advancing it"
require_block_contains \
  "$docs_workflow" \
  "  deploy:" \
  "      - uses: actions/deploy-pages@v5" \
  "      group: github-pages" \
  "Documentation preservation and deployment must share the global Pages lock"
require_block_contains \
  "$docs_workflow" \
  "  deploy:" \
  "      - uses: actions/deploy-pages@v5" \
  "      - uses: actions/upload-pages-artifact@v5" \
  "Documentation must resolve the published feed only after acquiring the Pages lock"
require_contains \
  "$materializer" \
  "GitHub Release is not immutable" \
  "Feed advancement must reject mutable GitHub Releases"
require_contains \
  "$materializer" \
  'release verify "$tag"' \
  "Feed advancement must verify GitHub's immutable release attestation"
require_contains \
  "$packaging_guide" \
  "plugin-repository.json" \
  "JetBrains packaging guide must name the authored source"
require_contains \
  "$packaging_guide" \
  "updatePlugins.xml" \
  "JetBrains packaging guide must name the generated feed boundary"
require_contains \
  "$github_guide" \
  "test-jetbrains-plugin-repository-contract.sh" \
  "GitHub guide must name the feed-to-asset gate"

printf 'JetBrains plugin repository contract passed.\n'
