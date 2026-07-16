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

repo_root="$(resolve_repo_root)"
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-local-refresh-contract.XXXXXX")"
tmp_root="$(cd -- "$tmp_root" && pwd -P)"
cleanup() {
  local guidance="${repo_root}/AGENTS.local.md"
  if [[ -L "$guidance" ]] \
    && [[ "$(readlink "$guidance")" == "${tmp_root}/missing-prefix/current/guidance/AGENTS.local.md" ]]; then
    rm -f "$guidance"
  fi
  rm -rf "$tmp_root"
}
trap cleanup EXIT

refresh_help="$(${repo_root}/gradlew -q help --task refreshDevelopmentLocal)"
grep -Fq 'Refreshes one revision-coherent local Kast development authority.' <<<"$refresh_help" \
  || die 'refreshDevelopmentLocal must describe the revision-coherent local authority boundary'
rollback_help="$(${repo_root}/gradlew -q help --task rollbackDevelopmentLocal)"
grep -Fq 'Idempotently reactivates the explicitly selected validated previous local generation.' <<<"$rollback_help" \
  || die 'rollbackDevelopmentLocal must expose validated generation rollback'
remove_help="$(${repo_root}/gradlew -q help --task removeDevelopmentLocal)"
grep -Fq 'Removes only receipt-owned local Kast state and restores ordinary authority.' <<<"$remove_help" \
  || die 'removeDevelopmentLocal must expose receipt-owned removal'

local_help="$(
  cargo run \
    --quiet \
    --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
    --locked \
    --bin kast \
    -- \
    developer local refresh --help
)"
grep -Fq 'Refresh one isolated, revision-coherent local development authority' <<<"$local_help" \
  || die 'developer local refresh must expose the typed local refresh boundary'

attest_help="$(
  cargo run \
    --quiet \
    --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
    --locked \
    --bin kast \
    -- \
    developer local attest --help
)"
grep -Fq 'Bind one built artifact to the captured source snapshot and its exact bytes' <<<"$attest_help" \
  || die 'developer local attest must expose source-bound artifact provenance'

rollback_cli_help="$(
  cargo run \
    --quiet \
    --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
    --locked \
    --bin kast \
    -- \
    developer local rollback --help
)"
grep -Fq -- '--to-generation <TO_GENERATION>' <<<"$rollback_cli_help" \
  || die 'developer local rollback must require an explicit generation target'

grep -Fxq '/AGENTS.local.md' "${repo_root}/.gitignore" \
  || die 'local guidance must be ignored by the source-owned root .gitignore'
grep -Fxq '/.kast/' "${repo_root}/.gitignore" \
  || die 'the default local authority prefix and stable namespace lock must be source-ignored'

snapshot_file="${tmp_root}/source-snapshot.json"
snapshot_json="$(
  cargo run \
    --quiet \
    --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
    --locked \
    --bin kast \
    -- \
    --output json \
    developer local snapshot \
    --source-root "$repo_root" \
    --output-file "$snapshot_file"
)"
expected_commit="$(git -C "$repo_root" rev-parse HEAD)"
[[ -f "$snapshot_file" ]] || die 'local snapshot must write the requested strict snapshot file'
grep -Fq "\"gitCommit\": \"${expected_commit}\"" "$snapshot_file" \
  || die 'local snapshot must record the exact Git commit'
grep -Eq '"sourceTreeSha256": "[0-9a-f]{64}"' "$snapshot_file" \
  || die 'local snapshot must record a SHA-256 over current checkout content'
grep -Fq '"sourceTreeSha256"' <<<"$snapshot_json" \
  || die 'local snapshot must print machine-readable source identity'

cli_binary="${repo_root}/cli-rs/target/debug/kast"
cli_provenance="${tmp_root}/cli-provenance.json"
set +e
"$cli_binary" \
  --output json \
  developer local attest \
  --source-root "$repo_root" \
  --expected-source-snapshot "$snapshot_file" \
  --artifact-kind cli \
  --artifact "$cli_binary" \
  --output-file "$cli_provenance" >"${tmp_root}/attest-output.json" 2>&1
attest_status=$?
set -e
[[ "$attest_status" -ne 0 ]] \
  || die 'an ordinary Cargo binary must not be relabeled as source-bound local CLI output'
grep -Fq 'LOCAL_CLI_SOURCE_ATTESTATION_MISSING' "${tmp_root}/attest-output.json" \
  || die 'ordinary Cargo bytes must fail with typed missing source attestation'
[[ ! -e "$cli_provenance" ]] \
  || die 'failed CLI attestation must not publish a provenance record'

dry_run="$(${repo_root}/gradlew -m refreshDevelopmentLocal --no-daemon)"
for required_task in \
  buildLocalDevelopmentCli \
  captureDevelopmentSourceSnapshot \
  rebuildLocalDevelopmentCli \
  syncPortableDist \
  writeLocalBackendComponentManifest \
  stageDevelopmentBackend \
  attestDevelopmentCli \
  attestDevelopmentBackend \
  refreshDevelopmentLocal; do
  grep -Fq ":${required_task}" <<<"$dry_run" \
    || die "refreshDevelopmentLocal must include ${required_task}"
