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

for contract_test in \
  public_result_schema_is_closed_without_repeating_failure_algebra \
  golden_and_benchmark_fixtures_are_registry_bound_and_repeatable \
  current_public_artifacts_contain_no_retired_routes_or_aliases
do
  cargo test \
    --manifest-path cli-rs/Cargo.toml \
    --locked \
    --test agent_operation_surface_smoke \
    "${contract_test}" \
    -- \
    --exact
done

cargo test \
  --manifest-path cli-rs/Cargo.toml \
  --locked \
  --test kast_agent_surface \
  typed_selector_vertical_slice_exposes_only_canonical_routes \
  -- \
  --exact

for surface_test in \
  typed_output_protocol::json_and_toon_encode_the_same_canonical_result \
  typed_pagination::continuations_are_operation_bound_and_stale_closed \
  typed_graph_protocol::graph_continuations_reject_stale_generations \
  typed_exact_operations::graph_nodes_and_neighbors_use_a_distinct_node_selector \
  typed_exact_operations::one_issued_selector_round_trips_verbatim_through_every_relation_consumer \
  typed_mutation_operations::rejected_mutation_targets_never_enter_planning_or_create_plan_artifacts
do
  cargo test \
    --manifest-path cli-rs/Cargo.toml \
    --locked \
    --test kast_agent_surface \
    "${surface_test}" \
    -- \
    --exact
done

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
  --test kast_public_operations \
  terminal_state::public_apply_persists_stable_rejected_and_conflicted_outcomes \
  -- \
  --exact

cargo test \
  --manifest-path cli-rs/Cargo.toml \
  --locked \
  --test kast_agent_surface \
  typed_selector_rejections::exact_routes_reject_substitutes_through_closed_selector_authentication \
  -- \
  --exact
