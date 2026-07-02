#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
target="${repo_root}/cli-rs/resources/kast-skill"
metric_pack_dir="${repo_root}/.github/plugin-eval/kast-routing-format-impact"
report_dir="${repo_root}/cli-rs/target/routing-format-impact"
observed_jsonl="${report_dir}/observed.jsonl"
answer_requests_jsonl="${report_dir}/answer-requests.jsonl"
summary_json="${report_dir}/summary.json"
agent_output_shape="${KAST_ROUTING_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE:-${KAST_SKILL_EVAL_AGENT_OUTPUT_SHAPE:-text}}"

case "$agent_output_shape" in
  text|json|toon) ;;
  *)
    printf 'Unsupported agent output shape: %s\n' "$agent_output_shape" >&2
    printf 'Use text, json, or toon via KAST_ROUTING_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE or KAST_SKILL_EVAL_AGENT_OUTPUT_SHAPE.\n' >&2
    exit 64
    ;;
esac

mkdir -p "$report_dir"

if [[ -n "${KAST_BIN:-}" ]]; then
  kast_bin="${KAST_BIN}"
else
  cargo build --manifest-path "${repo_root}/cli-rs/Cargo.toml" --bin kast --locked
  kast_bin="${repo_root}/cli-rs/target/debug/kast"
fi

answer_args=()
if [[ -n "${KAST_ROUTING_FORMAT_IMPACT_ANSWERS_JSONL:-}" ]]; then
  answer_args+=(--answers "$KAST_ROUTING_FORMAT_IMPACT_ANSWERS_JSONL")
fi

cargo run \
  --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
  --locked \
  --example format_impact_report \
  -- \
  --kast-bin "$kast_bin" \
  --target "$target" \
  --suite routing \
  --agent-output-shape "$agent_output_shape" \
  --output "$observed_jsonl" \
  --answer-requests "$answer_requests_jsonl" \
  "${answer_args[@]}" \
  >"$summary_json"

KAST_ROUTING_FORMAT_IMPACT_OBSERVED_JSONL="$observed_jsonl" \
  node "${metric_pack_dir}/emit-kast-routing-format-impact-metrics.mjs" "$target" skill

printf 'Kast routing format impact report written: %s\n' "$observed_jsonl"
printf 'Kast routing format impact answer requests written: %s\n' "$answer_requests_jsonl"
printf 'Kast routing format impact summary written: %s\n' "$summary_json"
printf 'Kast routing format impact agent answer shape: %s\n' "$agent_output_shape"
