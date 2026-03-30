---
title: Remaining work
description: Gaps between the current bootstrap and the intended production
  shape.
icon: lucide/construction
---

# Remaining work

This page lists the implementation areas that remain incomplete after the
initial ADR-001 bootstrap. The build is green, packaging works, and the repo
structure is in place, but several backend, verification, and hardening tasks
still need completion before the system has the intended production shape.

## Standalone semantic backend

The standalone backend currently exists as a runnable scaffold, not as a real
Kotlin semantic analysis engine.

- **Status:** Scaffolded only
- **Current state:** `StandaloneAnalysisBackend` advertises no read
  capabilities and only `APPLY_EDITS`
- **Where:** `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisBackend.kt`
- **Missing:** `resolveSymbol`, `findReferences`, `diagnostics`,
  `callHierarchy`, and `rename`
- **Impact:** The standalone process can start and apply prepared edits, but it
  cannot perform semantic analysis or mutation planning in CI
- **Next step:** Restore a resolvable Kotlin Analysis API dependency set, open
  a real workspace session, and implement the read and rename paths

## Call hierarchy support

The contract and route exist for call hierarchy, but no production backend
implements it yet.

- **Status:** Not implemented in any production backend
- **Current state:** The API model includes `CALL_HIERARCHY`, and the server
  exposes `/api/v1/call-hierarchy`
- **Where:** `analysis-api/src/main/kotlin/io/github/amichne/kast/api/AnalysisBackend.kt`,
  `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisApplication.kt`
- **Missing:** Real call graph discovery in IntelliJ and standalone backends
- **Impact:** The endpoint is part of the transport surface, but it is not part
  of real backend functionality
- **Next step:** Implement call hierarchy in IntelliJ first, then bring the
  standalone backend to parity before enabling the capability

## IntelliJ diagnostics

The IntelliJ backend reports parser-level diagnostics only, which is useful but
not sufficient for semantic analysis.

- **Status:** Partial
- **Current state:** `diagnostics()` collects `PsiErrorElement` instances
- **Where:** `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/IntelliJAnalysisBackend.kt`
- **Missing:** Kotlin semantic diagnostics such as type errors and resolution
  failures
- **Impact:** The endpoint works for syntax and parse failures, but not for
  richer compiler analysis
- **Next step:** Replace the PSI-error-only implementation with Kotlin-aware
  semantic diagnostics

## IntelliJ rename fidelity

The IntelliJ backend plans renames through symbol resolution and reference
search, not through the IntelliJ refactoring engine.

- **Status:** Partial
- **Current state:** Rename planning emits `TextEdit` values from declaration
  and reference locations
- **Where:** `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/IntelliJAnalysisBackend.kt`
- **Missing:** `RenameProcessor`-based refactoring semantics
- **Impact:** The current implementation can miss richer IDE rename behavior
  such as override-aware renames, JVM-specific cases, and refactoring-only edge
  cases
- **Next step:** Move rename planning onto IntelliJ refactoring APIs and keep
  `TextEdit` as the wire representation

## IntelliJ read-action hardening

The IntelliJ backend reads project state successfully, but its current smart
read path is not the hardened nonblocking variant.

- **Status:** Partial
- **Current state:** Reads use `runReadActionInSmartMode(...)`, and the build
  emits a deprecation warning for that path
- **Where:** `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/IntelliJAnalysisBackend.kt`
- **Missing:** The modern nonblocking or coroutine-native smart read pattern
  with stronger cancellation behavior
- **Impact:** The backend compiles and runs, but it does not yet meet the
  intended "do not freeze the IDE" hardening bar
- **Next step:** Replace the deprecated helper with the current nonblocking and
  cancellable read pattern

## IntelliJ K2 compatibility verification

The plugin packages successfully, but one sandbox verification path still uses a
temporary bypass.

- **Status:** Unverified or partially bypassed
- **Current state:** `buildSearchableOptions` is disabled because the sandbox
  IDE rejected the plugin in Kotlin K2 mode
- **Where:** `backend-intellij/build.gradle.kts`,
  `backend-intellij/src/main/resources/META-INF/plugin.xml`
- **Missing:** Explicit Kotlin plugin-mode compatibility metadata or equivalent
  configuration that satisfies the 2026.1 sandbox path
- **Impact:** Packaging succeeds, but runtime compatibility in all IDE bootstrap
  paths is not fully proven yet
- **Next step:** Add the required Kotlin compatibility declaration, then
  re-enable sandbox verification

## Network hardening

The server has the right local-first default, but it does not yet enforce the
stronger network safety policy from the plan.

- **Status:** Partial
- **Current state:** The server defaults to `127.0.0.1`
- **Where:** `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt`
- **Missing:** Startup validation that rejects unsafe non-loopback
  configurations unless a token is present and the user explicitly opts in
- **Impact:** Safe defaults exist, but the stronger guardrail is not enforced by
  code
- **Next step:** Validate `host` and `token` at startup and fail fast for unsafe
  configurations

## Test coverage

The current test suite verifies the transport bootstrap and descriptor-file
workflow, but it does not yet verify the deeper semantic behavior the plan
calls for.

- **Status:** Thin bootstrap coverage only
- **Current state:** Tests cover the HTTP surface and descriptor store
- **Where:** `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisApplicationTest.kt`,
  `analysis-server/src/test/kotlin/io/github/amichne/kast/server/DescriptorStoreTest.kt`
- **Missing:** Golden fixture projects, shared contract tests against real
  backends, rename edge-case coverage, and backend-specific integration tests
- **Impact:** The build proves transport behavior and packaging, not semantic
  correctness across real projects
- **Next step:** Add fixture projects and run the same assertions against fake,
  IntelliJ, and standalone implementations

## CI and release verification

The repo currently relies on manual Gradle execution for verification. That is
acceptable for bootstrap, but not for sustained development.

- **Status:** Not implemented
- **Current state:** No CI or release workflow files exist in the repo
- **Where:** Repo root
- **Missing:** Automated Linux and macOS smoke tests, plugin verifier runs,
  artifact-content checks, and packaging checks
- **Impact:** Packaging or compatibility regressions will only be caught by
  local manual runs
- **Next step:** Add CI pipelines for tests, standalone launch smoke tests,
  IntelliJ plugin verification, and artifact inspection
