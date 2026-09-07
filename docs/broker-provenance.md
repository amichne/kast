# Hosted tool bootstrap and Codex broker provenance

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
verification. The installed broker transport test proves that a fresh thread
receives the canonical policy and exact hosted catalog. Provider tests prove
cold invocation readiness and advertised-route integrity without a separate
model-dependent lifecycle acceptance program.
