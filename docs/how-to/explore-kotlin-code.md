---
type: How-to Guide
title: How to Explore Kotlin Code
description: Resolve Kotlin declarations and navigate their relationships with compiler-backed evidence.
tags: [kotlin, codex, symbols, references, callers]
code_sources:
  - path: cli-rs/src/agent/core/symbol_lookup/mod.rs
  - path: cli-rs/src/agent/navigation/relations.rs
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/relationships/evidence/RelationshipResultEvidence.kt
---

# How to Explore Kotlin Code

Use this guide when you know the Kotlin question you want answered but not the
files you need to read.

## Prepare the exact workspace

Start Codex at the exact Gradle workspace root. On macOS, start or resume the
IDEA backend:

```console
kast start \
  --workspace-root "$PWD" \
  --backend idea \
  --accept-indexing
```

On Linux or a hosted agent, use `--backend headless` instead. Then inspect the
selected runtime:

```console
kast --output json status \
  --workspace-root "$PWD" \
  --backend idea
```

Replace `idea` with `headless` on Linux. Continue when `selected.ready` is
`true`. Kast does not silently attach a different checkout. For native graph
queries, also require complete reported coverage or retain the returned
limitation in the answer.

## Ask for the declaration first

Name the symbol and the evidence you need:

```text
Resolve PaymentService.submit to its exact Kotlin declaration. Show its fully
qualified name, signature, owner, and source location before explaining it.
```

For overloaded or repeated names, add the package, containing type, parameter
types, or file. If resolution remains ambiguous, choose from the candidates
instead of asking Codex to guess.

## Navigate relationships from that identity

Reuse the resolved declaration in a focused follow-up:

```text
Starting from that exact declaration, list its callers and explain which call
site owns retry behavior. Report whether coverage is complete or limited.
```

Other useful relationship questions include:

- which declarations implement this interface;
- which references occur in production sources;
- which declarations this function calls; and
- which files are affected if this declaration changes.

Ask for source locations in the answer. A useful result identifies both ends
of each relationship and the occurrence that connects them.

## Use the repository demo when evaluating Kast

For a guided, read-only view of semantic evidence in the current repository:

```console
kast demo
```

To open the story around a particular symbol query:

```console
kast demo --symbol IdeaIndexSemanticAdmission
```

The demo reports when evidence is degraded or incomplete. Treat limited or
resumable coverage as a boundary to investigate, not as proof that no other
relationship exists.
