---
type: Module Guide
title: "Kast cli"
description: "Owns the public semantic CLI, private process modes, direct tool sessions, installation workflows, and typed output projections."
resource: "repo://cli"
tags: [kast, navigation]
timestamp: "2026-11-21T06:00:00Z"
source_revision: f771eded1ebbeb5b283caa14442713cbc0a86345
code_sources:
  - path: build.gradle.kts
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/KastPublicCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicJqNumberFactory.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicJqProgram.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicJqUnicode.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicJqWorker.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliArguments.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliDiscovery.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliHelp.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliSchemaPage.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliDiagnostics.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliInvocation.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicJqEngine.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicJqWorkers.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicJqRecovery.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/publicshell/NativeJqCompositionTest.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicInvocationOwner.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicShellExit.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHook.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastControlMain.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/publicshell/PublicReplayAdapter.kt
  - path: cli/build.gradle.kts
  - path: cli/src/main/js/query-delivery.mjs
  - path: cli/src/main/js/query-presentation.mjs
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedHeadlessShutdown.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedHostBridge.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedProcessCapture.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedProcessState.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedProcessObservation.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpRunnerInvocationOwner.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/QueryCostPlan.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/QuerySessionRejectionDetail.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySessionTest.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcResultAdmissionTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-15 | hash: b96782f52fd4 -->

# CLI

## Purpose

Owns the public semantic CLI, private process modes, direct tool sessions, installation workflows, and typed output projections.

## Key Files

- [KastPublicCliMain.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/KastPublicCliMain.kt) - public semantic commands through shared admission and direct execution.
- [PublicCliArguments.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliArguments.kt) - flags, JSON input modes, and exact catalog selection.
- [PublicCliDiscovery.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliDiscovery.kt) - descriptors, schemas, and examples from packaged contracts.
- [PublicCliSchemaPage.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliSchemaPage.kt) - literal schema locations, direct-child pages, and exact output budgets.
- [PublicCliHelp.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliHelp.kt) - command and namespace guidance.
- [PublicCliDiagnostics.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliDiagnostics.kt) - bounded stderr guidance for argument and input failures.
- [PublicInvocationOwner.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicInvocationOwner.kt) - public terminal-event precedence and output delivery.
- [PublicShellExit.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicShellExit.kt) - public shell status codes and outcome classification.
- [KastMcpMain.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt) - the client-owned stdio MCP process.
- [McpRunnerInvocationOwner.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/McpRunnerInvocationOwner.kt) - one query worker, exact cancellation, and worker cleanup.
- [KastToolRpcMain.kt](src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt) - catalog, request-only plans, one-shot invocation, and query sessions.
- [QueryCostPlan.kt](src/main/kotlin/io/github/amichne/kast/cli/rpc/QueryCostPlan.kt) - request-shape advice before installation access or host effects.
- [KastDirectToolSession.kt](src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt) - shared direct tool admission and existing-IDE composition.
- [KastToolRpcQuerySession.kt](src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt) - bounded query framing and output successors.
- [query-delivery.mjs](src/main/js/query-delivery.mjs) - portable query delivery shared by generated harness extensions.
- [McpStructuredResults.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt) - schema-backed MCP result projection.
- [ManagedHeadlessShutdown.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedHeadlessShutdown.kt) - settle and stop the exact managed headless Host, then await its exit.
- [KastCliMain.kt](src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt) - the private installer version entry.
- [KastServiceMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt) - private installed service control.
- [KastDaemonMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt) - the private managed daemon entry.
- [InstalledServerProjectionDocuments.kt](src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt) - installed schemas from canonical types.

## Subdirectories

- [src/main/kotlin/io/github/amichne/kast/cli/publicshell/](src/main/kotlin/io/github/amichne/kast/cli/publicshell/) - public semantic parsing, discovery, output, exit policy, and invocation lifecycle.
- [src/main/kotlin/io/github/amichne/kast/cli/direct/](src/main/kotlin/io/github/amichne/kast/cli/direct/) - shared native tool composition.
- [src/main/kotlin/io/github/amichne/kast/cli/mcp/](src/main/kotlin/io/github/amichne/kast/cli/mcp/) - MCP transport and support tools.
- [src/main/kotlin/io/github/amichne/kast/cli/rpc/](src/main/kotlin/io/github/amichne/kast/cli/rpc/) - tool RPC and query sessions.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/](src/main/kotlin/io/github/amichne/kast/cli/installation/) - installation and recovery workflows.
- [src/main/kotlin/io/github/amichne/kast/cli/command/](src/main/kotlin/io/github/amichne/kast/cli/command/) - private command graph and migration code.
- [src/main/js/](src/main/js/) - portable delivery and presentation owners.

## Entry Points

