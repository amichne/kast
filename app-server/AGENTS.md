<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-15 | hash: 27e0f11eccb0 -->

# app-server

## Purpose

Owns the persistent broker/coordinator, host attachment, existing-IDE invocation routing, and Codex protocol projection.

## Key Files

- [DaemonUpgradeGate.kt](src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeGate.kt) - shared session, lazy frontend and management admission; `DaemonUpgradeAdmission` owns pending/sealed/committed transitions.

- [FileThreadCatalogStore.kt](src/main/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStore.kt) - lazy thread-binding shards and legacy conversation migration; `ThreadBindingDocuments` owns stored identities and refinement.
- [PrivateRecordFiles.kt](src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt) - shared private-file, lock, atomic-write and directory-sync boundary for durable records.

- [InvocationFence.kt](src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFence.kt) - active admission and durable replay settlement; `FileInvocationRecords` owns sharded storage and legacy migration.

- [InstalledWorkspacePreparation.kt](src/main/kotlin/io/github/amichne/kast/appserver/InstalledWorkspacePreparation.kt) - shared installed preparation owner and native-bound semantic demand.

- [WorkspacePreparations.kt](src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt) - daemon-owned, coalesced IDEA preparation and retained operation outcomes.

- [DaemonManagementProtocol.kt](src/main/kotlin/io/github/amichne/kast/appserver/DaemonManagementProtocol.kt) - versioned local management requests and finite rejections.
- [InstalledDaemonManagementClient.kt](src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonManagementClient.kt) - ownership-qualified registration, session inspection, controller operations and update handoff without Codex initialization.
- [InstalledDaemonUpgrade.kt](src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt) - installer-facing service presence proof and exact sealed or committed update permit.
- [LegacyLoginBootstrap.kt](src/main/kotlin/io/github/amichne/kast/appserver/LegacyLoginBootstrap.kt) - exact legacy login-agent ownership admission before lifecycle effects.
- [BrokerLaunchdServiceDocument.kt](src/main/kotlin/io/github/amichne/kast/appserver/BrokerLaunchdServiceDocument.kt) - exact private daemon launchd documents for current-session and login starts.
- [ServiceLoginAgent.kt](src/main/kotlin/io/github/amichne/kast/appserver/ServiceLoginAgent.kt) - exact owned login entry for the persistent service job and legacy one-shot migration.
- [BrokerServiceStartLock.kt](src/main/kotlin/io/github/amichne/kast/appserver/BrokerServiceStartLock.kt) - shared installation lock for service start, retirement, and direct login resumption.

- [KastCatalogSource.kt](src/main/kotlin/io/github/amichne/kast/appserver/provider/KastCatalogSource.kt) - bounded packaged provider-contract reads without a Kast subprocess.

- [InstalledConfigurationAlias.kt](src/main/kotlin/io/github/amichne/kast/appserver/InstalledConfigurationAlias.kt) - owned current-alias resolution under the installation activation lock.

- [KastSourcePresentation.kt](src/main/kotlin/io/github/amichne/kast/appserver/provider/KastSourcePresentation.kt) - compact returned source precedes the unchanged canonical provider envelope.
- [NativePresentationEvidence.kt](src/test/kotlin/io/github/amichne/kast/appserver/acceptance/hostedchange/NativePresentationEvidence.kt) - bounded evidence from actual source-first provider content items.

- [NativeReadRequest.kt](src/test/kotlin/io/github/amichne/kast/appserver/acceptance/hostedchange/NativeReadRequest.kt) - actual provider-envelope and canonical payload validation against the qualified schema.
- [CodexToolTerminalReply.kt](src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexToolTerminalReply.kt) - single-document finite broker failure and cancellation replies.
- [README.md](README.md) - architecture, lifecycle, status, and public integration behavior.
- [docs/compatibility.md](docs/compatibility.md) - tested compatibility evidence and open release gates.
- [../knowledge/contracts/public-tools.md](../knowledge/contracts/public-tools.md) - externally visible tool and query semantics.
- [src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt](src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt) - Codex-facing executable entry.
- [src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt](src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt) - installed coordinator assembly.
- [src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorControl.kt](src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorControl.kt) - passive coordinator status and retired-worker rejection.
- [src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt](src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt) - Codex protocol boundary.

- [src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt](src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt) - intent-tool admission and schema identity.

## Subdirectories

- `src/main/kotlin/io/github/amichne/kast/appserver/core` - pure broker/session domain.
- `src/main/kotlin/io/github/amichne/kast/appserver/host` - CLI, desktop, and installed-client host adapters; read its nested `AGENTS.md` first.
- `src/main/kotlin/io/github/amichne/kast/appserver/protocol` - Codex and Copilot projections plus thread catalog state.
- `src/main/kotlin/io/github/amichne/kast/appserver/provider` - process, Gradle, invocation, and observer boundaries.
- `src/main/kotlin/io/github/amichne/kast/appserver/runtime` - workspace registration, broker sessions, and invocation control.
- `src/main/kotlin/io/github/amichne/kast/appserver/storage` - shared private-file admission, locking and atomic durable-record writes.
- `src/test` - focused behavior tests; [native acceptance](src/test/kotlin/io/github/amichne/kast/appserver/acceptance) contains integration harnesses.

## Entry Points

- Gradle project: `:app-server`.
- Private lifecycle and workspace registration are surfaced through
  [KastServiceMain.kt](../cli/src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt);
  semantic requests arrive through the hosted Codex provider.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/runtime-hosts.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For transport/session faults, read `README.md`, then `core`, `host`, and protocol tests.
- For coordinator readiness, start with `CoordinatorControl`, `CoordinatorStatus`, and `InstalledCoordinatorClient`. Status admits exactly zero isolated workers.
- For public tool shape, begin with [public tool contracts](../knowledge/contracts/public-tools.md), then `query/PublicToolContract.kt` and the schema generator. For Codex projection, follow `protocol/codex`.
