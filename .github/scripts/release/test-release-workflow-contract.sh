#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'release workflow contract: %s\n' "$*" >&2
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
ci="$repo_root/.github/workflows/ci.yml"
cut_release="$repo_root/.github/workflows/cut-release.yml"
release="$repo_root/.github/workflows/release.yml"
model="$repo_root/.github/scripts/release/metadata/release-workflow-model.json"
cli_builder="$repo_root/.github/scripts/release/actions/build-cli/action.yml"
setup_builder="$repo_root/.github/scripts/release/actions/build-setup-bundle/action.yml"
setup_publisher="$repo_root/.github/scripts/release/actions/publish-setup-bundle/action.yml"

[[ ! -e "$repo_root/.github/scripts/release/actions/publish-cli/action.yml" ]] \
  || die "raw CLI publisher action remains"
[[ -f "$cut_release" ]] || die "cut release workflow is missing"

workflow_jobs() {
  local workflow="$1"
  awk '
  /^jobs:$/ { in_jobs=1; next }
  in_jobs && /^  [a-z0-9][a-z0-9-]*:$/ {
    value=$1
    sub(/:$/, "", value)
    print value
  }
' "$workflow" | sort
}

cut_release_jobs="$(workflow_jobs "$cut_release")"
expected_cut_release_jobs="$(printf '%s\n' cut-release publish-release release-preflight | sort)"
[[ "$cut_release_jobs" == "$expected_cut_release_jobs" ]] \
  || die "unexpected cut release job inventory: ${cut_release_jobs//$'\n'/, }"

release_jobs="$(workflow_jobs "$release")"
expected_workflow_jobs="$(printf '%s\n' \
  build-agent-resources \
  build-cli \
  build-indexer \
  build-openapi-spec \
  build-release-metadata \
  build-setup-bundles \
  prepare-release \
  publish-agent-resources \
  publish-maven-central \
  publish-openapi-spec \
  publish-release \
  publish-setup-bundles \
  quarantine-failed-release-artifacts \
  release-preflight \
  verify-release-state | sort)"
[[ "$release_jobs" == "$expected_workflow_jobs" ]] \
  || die "unexpected release job inventory: ${release_jobs//$'\n'/, }"

require "$cut_release" 'name: Cut Release' \
  "manual tag cutting must have a distinct workflow identity"
require "$cut_release" '  workflow_dispatch:' \
  "cut release must be manually dispatched"
reject "$cut_release" '  push:' \
  "cut release must not run for tag pushes"
require "$release" 'name: Release' \
  "publication must retain the release workflow identity"
require "$release" '  push:' \
  "release publication must run for tag pushes"
require "$release" '      - "v*.*.*"' \
  "release publication must remain tag scoped"
require "$release" '  workflow_call:' \
  "manual tag cutting must invoke publication as a reusable workflow"
reject "$release" 'inputs.release_type' \
  "release publication must not contain tag-cutting inputs"

for cut_evidence in \
  'release_type="${{ inputs.release_type }}"' \
  'token: ${{ github.token }}' \
  'git tag "$new_tag"' \
  'git push origin "$new_tag"' \
  'release_tag: ${{ steps.release-tag.outputs.value }}' \
  'release_sha: ${{ steps.release-tag.outputs.sha }}' \
  'uses: ./.github/workflows/release.yml' \
  'release_tag: ${{ needs.cut-release.outputs.release_tag }}' \
  'release_sha: ${{ needs.cut-release.outputs.release_sha }}' \
  'secrets: inherit'; do
  require "$cut_release" "$cut_evidence" \
    "cut release must retain tag-cutting evidence: ${cut_evidence}"
done

for release_identity_evidence in \
  'release_tag:' \
  'release_sha:' \
  'RELEASE_TAG: ${{ inputs.release_tag || github.ref_name }}' \
  'RELEASE_SHA: ${{ inputs.release_sha || github.sha }}' \
  'tag="${RELEASE_TAG}"' \
  'git rev-parse "${tag}^{commit}"' \
  '[[ "$tag_sha" == "$RELEASE_SHA" ]]'; do
  require "$release" "$release_identity_evidence" \
    "release publication must preserve exact reusable identity: ${release_identity_evidence}"