- Gradle project: `:cli`.
- Focused public CLI cases: `mise exec -- ./gradlew :cli:test --tests 'io.github.amichne.kast.cli.publicshell.*'`. This task excludes the native launcher suite.
- Replay adapter: `mise exec -- ./gradlew :cli:prepareReplayAdapter` emits a digest-pinned JVM command for the interface experiment.
- The test-only adapter owns rendering and public admission. It composes no Host, MCP connection, or semantic executor.
- The production renderer owns TOON bytes. The test-only `Ktoon` decoder supplies an independent equality oracle.
- Control composition: `mise exec -- ./gradlew :publicLauncherTest`. The root task runs the staged Control product: the Go executable, its `lib`, and its catalog, against a local peer.
- `KastControlMain` is the single JVM entry behind the Go Control executable. It composes the existing command owners.
- `distribution/cli` owns management commands. `NativeControlInvocation` owns private mode admission.
- `assembleControlLayout` supplies the launchers and the `jvm-control-v1` layout marker.
- `assembleControlLibraries` supplies `lib`. JVM bytecode exists only there, in the catalog's class path order.
- `PublicHook.kt` owns `kast --internal-entrypoint=query hook session-start`. It admits host events for passive context without trust, workspace writes, or Host startup.
- Hook replies use JSON regardless of `KAST_OUTPUT_TOON`. Non-project directories return without IDE effects.
- CI qualification supplies `kastControlProduct`, so `publicLauncherTest` exercises the exact assembled product.
- The private installer entry in `KastCliMain.kt` admits only `--version`.
- The installation places private service control at `share/kast/libexec/kast-service`.
- [KastToolRpcQuerySessionTest.kt](src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySessionTest.kt) - query framing and retained delivery.
- [KastToolRpcResultAdmissionTest.kt](src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcResultAdmissionTest.kt) - result admission and rejection cases.

## Navigation Hints

- The native `distribution/cli` module owns `versions`, `upgrade`, and `install` command admission.
- Product installation and upgrade run in the Python installer; the prior Control retires itself through `kast-management --internal-install managed-retire`.
- `ManagedHeadlessShutdown` inspects, settles, and stops the managed headless Host through `ManagedHostBridge`, then awaits the same process's exit.
- `ManagedHostBridge` binds the selected launcher, profile, process start, and Host lifetime before an effect.
- Missing executable metadata needs a bounded native state probe. Only proven exit excludes a process whose executable remains unknown.
- The shared process-state parser keeps zombie rules identical to native reset. Active or unavailable state remains unresolved.
- Enumeration diagnostics record finite outcomes and bounded counts on stderr. They contain no process payloads.
- Normal stop acknowledgment does not prove process exit. Shutdown separately awaits the exit of the admitted process.
- These JVM adapters do not change the native public-query path.
- Read the [candidate evidence guide](../docs/reviews/managed-installation-cli.md) before release qualification.

- Public shell commands default to `COMPLETE`. Keep explicit `COMMON` restrictions without promotion.
- Schema discovery uses literal JSON Pointers and bounded direct-child pages. Keep full exports explicit, preserve exact scalar facts, and never imply that a page is a complete validation schema.
- Public shell results default to JSON. Explicit `KAST_OUTPUT_TOON=1` selects TOON; `--json` overrides it, including startup before the installed query launcher is admitted. Request documents remain JSON with either output format.
- `PublicJqEngine` checks optional `--jq` syntax and numeric literals before workspace effects and accepts exactly one JSON result. Keep projection options outside semantic request DTOs.
- `PublicJqProgram` owns embedded evaluation and exact JSON conversions. `PublicJqNumberFactory` rejects input narrowing that changes numeric values. Direct literals admit only whole values through `2147483647`; quoted conversions preserve larger or fractional input values. `PublicJqUnicode` validates complete Unicode scalars for `implode`. Intermediate arithmetic keeps the library's documented limits. `PublicJqWorker` runs it through a private Control entry with a 512 MiB heap. `PublicJqWorkers` owns worker reuse, byte limits, cancellation, and process cleanup. Never recover through Host lifecycle operations or semantic retries.
- Preserve unsuccessful documents and issued retained-result recovery. Use the shared guard for exact values before TOON encoding of user projections. `mise.toml` owns the independent jq oracle pin; `gradle/libs.versions.toml` owns the bundled library and Jackson versions. Runtime filtering never resolves jq from PATH.
- The native reachability metadata admits the JDK collection types observed in the `JToon` decoder. Keep `NativeJqCompositionTest` passing after JDK or `JToon` upgrades, including empty and nested arrays.
- `source --help` and `result --help` list their commands without workspace preparation.
- Keep parser diagnostics on stderr. Preserve the response contract and exclude request payloads and paths from those diagnostics.
- Packaged discovery and request planning use installed contracts before workspace or host composition.
- Keep `publicshell` exit and signal policy separate from private RPC and MCP behavior.
- `PublicInvocationOwner` accepts the first terminal event. `PublicCliInvocation` owns its worker and signal handling.
- Cancellation requests and child exit do not prove native semantic settlement.
- Direct MCP and tool RPC sessions default to `COMMON`. Select `--catalog=COMPLETE` before the command for advanced grammar.
- Keep the selected catalog stable through discovery, public admission, and retained reads. Pi native MCP registration explicitly selects `COMPLETE`.

- Trace MCP and tool RPC through `KastDirectToolSession`. The MCP transport does not start an App Server broker.
- Fresh and replacement installations select private endpoints and do not enable a daemon. Legacy retirement and recovery keep their ownership rules.
- MCP initialization is passive. Its first explicit semantic request uses the same exact-root managed admission as CLI and tool RPC; semantic calls wait for that workspace endpoint.
- In `RUNNER_CONTROLLED`, the caller owns semantic cancellation. The input owner accepts `notifications/cancelled` for the exact request identifier. End-of-input cancels the active query and drains its worker. A cancelled query emits no MCP response.
- For query output, trace canonical types into `InstalledServerProjectionDocuments` and the shared JavaScript delivery and presentation owners.
- For source reads and session rejection, examine `KastToolRpcQuerySession` and `QuerySessionRejectionDetail`. Issued selectors keep their current authority.
- For generated Copilot and Pi sections, change the delivery owner or public-query generator. Preserve authored extension adapters.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
