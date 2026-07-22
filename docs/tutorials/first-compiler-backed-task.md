---
type: Tutorial
title: Your First Compiler-Backed Task
description: Learn the Kast workflow by tracing semantic admission in the Kast repository without changing source.
tags: [tutorial, codex, kotlin, idea, compiler-evidence]
code_sources:
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaIndexSemanticAdmission.kt
  - path: backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaIndexSemanticAdmissionTest.kt
  - path: cli-rs/src/runtime/workspace_admission.rs
---

# Your First Compiler-Backed Task

In this tutorial, you will ask Codex to explain a real Kotlin state machine in
the Kast repository. You will see the difference between a text search and
compiler-backed evidence: the answer will connect declarations, typed states,
and tests to exact source locations.

You do not need to run Kast's agent commands yourself. The Codex plugin routes
the task to the installed CLI.

## Before you begin

You need:

- Kast installed on macOS;
- this repository open at the exact checkout or worktree root in IntelliJ IDEA
  or Android Studio;
- project loading and indexing finished; and
- a Codex task rooted at the same directory.

If you still need Kast, follow [Install or update Kast](../how-to/install-or-update.md).

## 1. Check the workspace

From the repository root, run:

```console
kast ready --for kotlin
```

A ready result means Kast found a compatible compiler-backed runtime for this
exact root. If it reports indexing or an unprepared workspace, use the action
it reports before continuing.

## 2. Ask for a semantic explanation

Start a Codex task with this prompt:

```text
Use Kast to explain how IdeaIndexSemanticAdmission moves between Pending,
Ready, and Failed. Cite the Kotlin declarations and tests that prove each
transition. Do not edit files.
```

The important part is not the wording. You named a declaration and an outcome,
then asked for evidence instead of asking Codex to scan files blindly.

## 3. Read the evidence

A successful answer should identify all of these facts:

- `IdeaIndexSemanticAdmission` starts in `Pending`;
- admission remains pending while IDEA indexing or Kotlin compiler inputs are
  unavailable;
- a semantically usable Kotlin module produces `Ready`; and
- timeout or another failure produces `Failed` and retains a non-blank detail.

The answer should point to
`IdeaIndexSemanticAdmission.kt` and
`IdeaIndexSemanticAdmissionTest.kt`. Those locations matter: Kast's semantic
graph carries repository-relative paths, declaration ranges, and compiler
relationships rather than returning an unsupported narrative.

## 4. Follow one relationship

Continue the same task:

```text
Which test proves that semantic admission yields to a pending IDE write action,
and which production method does that test exercise?
```

This follow-up asks Codex to navigate from behavior to test and back to the
production declaration. Kast can answer from symbol identity and relationships
instead of relying only on matching names.

## 5. Confirm that the task was read-only

Run:

```console
git diff --exit-code -- '*.kt'
```

No output and exit code zero confirm that the tutorial did not change Kotlin
source.

You have now completed the basic Kast loop: admit the exact workspace, ask for
a Kotlin outcome, inspect source-backed semantic evidence, and verify the task.
Next, use [Explore Kotlin code](../how-to/explore-kotlin-code.md) for your own
symbols or read [Compiler-backed evidence](../explanation/compiler-evidence.md)
to understand the evidence model.
