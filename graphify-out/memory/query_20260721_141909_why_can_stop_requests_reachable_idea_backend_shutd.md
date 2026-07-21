---
type: "query"
date: "2026-07-21T14:19:09.865377+00:00"
question: "Why can stop_requests_reachable_idea_backend_shutdown hang?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["stop_requests_reachable_idea_backend_shutdown", "request_runtime_lifecycle", "raw_wait_for_close"]
---

# Q: Why can stop_requests_reachable_idea_backend_shutdown hang?

## Answer

GitHub Actions run 29804261942 showed the test remained active for about 47 minutes after the other runtime lifecycle tests passed. Production lifecycle shutdown uses a bounded five-second RPC response wait, but the test's bespoke UnixListener used blocking accept and read calls and then joined the server thread. If the expected three-request sequence was incomplete, the harness waited forever. The fake server now uses a nonblocking accept loop with a 15-second deadline and a five-second client read timeout, preserving the sequence assertion while bounding failure.

## Outcome

- Signal: useful

## Source Nodes

- stop_requests_reachable_idea_backend_shutdown
- request_runtime_lifecycle
- raw_wait_for_close