# Agent-read reliability: audited starters

Execution index for [#735](https://github.com/amichne/kast/pull/735), not a new requirements or outcome authority. The [R1–R8 requirements](agent-read-reliability.md) and [AR-01–AR-12 audit](https://gist.github.com/amichne/745ee1e7237d6cfb6fd3ff43a5b284e0) retain their full acceptance criteria. Each linked issue contains a condensed, independently usable agent brief.

## Pinned basis

Audited on 2026-09-14:

- Reliability checkpoint: `8c2b16ccb3bfe98767325b7ad857421389b32749`, branch `work/0.40.0-agent-read-reliability`.
- `main` and released `v0.40.0`: `24a7e92d09a31624cf11e4f596c64d3a99e9a995`.
- Starter branch: `work/agent-read-audit-starters`, forked from the reliability checkpoint; review against the reliability branch, not directly against main.

Re-read these refs before implementation. Reuse the existing budget admission, canonical outcomes, continuations, enum exclusion, and installed harness; do not rebuild them. Avoid unrelated shared-K2 extraction (#729) or topology work (#722).

## Audit findings and evidence limits

**Observed remote failure:** the checkpoint's [Kotlin product run](https://github.com/amichne/kast/actions/runs/34877606289/job/104088578087) failed; documentation and CodeQL checks passed. This does not independently reproduce every locally reported failure in the gist. The reported 132/132 reads and 156/156 concurrent first attempts belong to earlier `671a319e7`, before traversal changes. Local report filenames are not accessible artifacts; publish bounded evidence links when closing gates.

**Source-confirmed filter ordering:** `source/intellij/.../LiveIntellijSourceRead.kt` reduces declaration selection to `includeDeclarations`, resolves visibility and constructs candidates, then calls `IntellijSourceEntityPageCollector.offer`, where requested kinds are filtered. Move already-known kind eligibility ahead of avoidable compiler work. The collector already filters before page-result capacity: do not misreport this as excluded entities filling output pages. Excluded containers must still provide structural parent/depth information and eligible descendants.

**Source-confirmed continuation policy:** `HostedOutputPages` expires at `age > TTL`, not `>=`; replay does not renew creation time. `HostedQueryContinuations.Active` owns five separately bounded stores: query checkpoints and query/relation/source/traversal output. Test the boundary and document aggregate retention; neither fact alone proves a defect or authorizes changing policy.

**Source-confirmed report boundary:** `HostedReadBudgetReports` preserves rejected source/traversal outcomes unchanged. Trace post-admission rejection evidence without inventing effective grants for requests rejected before admission.

**Reported, not diagnosed:** AR-10 lacks the exact offending stderr/help command and output. Capture the real reproduction before selecting a fix. Recompute knowledge impact rather than inventing names for the seven reported affected concepts.

Platform guidance was cross-checked through Exa and Context7 against JetBrains' [threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html) and [coroutine read actions](https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html). Restartable read actions and cross-action object validity support the existing detached-checkpoint/cancellation boundary. These documents are design constraints, not evidence that a specific Kast lifecycle bug occurred.

## Minimal first slice per task

The issue is the work item; the row below identifies the first bounded change. Only AR-01/AR-02 receive source/test changes in this starter branch. Other rows are test-first implementation or delivery briefs, not claimed implementations.

| Task | Issue | First bounded change | Integration dependency |
| --- | --- | --- | --- |
| AR-01: checkpoint checks | [#736](https://github.com/amichne/kast/issues/736) | Extract retained-output token admission; pin valid tokens and each finite failure before migrating wire fixtures. Then repair generators/catalog duplication without raising caps. | First unblocker. |
| AR-02: traversal | [#737](https://github.com/amichne/kast/issues/737) | Extend the existing traversal fixture with result-only fitting, unencodable envelope, and failed retention. Then prove repeated fitting/replay and installed contracts. | AR-01 for integration. |
| AR-03: budgets/deadlines | [#738](https://github.com/amichne/kast/issues/738) | Inject clock/counting provider; exhaust work alone before any unit and after one proven unit; assert grant, cause, invocation count, and outcome. Extend dimensions separately. | AR-01; coordinate traversal with AR-02. |
| AR-04: completion/recovery | [#739](https://github.com/amichne/kast/issues/739) | Terminal-incomplete upstream relation with two output pages: first permits output draining, final remains terminal, omissions survive both. | AR-01; coordinate contracts with AR-02/03. |
| AR-05: continuation policy | [#740](https://github.com/amichne/kast/issues/740) | Inject existing store clock at TTL−1, TTL, TTL+1; replay without renewal. Repeat lost-response relation token while preserving two occurrences of one target. | Characterize now; integrate AR-02/03/04. |
| AR-06: installed failures | [#741](https://github.com/amichne/kast/issues/741) | Add one disconnected peer to the existing 12×13 barrier replay; verify unrelated first attempts/listener health; extend malformed/saturation cases next. | Harness can start now; final schema matrix follows AR-01–05. |
| AR-07: early eligibility | [#742](https://github.com/amichne/kast/issues/742) | Request functions inside excluded class/property containers; count visibility/candidate work and preserve nested functions. Include constructor properties and existing enum regressions. | Independent fixture work; integrate after AR-01. |
| AR-08: R1–R7 qualification | [#743](https://github.com/amichne/kast/issues/743) | Populate the existing evidence ledger per requirement with exact revision, command, result, report, skip, and blocker. | All AR-01–07. |
| AR-09: names/scope | [#744](https://github.com/amichne/kast/issues/744) | Test old/preferred input names lowering to one canonical operation and preferred-only catalog advertisement; specify compatibility interval first. | AR-08. |
| AR-10: stderr/help | [#745](https://github.com/amichne/kast/issues/745) | Capture installed command/version/stdout/stderr/exit status; reproduce before adding the owning regression. | AR-09. |
| AR-11: delivery | [#746](https://github.com/amichne/kast/issues/746) | Prepare exact-head check/evidence/version/workflow/asset record; execute normal release path only after gates and applicable authorization. | AR-01–10. |
| AR-12: released inventory | [#747](https://github.com/amichne/kast/issues/747) | Derive acceptance inventory from the released catalog; bind every pass/fail/skip to the installed artifact checksum and surface. | AR-11. |

Test construction may proceed independently; shared contract and runtime changes require coordinated integration. Do not interpret these dependencies as permission to parallelize semantic execution.

## Concrete patch in this branch

- `protocol/contract/.../TraversalContinuationDocument.kt`: extracts only retained-output parsing into a private helper. Token families, grammar, size limit, failure precedence, and serialization remain unchanged.
- `protocol/contract/.../TraversalContinuationDocumentAdmissionTest.kt`: three tests, 15 fixed vectors, independent known payload/digest, including malformed retained output and each finite upstream failure.
- `runtime/hosted/.../HostedTraversalOutputBoundaryTest.kt`: three tests for result-only fitting with preserved graph/coverage, no retention for an unencodable envelope, and finite capacity/encoding failures without publishing a page. Reuses `HostedTraversalPagingFixture`.

These starters do not close their issues. In particular, no catalog cap, schema strictness, freshness rule, timeout, or continuation policy is relaxed.

## Verification and handoff

Executed locally: the three parser admission test bodies (15 vectors), plus 1,419 before/after differential token cases, passed under Kotlin 1.9.0 / JDK 21. This was an isolated parser check: serialization annotations/adapter and the JUnit runner were excluded. It does **not** qualify the repository toolchain, serialization integration, detekt, or installed runtime. Traversal tests were source-reviewed but not compiled or executed here. The required JDK 25 / pinned IDE environment was not available.

First repository checks, under the repository's required toolchain:

```sh
./gradlew :protocol:contract:test \
  --tests '*TraversalContinuationDocumentAdmissionTest' \
  :protocol:contract:detekt
./gradlew :runtime:hosted:test --tests '*HostedTraversal*Test' \
  :cli:compileTestKotlin
./gradlew verifyJsonContracts knowledgeImpact verifyKnowledgeBase
```

Then run the original audit's impacted-module, generated-reference/configuration, product, and installed commands as required by the implemented slice. Review impacted knowledge claims before recording structural validation. Do not substitute an isolated check for those gates.

For every completed issue, record `implementation SHA | fixture/command | observed result | report/artifact | skips | remaining gaps`. Keep CLI, provider, and stock Codex UI qualification separate. Green historical runs or an aggregate qualification flag cannot close untested requirements.

No merge, release, or installation is performed or authorized by this starter audit.
