# Codex tool broker provenance

Kast's Kotlin broker preserves behavior imported from the Slopsentral `broker/`
tree at tag `broker-v0.5.0`, commit `f18ab46`, including the typed Kast
projection-v2 adaptation from commit `9a5fb49`.

The behavior is implemented directly in Kast; the installed product has no Node
runtime requirement. The control distribution includes `kast-codex`, which owns
its integration server and launches the installed Codex client through the
supported `--remote` transport.

The host owns `$CODEX_HOME/kast-integration`, defaulting to
`~/.codex/kast-integration`. Its client socket and state are separate from the
legacy broker state. The semantic sidecar retains exact-root lifecycle
ownership.

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

The provider exposes only tools whose canonical approval policy is `NONE` by
default. An explicitly authorized broker launch may set
`KAST_CODEX_TOOL_EXPOSURE=mutation-enabled`; that typed launch authority is
retained through catalog construction and admits plan, apply, and recovery for
that process lifetime. Absence remains read-only, and unknown values fail closed.

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
verification. There is intentionally no separate installed-cold-broker,
app-server spike, or lifecycle acceptance program in the repository.
