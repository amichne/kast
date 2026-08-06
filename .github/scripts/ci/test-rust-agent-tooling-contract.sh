#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'Rust agent tooling contract: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
toolchain="${repo_root}/rust-toolchain.toml"
metadata_command="${repo_root}/scripts/rust-agent-metadata.sh"
nextest_config="${repo_root}/cli-rs/.config/nextest.toml"
deny_config="${repo_root}/cli-rs/.config/deny.toml"
agent_guide="${repo_root}/cli-rs/AGENTS.md"
workflow="${repo_root}/.github/workflows/ci.yml"
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-rust-agent-tooling.XXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT

[[ -f "$toolchain" ]] || die "missing rust-toolchain.toml"
grep -Fq 'channel = "stable"' "$toolchain" \
  || die "the Rust toolchain must follow stable"
for component in rust-analyzer rust-src rustfmt clippy; do
  grep -Fq "\"${component}\"" "$toolchain" \
    || die "the Rust toolchain must install ${component}"
done

[[ -x "$metadata_command" ]] || die "missing executable agent metadata command"
metadata_output="${scratch_dir}/metadata.json"
"$metadata_command" >"$metadata_output"
python3 - "$metadata_output" <<'PY'
import json
import sys
from pathlib import Path

document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
packages = document.get("packages", [])
if [package.get("name") for package in packages] != ["kast"]:
    raise SystemExit("agent metadata must describe exactly the Kast Rust package")
targets = packages[0].get("targets", [])
if not any("bin" in target.get("kind", []) for target in targets):
    raise SystemExit("agent metadata must retain the Kast binary target")
if not any("test" in target.get("kind", []) for target in targets):
    raise SystemExit("agent metadata must retain Rust test targets")
PY

[[ -f "$nextest_config" ]] || die "missing nextest repository configuration"
for profile_name in default ci; do
  awk -v target="[profile.${profile_name}]" '
    $0 == target { in_target = 1; next }
    /^\[/ && in_target { exit }
    in_target && $0 ~ /^[[:space:]]*test-threads[[:space:]]*=[[:space:]]*4[[:space:]]*$/ {
      found = 1
    }
    END { exit found ? 0 : 1 }
  ' "$nextest_config" \
    || die "nextest profile ${profile_name} must bound concurrent tests at 4"
done
grep -Fq '[profile.default]' "$nextest_config" \
  || die "nextest must define a local default profile"
grep -Fq '[profile.ci]' "$nextest_config" \
  || die "nextest must define a CI profile"
[[ "$(grep -Fc 'slow-timeout = "15s"' "$nextest_config")" -eq 2 ]] \
  || die "local and CI nextest profiles must report tests slower than 15 seconds"
[[ "$(grep -Fc 'retries = 0' "$nextest_config")" -eq 2 ]] \
  || die "local and CI nextest profiles must not hide flaky tests with retries"
grep -Fq '[profile.ci.junit]' "$nextest_config" \
  || die "the CI nextest profile must emit JUnit"
grep -Fq 'path = "junit.xml"' "$nextest_config" \
  || die "the CI nextest profile must use the stable JUnit report path"

[[ -f "$deny_config" ]] || die "missing cargo-deny policy"
for section in advisories licenses bans sources; do
  grep -Fq "[${section}]" "$deny_config" \
    || die "cargo-deny policy must configure ${section}"
done

for command_fragment in \
  'scripts/rust-agent-metadata.sh' \
  '--message-format=json-diagnostic-rendered-ansi' \
  'ast-grep run --lang rust' \
  'cargo nextest run'; do
  grep -Fq -- "$command_fragment" "$agent_guide" \
    || die "Rust agent guide is missing: ${command_fragment}"
done

[[ "$(grep -Fc 'taiki-e/install-action@cb33e69fad06166ca28a42b2575e4dadabf62ee8' "$workflow")" -eq 2 ]] \
  || die "CI must install nextest and cargo-deny through the pinned installer"
for workflow_fragment in \
  'tool: nextest' \
  'tool: cargo-deny' \
  './.github/scripts/ci/test-rust-agent-tooling-contract.sh' \
  'cargo deny --locked --all-features --config .config/deny.toml check' \
  'cargo nextest run --locked --all-targets --all-features --profile ci' \
  'cli-rs/target/nextest/ci/junit.xml'; do
  grep -Fq -- "$workflow_fragment" "$workflow" \
    || die "Rust CI is missing: ${workflow_fragment}"
done

printf '%s\n' 'Rust agent tooling contract passed'
