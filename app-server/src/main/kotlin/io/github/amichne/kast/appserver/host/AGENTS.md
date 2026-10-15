---
type: Engineering Guide
title: "Codex host engineering rules"
description: "Defines host-owned transport arguments, executable admission, and bounded Codex process behavior."
resource: "repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/host"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/CliRemoteClientHost.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/DesktopStdioHost.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/admission/CodexHostExecutable.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/admission/CodexHostInvocation.kt
---

# Codex Host Boundaries

- This package owns how a Codex client reaches the shared broker. Host selection must not create a second broker, tool catalog, or semantic execution path.
- `admission` refines raw executable and argument inputs before any process, socket, or stdio effect begins. Preserve admitted values as typed data; do not unwrap and re-parse them downstream.
- The CLI host owns its injected `--remote` transport. The App Server host owns its injected `--listen` transport. Reject caller-supplied transport overrides before adding either host-owned argument.
- Parse global and role argument segments independently. Unknown options, remote overrides, missing values, ambiguous roles, control characters, and size overflow are closed typed failures.
- Executable identity checks must fail closed on unavailable paths, recursive façade identity, I/O failure, or denied access.
- Stdio framing and process lifecycle remain bounded. Keep shutdown, interruption, upstream exit, and malformed-frame outcomes explicit; never write diagnostics to the App Server stdout protocol stream.
- Tests must cover successful role selection plus every failure that could transfer transport ownership, recurse into the façade, corrupt framing, or leak a process.

## Navigation

- [CodexHostInvocation.kt](admission/CodexHostInvocation.kt) - transport admission or host composition.
- [CodexHostExecutable.kt](admission/CodexHostExecutable.kt) - transport admission or host composition.
- [CliRemoteClientHost.kt](CliRemoteClientHost.kt) - transport admission or host composition.
- [DesktopStdioHost.kt](DesktopStdioHost.kt) - transport admission or host composition.
