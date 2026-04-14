# End-to-end request lifecycle

This page answers a simple question: how does a Kast request move from a user or
agent prompt to a semantic result or applied mutation?

## Short answer

The request lifecycle starts in the CLI or an agent wrapper, crosses the local
JSON-RPC boundary, reaches the standalone backend, and then fans out into
workspace modeling, session state, indices, and semantic operations before the
result is serialized back to the caller.

## Analysis

The first stage is request construction. A human calls the CLI directly, or an
agent uses the skill wrappers described in [[concepts/llm-agent-workflows]]. In
either case, the caller chooses a command such as `symbol resolve`,
`references`, `call hierarchy`, `diagnostics`, or `rename`.

The second stage is runtime resolution. The CLI discovers the correct binary and
finds or launches a daemon instance, using the discovery cascade and descriptor
files described in [[concepts/installation-and-instance-management]] and
[[concepts/client-daemon-architecture]].

The third stage is transport dispatch. `analysis-server` receives the JSON-RPC
request, validates it, and routes it to the backend method exposed through
`analysis-api`. This is where transport concerns end and semantic work begins.

The fourth stage is backend execution. `backend-standalone` ensures the session
is ready, refreshes workspace state if needed, consults indices or caches, and
then performs the requested semantic operation. For hierarchy or reference
queries, indexing and workspace modeling heavily shape performance. For rename
and edit application, validation and conflict awareness become part of the flow.

The final stage is response shaping. The backend returns typed results, the
server serializes them back over JSON-RPC, and the CLI or wrapper emits
human-readable text or structured JSON for the caller.

## Evidence used

The pages below support this lifecycle.

- [[entities/kast-cli]]
- [[entities/analysis-api]]
- [[entities/analysis-server]]
- [[entities/backend-standalone]]
- [[concepts/workspace-discovery-and-module-modeling]]
- [[concepts/indexing-and-caching]]

## Follow-ups

These are the next useful expansions for this analysis.

- Add a query-specific companion page for rename and apply-edits if you want a
  deeper mutation trace.
- Add measured cold-start and warm-path timings if performance questions become
  important.
