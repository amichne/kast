---
type: System Explanation
title: Tests and native qualification
description: Explains which tests establish pure rules, adapter effects, compiler
  semantics, assembled products, and installed-host behavior.
tags:
- kast
- development
code_sources:
- path: docs/development.md
- path: runtime/hosted/NATIVE_FIXTURES.md
- path: relation/intellij/NATIVE_FIXTURES.md
- path: runtime/hosted/native/README.md
- path: packaging/run-portable-tests.py
- path: packaging/run-portable-tests-container.sh
- path: .github/workflows/ci.yml
- path: experiments/host-observation/ACCEPTANCE.md
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-70991026f1538e8215a8789e
    resource: repo://docs/development.md
  - id: openwiki-source-553a1d23cddbdd6ecdb69f5d
    resource: repo://experiments/host-observation/ACCEPTANCE.md
  - id: openwiki-source-af8d30969eca7e840eedf039
    resource: repo://packaging/run-portable-tests.py
  - id: openwiki-source-a1bf1d8cd4e32016c6593087
    resource: repo://runtime/hosted/NATIVE_FIXTURES.md
  - id: openwiki-source-af72e3368d1f0c22109e4ecf
    resource: repo://runtime/hosted/native/README.md
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Tests and native qualification

## Start with the behavior's mechanism

A contract test uses explicit production values for parsing, identity, policy, and finite rejection.
A service test runs the real decision owner with bounded external inputs.
A filesystem or process test adds an owned temporary root or a real child only when the assertion needs it.
A compiler fixture adds the platform model needed for semantic authority.
Native qualification adds a disposable product composition for the actual OS or IDE effect.

Build tools and runner setup remain separate from the behavior's dependency budget.
A runner failure, unavailable dependency, or empty test selector does not establish a product regression.
Expected results need an independent assertion source.
A fake that manufactures the expected domain success cannot establish the production admission rule.

## Preserve the adverse case

State each case's starting facts, permitted effects, expected result, and nearest counterexample.
Script external outcomes at the narrowest effect interface.
Reject unexpected calls, unused expectations, and excess interactions.
Keep fixture violations visible even when production code catches the injected exception.

Assert exact identities, qualifications, finite failures, and protected state on rejection.
An adverse operation can still emit legitimate stage or transition evidence.
The assertion must distinguish that evidence from a forbidden external effect.
Use a controlled callback or scheduler when the behavior consumes that transition.
Do not use sleep or a race to manufacture a timeout.

## Native and installed evidence

The relation and hosted native fixture guides define separate IDEA entry conditions and selectors.
They cover platform-owned semantics that scripted service inputs cannot establish.
The installed-host harness adds component identity, loaded plugin identity, exact workspace enrollment, and fresh invocation evidence.
A successful installation or handshake alone does not establish a usable semantic workspace.

The portable packaging runner enumerates the checked-in offline Python suites under owned state.
Its container form adds a read-only checkout and disabled network access.
The runner rejects unexpected skips and records the permitted platform-specific count.
A Linux container pass does not qualify macOS IDEA or launchd behavior.

## Report the proven layer

Record the exact revision, selector, fixtures, assertions, outcome, and skipped cases.
Keep source, local adapter, compiler fixture, assembled product, and installed-host conclusions separate.
A source-backed explanation of a native mechanism does not claim a new live run.
Repeat a broader suite only when a changed consumer or a named remaining risk needs it.

## Source owners

- [development.md](../../docs/development.md)
- [NATIVE_FIXTURES.md](../../runtime/hosted/NATIVE_FIXTURES.md)
- [NATIVE_FIXTURES.md](../../relation/intellij/NATIVE_FIXTURES.md)
- [README.md](../../runtime/hosted/native/README.md)
- [run-portable-tests.py](../../packaging/run-portable-tests.py)
- [run-portable-tests-container.sh](../../packaging/run-portable-tests-container.sh)
- [ci.yml](../../.github/workflows/ci.yml)
- [ACCEPTANCE.md](../../experiments/host-observation/ACCEPTANCE.md)

## Read next

- [Build gates](build-and-architecture.md)
- [Package routes](../architecture/functional-packages.md)
- [Hosted lifecycle](../runtime/idea-lifecycle-and-refresh.md)
