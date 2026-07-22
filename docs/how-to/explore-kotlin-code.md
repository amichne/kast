---
type: How-to Guide
title: Explore Kotlin Code
description: Resolve Kotlin declarations and navigate their relationships with compiler-backed evidence.
tags: [kotlin, codex, symbols, references, callers]
code_sources:
  - path: cli-rs/src/agent/symbol_lookup.rs
  - path: cli-rs/src/agent/relations.rs
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RelationshipResultEvidence.kt
---

# Explore Kotlin Code

Use this guide when you know the Kotlin question you want answered but not the
files you need to read.

## Prepare the exact workspace

Open the project root in IntelliJ IDEA or Android Studio and let indexing
finish. Start Codex at that same root, then check:

```console
kast ready --for kotlin
```

Kast does not silently attach a different checkout. If more than one backend
is ready for the root, select the intended backend explicitly or close the
unwanted runtime.

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
