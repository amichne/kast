---
title: Mutation Selectors
description: Reference for selectors, anchors, and apply gates on Kast mutation commands.
icon: lucide/crosshair
---

# Mutation Selectors

Mutation selectors describe which Kotlin file, declaration, scope, or anchor a
plan targets. Public mutation commands are identity-first and plan-first:
without `--apply`, Kast reports the planned request instead of writing files.

## Mutation Commands

| Command | Target selector | Content selector | Apply gate |
| --- | --- | --- | --- |
| `kast agent rename` | `--symbol <fq-name>` plus optional `--kind`, `--file-hint`, `--containing-type` | `--new-name <name>` | `--apply` |
| `kast agent add-file` | `--file-path <absolute-path>` | `--content-file <path>` | `--apply` |
| `kast agent add-declaration` | `--inside-file <path>` or `--inside-scope <fq-name>` | `--content-file <path>` | `--apply` |
| `kast agent add-implementation` | `--inside-file <path>` or `--inside-scope <fq-name>` | `--content-file <path>` | `--apply` |
| `kast agent add-statement` | `--inside-scope <fq-name>` and `--at body-end` | `--content-file <path>` | `--apply` |
| `kast agent replace-declaration` | `--symbol <fq-name>` plus optional `--kind`, `--file-hint`, `--containing-type` | `--content-file <path>` | `--apply` |

## Identity Selectors

`--symbol <fq-name>` means compiler-resolved declaration identity. It is not a
repository-wide string match.

Optional narrowing flags are available where the command help exposes them:

| Flag | Applies to | Meaning |
| --- | --- | --- |
| `--kind <class|interface|object|function|property>` | `symbol`, `rename`, `replace-declaration` | Restrict by declaration kind |
| `--file-hint <path>` | `symbol`, `rename`, `replace-declaration` | Prefer or disambiguate a declaration associated with a file |
| `--containing-type <fq-name>` | `symbol`, `rename`, `replace-declaration` | Restrict candidates to a containing declaration |

Local-variable rename is not part of the current public dialect. Use named
declaration identities until Kast has a typed non-offset selector for locals.

## Scope Selectors

Insertion commands select either a file scope or a named declaration scope.

| Selector | Meaning |
| --- | --- |
| `--inside-file <path>` | The selected file receives the declaration or implementation content |
| `--inside-scope <fq-name>` | The named declaration scope receives the content |
| `--after-symbol <fq-name>` | Insert after a named symbol inside the selected scope |
| `--before-symbol <fq-name>` | Insert before a named symbol inside the selected scope |

`add-statement` is narrower than declaration insertion. It requires
`--inside-scope <fq-name>` where the scope is a named function or accessor.

## Placement Anchors

Anchors are command-specific. Use only anchors shown by the command help for
the selected command.

| Anchor | Applies to |
| --- | --- |
| `file-top` | `add-declaration`, `add-implementation` when a file scope is selected |
| `after-imports` | `add-declaration`, `add-implementation` when a file scope is selected |
| `file-bottom` | `add-declaration`, `add-implementation` when a file scope is selected |
| `body-start` | `add-declaration`, `add-implementation` when a body scope is selected |
| `body-end` | `add-declaration`, `add-implementation`, `add-statement` |

## Content Files

Mutation commands read Kotlin content from `--content-file`. This keeps shell
quoting, editor formatting, and agent prompt text outside the command-line
argument itself.

```console
kast agent replace-declaration \
  --symbol com.example.OrderService.process \
  --kind function \
  --content-file /tmp/replacement.kt \
  --workspace-root "$PWD"
```

The planned write set, selected target, diagnostics, and content file path are
the review surface before `--apply`.
