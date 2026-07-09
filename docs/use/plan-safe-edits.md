---
title: Plan Safe Edits
description: Understand how agents plan identity-first Kotlin edits before applying them.
icon: lucide/pencil-ruler
---

# Plan Safe Edits

Use this page to understand what happens when an agent asks Kast to change
Kotlin. The developer-facing rule is simple: Kast plans first, then writes only
after the selected symbol, scope, diagnostics, and write set make sense.

## What The Agent Checks

Every public mutation path is plan-first. Before an edit is applied, the agent
should have evidence for:

- the symbol or scope Kast selected;
- whether diagnostics show stale or broken source state;
- the content that would be inserted or replaced;
- the files that would change;
- whether the edit target is supported by the public selector model.

That plan is what prevents a semantic task from becoming a blind text edit.

## Rename Boundary

Rename uses compiler identity, not string replacement. Local-variable rename is not part of the current public dialect;
agents should use named declaration identities until Kast has a typed non-offset
selector for locals.

## Add Or Replace Kotlin

For insertions and replacements, agents provide content through a file and ask
Kast to place it inside a typed file, declaration, or executable scope. This
keeps shell quoting and prompt text out of the source code being applied.

Use [mutation selectors](../reference/mutation-selectors.md) when you need the
exact selector contract.

??? info "Mutation command examples"
    These examples show the agent-facing execution shape. They are not required
    for normal developer use.

    === "Rename"

        ```console
        kast agent rename \
          --symbol com.example.OrderService \
          --new-name Orders \
          --workspace-root "$PWD"

        kast agent rename \
          --symbol com.example.OrderService \
          --new-name Orders \
          --apply \
          --workspace-root "$PWD"
        ```

    === "Create file"

        ```console
        cat >/tmp/NewType.kt <<'KOTLIN'
        package com.example

        class NewType
        KOTLIN

        kast agent add-file \
          --file-path "$PWD/src/main/kotlin/NewType.kt" \
          --content-file /tmp/NewType.kt \
          --workspace-root "$PWD"
        ```

    === "Insert implementation"

        ```console
        cat >/tmp/member.kt <<'KOTLIN'
        fun newBehavior(): String = "ready"
        KOTLIN

        kast agent add-implementation \
          --inside-scope com.example.OrderService \
          --at body-end \
          --content-file /tmp/member.kt \
          --workspace-root "$PWD"
        ```

    === "Replace declaration"

        ```console
        cat >/tmp/replacement.kt <<'KOTLIN'
        fun process(): String = "ready"
        KOTLIN

        kast agent replace-declaration \
          --symbol com.example.OrderService.process \
          --kind function \
          --content-file /tmp/replacement.kt \
          --workspace-root "$PWD"
        ```