done
capture_line="$(grep -nF ':captureDevelopmentSourceSnapshot' <<<"$dry_run" | head -1 | cut -d: -f1)"
rebuild_line="$(grep -nF ':rebuildLocalDevelopmentCli' <<<"$dry_run" | head -1 | cut -d: -f1)"
backend_line="$(grep -nF ':backend-headless:syncPortableDist' <<<"$dry_run" | head -1 | cut -d: -f1)"
[[ "$capture_line" -lt "$rebuild_line" && "$capture_line" -lt "$backend_line" ]] \
  || die 'CLI and backend producer tasks must run after the captured source snapshot'
if grep -Eq 'installDevelopmentIdeaPlugin|configureDevelopmentMachineDefaults' <<<"$dry_run"; then
  die 'local refresh must not mutate a user JetBrains profile or release configuration'
fi
grep -Fq '"-Dkast.idea.autostart=false"' "${repo_root}/backend-headless/build.gradle.kts" \
  || die 'headless startup must disable the unrelated IDEA project-open profile hook before JVM bootstrap'

rollback_dry_run="$(${repo_root}/gradlew -m rollbackDevelopmentLocal -PkastLocalGeneration=test-generation --no-daemon)"
grep -Fq ':rollbackDevelopmentLocal' <<<"$rollback_dry_run" \
  || die 'rollbackDevelopmentLocal must remain directly executable'
if grep -Fq ':buildLocalDevelopmentCli' <<<"$rollback_dry_run"; then
  die 'rollbackDevelopmentLocal must not rebuild the checkout it is recovering'
fi
remove_dry_run="$(${repo_root}/gradlew -m removeDevelopmentLocal --no-daemon)"
grep -Fq ':removeDevelopmentLocal' <<<"$remove_dry_run" \
  || die 'removeDevelopmentLocal must remain directly executable'
if grep -Fq ':buildLocalDevelopmentCli' <<<"$remove_dry_run"; then
  die 'removeDevelopmentLocal must not rebuild the checkout it is recovering'
fi

recovery_prefix="${tmp_root}/recovery-prefix"
recovery_log="${tmp_root}/recovery-args.txt"
mkdir -p "${recovery_prefix}/bin"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'printf "%s\n" "$@" >"${KAST_RECOVERY_LOG:?}"' \
  >"${recovery_prefix}/bin/kast-dev"
chmod 755 "${recovery_prefix}/bin/kast-dev"
KAST_RECOVERY_LOG="$recovery_log" CARGO="${tmp_root}/unbuildable-cargo" \
  "${repo_root}/gradlew" rollbackDevelopmentLocal \
    -PkastLocalPrefix="$recovery_prefix" \
    -PkastLocalGeneration=test-generation \
    --no-daemon >/dev/null
grep -Fxq 'rollback' "$recovery_log" \
  || die 'rollbackDevelopmentLocal must execute the installed stable controller'
grep -Fxq 'test-generation' "$recovery_log" \
  || die 'rollbackDevelopmentLocal must forward the explicit generation'
KAST_RECOVERY_LOG="$recovery_log" CARGO="${tmp_root}/unbuildable-cargo" \
  "${repo_root}/gradlew" removeDevelopmentLocal \
    -PkastLocalPrefix="$recovery_prefix" \
    -PkastLocalWorkspaceRoot="$repo_root" \
    --no-daemon >/dev/null
grep -Fxq 'remove' "$recovery_log" \
  || die 'removeDevelopmentLocal must execute the installed stable controller'

cargo build \
  --quiet \
  --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
  --locked
missing_prefix="${tmp_root}/missing-prefix"
missing_guidance="${repo_root}/AGENTS.local.md"
ln -s "${missing_prefix}/current/guidance/AGENTS.local.md" "$missing_guidance"
KAST_RECOVERY_LOG="$recovery_log" CARGO="${tmp_root}/unbuildable-cargo" \
  "${repo_root}/gradlew" removeDevelopmentLocal \
    -PkastLocalPrefix="$missing_prefix" \
    -PkastLocalWorkspaceRoot="$repo_root" \
    -PkastLocalRecoveryController="${repo_root}/cli-rs/target/debug/kast" \
    --no-daemon >/dev/null
[[ ! -e "$missing_guidance" && ! -L "$missing_guidance" ]] \
  || die 'removeDevelopmentLocal must clean owned dangling guidance after the prefix is missing'

legacy_install_dry_run="$(${repo_root}/gradlew -m installDevelopmentLocal --no-daemon)"
for required_task in installDevelopmentCli installDevelopmentIdeaPlugin configureDevelopmentMachineDefaults; do
  grep -Fq ":${required_task}" <<<"$legacy_install_dry_run" \
    || die "installDevelopmentLocal must preserve its established ${required_task} contract"
done

cargo test \
  --quiet \
  --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
  --locked \
  local_development::
cargo test \
  --quiet \
  --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
  --locked \
  --test local_development_refresh_smoke

printf '%s\n' 'Local development refresh contract passed'
