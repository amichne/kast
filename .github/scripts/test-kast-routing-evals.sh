#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
metric_pack_dir="${repo_root}/.github/plugin-eval/kast-routing"
target="${repo_root}/cli-rs/resources/kast-skill"
tmp_file="$(mktemp "${TMPDIR:-/tmp}/kast-routing-evals.XXXXXX.json")"
tools_file="$(mktemp "${TMPDIR:-/tmp}/kast-routing-tools.XXXXXX.json")"
trap 'rm -f -- "$tmp_file" "$tools_file"' EXIT

if [[ -n "${KAST_BIN:-}" ]]; then
  kast_bin="${KAST_BIN}"
else
  cargo build --manifest-path "${repo_root}/cli-rs/Cargo.toml" --bin kast --locked
  kast_bin="${repo_root}/cli-rs/target/debug/kast"
fi

"$kast_bin" agent tools >"$tools_file"
KAST_AGENT_TOOLS_FILE="$tools_file" node "${metric_pack_dir}/emit-kast-routing-metrics.mjs" "$target" skill >"$tmp_file"

node --input-type=module - "$tmp_file" <<'NODE'
import { readFileSync } from "node:fs";

const payload = JSON.parse(readFileSync(process.argv[2], "utf8"));
const failed = payload.checks.filter((check) => check.status !== "pass");
if (failed.length > 0) {
  for (const check of failed) {
    console.error(`${check.id}: ${check.message}`);
    for (const evidence of check.evidence ?? []) {
      console.error(`  - ${evidence}`);
    }
  }
  process.exit(1);
}
const score = payload.metrics.find((metric) => metric.id === "kast-routing-score")?.value;
if (score !== 100) {
  throw new Error(`expected kast-routing-score=100, got ${score}`);
}
console.log(JSON.stringify({ ok: true, checks: payload.checks.length, score }));
NODE

printf '%s\n' "Kast routing evals passed"