done

for tokenless_surface in "$cut_release" "$release"; do
  reject "$tokenless_surface" 'RELEASE_GITHUB_TOKEN' \
    "release automation must not depend on a long-lived GitHub token"
done

reject "$release" 'CI_AUX_' "release must not depend on mutable auxiliary flags"
reject "$release" 'publish-cli-' "release must not publish intermediate CLI archives"
reject "$release" 'gradle-ro-dep-cache' "release must not publish a build cache"
reject "$release" 'runtime-manifest' "release must not publish a second runtime manifest"
reject "$release" 'ubuntu-debian-headless' "release must not publish a legacy Linux bundle"
reject "$release" 'linux-headless' "release must not publish a standalone runtime product"

for preflight_surface in "$cut_release" "$release"; do
  require "$preflight_surface" 'timeout-minutes: 30' \
    "release preflight must bound the exact-source CI wait"
  require "$preflight_surface" 'gh run watch "$ci_run_id" --repo "$GITHUB_REPOSITORY" --exit-status --interval 10' \
    "release preflight must wait for exact-source CI with explicit repository context"
  reject "$preflight_surface" '-f status=success' \
    "release preflight must discover in-progress exact-source CI"
done

for exact_ci_evidence in \
  'actions: read' \
  'GH_TOKEN: ${{ github.token }}' \
  'actions/workflows/ci.yml/runs' \
  'ci-artifact-ledger-maven-publication' \
  '-f branch=main' \
  '-f event=push' \
  '-f head_sha="$GITHUB_SHA"' \
  '.head_sha == $sha' \
  '.head_branch == "main"' \
  '.path == ".github/workflows/ci.yml"' \
  '.event == "push"' \
  '.status == "completed"' \
  '.conclusion == "success"' \
  '.expired == false'; do
  for preflight_surface in "$cut_release" "$release"; do
    require "$preflight_surface" "$exact_ci_evidence" \
      "release preflight must require exact-source CI evidence: ${exact_ci_evidence}"
  done
done
reject "$release" 'actions/runs/${ci_run_id}/jobs' \
  "successful required CI must not be followed by a duplicate per-job policy query"

for maven_evidence in \
  'name: ci-artifact-ledger-maven-publication' \
  'run-id: ${{ needs.prepare-release.outputs.source_ci_run_id }}' \
  'github-token: ${{ github.token }}' \
  '.workflowRunId == $run_id' \
  '.sourceRef == "refs/heads/main"' \
  '--require-kind ci-maven-publication-validation'; do
  require "$release" "$maven_evidence" \
    "Maven publication must consume exact-source CI evidence: ${maven_evidence}"
done
reject "$release" 'release-artifact-ledger-maven-publication' \
  "release must not relay the Maven ledger through another artifact"
require "$release" 'name: Verify Maven Central publication' \
  "Maven publication must end with authoritative remote verification"

for platform in linux-x64 linux-arm64 macos-x64 macos-arm64; do
  require "$release" "asset_id: ${platform}" "CLI build matrix must include ${platform}"
  require "$release" "- ${platform}" "setup matrices must include ${platform}"
done
require "$release" 'runs-on: ${{ matrix.runner }}' \
  "CLI matrix must select the native platform runner"
require "$release" 'uses: ./.github/scripts/release/actions/build-cli' \
  "CLI matrix must use the shared retained-byte builder"
require "$cli_builder" 'name: rust-cli-${{ inputs.asset_id }}-${{ github.run_id }}' \
  "CLI builders must retain private setup inputs"
require "$cli_builder" 'retention-days: 30' \
  "private CLI inputs must survive the release retry window"

for indexer_contract in \
  ':indexer:portableDistZip' \
  ':indexer:verifyPortableDistLayout' \
  "find indexer/build/distributions" \
  'dist/indexer.zip' \
  'name: indexer-${{ github.run_id }}' \
  '--artifact-kind release-indexer'; do
  require "$release" "$indexer_contract" \
    "release must build one retained indexer: ${indexer_contract}"
done

