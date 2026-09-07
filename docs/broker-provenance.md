# Hosted tool bootstrap and Codex broker provenance

Kast's Kotlin broker preserves behavior imported from the Slopsentral `broker/`
tree at tag `broker-v0.5.0`, commit `f18ab46`, including the typed Kast
projection-v2 adaptation from commit `9a5fb49`.

The behavior is implemented directly in Kast; the installed product has no Node
runtime requirement of its own. The control distribution includes one
`kast-codex` integration with two process and transport projections. The CLI
host owns its integration server and launches the installed Codex client through
`--remote`. The App Server host exposes bounded JSONL stdio to its parent and
connects that stream to the same integration server. Catalog qualification,
dispatch, and item presentation do not branch by host.

The host owns `$CODEX_HOME/kast-integration`, defaulting to
`~/.codex/kast-integration`. Its client socket and state are separate from the
legacy broker state. The semantic sidecar retains exact-root lifecycle
ownership.

## Host authority

`kast-codex` admits exactly one process role before starting the broker. An
ordinary argument vector selects the existing CLI remote-client host. One exact
`app-server` role token selects the stdio host used by Codex Desktop; global
Codex options before that token and App Server options after it remain ordered.
Caller-supplied transport options, App Server tooling subcommands, duplicate
roles, malformed arguments, and oversized stdio frames fail closed.

`kast codex desktop` locates the installed Desktop application and launches it
with `CODEX_CLI_PATH` set to the sibling installed `kast-codex` executable for
only that process tree. It also establishes `KAST_REAL_CODEX_EXECUTABLE` from an
independent upstream resolution. `DesktopFacadeExecutable` and
`UpstreamCodexExecutable` are distinct refined types, and equal canonical
executable identity is rejected before process launch. The façade therefore
cannot recursively select itself.

The App Server role starts the real installed `codex app-server` on Kast's
private Unix socket, then bridges the parent's stdio to the existing public
broker socket. All non-owned messages remain transparent. The existing
`CodexProtocolAdapter` is still the only owner of `thread/start` catalog
injection, Kast dynamic calls, thread identity, and native item projection.
Process logging is directed to stderr so stdout remains a pure App Server JSONL
transport.

## Protocol authority

Codex protocol authority comes from the installed CLI. At startup Kast executes
`codex app-server generate-json-schema --experimental`, bounds and compiles the
generated schemas, and rejects broker startup when a broker-owned protocol shape
cannot be proven.

The public socket accepts Codex's canonical `/rpc` WebSocket route and the
legacy `/` route. A process-lifetime filesystem lease serializes stale-socket
recovery. Readiness requires a complete `/rpc` WebSocket `initialize` exchange;
raw socket connectivity is not sufficient.

Provider qualification admits local commands through typed boundaries. Semantic
commands demand the sidecar directly; they do not discover Codex, inspect
`CODEX_HOME`, or issue a preceding `kast start`. A broker failure therefore does
not remove ordinary semantic CLI capability.

## Session bootstrap authority

The installed schema separates canonical hosted metadata from CLI invocation
bindings. Hosted metadata owns operation identity, task-oriented name and
description, input and output schemas, effect, approval policy, and execution
budget. The CLI binding owns only command and option syntax. Provider
qualification joins these documents by canonical operation identity and rejects
missing, duplicate, or contradictory bindings.

Only that qualified projection can construct an `AgentSessionBootstrap`. Before
construction, the provider narrows the qualified tools through the typed launch
authority. The default admits only tools whose canonical approval policy is
`NONE`. An explicitly authorized broker launch may set
`KAST_CODEX_TOOL_EXPOSURE=mutation-enabled` to admit plan, apply, and recovery
for that process lifetime. Absence remains read-only, and unknown values fail
closed.

The bootstrap catalog must exactly match the provider's executable broker routes.
The same bootstrap deterministically produces the Codex projection and a
provider-neutral Copilot fixture projection; neither projection changes semantic
operation, workspace readiness, topology, or trust authority.

On `thread/start`, the Codex adapter adds the qualified Kast namespace and the
short canonical selection policy to the request before forwarding it upstream.
The read-only surface contains symbol lookup and inspection, source read,
semantic query, impact analysis, and diagnostics. Mutation-enabled launches add
change planning, application, and recovery. Lifecycle, index synchronization,
topology preparation, status, and broker operations are not hosted tools.
Approval requirements remain canonical hosted metadata and are retained in
tool-local descriptions where the Codex dynamic-tool protocol has no separate
approval field.

