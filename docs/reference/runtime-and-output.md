---
title: Runtime And Output
description: Reference for backend selection and readable or JSON output.
icon: lucide/activity
---

# Runtime And Output

Kast can answer semantic requests through an IDE-backed runtime or a headless
runtime. The command surface is the same in both cases; the runtime only
changes where the semantic evidence comes from.

## Runtime Choices

| Runtime | Typical host | User-facing model |
| --- | --- | --- |
| IDEA or Android Studio | macOS developer machine | Open the project in the IDE and let the plugin serve semantic work |
| Headless | Linux CI, hosted agents, server images | Install the bundle and let agents or CI start the backend when needed |

## Output Shapes

Human-facing commands should be readable. Automation that needs a stable parser
contract should request JSON explicitly.

??? info "Runtime commands for agents and support"
    These commands are useful for support, CI, and agent workflows. They are not
    part of the normal developer install path.

    ```console
    kast developer runtime status --workspace-root "$PWD"
    kast developer runtime up --backend=headless --workspace-root "$PWD"
    kast developer runtime restart --backend=headless --workspace-root "$PWD"
    kast --output json status --workspace-root "$PWD"
    kast --output json agent verify --workspace-root "$PWD"
    ```

Use the [troubleshooting matrix](../troubleshoot.md) when runtime checks
identify a stale backend or indexing state.