for setup_contract in \
  'name: indexer-${{ github.run_id }}' \
  'indexer/indexer.zip' \
  '--indexer-archive "$indexer_asset"' \
  '--require-kind release-indexer' \
  'setup-bundle-${{ inputs.platform }}-${{ github.run_id }}' \
  'build-provenance-setup-${platform}.json' \
  'build-ledger-setup-${platform}.json'; do
  require "$setup_builder" "$setup_contract" \
    "setup builder must consume retained indexer evidence: ${setup_contract}"
done
require "$setup_builder" 'compression-level: 0' \
  "compressed setup bundles must not be recompressed as workflow artifacts"
require "$setup_publisher" '.github/scripts/release/upload-immutable-release-asset.sh' \
  "setup publishers must use immutable upload recovery"
require "$setup_publisher" 'setup-bundle-provenance-${{ inputs.platform }}-${{ github.run_id }}' \
  "setup publishers must retain provenance"

workflow_job() {
  local job="$1"
  awk -v job="$job" '
    $0 == "  " job ":" { selected=1 }
    selected && $0 ~ /^  [a-z0-9][a-z0-9-]*:$/ && $0 != "  " job ":" { exit }
    selected { print }
  ' "$release"
}

for disabled_indexing_surface in \
  'prepare-real-repository-indexing:' \
  'real-repository-indexing:' \
  'ktorio/ktor-samples.git' \
  'AleksK1NG/Kotlin-Clean-Architecture-CQRS.git' \
  'square/okhttp.git' \
  'scripts/release/benchmark-real-repositories.sh' \
  'comparativePerformance' \
  'kast-real-repository-indexing-' \
  'aggregate-indexing-benchmark-evidence.py aggregate-release'; do
  reject "$release" "$disabled_indexing_surface" \
    "release must not claim disabled external-repository indexing: ${disabled_indexing_surface}"
done

for publication_job in \
  publish-maven-central \
  publish-openapi-spec \
  publish-agent-resources \
  publish-setup-bundles; do
  publication_contract="$(workflow_job "$publication_job")"
  for artifact_build in build-openapi-spec build-agent-resources build-setup-bundles; do
    [[ "$publication_contract" == *"- $artifact_build"* ]] \
      || die "${publication_job} must wait for $artifact_build"
    [[ "$publication_contract" == *"needs.$artifact_build.result == 'success'"* ]] \
      || die "${publication_job} must reject a failed $artifact_build"
  done
  [[ "$publication_contract" != *'real-repository-indexing'* ]] \
    || die "${publication_job} must not wait for disabled external-repository indexing"
done

quarantine_contract="$(workflow_job quarantine-failed-release-artifacts)"
for producer_job in \
  build-openapi-spec \
  build-cli \
  build-agent-resources \
  build-indexer \
  build-setup-bundles; do
  [[ "$quarantine_contract" == *"- $producer_job"* ]] \
    || die "failed-artifact quarantine must wait for $producer_job"
done
for quarantine_evidence in \
  "needs.build-openapi-spec.result != 'success'" \
  "needs.build-cli.result != 'success'" \
  "needs.build-agent-resources.result != 'success'" \
  "needs.build-indexer.result != 'success'" \
  "needs.build-setup-bundles.result != 'success'" \
  'actions: write' \
  'actions/runs/$GITHUB_RUN_ID/artifacts?per_page=100' \
  "mapfile -t artifact_ids" \
  'actions/artifacts/$artifact_id'; do
  [[ "$quarantine_contract" == *"$quarantine_evidence"* ]] \
    || die "failed-artifact quarantine is missing: $quarantine_evidence"
done

for retained_product in \
  publish-openapi-spec \
  publish-agent-resources \
  publish-setup-bundles; do
  require "$release" "- ${retained_product}" \
    "metadata finalization must wait for ${retained_product}"
done
require "$release" 'provenance-openapi' \
  "combined provenance must include OpenAPI"
require "$release" 'provenance-setup' \
  "combined provenance must include setup bundles"
metadata_contract="$(workflow_job build-release-metadata)"
[[ "$metadata_contract" != *'real-repository-indexing'* ]] \
  || die "release metadata must not wait for disabled external-repository indexing"
[[ "$metadata_contract" != *'comparativePerformance'* ]] \
  || die "release metadata must not claim disabled comparative performance"
reject "$release" 'provenance-cli' \
  "combined provenance must not claim unpublished CLI assets"
