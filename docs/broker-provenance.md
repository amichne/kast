# Codex tool broker provenance

Kast's Kotlin broker preserves the product behavior imported from the Slopsentral `broker/` tree
at tag `broker-v0.5.0`, commit `f18ab46`, including the typed Kast projection-v2 adaptation from
commit `9a5fb49`.

The imported behavior is implemented directly in Kast rather than shipping the original Node.js
program. The installed product has no Node runtime requirement. `kast broker serve` owns the Codex
control socket, qualifies the exact installed Codex and Kast contracts, starts the private Codex
App Server, and exposes Gradle and Kast tools through proof-carrying Kotlin domain types.
The public socket accepts Codex's canonical `/rpc` WebSocket route and the legacy `/` route. A
process-lifetime filesystem lease serializes stale-socket recovery, and service readiness is proven
by a complete `/rpc` WebSocket `initialize` exchange rather than raw socket connectivity.

Codex protocol authority comes from the installed CLI itself. At startup Kast executes
`codex app-server generate-json-schema --experimental`, bounds and compiles the generated schemas,
and refuses startup when a broker-owned protocol shape cannot be proven. This is used because
OpenAI currently provides a TypeScript Codex SDK but no Kotlin/JVM SDK for the App Server protocol.

The retained broker version is `0.5.0`. Its behavioral defaults remain eight in-flight calls per
connection, four per provider, a 1 MiB catalog, 64 provider descriptors, 64 KiB tool arguments,
1 MiB tool results, a 10-second provider startup deadline, and a 30-second provider invocation
deadline.

Admitted dynamic-tool invocations publish one payload-free start event and one finite terminal
event to `$CODEX_HOME/broker/service.log`. Each JSON line carries only thread, turn, call,
namespace, tool, and completion identity; tool arguments, results, and working-directory content
never enter this activity stream.

The active WebSocket session has a separate, user-facing presentation boundary. For namespaces
owned by Kast, dynamic-tool lifecycle items are projected one-for-one to Codex's `mcpToolCall`
shape before they reach the downstream CLI. This observer projection retains call identity,
status, and duration while replacing semantic capability arguments such as selectors,
continuations, and change plans with typed display placeholders. The exact arguments still reach
Kast unchanged, and Kast's canonical result still returns unchanged to the App Server and model.

For successful live `symbol.discover`, `symbol.inspect`, `source.read`, `relation.read`, and
`traversal.run` calls, the broker may
consume a bounded, process-local presentation at the corresponding completed lifecycle event. It
then emits the sanitized MCP completion followed by a schema-admitted `agentMessage` whose phase
is `commentary` and whose text is Markdown. When that companion is available, the MCP result is an
empty accepted result rather than a duplicate of the canonical model document. Presentation
capacity exhaustion, projection failure, or rejection by the installed Codex contract suppresses
the companion without changing tool execution or its model-facing reply. No presentation state is
persisted, and historical companions are not reconstructed after restart or resume.

Representative observer screenshots are generated without a Codex session or model request:

```bash
./gradlew :cli:renderKastObserverScreenshots
```

The task runs the real Kotlin observer projector over deterministic, schema-shaped fixtures, then
uses Pandoc and a local headless Chrome/Chromium executable to render the resulting Markdown. It
updates `docs/public/images/kast-observer-symbol-source.png` and
`docs/public/images/kast-observer-semantic-impact.png`. Set `KAST_OBSERVER_PANDOC` or
`KAST_OBSERVER_CHROME` when those executables are not on their usual paths. The renderer rejects
opaque selectors, fingerprints, workspace roots, and source selectors before capturing any image.

Final turn snapshots, thread start/resume/fork and read/mutation responses, thread list/search,
review and queued-turn responses, and turns/items/timeline pages continue to use the MCP
projection. Startup qualification proves the dynamic source and rendered MCP shapes against every
installed item-bearing schema. Other namespaces remain byte-exact pass-through.
