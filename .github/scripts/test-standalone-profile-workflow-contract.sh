#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/standalone-profile.yml"

fail() {
  printf 'standalone-profile workflow contract failed: %s\n' "$*" >&2
  exit 1
}

[[ -f "$workflow" ]] || fail "missing $workflow"

require_text() {
  local needle="$1"
  grep -Fq -- "$needle" "$workflow" || fail "missing required workflow text: $needle"
}

require_text "workflow_dispatch:"
require_text "default: ktor"
require_text "- ktor"
require_text "permissions:"
require_text "contents: read"
require_text "actions/checkout@v5"
require_text "actions/setup-java@v5"
require_text "gradle/actions/setup-gradle@v5"
require_text "scripts/profile-standalone-large-repo.sh"
require_text "--target \"\${{ inputs.target }}\""
require_text "--duration \"\${{ inputs.duration_seconds }}\""
require_text "--profile-modes \"\${{ inputs.profile_modes }}\""
require_text "actions/upload-artifact@v6"
require_text ".benchmarks/standalone-profile/results/"

if grep -Eq 'pull_request:|push:' "$workflow"; then
  fail "profiling workflow must stay manual-only unless artifact cost is explicitly accepted"
fi