require "$release" 'name: release-metadata-${{ github.run_id }}' \
  "validated metadata must be retained before publication"
require "$release" 'name: Generate SHA256SUMS' \
  "release must render one authoritative checksum manifest"
require "$release" 'needs.publish-release.result }}" != "success"' \
  "published-state verification must require final publication"
require "$release" 'scripts/release/verify-release-state.sh' \
  "published-state verification must remain mandatory"
reject "$release" '--clobber' "release assets must never bypass immutable recovery"

for surface in "$release" "$cli_builder" "$setup_builder" "$setup_publisher"; do
  ! grep -Eq 'retention-days:[[:space:]]+3([[:space:]]|$)' "$surface" \
    || die "release artifacts expire before the supported retry window"
done

"$repo_root/.github/scripts/release/test-upload-immutable-release-asset.sh"
python3 "$repo_root/.github/scripts/release/metadata/test-render-release-checksums.py"
python3 - "$model" "$release" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

model = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
release_path = Path(sys.argv[2])
if model.get("schemaVersion") != 2 or model.get("evidenceMode") != "structural-only":
    raise SystemExit("release graph must use schema 2 structural evidence")
if model.get("provenance", {}).get("sourceSha256") != hashlib.sha256(release_path.read_bytes()).hexdigest():
    raise SystemExit("release graph must bind to the exact release workflow")
if "durationSamplesSeconds" in json.dumps(model):
    raise SystemExit("release graph must not invent timing evidence")

tasks = {task["id"]: task for task in model["tasks"]}
if len(tasks) != len(model["tasks"]):
    raise SystemExit("release graph task IDs must be unique")
expected_tasks = {
    "prepare-release",
    "publish-maven-central",
    "build-openapi-spec",
    "publish-openapi-spec",
    "build-cli",
    "build-agent-resources",
    "publish-agent-resources",
    "build-indexer",
    "build-setup-bundles",
    "publish-setup-bundles",
    "build-release-metadata",
    "publish-release",
    "verify-release-state",
}
if set(tasks) != expected_tasks:
    raise SystemExit(f"unexpected release graph tasks: {sorted(tasks)}")

expected_needs = {
    "prepare-release": set(),
    "publish-maven-central": {
        "prepare-release",
        "build-openapi-spec",
        "build-agent-resources",
        "build-setup-bundles",
    },
    "build-openapi-spec": {"prepare-release"},
    "publish-openapi-spec": {
        "prepare-release",
        "build-openapi-spec",
        "build-agent-resources",
        "build-setup-bundles",
    },
    "build-cli": {"prepare-release"},
    "build-agent-resources": {"prepare-release"},
    "publish-agent-resources": {
        "prepare-release",
        "build-openapi-spec",
        "build-agent-resources",
        "build-setup-bundles",
    },
    "build-indexer": {"prepare-release"},
    "build-setup-bundles": {"prepare-release", "build-cli", "build-indexer"},
    "publish-setup-bundles": {
        "prepare-release",
        "build-openapi-spec",
        "build-agent-resources",
        "build-setup-bundles",
    },
    "build-release-metadata": {
        "prepare-release",
        "publish-openapi-spec",
        "publish-agent-resources",
        "publish-setup-bundles",
    },
    "publish-release": {"prepare-release", "build-release-metadata"},
    "verify-release-state": {"prepare-release", "publish-release"},
}
for task_id, needs in expected_needs.items():
    if set(tasks[task_id]["needs"]) != needs:
        raise SystemExit(f"{task_id} has the wrong dependencies")

remaining = set(tasks)
resolved = set()
while remaining:
    ready = {task_id for task_id in remaining if set(tasks[task_id]["needs"]) <= resolved}
    if not ready:
        raise SystemExit("release graph contains a dependency cycle")
    resolved |= ready
    remaining -= ready

output_owners = {}
for task_id, task in tasks.items():
    for output in task["outputs"]:
        if output in output_owners:
            raise SystemExit(f"duplicate release graph output: {output}")
        output_owners[output] = task_id
if set(output_owners) != set(model["requiredOutputs"]):
    raise SystemExit("release graph outputs must match the required proof set")
PY

printf '%s\n' 'release setup workflow contract passed'
