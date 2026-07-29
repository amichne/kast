---
type: Reference
title: Codex Plugin Reference
description: Components and ownership boundaries of the separately published kast@kast plugin.
tags: [codex, reference, hooks, marketplace]
code_sources:
  - path: cli-rs/src/interface/codex/hook.rs
  - path: cli-rs/src/semantics/repository_intelligence/contract/label_index.rs
  - path: cli-rs/src/semantics/repository_intelligence/contract/request.rs
  - path: cli-rs/src/semantics/repository_intelligence/query/execution.rs
  - path: cli-rs/src/semantics/repository_intelligence/coverage/read.rs
  - path: install.sh
---

# Codex Plugin Reference

`kast@kast` is Kast's Codex guidance surface. It is published from the
public [amichne/kast-marketplace](https://github.com/amichne/kast-marketplace)
repository and tracks its `main` branch independently from Kast releases.

## Installed components

| Component | Contract |
| --- | --- |
| `kast-query` skill | Routes read-only Kotlin and Gradle discovery to the installed Kast CLI. |
| `kast-change` skill | Routes Kotlin and Gradle edits to typed mutations and compiler proof. |
| `kast-codex` hook | Runs `SessionStart` for exact-root startup and `PostToolUse` for post-write diagnostics. |
| Launcher | Resolves `KAST_BINARY`, `kast` from `PATH`, `$HOME/.local/bin/kast`, then `$HOME/.local/share/kast/current/bin/kast`, and forwards the hook event. |

The plugin does not embed the Kast runtime. The matched CLI and compiler
backend come from the active setup release.

## Native command routing

| Need | Kast command |
| --- | --- |
| Natural-language identity, path, impact, architecture, or repository-context evidence | `kast agent repository` |
| Persisted compiler-backed topology | `kast agent graph` |
| Exact symbol, relationship, diagnostic, or mutation contracts | The corresponding scoped command under `kast agent` |

The repository command preserves exact canonical identities and typed
`AMBIGUOUS`, `EMPTY`, and `QUALIFIED_EMPTY` outcomes. The graph command owns
generation-pinned native topology; neither command creates a second semantic
authority.

## Repository intelligence beta boundaries

Repository intelligence currently provides compiler semantics for Kotlin only.
Natural-language discovery uses deterministic lexical and structural ranking;
it does not use embeddings or an LLM at query time. Regex discovery is
resolve-only.

### Intents

| Intent | Question |
| --- | --- |
| `RESOLVE` | Which exact compiler identity matches this name or description? |
| `PATH` | What directed semantic path connects two identities? |
| `INCOMING_IMPACT` | Which admitted identities can reach the selected identity? |
| `OUTGOING_IMPACT` | Which admitted identities are reachable from it? |
| `ARCHITECTURE` | What boundary, topology, or metric finding exists in this scope? |
| `CONTEXT_RELATIONSHIP` | Which admitted non-Kotlin artifact relates to a compiler identity? |

### Result status

| Status | Contract |
| --- | --- |
| `ANSWERED` | Usable evidence from complete compiler coverage. |
| `AMBIGUOUS` | More than one identity matched; Kast did not choose. |
| `EMPTY` | No answer exists in complete, untruncated coverage. |
| `QUALIFIED_EMPTY` | No answer was found, but coverage or a bound prevents definitive absence. |

Positive repository answers fail with `REPOSITORY_COVERAGE_INCOMPLETE` when
compiler coverage is incomplete. `QUALIFIED_EMPTY`, incomplete coverage, or
`truncated: true` means the result is not exhaustive.

### Bounds and resumption

| Bound | Contract |
| --- | --- |
| Traversal depth | Maximum 6 |
| Query results | 1 through 500 |
| Evidence occurrences per edge | 1 through 50 |
| Coverage page size | 1 through 200 |
| CLI continuation | At most 16,384 printable ASCII characters |

Path and impact traversal and per-edge evidence expose signed continuations.
Resolve ambiguity candidates, architecture findings, and repository-context
results are bounded and are not pageable.

### Label artifact

A version-1 label artifact can improve retrieval for an existing compiler
identity. It cannot introduce symbols, edges, locations, owners, types,
coverage, or answer evidence.

| Property | Contract |
| --- | --- |
| Location | A regular file contained by the canonical workspace |
| Size | At most 8 MiB |
| Entries | 1 through 50,000 unique compiler canonical keys |
| Labels per entry | 1 through 16 |
| Label length | At most 160 characters |
| Identity binding | The key must exist in the active compiler snapshot |
| Source binding | The content hash must match the compiler-indexed file |

### Failure families

| Error family | Broken authority |
| --- | --- |
| `REPOSITORY_WORKSPACE_*` | Canonical root or workspace containment |
| `INVALID_REPOSITORY_SCOPE` / `AMBIGUOUS_REPOSITORY_SCOPE` | Gradle project or source-set identity |
| `GRAPH_COVERAGE_*` | File inventory, semantic coverage, or generation stability |
| `REPOSITORY_INDEX_*` | SQLite availability, schema, or required semantic tables |
| `REPOSITORY_QUERY_UNSTABLE` | Coverage and execution could not pin one generation |
| `*_REPOSITORY_CONTINUATION` | Token schema, signature, root, query, or snapshot binding |
| `*_REPOSITORY_LABEL_INDEX` | Label containment, schema, size, identity, or hash binding |
| `REPOSITORY_CONTEXT_*` | Context file admission, read, or replacement race |

### Output projections

The default compact machine view is TOON v3-compatible through `toon-format`
0.5.0; it does not claim TOON v4.1 encoder conformance. Compact output omits
canonical scope, applied filters, and ordering. Count output is cardinality
metadata and omits qualification text. Use `--explain` for the complete
validated result. Explicit human output is an overview and does not include
every candidate, edge, path, or continuation token; use the default TOON view
with `--explain` for actionable evidence and resumption.

## Runtime boundary

IDEA owns compiler state and workspace indexing on macOS. The packaged
headless backend owns compiler state on supported non-IDE hosts. The CLI owns
exact-root routing, compatibility, command execution, and result projection.
The Codex plugin supplies hooks and invocation guidance.

Setup receipts hash the CLI and IDEA plugin only. Marketplace contents and
plugin versions are not coupled to Kast release digests. Reconciliation uses
`amichne/kast-marketplace` at `main` and installs `kast@kast`.

The plugin's two hooks are advisory. A hook failure can add task context, but
it does not silently turn an unprepared workspace into compiler-backed
evidence.
