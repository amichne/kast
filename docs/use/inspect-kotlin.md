---
title: Inspect Kotlin
description: Resolve symbols, references, callers, diagnostics, and impact before editing.
icon: lucide/search
---

# Inspect Kotlin

Use semantic inspection when a name, caller, diagnostic, or impact question
needs compiler-backed evidence. Start by proving the backend is ready, then
resolve identity before asking for broader usage evidence.

## Verify The Backend

`agent verify` checks backend health, runtime state, capabilities, and the
workspace root the backend is serving.

```console
kast agent verify --workspace-root "$PWD"
```

If verification reports a missing or stale backend, use the runtime commands
before retrying the semantic query.

```console
kast developer runtime status --workspace-root "$PWD"
kast developer runtime up --backend=headless --workspace-root "$PWD"
kast agent verify --workspace-root "$PWD"
```

## Resolve A Symbol

Start broad, then refine if multiple candidates match.

```console
kast agent symbol --query OrderService --workspace-root "$PWD"
```

Use references when the question is "where is this declaration used?"

```console
kast agent symbol \
  --query OrderService \
  --references \
  --workspace-root "$PWD"
```

Use callers when the question is about execution relationships.

```console
kast agent symbol \
  --query process \
  --callers incoming \
  --workspace-root "$PWD"
```

## Run Diagnostics

Diagnostics refresh the touched file first unless you opt out with
`--skip-refresh`.

```console
kast agent diagnostics \
  --file-path "$PWD/src/main/kotlin/App.kt" \
  --workspace-root "$PWD"
```

Run diagnostics before safe edits when you need to confirm the backend sees the
same file state you intend to change.

## Inspect Impact

Impact uses the source index and a compiler identity. Verify first when a
workspace was just opened or refreshed.

```console
kast agent verify --workspace-root "$PWD"
kast agent impact \
  --symbol com.example.OrderService \
  --workspace-root "$PWD" \
  --depth 3
```

Impact results may be bounded by source-index state, depth, timeout, or
traversal limits. Treat bounded results as evidence with stated limits, not as
exhaustive proof.

Continue with [plan safe edits](plan-safe-edits.md) after the target identity
and file state are clear.
