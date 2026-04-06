---
title: How Kast works
description: Understand the high-level request flow, daemon model, and
  workspace scoping.
icon: lucide/layers
---

This page explains the high-level path a Kast request takes. Keep it in mind
when you want to understand why the first command is slower, why repeated
commands get faster, and why results stay tied to one workspace.

## Request flow

Each Kast request moves through three layers. The CLI accepts the request and
finds or starts the workspace daemon. The daemon keeps the analysis session
warm and hands the semantic work to the Kotlin K2 Analysis API engine. Kast
then returns a structured JSON result to the caller.

```mermaid
flowchart LR
    CLI["`kast` CLI"] --> Daemon["Workspace daemon"]
    Daemon --> K2["K2 Analysis API engine"]
    K2 --> Daemon
    Daemon --> JSON["Structured JSON result"]
```

## Why a daemon?

Starting a Kotlin analysis session is the expensive part. Kast keeps that
session alive per workspace so the first command pays the startup cost and
later commands reuse warm state.

- **The first command is slower.** Workspace discovery, session startup, and
  initial indexing happen up front.
- **Later commands are faster.** The daemon reuses loaded state instead of
  rebuilding it for every request.
- **One process holds the analysis context.** Caches and indexes stay with the
  workspace until you stop the daemon or point Kast at a different workspace.

## Workspace model

Kast always starts from a workspace root. In a Gradle workspace, it discovers
modules, source roots, and classpath information from the Gradle model.
Outside Gradle, it falls back to conventional source roots and discovered
Kotlin or Java directories. The daemon then analyzes that workspace as one
session, which is why read results stay workspace-scoped.

## Next steps

- [Things to know](things-to-know.md)
- [Get started](get-started.md)
- [Run analysis commands](run-analysis-commands.md)
