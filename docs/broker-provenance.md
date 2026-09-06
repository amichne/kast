# Codex tool broker provenance

Kast's Kotlin broker preserves the product behavior imported from the Slopsentral `broker/` tree
at tag `broker-v0.5.0`, commit `f18ab46`, including the typed Kast projection-v2 adaptation from
commit `9a5fb49`.

The imported behavior is implemented directly in Kast rather than shipping the original Node.js
program. The installed product has no Node runtime requirement. The control distribution now
includes `kast-codex`. This integration host qualifies the installed Codex and Kast contracts,
starts the broker and private Codex App Server, and launches the Codex client with
`codex --remote unix://<integration-socket>`. Exiting the client closes its integration server.

The host owns `$CODEX_HOME/kast-integration`, defaulting to `~/.codex/kast-integration`.
Its client socket and upstream state are separate from the legacy broker control socket
and `$CODEX_HOME/broker` state. Broker startup is no longer a public Kast command. The
integration host owns its transport; the semantic sidecar retains exact-root lifecycle ownership.
Both expose Gradle and admitted Kast tools through proof-carrying Kotlin domain types.
The public socket accepts Codex's canonical `/rpc` WebSocket route and the legacy `/` route. A
process-lifetime filesystem lease serializes stale-socket recovery, and service readiness is proven
by a complete `/rpc` WebSocket `initialize` exchange rather than raw socket connectivity.

Codex protocol authority comes from the installed CLI itself. At startup Kast executes
`codex app-server generate-json-schema --experimental`, bounds and compiles the generated schemas,
and refuses startup when a broker-owned protocol shape cannot be proven. This is used because
OpenAI currently provides a TypeScript Codex SDK but no Kotlin/JVM SDK for the App Server protocol.

The retained broker version is `0.5.0`. Its behavioral defaults remain eight in-flight calls per
connection, four per provider, a 1 MiB catalog, 64 provider descriptors, 64 KiB tool arguments,
1 MiB tool results. Kast execution deadlines now come from canonical operation metadata,
projected by server projection version 4: 10 seconds per local qualification command, 17 minutes
for workspace readiness, 60 seconds for a semantic operation, and 240 seconds for traversal and its lazy topology acquisition.
Provider qualification admits both local commands; each semantic subprocess receives the
combined readiness and operation budget from its canonical invocation metadata. The 17-minute operational ceiling preserves the existing
sidecar contract; release acceptance separately requires cold startup within 240 seconds.

Semantic commands and `kast start` demand the sidecar directly. They do not discover Codex,
read `CODEX_HOME`, or start the broker. A broker rejection cannot remove semantic CLI
capability. The provider runs the selected semantic command directly. It does not issue a
preceding `kast start`; automatic readiness belongs to the semantic CLI. Cancellation terminates
only that CLI wrapper. The detached sidecar remains owned by its exact bootstrap attempt;
bare `kast` exposes that state passively, and the next call joins that attempt or reuses its
ready endpoint without launching a duplicate.

The provider admits tools only when their canonical approval policy is `NONE`. Mutation tools
remain excluded while explicit approval routing is unresolved. Direct CLI change operations
must not be described as available mutation tools in the Codex integration.

The integration host has completed cold `symbol_lookup`, exact `symbol_inspect`, and repeated
`impact_analyze` requests through the real Codex TUI. The semantic CLI and installed schema
now exclude lifecycle mechanics from their public projections. Internal synchronization and
topology identities remain available to runtime composition. The repeatable provider test below
proves the local boundary; it does not substitute for a real authenticated Codex turn.

`:cli:installedColdBrokerAcceptance` executes the production provider and dispatcher against a
requested installed executable in a fresh workspace. Its JVM entry point is independently tested,
and the journey checks cold readiness, direct-CLI result equivalence, exact selector reuse, source
line presentation, and the read-only catalog without a cloud account. The bounded receipt retains
digests and elapsed time. A run against a staged product is integration evidence; the release gate
must additionally bind that journey to the exact verified release archives.

Admitted dynamic-tool invocations publish one payload-free start event and one finite terminal
event to the host-owned `service.log`: `$CODEX_HOME/kast-integration/service.log` for
`kast-codex`, or `$CODEX_HOME/broker/service.log` for the legacy host. Each JSON line carries only thread, turn, call,
namespace, tool, and completion identity; tool arguments, results, and working-directory content
never enter this activity stream.

The active WebSocket session has a separate, user-facing presentation boundary. For namespaces
owned by Kast, dynamic-tool lifecycle items are projected one-for-one to Codex's `mcpToolCall`
shape before they reach the downstream CLI. This observer projection retains call identity,
status, and duration while replacing semantic capability arguments such as selectors,
continuations, and change plans with typed display placeholders. The exact arguments still reach
Kast unchanged, and Kast's canonical result still returns unchanged to the App Server and model.

For successful live `symbol.discover`, `symbol.inspect`, `source.read`, `relation.read`,
`traversal.run`, and `diagnostic.check` calls, the broker may
consume a bounded, process-local presentation at the corresponding completed lifecycle event. It
then emits the sanitized MCP completion followed by a schema-admitted `agentMessage` whose phase
is `commentary` and whose text is Markdown. When that companion is available, the MCP result is an
empty accepted result rather than a duplicate of the canonical model document. Presentation
capacity exhaustion, projection failure, or rejection by the installed Codex contract suppresses
the companion without changing tool execution or its model-facing reply. No presentation state is persisted. Thread reload and resume reconstruct the same deterministic
companions from the canonical results and the historical working directory. This projection is
pure: it neither reads current repository files nor starts a runtime. Full thread and turn item
arrays can carry companions; standalone paginated item and timeline entries retain their ordinary
sanitized tool result because they do not carry a proven working directory or a companion slot.

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

Source outcome schema `kast.source.read.v3` includes inclusive one-based `text.lines` coordinates
when text is returned. They are derived from the full normalized committed document only after
its length and digest match the selected snapshot. The source companion shows those lines beside
the repository-relative file and bounded Kotlin block. Diagnostic companions show severity,
location, exact UTF-16 offsets, and message; selector and generation evidence stays in the
canonical model result. Older source outcomes without line evidence retain their file and code
presentation without invented coordinates.


Implementation authorities are [KastCodexMain](../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/KastCodexMain.kt),
[InstalledBrokerServer](../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/InstalledBrokerServer.kt),
and [KastProvider](../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/provider/KastProvider.kt).
The [installed provider acceptance](../cli/src/test/kotlin/io/github/amichne/kast/cli/broker/provider/InstalledColdBrokerAcceptance.kt)
and [lifecycle acceptance driver](../integration-tests/lifecycle_convergence_acceptance.py)
retain executable checks for these boundaries.
