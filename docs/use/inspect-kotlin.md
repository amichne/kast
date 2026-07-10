---
title: Inspect Kotlin
description: Understand how agents resolve symbols, diagnostics, and impact before editing.
icon: lucide/search
---

# Inspect Kotlin

Use semantic inspection when a name, caller, diagnostic, or impact question
needs compiler-backed evidence. In normal use, the agent runs these checks for
you after Kast is installed and the project is open.

## Resolve Identity First

The agent starts broad, then narrows until the target declaration is clear.
That avoids treating every matching string as the same Kotlin symbol.

## Gather The Right Evidence

Different questions need different evidence:

| Question | Evidence |
| --- | --- |
| Which declaration is this? | Symbol identity |
| Where is this used? | References |
| Who calls this? | Caller evidence |
| What files might be affected? | Source-index impact |
| Does the backend see a clean file? | Diagnostics |

## Continue To Safe Edits

After the target identity and file state are clear, an agent can plan an edit.
Continue with [plan safe edits](plan-safe-edits.md) for the mutation flow.

??? info "Agent inspection commands"
    These commands are examples for agent authors and support workflows.

    ```console
    kast agent verify --workspace-root "$PWD"
    kast agent symbol --query OrderService --workspace-root "$PWD"
    kast agent symbol --query OrderService --references --workspace-root "$PWD"
    kast agent diagnostics \
      --file-path "$PWD/src/main/kotlin/App.kt" \
      --workspace-root "$PWD"
    kast agent impact \
      --symbol com.example.OrderService \
      --workspace-root "$PWD" \
      --depth 3
    ```
