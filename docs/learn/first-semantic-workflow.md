---
title: First Semantic Workflow
description: See the semantic workflow agents run after Kast is installed.
icon: lucide/zap
---

# First Semantic Workflow

Use this page to understand the first Kast-backed workflow an agent runs after
installation. This is not a copy-paste tutorial for developers; the exact
commands are agent-facing and normally stay out of sight.

## 1. Confirm The Project Is Usable

The agent starts with read-only checks. It should know whether the IDE or
headless backend is reachable before it asks semantic questions.

## 2. Resolve Symbol Identity

The agent looks up the declaration it intends to reason about. If more than one
candidate matches, it narrows by kind, file, or containing type before moving
on.

## 3. Gather Evidence

The agent asks for the evidence the task needs: references, callers, impact, or
diagnostics. Results may be bounded, and the agent should treat bounded
evidence as useful but not exhaustive.

## 4. Plan Before Editing

If the task needs a code change, Kast plans the mutation first. The agent
reviews target identity, selected scope, diagnostics, conflicts, content, and
write set before applying anything.

??? info "Agent execution details"
    These examples use placeholder Kotlin names such as `OrderService`.
    Replace them with symbols from the target project.

    ```console
    kast agent verify --workspace-root "$PWD"
    kast agent symbol --query OrderService --workspace-root "$PWD"
    kast agent references \
      --symbol com.example.OrderService \
      --declaration-file "$PWD/src/main/kotlin/com/example/OrderService.kt" \
      --declaration-start-offset 42 \
      --kind class \
      --workspace-root "$PWD"
    kast agent diagnostics \
      --file-path "$PWD/src/main/kotlin/App.kt" \
      --workspace-root "$PWD"
    kast agent rename \
      --symbol com.example.OrderService \
      --new-name Orders \
      --workspace-root "$PWD"
    ```

    Mutation commands also plan first and apply only after the plan has been
    reviewed.

    ```console
    kast agent add-implementation \
      --inside-scope com.example.OrderService \
      --at body-end \
      --content-file /tmp/member.kt \
      --workspace-root "$PWD"
    ```

Continue with [how Kast thinks about evidence](evidence-model.md) for the
model behind the workflow.
