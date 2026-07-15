#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: materialize-jetbrains-plugin-repository-pages.sh \
  --source <plugin-repository.json> \
  --output-directory <site/jetbrains> \
  (--tag <vX.Y.Z> | --latest-stable | --published-release) \
  [--allow-unconfigured] [--allow-unpublished] \
  [--certificate-chain <chain.crt>] \
  [--signature-verifier-jar <marketplace-zip-signer-cli.jar>] \
  [--artifact-verifier <verify-idea-plugin-artifact.py>] \
  [--gh-bin <path>] [--curl-bin <path>]
USAGE
  exit 2
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

repo_root="$(resolve_repo_root)"
renderer="${repo_root}/.github/scripts/render-jetbrains-plugin-repository.py"
source_file=""
output_directory=""
tag=""
latest_stable=false
published_release=false
allow_unconfigured=false
allow_unpublished=false
gh_bin="gh"
curl_bin="curl"
certificate_chain=""
signature_verifier_jar=""
artifact_verifier="${repo_root}/scripts/verify-idea-plugin-artifact.py"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      [[ $# -ge 2 ]] || usage
      source_file="$2"
      shift 2
      ;;
    --output-directory)
      [[ $# -ge 2 ]] || usage
      output_directory="$2"
      shift 2
      ;;
    --tag)
      [[ $# -ge 2 ]] || usage
      tag="$2"
      shift 2
      ;;
    --latest-stable)
      latest_stable=true
      shift
      ;;
    --published-release)
      published_release=true
      shift
      ;;
    --allow-unconfigured)
      allow_unconfigured=true
      shift
      ;;
    --allow-unpublished)
      allow_unpublished=true
      shift
      ;;
    --gh-bin)
      [[ $# -ge 2 ]] || usage
      gh_bin="$2"
      shift 2
      ;;
    --curl-bin)
      [[ $# -ge 2 ]] || usage
      curl_bin="$2"
      shift 2
      ;;
    --certificate-chain)
      [[ $# -ge 2 ]] || usage
      certificate_chain="$2"
      shift 2
      ;;
    --signature-verifier-jar)
      [[ $# -ge 2 ]] || usage
      signature_verifier_jar="$2"
      shift 2
      ;;
    --artifact-verifier)
      [[ $# -ge 2 ]] || usage
      artifact_verifier="$2"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

[[ -n "$source_file" ]] || usage
[[ -n "$output_directory" ]] || usage
mode_count=0
[[ -n "$tag" ]] && mode_count=$((mode_count + 1))
[[ "$latest_stable" == true ]] && mode_count=$((mode_count + 1))
[[ "$published_release" == true ]] && mode_count=$((mode_count + 1))
if [[ "$mode_count" -ne 1 ]]; then
  usage
fi
if [[ "$allow_unpublished" == true && "$published_release" != true ]]; then
  usage
fi
[[ -x "$renderer" ]] || die "JetBrains repository renderer is not executable: $renderer"

"$renderer" validate-source --source "$source_file"
signing_state="$("$renderer" signing-state --source "$source_file")"
if [[ "$signing_state" == "unconfigured" ]]; then
  if [[ "$allow_unconfigured" == true ]]; then
    rm -rf -- "$output_directory"
    printf 'JetBrains repository publication is disabled until the production signer is configured.\n'
    exit 0
  fi
  die "JetBrains repository publication requires a configured production signer"
fi
"$renderer" validate-source --source "$source_file" --require-configured

scratch_dir="$(mktemp -d)"
trap 'rm -rf "$scratch_dir"' EXIT
if [[ "$published_release" == true ]]; then
  command -v "$curl_bin" >/dev/null 2>&1 || die "curl is required: $curl_bin"
  published_manifest="${scratch_dir}/published-plugin-repository-manifest.json"
  published_xml="${scratch_dir}/published-updatePlugins.xml"
  published_manifest_url="$("$renderer" manifest-url --source "$source_file")"
  published_feed_url="$("$renderer" feed-url --source "$source_file")"
  set +e
  http_status="$($curl_bin \
    --silent \
    --show-error \
    --location \
    --proto '=https' \
    --tlsv1.2 \
    --output "$published_manifest" \
    --write-out '%{http_code}' \
    "$published_manifest_url")"
  curl_status=$?
  set -e
  [[ "$curl_status" -eq 0 ]] || die "Published JetBrains repository manifest request failed"
  if [[ "$http_status" == "404" && "$allow_unpublished" == true ]]; then
    rm -rf -- "$output_directory"
    printf 'JetBrains repository has no previously published feed to preserve.\n'
    exit 0
  fi
  [[ "$http_status" == "200" ]] \
    || die "Published JetBrains repository manifest returned HTTP ${http_status}"
  xml_http_status="$($curl_bin \
    --silent \
    --show-error \
    --location \
    --proto '=https' \
    --tlsv1.2 \
    --output "$published_xml" \
    --write-out '%{http_code}' \
    "$published_feed_url")"
  [[ "$xml_http_status" == "200" ]] \
    || die "Published JetBrains repository XML returned HTTP ${xml_http_status}"
  tag="$("$renderer" verify-published \
    --source "$source_file" \
    --manifest "$published_manifest" \
    --xml "$published_xml")"
  rm -rf -- "$output_directory"
  mkdir -p -- "$output_directory"
  cp -- "$published_manifest" "${output_directory}/plugin-repository-manifest.json"
  cp -- "$published_xml" "${output_directory}/updatePlugins.xml"
  printf 'Preserved validated JetBrains plugin repository for %s.\n' "$tag"
  exit 0
fi

command -v "$gh_bin" >/dev/null 2>&1 || die "GitHub CLI is required: $gh_bin"
[[ -n "$certificate_chain" ]] || die "Feed advancement requires --certificate-chain"
[[ -n "$signature_verifier_jar" ]] || die "Feed advancement requires --signature-verifier-jar"
[[ -f "$certificate_chain" ]] || die "Public certificate chain does not exist: $certificate_chain"
[[ -f "$signature_verifier_jar" ]] \
  || die "Marketplace ZIP Signer CLI does not exist: $signature_verifier_jar"
[[ -f "$artifact_verifier" ]] || die "IDEA plugin artifact verifier does not exist: $artifact_verifier"
if [[ "$latest_stable" == true ]]; then
  tag="$($gh_bin release list \
    --repo amichne/kast \
    --exclude-drafts \
    --exclude-pre-releases \
    --limit 1 \
    --json tagName \
    --jq '.[0].tagName // empty')"
  [[ -n "$tag" ]] || die "No finalized stable GitHub Release is available"
fi
[[ "$tag" =~ ^v[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || die "Invalid release tag: $tag"
[[ "$tag" != *-* ]] || die "The stable JetBrains repository cannot publish a prerelease tag"

release_state="$($gh_bin release view "$tag" \
  --repo amichne/kast \
  --json tagName,isDraft,isPrerelease \
  --jq '[.tagName, .isDraft, .isPrerelease] | @tsv')"
IFS=$'\t' read -r actual_tag is_draft is_prerelease <<< "$release_state"
[[ "$actual_tag" == "$tag" ]] || die "GitHub Release tag mismatch: ${actual_tag:-<missing>}"
[[ "$is_draft" == "false" ]] || die "GitHub Release is still a draft: $tag"
[[ "$is_prerelease" == "false" ]] || die "GitHub Release is not stable: $tag"
immutable="$($gh_bin api "repos/amichne/kast/releases/tags/${tag}" --jq '.immutable')"
[[ "$immutable" == "true" ]] \
  || die "GitHub Release is not immutable: ${tag}; enable repository release immutability before publishing"
"$gh_bin" release verify "$tag" --repo amichne/kast >/dev/null

asset_name="$("$renderer" asset-name --source "$source_file" --tag "$tag")"
"$gh_bin" release download "$tag" --dir "$scratch_dir" --pattern "$asset_name"
"$gh_bin" release download "$tag" --dir "$scratch_dir" --pattern build-provenance.json
[[ -f "${scratch_dir}/${asset_name}" ]] || die "Finalized plugin asset was not downloaded: $asset_name"
[[ -f "${scratch_dir}/build-provenance.json" ]] || die "Finalized release provenance was not downloaded"
provenance_sha="$("$renderer" provenance-release-sha \
  --provenance "${scratch_dir}/build-provenance.json")"
tag_sha="$($gh_bin api "repos/amichne/kast/commits/${tag}" --jq '.sha')"
[[ "$provenance_sha" == "$tag_sha" ]] \
  || die "IDEA release provenance SHA does not match immutable release tag target"

rm -rf -- "$output_directory"
"$renderer" render \
  --source "$source_file" \
  --plugin-zip "${scratch_dir}/${asset_name}" \
  --provenance "${scratch_dir}/build-provenance.json" \
  --certificate-chain "$certificate_chain" \
  --signature-verifier-jar "$signature_verifier_jar" \
  --artifact-verifier "$artifact_verifier" \
  --output-directory "$output_directory"
