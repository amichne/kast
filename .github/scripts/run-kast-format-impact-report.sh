#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
target="${repo_root}/cli-rs/resources/kast-skill"
metric_pack_dir="${repo_root}/.github/plugin-eval/kast-format-impact"
report_dir="${repo_root}/cli-rs/target/format-impact"
observed_jsonl="${report_dir}/observed.jsonl"
summary_json="${report_dir}/summary.json"

mkdir -p "$report_dir"

if [[ -n "${KAST_BIN:-}" ]]; then
  kast_bin="${KAST_BIN}"
else
  cargo build --manifest-path "${repo_root}/cli-rs/Cargo.toml" --bin kast --locked
  kast_bin="${repo_root}/cli-rs/target/debug/kast"
fi

cargo run \
  --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
  --locked \
  --example format_impact_report \
  -- \
  --kast-bin "$kast_bin" \
  --target "$target" \
  --output "$observed_jsonl" \
  >"$summary_json"

KAST_FORMAT_IMPACT_OBSERVED_JSONL="$observed_jsonl" \
  node "${metric_pack_dir}/emit-kast-format-impact-metrics.mjs" "$target" skill

printf 'Kast format impact report written: %s\n' "$observed_jsonl"
printf 'Kast format impact summary written: %s\n' "$summary_json"
