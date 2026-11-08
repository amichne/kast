---
type: System Explanation
title: Testing and native qualification
description: Explains how source tests, scoped CI, native compilation, and assembled-product checks establish distinct levels of confidence. Clarifies what portable and native evidence can and cannot prove.
tags: [testing, CI, native-qualification, release]
code_sources:
- path: .github/scripts/ci/validation_scope.py
- path: .github/scripts/ci/routine_gate.py
- path: .github/scripts/ci/native_handoff.py
- path: .github/workflows/ci.yml
- path: config/ci/verification-graph.json
- path: packaging/run-portable-tests.py
- path: build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
verified:
  - by: openwiki/0.9.0
    at: 2026-11-08T22:20:55.770Z
sources:
  - id: openwiki-source-782f88ea0a8c13260d76dfa4
    resource: repo://.github/scripts/ci/native_handoff.py
  - id: openwiki-source-de62ffd8b4fe61a2095f5212
    resource: repo://.github/scripts/ci/routine_gate.py
  - id: openwiki-source-3da7270665885f2d3fdf6d53
    resource: repo://.github/scripts/ci/validation_scope.py
  - id: openwiki-source-164e2da859b5277df81c7d94
    resource: repo://.github/workflows/ci.yml
  - id: openwiki-source-a387d9d545afff426d34a10a
    resource: repo://build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - id: openwiki-source-a0f188c7815d1e39ad40b27f
    resource: repo://config/ci/verification-graph.json
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
generated: { by: "openwiki/0.9.0", at: "2026-11-08T22:20:55.770Z" }
---

# Testing and native qualification

Verification is layered: focused tests establish local contracts, Gradle tasks exercise their owning modules and product integrations, CI selects an appropriate scope, and native/product lanes test effects that a Linux source run cannot establish. These results are complementary, not interchangeable. A test passing in one layer is not evidence that a later layer passed.

## From focused tests to product behavior

Choose the narrowest test whose real assertion boundary matches the behavior under change. Contract tests pin parsing, identity, policy, and finite rejection; service tests exercise decision owners; filesystem and process tests add owned temporary state or real subprocesses when those effects matter; compiler fixtures provide platform semantic authority. Native qualification exercises disposable product composition against the target OS or IDE.

For adverse cases, specify initial facts, allowed effects, result, and nearest counterexample. Script external outcomes at the narrowest effect boundary, reject unexpected or unused interactions, and assert exact identities, finite errors, and protected state. Keep legitimate transition evidence distinct from forbidden external effects. Use controlled callbacks/schedulers for transitions rather than sleeps or races. A fake that manufactures expected success cannot prove production admission policy; the expected result needs an independent assertion source.

Gradle owns module checks and product verification tasks. The distribution verifier checks that the packaged control executable is byte-for-byte the qualified producer output, has the requested native platform header, and that the distribution contains exactly one native executable, no JVM libraries/bytecode, and stays within inventory and size limits. This is an artifact invariant, not a live host-behavior test.

## CI scope selection and fallback

The `scope` job calculates checks from the exact base/head diff. Only explicitly inventoried independent input classes can narrow work. Product Kotlin changes are assigned according to module ownership; documentation has a separate lane. Unknown, mixed, malformed, unavailable, or otherwise uncertain diff inputs retain the full check set. PR and push ranges differ (merge-base comparison for PRs, complete before-to-head range for pushes); non-PR/push events also default to full scope. Main pushes retain product qualification for source-only changes.

When affected selection is enabled, the checked-in verification plan and resolved Gradle task graph constrain what can be skipped. The plan is tied to revision/tree identity and source ownership; an invalid or rejected plan broadens back to full validation. `routine_gate.py` verifies that the resolved product gate still contains required proofs and that the native-input module inventory matches the audited inventory. Missing proof or inventory mismatch is rejection, not a reason to silently omit work. `KAST_NATIVE_COMPILATION=false` can remove product and gate-graph checks from a selected scope; it does not convert omitted checks into successful qualification.

The workflow separates documentation, offline portable tests, source obligations, macOS native compilation, Linux release assembly, and Kotlin product checks. CI artifact reuse is conditional and retains candidate identity; reports and test results are uploaded as evidence. An unavailable dependency, failed runner setup, empty test selector, cancellation, or timeout does not demonstrate a product regression or a passing gate: report the observed outcome and its limitation.

## Native handoff and assembled-product boundary

Native qualification is split across hosts. The source job compiles the exact native input modules and retains a handoff; the macOS ARM64 job compiles the target native executable, and the Linux assembly job admits that output before producing the release composition. `native_handoff.py` binds handoff records to source revision, tree and version, checks kind and hashes, rejects malformed identity or mismatched payload, and refuses unsafe archive members and wrong-platform/toolchain inputs. This prevents a plausible but unrelated binary from being treated as the qualified output.

The product lane then tests the assembled product, including macOS product behavior and cold RPC subprocess behavior, before sealing a candidate. Distribution structural checks and live product checks answer different questions: the former checks composition and payload identity; the latter exercises actual invocations on a host. Neither by itself qualifies every supported host/IDE integration.

The relation and hosted native fixture guides define distinct IDEA entry conditions and selectors for platform-owned semantics that scripted service inputs cannot establish. An installed-host harness adds component and loaded-plugin identity, exact workspace enrollment, and fresh invocation evidence. Successful installation or handshake alone does not establish a usable semantic workspace. A source-backed explanation of these mechanisms is not evidence that a live native run occurred.

## Portable tests and evidence reporting

`packaging/run-portable-tests.py` enumerates every checked-in `packaging/test-*.py` suite and executes each with isolated home, temporary, XDG, and tool-path state. It rejects an empty inventory, enforces per-suite deadlines, and checks observed skips against an explicit platform-specific allowance. The container wrapper used by CI adds a read-only checkout and disabled network access. This establishes offline packaging behavior under that environment; a Linux container pass does not qualify macOS IDEA or launchd effects.

Report revision, selector, fixtures, assertions, result, and skipped cases. Keep source, adapter, compiler fixture, assembled product, and installed-host conclusions separate. Broaden a suite when a changed consumer or specific remaining risk requires it, rather than treating suite volume as stronger evidence by itself.

## Source owners

- [Validation scope](../../.github/scripts/ci/validation_scope.py)
- [Routine gate](../../.github/scripts/ci/routine_gate.py)
- [Native handoff](../../.github/scripts/ci/native_handoff.py)
- [CI workflow](../../.github/workflows/ci.yml)
- [Verification graph](../../config/ci/verification-graph.json)
- [Portable test runner](../../packaging/run-portable-tests.py)
- [Distribution verification tasks](../../build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt)

## Read next

- [Build and architecture](build-and-architecture.md)
- [Release candidates and promotion](../distribution/release-candidates-and-promotion.md)
- [Distribution](../modules/distribution.md)
