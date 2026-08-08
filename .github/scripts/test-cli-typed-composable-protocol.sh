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

cargo test \
  --manifest-path cli-rs/Cargo.toml \
  --locked \
  --test agent_operation_surface_smoke \
  public_operation_registry_is_complete_typed_and_callable \
  -- \
  --exact

cargo test \
  --manifest-path cli-rs/Cargo.toml \
  --locked \
  --test agent_operation_surface_smoke \
  every_required_public_projection_is_checked_in_and_registry_bound \
  -- \
  --exact

cargo test \
  --manifest-path cli-rs/Cargo.toml \
  --locked \
  --test kast_agent_surface \
  typed_selector_vertical_slice_exposes_only_canonical_routes \
  -- \
  --exact

cargo test \
  --manifest-path cli-rs/Cargo.toml \
  --locked \
  --test kast_agent_surface \
  selectors_round_trip_verbatim_across_the_overloaded_vertical_slice \
  -- \
  --exact

cargo test \
  --manifest-path cli-rs/Cargo.toml \
  --locked \
  --test kast_agent_surface \
  exact_routes_reject_substitutes_through_closed_selector_authentication \
  -- \
  --exact
