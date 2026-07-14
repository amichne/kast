---
title: How Kast Thinks About Evidence
description: Understand semantic evidence, bounded results, and plan-first edits.
icon: lucide/search-check
---

# How Kast Thinks About Evidence

Kast is useful when text search can show where a spelling appears, but cannot
prove which Kotlin declaration a name resolves to, which callers are real, or
whether a planned edit targets the intended symbol. Agents use Kast to get that
evidence before they act.

## Identity Comes Before Text

Typed agent operations use compiler identity where public workflows need
safety. A fully qualified symbol means a compiler-resolved declaration, not
every matching string in the repository.

The practical rule is: resolve broad names first, then act only after the
selected identity matches the target declaration.

Exact lookup distinguishes three expected states: one resolved declaration, no
exact declaration, or multiple exact declarations. Keeping not-found and
ambiguous explicit prevents a similarly spelled declaration from being treated
as proof. Fuzzy candidates are useful for discovery, but they become actionable
only after a separate exact lookup.

Every public lookup names its evidence source. `compiler` is canonical backend
identity, `indexed-exact` is equality proven by the source index while the
compiler is unavailable, and `fuzzy` is discovery evidence. This makes the
strength of the claim visible to the next agent step.

## Evidence Can Be Bounded

Reference, caller, hierarchy, and impact evidence may be bounded by depth,
timeout, traversal limits, or source-index availability. A bounded result is
still useful, but agents should treat it differently from an exhaustive answer.

## Plans Carry Write Evidence

Mutation plans identify the requested target, content source, selected scope,
diagnostics, conflicts, and write set. That plan is the review surface before
anything writes to disk.

This plan-first shape is part of the public command contract. Kast should fail
loudly for removed raw surfaces or unsupported selectors instead of silently
falling back to text edits.

## Layers Stay Separate

Kast separates distribution, workspace setup, runtime backends, semantic
commands, and evidence. That separation keeps developer setup simple while
letting agents diagnose the layer that actually failed.

| Layer | Question |
| --- | --- |
| Distribution | Is the right binary, plugin, or bundle installed? |
| Workspace setup | Has the project been prepared for agents? |
| Runtime backend | Is IDEA or headless analysis available? |
| Semantic command | Did the request use typed public behavior? |
| Evidence | Is the result complete, bounded, or blocked? |

??? info "Example agent checks"
    These examples show the agent-facing execution shape behind the evidence
    model.

    ```console
    kast agent symbol --query OrderService --workspace-root "$PWD"
    kast agent impact \
      --symbol com.example.OrderService \
      --declaration-file "$PWD/src/main/kotlin/com/example/OrderService.kt" \
      --declaration-start-offset 42 \
      --kind class \
      --workspace-root "$PWD" \
      --depth 3
    kast agent replace-declaration \
      --symbol com.example.OrderService.process \
      --kind function \
      --content-file /tmp/replacement.kt \
      --workspace-root "$PWD"
    ```

Use the [operating model](../design/operating-model.md) for the full system
boundary, and use [troubleshooting](../troubleshoot.md) when one layer fails.
