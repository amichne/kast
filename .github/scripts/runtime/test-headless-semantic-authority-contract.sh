#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd -- "$repo_root"

fail() {
  printf 'headless semantic authority contract: %s\n' "$*" >&2
  exit 1
}

classifier="cli-rs/src/execution/runtime/backend/headless_authority.rs"
contract_script=".github/scripts/runtime/test-headless-semantic-authority-contract.sh"
rust_idea_pattern='BackendName::Idea'
rust_retired_selection_pattern='--backend(?:=|\s+)idea|"--backend"\s*\)?\s*,(?:\s*OsString::from\()?\s*"idea"|(?:backendName|defaultBackend|backend_name|\bbackend)"?\s*[:=]\s*[\\]?"idea[\\]?"'
kotlin_idea_pattern='backendName[[:space:]]*=[[:space:]]*"idea"|RuntimeBackendKind\.IDEA|defaultBackendName'

rust_matches=""
if rust_matches="$(rg -n --glob '*.rs' "$rust_idea_pattern" cli-rs/src)"; then
  unexpected_rust_matches="$(
    printf '%s\n' "$rust_matches" \
      | awk -v allowed_prefix="${classifier}:" 'index($0, allowed_prefix) != 1'
  )"
  if [[ -n "$unexpected_rust_matches" ]]; then
    printf '%s\n' "$unexpected_rust_matches" >&2
    fail "Rust IDEA semantic identity exists outside the legacy-ingress classifier"
  fi
fi

[[ -f "$classifier" ]] || fail "missing legacy-ingress classifier: $classifier"
classifier_match_count="$(rg -c "$rust_idea_pattern" "$classifier" || true)"
[[ "$classifier_match_count" == "1" ]] \
  || fail "legacy-ingress classifier must contain exactly one IDEA identity match; found ${classifier_match_count:-0}"

retired_selection_probes=(
  '--backend idea'
  '--backend=idea'
  $'OsString::from("--backend"),\nOsString::from("idea"),'
  '{"backendName":"idea"}'
  'defaultBackend = \"idea\"'
  'backend_name: "idea".to_string()'
)
for probe in "${retired_selection_probes[@]}"; do
  if ! printf '%s\n' "$probe" | rg -q -U --pcre2 -- "$rust_retired_selection_pattern"; then
    fail "Rust retired-selector pattern does not recognize its focused proof: $probe"
  fi
done
if printf '%s\n' \
  '--backend headless' \
  'OsString::from("--backend"), OsString::from("headless")' \
  '{"backendName":"headless"}' \
  'defaultBackend = \"headless\"' \
  | rg -q -U --pcre2 -- "$rust_retired_selection_pattern"; then
  fail "Rust retired-selector pattern rejects a headless selector"
fi

retired_selection_files=""
if retired_selection_files="$(rg -l -U --pcre2 \
  --glob '*.rs' \
  --glob '!**/tests/**' \
  -- "$rust_retired_selection_pattern" \
  cli-rs/src)"; then
  unexpected_selection_files="$(
    printf '%s\n' "$retired_selection_files" \
      | awk -v allowed="$classifier" '$0 != allowed'
  )"
  if [[ -n "$unexpected_selection_files" ]]; then
    while IFS= read -r candidate; do
      rg -n -U --pcre2 -- "$rust_retired_selection_pattern" "$candidate" >&2 || true
    done <<< "$unexpected_selection_files"
    fail "Rust production selects the retired IDEA backend outside the legacy-ingress classifier"
  fi
fi

kotlin_matches=""
if kotlin_matches="$(rg -n --glob '*.kt' "$kotlin_idea_pattern" \
  analysis-api/src/main/kotlin \
  analysis-server/src/main/kotlin \
  backend-headless/src/main/kotlin \
  backend-idea/src/main/kotlin \
  index-store/src/main/kotlin)"; then
  printf '%s\n' "$kotlin_matches" >&2
  fail "Kotlin production sources retain foreground IDEA semantic identity"
fi

duplicate_allowlists=""
candidate_scripts=""
if candidate_scripts="$(rg -l --fixed-strings "$classifier" .github/scripts \
  --glob '*.sh' \
  --glob "!${contract_script}")"; then
  while IFS= read -r candidate; do
    if rg -q "$rust_idea_pattern" "$candidate"; then
      duplicate_allowlists+="${candidate}"$'\n'
    fi
  done <<< "$candidate_scripts"
fi
if [[ -n "$duplicate_allowlists" ]]; then
  printf '%s\n' "$duplicate_allowlists" >&2
  fail "a second headless-authority allowlist exists"
fi

printf '%s\n' 'headless semantic authority contract: ok'
