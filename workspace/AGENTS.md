<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: 9e5a40e9647e -->

# workspace

## Purpose

Owns canonical project identity, readiness, read epochs, resource admission, synchronization, publication, and IntelliJ/Gradle workspace effects.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/CanonicalSemanticProjectRoot.kt](contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/CanonicalSemanticProjectRoot.kt) - canonical root identity.
- [contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt](contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt) - lifecycle state.
- [contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt](contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt) - admitted semantic read.
- [service/src/main/kotlin/io/github/amichne/kast/workspace/service/WorkspaceTransitionCoordinator.kt](service/src/main/kotlin/io/github/amichne/kast/workspace/service/WorkspaceTransitionCoordinator.kt) - lifecycle orchestration.
- [service/src/main/kotlin/io/github/amichne/kast/workspace/service/WorkspaceResourceAdmissionController.kt](service/src/main/kotlin/io/github/amichne/kast/workspace/service/WorkspaceResourceAdmissionController.kt) - resource admission.
- [intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledIntellijWorkspace.kt](intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledIntellijWorkspace.kt) - hosted workspace assembly.
- [intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt](intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt) - bounded detached model capture.

## Subdirectories

- `contract` - roots, readiness, transitions, synchronization, resources, and read epochs.
- `service` - transition, publication, synchronization, and resource controllers.
- `intellij` - Gradle import, JDK/network policy, readiness, and hosted workspace state.
- `intellij-read` - passive VFS/IDE model reads and epoch admission.

## Entry Points

- Gradle projects: `:workspace:contract`, `:workspace:service`, `:workspace:intellij`, `:workspace:intellij-read`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/workspace.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For readiness or lifecycle bugs, start with contract states and the transition coordinator before platform observers.
- For stale reads, start with epoch identity/lease types, then `intellij-read` listeners and admission.
- For import/JVM/network failures, start in `intellij` and follow configuration authority into `distribution`.
