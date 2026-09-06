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

The provider exposes only tools whose canonical approval policy is `NONE`.
Mutation tools remain excluded while explicit approval routing is unresolved.
Direct CLI change operations must not be represented as available Codex mutation
tools.

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

Successful semantic operations may also produce a bounded commentary companion.
Presentation failure or capacity exhaustion suppresses that companion without
changing tool execution. Presentation state is process-local and is reconstructed
from canonical results where the protocol provides enough context.

Source companions use inclusive one-based line coordinates only when those
coordinates were derived from the normalized source document whose length and
digest matched the selected snapshot. Diagnostic companions retain severity and
location while selector and generation evidence stays in the canonical model
result.

## Observer fixtures

Representative observer screenshots can be regenerated without a Codex session
or model request:

```bash
./gradlew :cli:renderKastObserverScreenshots
```

The task runs the production observer projector over deterministic fixtures and
renders the resulting Markdown. The renderer rejects opaque selectors,
fingerprints, workspace roots, and source selectors before capturing images.

## Verification ownership

Production authorities are
[KastCodexMain](../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/KastCodexMain.kt),
[InstalledBrokerServer](../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/InstalledBrokerServer.kt),
and [KastProvider](../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/provider/KastProvider.kt).

Their focused broker, provider, schema, WebSocket, observer, and process tests own
verification. There is intentionally no separate installed-cold-broker,
app-server spike, or lifecycle acceptance program in the repository.
