---
title: Mutation Selectors
description: Reference for the target model agents use when planning Kotlin edits.
icon: lucide/crosshair
---

# Mutation Selectors

Mutation selectors describe the target of a planned Kotlin edit. The visible
rule is simple: the agent should plan against a typed file, declaration, or
scope target, then apply only after the plan matches the requested change.

## Selector Concepts

| Concept | Meaning |
| --- | --- |
| Identity selector | A compiler-resolved declaration, not a text match |
| File selector | A complete Kotlin file target |
| Scope selector | A named declaration or executable body that receives content |
| Placement anchor | A supported location inside the selected file or scope |
| Content file | The Kotlin content the agent asks Kast to insert or replace |
| Operation ID | The stable backend-issued identity of one applied mutation |
| Idempotency key | The stable caller-issued identity used to submit and recover one mutation |

Local-variable rename is not part of the current public dialect. Agents should
use named declaration identities until Kast has a typed non-offset selector for
locals.

## Operation Selectors

`kast agent operation status` and `kast agent operation cancel` accept exactly
one operation selector:

| Selector | Flag | Source |
| --- | --- | --- |
| Operation ID | `--operation-id <uuid>` | Returned by mutation submission |
| Idempotency key | `--idempotency-key <stable-key>` | Chosen by the submitting caller |

Status and cancellation requests are idempotent. A cancellation response can
show `cancellationRequested` while the operation remains active; `CANCELLED` is
terminal only after execution has cooperatively stopped.

## Plan Review

A mutation plan should expose the selected identity or scope, content source,
diagnostics, conflicts, and write set. If any of those facts are wrong, the
agent should refine the request before applying it.

??? info "Selector flags for agent authors"
    Exact selectors are useful for agent authors and support workflows.

    | Command family | Target selector | Content selector |
    | --- | --- | --- |
    | Rename | `--symbol <fq-name>` plus optional narrowing flags | `--new-name <name>` |
    | Create file | `--file-path <absolute-path>` | `--content-file <path>` |
    | Add declaration | `--inside-file <path>` or `--inside-scope <fq-name>` | `--content-file <path>` |
    | Add implementation | `--inside-file <path>` or `--inside-scope <fq-name>` | `--content-file <path>` |
    | Add statement | `--inside-scope <fq-name>` and `--at body-end` | `--content-file <path>` |
    | Replace declaration | `--symbol <fq-name>` plus optional narrowing flags | `--content-file <path>` |

    Optional narrowing flags include `--kind`, `--file-hint`, and
    `--containing-type` where the command supports them.

??? info "Placement anchors"
    Anchors are command-specific. Use only anchors shown by the command help for
    the selected command.

    | Anchor | Applies to |
    | --- | --- |
    | `file-top` | File-scope declaration or implementation insertion |
    | `after-imports` | File-scope declaration or implementation insertion |
    | `file-bottom` | File-scope declaration or implementation insertion |
    | `body-start` | Body-scope declaration or implementation insertion |
    | `body-end` | Body-scope declaration, implementation, or statement insertion |
