---
title: Runtime And Output
description: Reference for backend selection and readable or TOON output.
icon: lucide/activity
---

# Runtime And Output

Kast can answer semantic requests through an IDE-backed runtime or a headless
runtime. The command surface is the same in both cases; the runtime only
changes where the semantic evidence comes from.

Runtime selection is exact-root. A descriptor or runtime-status response must
name the normalized requested workspace root and selected backend. Git ancestry,
branch names, and matching commits do not make another checkout's runtime
eligible.

Automatic selection succeeds only when at most one backend kind is ready for
that root. Exactly one ready kind is selected even when it differs from the
host fallback. If IDEA and headless are both ready, Kast returns
`SEMANTIC_BACKEND_AMBIGUOUS` with candidate evidence and requires an explicit
backend. Verification never resolves ambiguity by preferring IDEA, the host
default, or descriptor order, and reuse-only verification never prunes or
rewrites descriptor registry state.

## Runtime Choices

| Runtime | Typical host | User-facing model |
| --- | --- | --- |
| IDEA or Android Studio | macOS developer machine | Open the project in the IDE and let the plugin serve semantic work |
| Headless | Linux CI, hosted agents, server images | Install the bundle and let agents or CI start the backend when needed |

## Output Shapes

Human-facing operator commands can stay readable. Agent commands default to
TOON so structured evidence remains compact and deterministic. Explicit JSON
is temporarily compatible but deprecated.

??? info "Runtime commands for agents and support"
    These commands are useful for support, CI, and agent workflows. They are not
    part of the normal developer install path.

    ```console
    kast developer runtime status --workspace-root "$PWD"
    kast developer runtime up --backend=headless --workspace-root "$PWD"
    kast developer runtime restart --backend=headless --workspace-root "$PWD"
    kast --output toon status --workspace-root "$PWD"
    kast --output toon agent verify --workspace-root "$PWD"
    ```

Use the [troubleshooting matrix](../troubleshoot.md) when runtime checks
identify a stale backend or indexing state.
