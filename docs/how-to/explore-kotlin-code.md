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

Start your coding agent at the exact Gradle workspace root, then start or resume its
semantic runtime:

```console
kast up
```

Inspect the selected runtime and next action:

```console
kast
```

Continue when `ready` is true. Kast does not silently attach a different
checkout. For graph queries, also require complete reported coverage or retain
the returned limitation in the answer.

## Ask for the declaration first

Name the symbol and the evidence you need:

```text
Resolve PaymentService.submit to its exact Kotlin declaration. Show its fully
qualified name, signature, owner, and source location before explaining it.
```

For overloaded or repeated names, add the package, containing type, parameter
types, or file. If resolution remains ambiguous, choose from the candidates
instead of asking the agent to guess.

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

## Query the same evidence directly

Find the symbol and follow its exact identity:

```console
kast symbol find IdeaIndexSemanticAdmission
kast symbol show <symbol>
```

Then choose the relationship you need:

```console
kast symbol refs <symbol>
kast symbol callers <symbol>
```

Treat limited or resumable coverage as a boundary to investigate, not as proof
that no other relationship exists.
