---
type: Tutorial
title: Your First Compiler-Backed Task
description: Learn the Kast workflow by tracing headless Gradle settlement in the Kast repository without changing source.
tags: [tutorial, codex, kotlin, headless, compiler-evidence]
code_sources:
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/gradle/settlement/HeadlessGradleModelSettlementOutcome.kt
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/gradle/settlement/HeadlessGradleModelSettlementAwaiter.kt
  - path: backend-headless/src/test/kotlin/io/github/amichne/kast/headless/HeadlessGradleModelSettlementAwaiterTest.kt
---

# Your First Compiler-Backed Task

In this tutorial, you will ask Codex to explain a real Kotlin state machine in
the Kast repository. You will see the difference between a text search and
compiler-backed evidence: the answer will connect declarations, typed states,
and tests to exact source locations.

The Codex plugin routes the task to the same small `kast` surface shown here.

## Before you begin

You need:

- Kast installed on macOS;
- on macOS, IntelliJ IDEA 2026.2 or Android Studio 2026.1.2 installed as the
  headless runtime input;
- a Codex task rooted at the same directory.

If you still need Kast, follow [Install or update Kast](../how-to/install-or-update.md).

## 1. Check the workspace

From the repository root, run:

```console
kast up
```

A `READY` result means Kast admitted one compatible headless runtime for this
exact root. A new worktree gets its own descriptor, socket, writer lease, and
index. Foreground IDE state does not affect this identity.

If the blocker says the exact runtime reached `INDEXING`, the server is
reachable but its evidence is not ready. Wait for headless Gradle import,
Kotlin indexing, and the Kast reference index, then rerun the command. Follow any
other typed action before continuing.

`READY` proves runtime admission, not persisted graph completeness. If the
answer uses the native graph, it should also report whether coverage is
complete or limited.

## 2. Ask for a semantic explanation

Start a Codex task with this prompt:

```text
Use Kast to explain how HeadlessGradleModelSettlementOutcome distinguishes
Settled, TimedOut, Interrupted, and ProjectDisposed. Cite the Kotlin
declarations and tests that prove each outcome. Do not edit files.
```

The important part is not the wording. You named a declaration and an outcome,
then asked for evidence instead of asking Codex to scan files blindly.

## 3. Read the evidence

A successful answer should identify all of these facts:

- `HeadlessGradleModelSettlementOutcome` is a closed sealed interface;
- `Settled` carries typed settlement evidence;
- `TimedOut` retains the last readiness observation; and
- interruption and project disposal are separate outcomes.

The answer should point to
`HeadlessGradleModelSettlementOutcome.kt` and
`HeadlessGradleModelSettlementAwaiterTest.kt`. Those locations matter: Kast's semantic
graph carries repository-relative paths, declaration ranges, and compiler
relationships rather than returning an unsupported narrative.

## 4. Follow one relationship

Continue the same task:

```text
Which test proves that interruption remains distinct from timeout, and which
production method does that test exercise?
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
