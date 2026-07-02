#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"

if [[ -z "${KAST_BIN:-}" ]]; then
  cargo build --manifest-path "${repo_root}/cli-rs/Cargo.toml" --bin kast --locked
  export KAST_BIN="${repo_root}/cli-rs/target/debug/kast"
fi

printf 'Kast skill eval agent answer shape: %s\n' "${KAST_SKILL_EVAL_AGENT_OUTPUT_SHAPE:-text}"
"${repo_root}/.github/scripts/run-kast-format-impact-report.sh"
"${repo_root}/.github/scripts/run-kast-routing-format-impact-report.sh"

printf '%s\n' "Kast skill eval JSON/TOON comparison reports complete"
