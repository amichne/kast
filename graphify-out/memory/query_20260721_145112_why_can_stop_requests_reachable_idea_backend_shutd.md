---
type: "query"
date: "2026-07-21T14:51:12.600791+00:00"
question: "Why can stop_requests_reachable_idea_backend_shutdown hang on Linux CI?"
contributor: "graphify"
outcome: "corrected"
correction: "The unbounded fake-server accept explained the indefinite symptom, but the deterministic root cause was missing explicit IDEA backend selection on Linux, evidenced by the empty request list in CI."
source_nodes: ["stop_requests_reachable_idea_backend_shutdown", "runtime_backend_preference", "kast"]
---

# Q: Why can stop_requests_reachable_idea_backend_shutdown hang on Linux CI?

## Answer

Run 29839812224 converted the hang into a bounded failure and proved the fake server received zero requests. The test's stop command omitted --backend idea and relied on KAST_CONFIG_HOME/config.toml. After installation path authority changed, that environment override no longer selected IDEA; Linux defaulted to headless while macOS still selected IDEA. The sibling restart test already passed --backend idea. Adding the same explicit backend selection is the root fix.

## Outcome

- Signal: corrected
- Correction: The unbounded fake-server accept explained the indefinite symptom, but the deterministic root cause was missing explicit IDEA backend selection on Linux, evidenced by the empty request list in CI.

## Source Nodes

- stop_requests_reachable_idea_backend_shutdown
- runtime_backend_preference
- kast