## Dynamic-tool observation

Admitted dynamic-tool invocations publish one payload-free start event and one
finite terminal event to the host-owned service log. Each record carries bounded
identity and outcome fields; tool arguments, results, source, and working-directory
content do not enter the activity stream.

For Kast-owned namespaces, dynamic-tool lifecycle items are projected to Codex's
`mcpToolCall` presentation before reaching the downstream CLI. The observer
projection retains call identity and status while replacing semantic capability
arguments such as selectors and continuations with typed display placeholders.
The exact arguments still reach Kast unchanged, and the canonical model-facing
result remains unchanged.

Successful semantic reads replace the native tool result with bounded human-facing
Markdown. This keeps the collapsed transcript to one useful tool row; expanding
that row reveals source, symbol, relation, traversal, or diagnostic detail without
adding a synthetic assistant message. Presentation failure or capacity exhaustion
retains the canonical tool result without changing execution. Presentation state
is process-local and is reconstructed from canonical results where the protocol
provides enough context.

Source presentations use inclusive one-based line coordinates only when those
coordinates were derived from the normalized source document whose length and
digest matched the selected snapshot. Diagnostic presentations retain severity and
location while selector and generation evidence stays in the canonical model
result.

Change plan and apply results carry a non-empty typed set of workspace-relative
paths, closed change kinds, and bounded semantic diff fragments. A completed
`change.apply` observation projects that proof to Codex's native `fileChange` item,
so the compact row expands into the host diff visualization. Malformed, escaped,
duplicate, empty, or oversized observer data fails closed to the canonical tool
result. Change planning renders the same admitted preview in the expandable tool
body without exposing its opaque plan identity, and recovery renders its closed
outcome. These presentation paths do not broaden the catalog authority.

## Observer fixtures

Representative observer screenshots can be regenerated without a Codex session
or model request:

```bash
./gradlew :cli:renderKastObserverScreenshots
```

The task runs the production observer projector over deterministic fixtures for
all nine canonical operations and renders Markdown plus the native apply diff.
The renderer rejects opaque selectors, fingerprints, workspace roots, and source
selectors before capturing images.

A complete terminal recording uses the same generated manifest:

```bash
./gradlew :cli:generateKastObserverSnapshotManifest
asciinema record --overwrite --window-size 120x42 \
  --command "python3 docs/play_kast_observer_demo.py \
    --manifest cli/build/observer-snapshots/kast-observer-presentations.json" \
  build/kast-native-tui.cast
```

## Verification ownership

Production authorities are
[KastCodexMain](../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/KastCodexMain.kt),
[InstalledBrokerServer](../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/InstalledBrokerServer.kt),
and [KastProvider](../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/provider/KastProvider.kt).

Their focused broker, provider, schema, WebSocket, observer, and process tests own
verification. The installed broker transport test proves that a fresh thread
receives the canonical policy and exact hosted catalog. Provider tests prove
cold invocation readiness and advertised-route integrity without a separate
model-dependent lifecycle acceptance program.

`./gradlew installedCodexHostTest` stages the distribution and uses its actual
`kast-codex app-server` entrypoint with the installed Codex protocol authority.
It proves `initialize`, `initialized`, `thread/start`, JSONL-only stdout, exact
project retention, parent-stdio closure, teardown, the generated installed-Codex
schema digest, and the staged Kast catalog and executable digests.
`DesktopStdioProtocolIntegrationTest` drives the same stdio façade through the
real broker transport with a deterministic Codex upstream and proves the exact
catalog injection plus one `Broker.dispatch` without requiring a model request.
The remaining unit integration tests prove Markdown and `fileChange`
presentation, transparent forwarding, and CLI-host regression.

`./gradlew :cli:generateCodexHostIntegrationManifest` runs those dependencies and
writes `cli/build/reports/codex-host/codex-host-integration.json`. The receipt
binds the Git revision and source-tree digest, the staged Kast contract, the
default qualified Codex catalog projection, the generated installed-Codex
schema, staged executable digests, both closed host modes, installed commands,
installed acceptance receipt, and command digests. Its own dependency graph
runs CLI tests, both installed journeys, and architecture verification before it
can write `COMPLETE`. `productBuildGate` depends on that manifest generation.
