---
type: Runtime Flow
title: Headless Load and Bootstrap
description: How public demand admits or starts one isolated exact-root semantic runtime.
tags: [internal, headless, startup, bootstrap, workspace-state]
code_sources:
  - path: cli-rs/src/agent/adapter/commands.rs
  - path: cli-rs/src/execution/runtime/backend/headless_authority.rs
  - path: cli-rs/src/execution/runtime/backend/workspace.rs
  - path: cli-rs/src/execution/runtime/control/inspect.rs
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/runtime/HeadlessRuntime.kt
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/runtime/HeadlessMain.kt
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/project/HeadlessProjectOpener.kt
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspacePaths.kt
---

# Headless Load and Bootstrap

Public demand begins with `kast up` or a semantic command from the canonical
workspace root. The Rust runtime layer inspects only exact-root candidates and
passes them through the central headless admission boundary.

```mermaid
sequenceDiagram
    autonumber
    participant Agent as Public kast command
    participant Authority as Headless authority
    participant Registry as Descriptor registry
    participant Launcher as Headless launcher
    participant Host as Isolated IntelliJ process
    participant Server as Analysis server

    Agent->>Authority: demand canonical root
    Authority->>Registry: inspect exact-root descriptors
    Registry-->>Authority: identity, health, endpoint, readiness
    alt one healthy compatible runtime
        Authority-->>Agent: admitted identity
    else no runtime
        Authority->>Launcher: start one release-matched headless host
        Launcher->>Host: isolated config, system, log, plugin, and VFS paths
        Host->>Host: open exact root and settle build model
        Host->>Server: bind ownership-safe endpoint
        Server->>Registry: publish complete descriptor identity
        Registry-->>Authority: healthy exact-root candidate
        Authority-->>Agent: admitted identity
    else conflict or retired intent
        Authority-->>Agent: typed failure before semantic side effects
    end
```

## Isolation boundary

The installed private payload is
`idea-home/plugins/kast-headless`. It runs only inside the release-owned
headless home. The foreground plugin descriptor has no startup activity,
project service, settings page, status widget, tool window, notification, or
semantic server extension.

On macOS, installed IntelliJ IDEA or Android Studio supplies a compatible JBR
and platform runtime. Kast does not ask that application to open a project and
does not attach to its VFS.

## Identity boundary

The descriptor binds canonical root, release version, headless backend kind,
runtime instance identifier, process start time, owner UID, socket path, and
socket file identity. Admission rechecks live status identity. A stale file,
PID reuse, alias path, or replacement socket fails closed.

Continue with [Gradle sync](gradle-sync.md).
