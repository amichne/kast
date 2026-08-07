#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

cargo test \
  --manifest-path cli-rs/Cargo.toml \
  --locked \
  --test agent_operation_surface_smoke \
  semantic_contract_inventory_is_complete_and_machine_testable \
  -- \
  --exact
