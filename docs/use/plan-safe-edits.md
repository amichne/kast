---
title: Plan Safe Edits
description: Plan identity-first renames and scope mutations before applying them.
icon: lucide/pencil-ruler
---

# Plan Safe Edits

Use Kast mutation commands when a Kotlin edit should be tied to compiler
identity, a named scope, a content file, and a reviewable write set. Every public mutation path is plan-first.
Add `--apply` only after reviewing the planned request.

## Resolve The Target First

Resolve broad names before using `--symbol` in mutation commands.

```console
kast agent symbol --query OrderService --workspace-root "$PWD"
```

`--symbol <fq-name>` means compiler identity, not a text match. If several
candidates match the query, refine the lookup before planning the edit.

## Plan And Apply Rename

Run the plan first.

```console
kast agent rename \
  --symbol com.example.OrderService \
  --new-name Orders \
  --workspace-root "$PWD"
```

Apply only after the reported target identity, diagnostics, conflicts, and
write set match the intended change.

```console
kast agent rename \
  --symbol com.example.OrderService \
  --new-name Orders \
  --apply \
  --workspace-root "$PWD"
```

Local-variable rename is not part of the current public dialect. Use named
declaration identities until Kast has a typed non-offset selector for locals.

## Add Kotlin From A File

Mutation commands read Kotlin content from files so shell quoting cannot
change the code being applied.

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

## Review Before Apply

Before adding `--apply`, check:

- the command selected the intended symbol or scope;
- diagnostics do not describe a stale or broken file state;
- the content file contains exactly the Kotlin you intend to apply;
- the write set is expected;
- the selector matches the desired placement.

Use [mutation selectors](../reference/mutation-selectors.md) for lookup details.
Use [troubleshooting](../troubleshoot.md) when a plan fails or reports an
unexpected target.
