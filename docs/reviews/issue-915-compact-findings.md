# Review #915: compact retained findings

This branch adds one compact finding per original retained impact path. Each finding preserves the original path ordinal and row ID, producer, destination, representation alternatives, provenance references, terminal and boundary obligations. Expanding a finding reads the original evidence through the existing result store. It does not schedule semantic execution or change semantic coverage.

## Review base

Compare `codex/representation-impact-foundation` at `93a47e83bbfaca148428b4d4061d42bc7ee8f4b1` with `codex/issue-915-compact-findings`. The production change is the original commit `5c57c6f009bac1db1709a9ff4cd94515d181766a`; the added review note and navigation do not change its code. The prerequisite branch contains the still-unsplit initial implementation and its first repairs. This is a stacked review against that prerequisite, rather than an independent change against main.

[Focused comparison](https://github.com/amichne/kast/compare/codex/representation-impact-foundation...codex/issue-915-compact-findings)

## Invariant and reading order

1. [QueryImpactFinding](../../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactFinding.kt) admits an ordinal against the original ledger and retains its exact path by reference. Distinct routes to the same destination remain distinct findings.
2. [RetainedQueryPresentation](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt) requires the original ledger and row-ID inventory to agree before selecting a findings window.
3. [ImpactFindingProjection](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingProjection.kt) and its terminal projection produce the compact document; [ImpactWitnessDocuments](../../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactWitnessDocuments.kt) own the typed wire shape.
4. [ImpactAccountingValidation](../../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactAccountingValidation.kt) rejects inconsistent section counts, original ordinals, duplicate row references and empty modeled branch sets. Selected presentation retains the original qualified closure.

The change includes encoded goldens, schema consumers, generated public catalogs, examples and source-bound knowledge. It changes no `query/service`, `relation`, `runtime` or `workspace` production files relative to its prerequisite. Native flow and the existing investigation store remain the execution authorities.

## Executed checks

The focused selector executed 20 cases in six classes, with zero failures, errors or skips: three query-contract, six query-protocol, eight public-contract and three CLI schema cases. It proves distinct original routes, stable links and ordinals, full-path expansion without semantic replay, pagination conservation, representation/terminal qualifications, compact encoding and malformed reference rejection. These are compiler, schema and controlled adapter proofs.

```sh
./gradlew --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :query:contract:test --tests '*QueryImpactFindingTest' \
  :query:protocol:test --tests '*ImpactFindingPresentationTest' --tests '*ImpactFindingQualificationTest' \
  :protocol:contract:test --tests '*ImpactFindingAccountingTest' --tests '*ImpactFindingShapeTest' \
  :cli:test --tests '*ImpactWitnessSchemaTest'
```

The exact selector ran at `5c57c6f009bac1db1709a9ff4cd94515d181766a`; its log is `/tmp/kast-issue-915-compact-focused.log`. The prerequisite passed its mandatory pre-push `productBuildGate`, recorded in `/tmp/kast-impact-foundation-push.log`. Publication of this branch also runs the repository's mandatory gate with GraalVM 25 and a fresh stable-release version observation; its outcome belongs to the final push handoff.

## Scope and evidence limits

This unit covers compact reporting over an original investigation ledger. Later pending-checkpoint retention, independently requested-site accounting and independent-peer admission are separate changes. It does not close #915 or claim semantic completion for qualified paths.

No new live IntelliJ semantic qualification was performed for this separation. Existing installed observations retain their original artifact identities in the [full proof receipt](https://github.com/amichne/kast/blob/2a1dc5681070e610ce8d8c849400b5ae4fe35ed2/docs/reviews/representation-impact-proof.md). A smaller encoded document is not evidence of CPU, heap or latency improvement.

## Finding-page allocation reduction

The review found that every findings page constructed entries and ordinal records for every original path before slicing its window. The follow-up constructs findings only for `start until end`, after admitting the range against the original section count. Each record still retains its original ordinal and exact path object; the section count and ledger closure remain unchanged. Other witness sections retain their existing collection behavior.

The baseline production revision was `ec17a04f68a6f5fc8059e5d79ca50241f1c256d7`. The fixed `QueryImpactWitness.kt` has SHA-256 `a8cd4b040d08f2af78073292351e16348484032d5305d22b0d8bc0c8b72f82f4`. [QueryImpactFindingWorkTest](../../query/contract/src/test/kotlin/io/github/amichne/kast/query/contract/QueryImpactFindingWorkTest.kt) calls the actual production factory with admitted synthetic ledgers containing 16 or 1,000 distinct producer paths. Fixture construction and output reporting occur outside the allocation interval. A volatile sink retains the returned view. Both runs used JVM `25.0.2+10-LTS`, 512 paired one-row warmup calls, and the median of three current-thread allocated-byte observations. Each page sample executes 64 calls; each drain sample executes one complete drain. No clocks, sleeps, IDE, semantic providers or filesystem operations enter the measured factory calls.

| Factory workload | Baseline allocated bytes | Fixed allocated bytes |
| --- | ---: | ---: |
| One-row page, 16-path ledger | 1,848 | 408 |
| One-row page, 1,000-path ledger | 94,344 | 408 |
| 100-row page, 1,000-path ledger | 95,176 | 15,472 |
| Empty page at the end of 1,000 paths | 94,320 | 216 |
| Complete 1,000-path drain, one row per page | 94,344,000 | 408,000 |
| Complete 1,000-path drain, 100 rows per page | 951,808 | 154,768 |

The one-row page and drain allocated 99.57% fewer bytes in this run. Increasing the retained ledger from 16 to 1,000 paths left fixed one-row allocation at 408 bytes. The opt-in allocation assertion failed on the baseline specifically because excluded paths increased allocation (`1,848 -> 94,344`), and passed after the range change. Pure behavior checks separately drain all 1,000 paths with page sizes 1, 37 and 100, verify every original ordinal and exact path identity, retain the original closure, and check empty pages, immutable entries and finite range rejection. Existing protocol tests also passed for row-ID links, full-path expansion without semantic replay and representation/terminal qualification.

Run the allocation assertion explicitly; the repository excludes `performance` tests from routine checks:

```sh
./gradlew --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :query:contract:test -PincludeTags=performance --tests '*QueryImpactFindingWorkTest'
```

To reproduce the baseline, use a separate checkout of `ec17a04f6` with only this follow-up's `QueryImpactFindingWorkTest.kt` and `QueryImpactLedgerTest.kt` test files copied into it, then run the same selector. Its allocation assertion should fail; do not copy the production fix. The test prints all measurements before the assertion. Unsupported allocation observations are measurement failures, never silently skipped evidence.

Native records for these runs are `/tmp/kast-findings-work-baseline.xml` and `/tmp/kast-findings-work-fixed.xml`; Gradle logs are `/tmp/kast-findings-work-baseline.log` and `/tmp/kast-findings-work-fixed.log`. The focused consumer run passed; subsequent contract `check` executed 52 cases, and the selected query-protocol, public-contract and CLI consumers executed 11, eight and three cases respectively, all without failures, errors or skips. The separate allocation selector executed one case. `verifyKastArchitecture`, `verifyJsonContracts`, `knowledgeImpact` and `verifyKnowledgeBase` passed. Knowledge impact names the existing semantic-query and query-protocol pages; their semantic claims remain valid and their generated refresh belongs to the scheduled OpenWiki workflow.

This measures allocated bytes in the pure witness factory. It establishes removal of transient allocation for excluded finding paths. It does not measure end-to-end hosted allocation, CPU, elapsed time, peak heap, retained-store size or native semantic work. The retained presentation's separate path-order validation and the existing projection/byte fitter remain outside this measurement.
