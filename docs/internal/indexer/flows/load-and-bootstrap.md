---
type: Runtime Flow
title: Indexer Load and Bootstrap
description: How public demand reuses or creates one isolated exact-root indexer.
tags: [internal, indexer, startup, bootstrap, workspace-state]
code_sources:
  - path: cli-rs/src/agent/adapter/commands.rs
  - path: cli-rs/src/execution/runtime/backend/workspace_admission.rs
  - path: cli-rs/src/execution/runtime/backend/workspace.rs
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerRuntime.kt
  - path: indexer/src/main/scripts/kast-indexer
  - path: indexer/src/main/resources/META-INF/plugin.xml
---

# Indexer Load and Bootstrap

Public demand begins with the semantic command the caller actually needs. The
runtime layer inspects only exact-root candidates and passes them through one
admission boundary; no separate ensure or lifecycle prerequisite exists.

```mermaid
sequenceDiagram
    autonumber
    participant Agent as Public kast command
    participant Admission as Exact-root admission
    participant Registry as Descriptor registry
    participant Launcher as Indexer launcher
    participant Indexer as Kast indexer

    Agent->>Admission: demand canonical root
    Admission->>Registry: inspect exact-root descriptors
    Registry-->>Admission: identity, health, endpoint, readiness
    alt one eligible healthy indexer
        Admission-->>Agent: reuse admitted identity
    else absent or proven dead
        Admission->>Launcher: start release-matched indexer
        Launcher->>Indexer: isolated config, system, log, and VFS
        Indexer->>Indexer: open root and settle Gradle model
        Indexer->>Registry: publish complete identity
        Registry-->>Admission: healthy exact-root candidate
        Admission-->>Agent: admit new identity
    else conflict, ambiguity, or failed replacement
        Admission-->>Agent: typed failure before semantic work
    end
```

## Isolation boundary

The release contains an internal payload under
`idea-home/plugins/kast-indexer`. It exists only to start the isolated indexer.
It is not installed into IntelliJ IDEA or Android Studio and exposes no user
interface or foreground lifecycle hook.

On macOS, a supported installation supplies a compatible JBR and platform
libraries. Kast does not ask that application to open a project or attach to
its VFS.

## Identity boundary

The descriptor binds canonical root, release version, indexer kind, runtime
instance identifier, process start time, owner UID, socket path, and socket
file identity. Admission rechecks live identity. A stale file, reused PID,
alias path, or replacement socket fails closed.

Continue with [Gradle sync](gradle-sync.md).
