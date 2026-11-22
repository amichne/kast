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
- path: .github/workflows/ci.yml
- path: packaging/internal/verification/run-portable-tests.py
- path: packaging/internal/verification/run-portable-tests-container.sh
- path: packaging/internal/verification/run-installed-product.py
- path: packaging/internal/verification/local_product_probe.py
- path: experiments/host-observation/promptfoo/README.md
- path: experiments/host-observation/mcp_replay.py
sources:
  - id: openwiki-source-70991026f1538e8215a8789e
    resource: repo://docs/development.md
  - id: openwiki-source-91a187e8f9b4ae08c1ab931b
    resource: repo://experiments/host-observation/mcp_replay.py
  - id: openwiki-source-10bd98a55172871e1d97ee95
    resource: repo://experiments/host-observation/promptfoo/README.md
  - id: openwiki-source-9947fbfd711e87d328509a2c
    resource: repo://packaging/internal/verification/harness_launchd.py
  - id: openwiki-source-afb4001831005bbe57e5aab3
    resource: repo://packaging/internal/verification/local_product_probe.py
  - id: openwiki-source-6904899612d0ede016a5965a
    resource: repo://packaging/internal/verification/product_lifecycle_probe.py
  - id: openwiki-source-27b11dfabcb3ca89e348f52d
    resource: repo://packaging/internal/verification/run-installed-product.py
  - id: openwiki-source-cfdb04a9f90792f97961517a
    resource: repo://packaging/internal/verification/run-portable-tests.py
  - id: openwiki-source-e7530ede87bdaaf6e72b0180
    resource: repo://packaging/tests/test-portable-tests.py
  - id: openwiki-source-2862ccb68106d6b3d73cdd3b
    resource: repo://relation/intellij/NATIVE_FIXTURES.md
  - id: openwiki-source-a1bf1d8cd4e32016c6593087
    resource: repo://runtime/hosted/NATIVE_FIXTURES.md
generated: { by: "codex", at: "2026-11-22T20:53:36.158Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-22T21:18:16.148Z
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

Kast qualifies only the managed headless Host. No desktop IDEA path remains.
Qualification uses private workspace containers and automatic workspace creation through the managed product.
The contributor installer, `packaging/internal/product/local_product_install.py`, installs a checkout as an isolated managed product.

The relation and hosted native fixture guides define separate IDEA entry conditions and selectors.
They cover platform-owned semantics that scripted service inputs cannot establish.
A successful installation or handshake alone does not establish a usable semantic workspace.

The [Promptfoo campaign](../../experiments/host-observation/promptfoo/README.md) evaluates agents that receive the skill and Kast access against source-only controls.
Its MCP replay, `mcp_replay.py`, admits each MCP wire reply and records it as a receipt.
Campaign results measure agent outcomes; they do not qualify a product release.

The portable packaging runner enumerates the checked-in offline Python suites under owned state.
Suite path arguments select only those suites from that inventory; a path outside it is rejected.
Its container form adds a read-only checkout and disabled network access.
The runner requires one nonempty test summary for each suite.
It scans summary lines throughout the log so buffered output cannot hide the test count.
It rejects unexpected skips and records the permitted platform-specific count.
A Linux container pass does not qualify macOS IDEA or launchd behavior.

`run-installed-product.py` delegates native acceptance to `product_lifecycle_probe.py`.
It requires complete qualified candidate and baseline inputs, pinned interpreter inputs, and explicit evidence destinations.
Missing prerequisites reject rather than skipping or substituting a staged component pair.

## Disposable local installed acceptance

`local_product_probe.py` accepts exact component-qualified macOS ARM64 archives, pinned interpreter inputs, and an explicit isolation sandbox.
It installs through the packaged coordinator into an owned temporary HOME.
The coordinator uses Python's `-I -B` flags so imports cannot write bytecode into the installed payload.
Its observation records the interpreter, module paths, isolation, and disabled bytecode writes before product admission.
Each trial releases three shipped public cold queries together and compares their compiler lifetime with a warm query.
Uninstall separates the trials; every trial must establish a fresh Host lifetime.

The last trial damages an owned runtime file under a live Host.
Repair must preserve the damaged bytes through actual native retirement, restore the original digest, and start a fresh Host.
Protected workspace, profile, and external Gradle files must survive the lifecycle.
Final uninstall must leave neither managed state nor a live Host.

An overridden HOME still registers semantic coordinators in the user's launchd domain.
The local probe and the installed-product lifecycle probe retire every semantic coordinator job under their disposable root, also after a failure.
`harness_launchd.py` lists, and with `--retire` removes, jobs that leaked earlier.

Only release acquisition uses a local schema fixture; the other target has no payload and its CI producer identity is not real evidence.
The report records `LOCAL_INSTALLED_ACCEPTANCE` and `releaseQualification=NOT_ESTABLISHED`.
This path does not replace the sealed two-target release gate or prove public network repair selection.
The [development guide](../../docs/development.md#disposable-local-installed-acceptance) owns the command and its input requirements.

## Report the proven layer

Record the exact revision, selector, fixtures, assertions, outcome, and skipped cases.
Keep source, local adapter, compiler fixture, assembled product, and installed-host conclusions separate.
A source-backed explanation of a native mechanism does not claim a new live run.
Repeat a broader suite only when a changed consumer or a named remaining risk needs it.

## Source owners

- [development.md](../../docs/development.md)
- [NATIVE_FIXTURES.md](../../runtime/hosted/NATIVE_FIXTURES.md)
- [NATIVE_FIXTURES.md](../../relation/intellij/NATIVE_FIXTURES.md)
- [run-portable-tests.py](../../packaging/internal/verification/run-portable-tests.py)
- [run-portable-tests-container.sh](../../packaging/internal/verification/run-portable-tests-container.sh)
- [local_product_probe.py](../../packaging/internal/verification/local_product_probe.py)
- [ci.yml](../../.github/workflows/ci.yml)
- [Promptfoo README.md](../../experiments/host-observation/promptfoo/README.md)
- [mcp_replay.py](../../experiments/host-observation/mcp_replay.py)

## Read next

- [Build gates](build-and-architecture.md)
- [Package routes](../architecture/functional-packages.md)
- [Hosted lifecycle](../runtime/idea-lifecycle-and-refresh.md)
